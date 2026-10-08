package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.SandboxEndpoint;
import com.cmagent.core.runtime.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.cmagent.server.service.SandboxEndpointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 按可信tenant选择默认端点和注册后端，统一限额及成功输出脱敏，不缓存可变默认值。 */
@Component
@Primary
public class ManagedSkillSandbox implements SkillSandboxBackend {
    /** 服务端注册表，不按请求反射加载实现。 */ private final Map<String,SkillSandboxBackend> backends;
    /** 当前部署不可放宽限额。 */ private final SkillProperties properties;
    /** 从租户仓储解析版本与临时认证。 */ private final SandboxEndpointService endpoints;
    /** 严格记录安全路由元数据，不记录主机或凭据。 */ private final AuditAppender audit;
    /** 实例级共享并发，切换端点不能突破上限。 */ private final Semaphore permits;
    /** 额外保护其他扩展输出。 */ private final ToolOutputSanitizer sanitizer;
    /** @param registered 注册后端 @param properties 部署上限 @param endpoints 租户配置 @param audit 审计 @param mapper JSON */
    public ManagedSkillSandbox(List<SkillSandboxBackend> registered,SkillProperties properties,SandboxEndpointService endpoints,
            AuditAppender audit,ObjectMapper mapper){
        this.properties=properties;this.endpoints=endpoints;this.audit=audit;
        var registry=new HashMap<String,SkillSandboxBackend>();
        for(var backend:registered){
            if("managed".equals(backend.backendId()))continue;
            if(registry.putIfAbsent(backend.backendId(),backend)!=null)throw new IllegalStateException("沙箱后端重复注册");
        }
        if(!registry.containsKey(properties.getSandbox().getBackend()))throw new IllegalStateException("部署沙箱后端未注册");
        backends=Map.copyOf(registry);permits=new Semaphore(properties.getSandbox().getMaxConcurrent());sanitizer=new ToolOutputSanitizer(mapper);
    }
    @Override public String backendId(){return "managed";}
    @Override public String execute(SkillReadRequest request,Map<String,String> files,String stdin){
        try(var execution=open(request,Map.of())){String output=execution.execute(files,stdin);execution.verifyAccess();return output;}
    }
    /** 打开后固定原连接；打开失败与关闭失败都带本次调用的错误编号。 */
    @Override public Execution open(SkillReadRequest request,Map<String,String> ignored){
        if(!properties.getSandbox().isEnabled())throw failure(request,ApiErrorCode.SKILL_SANDBOX_DISABLED,"技能沙箱未启用");
        if(!ignored.isEmpty())throw failure(request,ApiErrorCode.SKILL_SANDBOX_INVALID,"调用方不能覆盖沙箱连接");
        if(!permits.tryAcquire())throw failure(request,ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED,"技能沙箱繁忙，请稍后重试");
        long deadline=System.nanoTime()+properties.getSandbox().getTimeout().toNanos();
        try{
            var tenant=request.principal().tenantId();
            UUID defaultId=endpoints.defaultId(tenant).orElse(null);
            SandboxEndpoint snapshot=defaultId==null?null:endpoints.get(tenant,defaultId);
            if(snapshot!=null && (!snapshot.enabled() || snapshot.probeRevision()!=snapshot.revision()))
                throw failure(request,ApiErrorCode.SKILL_SANDBOX_ENDPOINT_CONFLICT,"默认沙箱端点停用或配置未通过当前版本连接测试");
            String backendId=snapshot==null?properties.getSandbox().getBackend():snapshot.backend();
            var backend=backends.get(backendId);
            if(backend==null)throw failure(request,ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE,"沙箱后端不可用");
            Map<String,String> connection=snapshot==null?endpoints.deploymentConnection():endpoints.connection(snapshot);
            audit.append(tenant,request.principal().principalId(),"SANDBOX_ROUTE","SANDBOX_ENDPOINT",
                snapshot==null?"deployment":snapshot.id().toString(),"PREPARED",
                "runId="+request.runId()+" toolCallId="+request.modelCallId()+" revision="+(snapshot==null?0:snapshot.revision()));
            Execution delegate=backend.open(request,connection);
            return new Execution(){
                /** 清理失败保留实例配额，即使随后认证文件关闭成功也不释放。 */ private boolean cleanupFailed;
                /** 幂等关闭，避免重复释放同一并发令牌。 */ private boolean closed;
                public String execute(Map<String,String> files,String stdin){
                    try{
                        verifyAccess();
                        if(stdin==null || stdin.getBytes(StandardCharsets.UTF_8).length>properties.getSandbox().getMaxInputBytes())
                            throw failure(request,ApiErrorCode.SKILL_SANDBOX_INVALID,"沙箱输入超过上限");
                        if(files==null || files.size()>65 || files.keySet().stream().anyMatch(path->path==null || path.length()>240 || path.startsWith("/") || path.contains(":") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl) || Arrays.asList(path.split("/",-1)).stream().anyMatch(p->p.isEmpty() || p.equals(".") || p.equals("..")))
                            || files.values().stream().anyMatch(Objects::isNull)
                            || files.values().stream().mapToLong(value->value.getBytes(StandardCharsets.UTF_8).length).sum()>4*1024*1024)
                            throw failure(request,ApiErrorCode.SKILL_SANDBOX_INVALID,"沙箱资源不合法或超过上限");
                        // 统一约束扩展后端的耗时，取消后先给后端清理机会；无法确认停止时保留并发名额。
                        if(System.nanoTime()>=deadline)throw failure(request,ApiErrorCode.SKILL_SANDBOX_TIMEOUT,"沙箱准备与连接已耗尽执行预算");
                        var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                        var stopped=new java.util.concurrent.CountDownLatch(1);
                        var terminal=new java.util.concurrent.atomic.AtomicReference<SkillAccessException>();
                        var task=workers.submit(()->{
                            try{return delegate.execute(Map.copyOf(files),stdin);}
                            catch(SkillAccessException error){terminal.set(error);throw error;}
                            finally{stopped.countDown();}
                        });
                        String output;
                        try{output=task.get(Math.max(1,deadline-System.nanoTime()),java.util.concurrent.TimeUnit.NANOSECONDS);}
                        catch(java.util.concurrent.TimeoutException error){
                            task.cancel(true);
                            try{cleanupFailed|=!stopped.await(3,java.util.concurrent.TimeUnit.SECONDS);}
                            catch(InterruptedException interrupted){Thread.currentThread().interrupt();cleanupFailed=true;}
                            if(terminal.get()!=null && terminal.get().code()==ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED){cleanupFailed=true;throw terminal.get();}
                            throw failure(request,ApiErrorCode.SKILL_SANDBOX_TIMEOUT,"技能沙箱执行超时");
                        }catch(InterruptedException error){
                            task.cancel(true);
                            // 调用线程中断不等于后台资源已停止；先保留原连接等待清理，再恢复中断状态。
                            try{cleanupFailed|=!stopped.await(6,java.util.concurrent.TimeUnit.SECONDS);}
                            catch(InterruptedException repeated){cleanupFailed=true;}
                            finally{Thread.currentThread().interrupt();}
                            if(terminal.get()!=null && terminal.get().code()==ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED){cleanupFailed=true;throw terminal.get();}
                            throw failure(request,ApiErrorCode.SKILL_SANDBOX_TIMEOUT,"技能沙箱执行已中断");
                        }
                        catch(java.util.concurrent.ExecutionException error){
                            if(error.getCause() instanceof SkillAccessException controlled)throw controlled;
                            // 未预期后端故障交给治理层唯一诊断边界；保留发生位置，丢弃可能含材料的异常原文。
                            var safe=new IllegalStateException("沙箱后端内部执行失败");safe.setStackTrace(error.getCause().getStackTrace());throw safe;
                        }finally{workers.shutdownNow();}
                        if(output==null || output.getBytes(StandardCharsets.UTF_8).length>properties.getSandbox().getMaxOutputBytes())
                            throw failure(request,ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED,"沙箱输出超过上限");
                        return sanitizer.sanitize(output,List.of());
                    }catch(SkillAccessException error){cleanupFailed|=error.code()==ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED;throw correlated(request,error);}
                }
                public void verifyAccess(){
                    if(!properties.getSandbox().isEnabled())throw failure(request,ApiErrorCode.SKILL_SANDBOX_DISABLED,"技能沙箱已关闭");
                    if(snapshot!=null){
                        var current=endpoints.get(tenant,snapshot.id());
                        if(!current.enabled())throw failure(request,ApiErrorCode.SKILL_ACCESS_REVOKED,"沙箱端点已停用");
                        // 只重新检查旧目标的部署允许策略，不解密新凭据或切换默认连接。
                        new SandboxTargetPolicy(properties).validate(snapshot.mode(),snapshot.host(),snapshot.port(),snapshot.username());
                    }
                    delegate.verifyAccess();
                }
                public synchronized void close(){
                    if(closed)return;closed=true;
                    boolean interrupted=Thread.interrupted();
                    try{delegate.close();}catch(SkillAccessException error){cleanupFailed=true;throw correlated(request,error);}
                    catch(RuntimeException error){cleanupFailed=true;throw failure(request,ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED,"沙箱句柄关闭失败");}
                    finally{if(!cleanupFailed)permits.release();if(interrupted)Thread.currentThread().interrupt();}
                }
            };
        }catch(SkillAccessException failure){if(failure.code()!=ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED)permits.release();throw correlated(request,failure);}
        catch(RuntimeException failure){permits.release();throw failure;}
    }
    private static SkillAccessException correlated(SkillReadRequest request,SkillAccessException error){
        return new SkillAccessException(error.code(),error.safeMessage(),request.attemptId().toString(),true);
    }
    private static SkillAccessException failure(SkillReadRequest request,ApiErrorCode code,String message){
        return new SkillAccessException(code,message,request.attemptId().toString(),true);
    }
}
