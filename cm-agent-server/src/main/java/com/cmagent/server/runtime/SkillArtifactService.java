package com.cmagent.server.runtime;
import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
/** 文件归属、存储与运行生命周期编排；网络/文件 I/O 不进入元数据事务。 */
@Service
public class SkillArtifactService {
    /** 唯一元数据与跨实例租约入口。 */ private final SkillArtifactRepository repository;
    /** 独立文件存储，禁用时不创建目录或读取密钥。 */ private final ObjectProvider<SkillArtifactStorage> storage;
    /** 当前可信运行状态源。 */ private final RunRepository runs;
    /** TEST 创建者与技能路径核验源。 */ private final SkillTrialRepository trials;
    /** 会话创建者核验源，不依赖客户端 conversationId。 */ private final ConversationRepository conversations;
    /** 由认证会话提供的权限判定。 */ private final PermissionEvaluator permissions;
    /** 严格审计不允许静默失败。 */ private final AuditAppender audit;
    /** 当前部署的独立文件策略。 */ private final SkillArtifactProperties policy;
    /** 单实例限制验证后明文驻留，跨实例同文件互斥仍由数据库租约保证。 */ private final Semaphore downloads=new Semaphore(4);
    /** 只记录安全上下文，异常原文可能带宿主路径不得写日志。 */
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SkillArtifactService.class);
    public SkillArtifactService(SkillArtifactRepository repository,ObjectProvider<SkillArtifactStorage> storage,RunRepository runs,
            SkillTrialRepository trials,ConversationRepository conversations,PermissionEvaluator permissions,AuditAppender audit,SkillProperties properties){
        this.repository=repository;this.storage=storage;this.runs=runs;this.trials=trials;this.conversations=conversations;
        this.permissions=permissions;this.audit=audit;this.policy=properties.getSandbox().getArtifacts();policy.validate();
    }
    /** 关闭时不影响原文本工具；当前 Run 身份在元数据预留前复核。 */
    public Collection begin(SkillReadRequest request) {
        if(!policy.isEnabled())return null;
        var run=runs.findByTenantAndAgentAndId(request.principal().tenantId(),request.agentId(),request.runId()).orElseThrow(()->notFound(request.principal(),request.runId()));
        if(!run.principalId().equals(request.principal().principalId())||run.status()!=RunStatus.RUNNING)
            throw notFound(request.principal(),request.runId());
        if(request.modelCallId().length()>160)throw failure(request.principal(),request.runId(),ApiErrorCode.SKILL_ARTIFACT_INVALID,"文件调用标识不合法",request.attemptId().toString(),null);
        return new Collection(request,run);
    }
    /** 当前调用持有所有暂存文件；只有治理后置复核及审计成功才保留 PENDING。 */
    public final class Collection implements SkillArtifactSink,AutoCloseable {
        /** 已校验的当前调用身份。 */ private final SkillReadRequest request;
        /** 不可变运行归属快照，发布还会核对运行终态。 */ private final RunRecord run;
        /** 接收器可能运行于沙箱读取线程，提交标志由治理线程在读取结束后设置。 */ private boolean completed;
        /** SPI 实现不能绕过单次调用预算，计数在预留前推进。 */ private int count;
        /** 单次调用累计实际声明字节，写入器另核对实际流长度。 */ private long total;
        /** 调用内重复名称一律拒绝，避免覆盖或产生歧义。 */ private final Set<String> names=new HashSet<>();
        private Collection(SkillReadRequest request,RunRecord run){this.request=request;this.run=run;}
        public synchronized void accept(String name,long size,InputStream input)throws IOException {
            if(!SkillArtifactProtocol.safeName(name)||size<1||size>policy.getMaxFileBytes())
                throw failure(request.principal(),run.id(),ApiErrorCode.SKILL_ARTIFACT_INVALID,"文件产物名称或大小不合法",request.attemptId().toString(),null);
            String type=mediaType(name);
            if(type==null)throw failure(request.principal(),run.id(),ApiErrorCode.SKILL_ARTIFACT_INVALID,"文件产物类型未允许",request.attemptId().toString(),null);
            if(!names.add(name)||++count>policy.getMaxFilesPerCall()||size>policy.getMaxTotalBytesPerCall()-total)
                throw failure(request.principal(),run.id(),ApiErrorCode.SKILL_ARTIFACT_LIMIT_EXCEEDED,"本次调用文件数量或字节已达上限",request.attemptId().toString(),null);
            total+=size;
            Instant now=Instant.now();
            var a=new SkillArtifact(UUID.randomUUID(),run.tenantId(),run.principalId(),run.agentId(),run.id(),run.kind(),
                request.skillId(),request.versionId(),request.modelCallId(),name,type,size,"",SkillArtifactStatus.STAGING,now,now.plus(Duration.ofDays(1)),null,null);
            if(!repository.reserve(a,policy.getMaxTotalBytesPerRun(),policy.getMaxStoredBytesPerTenant(),policy.getMaxFilesPerRun(),policy.getMaxFilesPerTenant()))
                throw failure(request.principal(),run.id(),ApiErrorCode.SKILL_ARTIFACT_LIMIT_EXCEEDED,"文件存储配额不足或调用重复",request.attemptId().toString(),null);
            try {
                String digest=store().write(a,input);
                if(!repository.stored(a.tenantId(),a.id(),digest))throw new IOException("文件状态未确认");
            }catch(FileSystemSkillArtifactStorage.FormatFailure e){
                throw failure(request.principal(),run.id(),ApiErrorCode.SKILL_ARTIFACT_INVALID,"文件内容与声明类型不符或文档结构不合法",request.attemptId().toString(),null);
            }catch(IOException e){
                throw failure(request.principal(),run.id(),ApiErrorCode.SKILL_ARTIFACT_UNAVAILABLE,"文件产物存储或格式校验失败，请重试并联系管理员",request.attemptId().toString(),e);
            }
        }
        /** 后置授权、容器清理与严格审计均成功后调用，仍不开放下载。 */
        public void complete(){completed=true;}
        public void close(){
            if(!completed)repository.discardCall(run.tenantId(),run.id(),request.modelCallId());
        }
    }
    /** 在 Run 完成的短事务中调用；内存模式审计先完成，任何异常使产物保持不可见。 */
    public void finalizeRun(RunRecord run) {
        if(!policy.isEnabled())return;
        if(run.status()==RunStatus.SUCCEEDED){
            audit.append(run.tenantId(),run.principalId(),"SKILL_ARTIFACT_PUBLISH","RUN",run.id().toString(),"SUCCEEDED","文件产物发布");
            repository.publish(run.tenantId(),run.id(),Instant.now().plus(policy.getRetention()));
        }else if(!run.status().isActive())repository.discard(run.tenantId(),run.id());
    }
    private SkillArtifactStorage store(){
        var value=storage.getIfAvailable();if(value==null)throw new IllegalStateException("文件存储未配置");return value;
    }
    private String mediaType(String name){
        int dot=name.lastIndexOf('.');String extension=dot<0?"":name.substring(dot).toLowerCase(Locale.ROOT);
        if(!policy.getAllowedTypes().contains(extension))return null;
        return switch(extension){
            case ".docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".pptx"->"application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".pdf"->"application/pdf";case ".txt"->"text/plain";case ".csv"->"text/csv";case ".json"->"application/json";default->null;
        };
    }
    /** 鉴权后只返回安全字段；当前状态未成功时不公开暂存文件名。 */
    public Listing listTrial(PrincipalRef principal,UUID skillId,UUID runId){
        requirePermission(principal,"skill:read",runId);
        var trial=trials.find(principal.tenantId(),runId).filter(t->t.skillId().equals(skillId)&&t.createdBy().equals(principal.principalId()))
            .orElseThrow(()->notFound(principal,runId));
        return list(principal,trial.agentId(),runId,skillId);
    }
    /** 正式/TEST 入口均复核当前认证主体与 Run 状态，响应不含磁盘路径。 */
    public Listing list(PrincipalRef principal,UUID agentId,UUID runId,UUID skillId) {
        var run=authorizeRun(principal,agentId,runId,skillId);
        if(!policy.isEnabled())return new Listing("DISABLED",List.of());
        if(run.status()!=RunStatus.SUCCEEDED)return new Listing(run.status().isActive()?"PENDING":"FAILED",List.of());
        var rows=repository.list(principal.tenantId(),runId);Instant now=Instant.now();
        var views=rows.stream().filter(a->a.principalId().equals(principal.principalId()))
            .filter(a->a.status()==SkillArtifactStatus.READY || !a.expiresAt().isAfter(now)&&a.status()!=SkillArtifactStatus.STAGING&&a.status()!=SkillArtifactStatus.PENDING)
            .map(a->new View(a.id(),a.filename(),a.mediaType(),a.sizeBytes(),a.sha256(),a.expiresAt(),a.expiresAt().isAfter(now)?"READY":"EXPIRED")).toList();
        return new Listing(views.isEmpty()&&rows.stream().anyMatch(a->a.status()==SkillArtifactStatus.PENDING||a.status()==SkillArtifactStatus.STAGING)?"PENDING":views.isEmpty()?"EMPTY":"READY",views);
    }
    /** 开始下载前校验当前主体、Run 成功、有效期、租约、完整性和严格审计。 */
    public Download download(PrincipalRef principal,UUID id){
        var a=repository.find(principal.tenantId(),id).filter(x->x.principalId().equals(principal.principalId())).orElseThrow(()->notFound(principal,id));
        UUID skill=a.runKind()==RunKind.TEST?a.skillId():null;
        var run=authorizeRun(principal,a.agentId(),a.runId(),skill);
        if(!policy.isEnabled()||run.status()!=RunStatus.SUCCEEDED)throw notFound(principal,id);
        Instant now=Instant.now();String errorId=UUID.randomUUID().toString();
        if(!a.expiresAt().isAfter(now))throw failure(principal,id,ApiErrorCode.SKILL_ARTIFACT_EXPIRED,"生成文件已过期，请重新生成",errorId,null);
        if(a.status()!=SkillArtifactStatus.READY)throw notFound(principal,id);
        if(!downloads.tryAcquire())throw failure(principal,id,ApiErrorCode.SKILL_ARTIFACT_UNAVAILABLE,"文件下载繁忙，请稍后重试",errorId,null);
        UUID token=UUID.randomUUID();boolean leased=false;byte[] bytes=null;
        try {
            leased=repository.lease(a.tenantId(),id,token,now,now.plus(Duration.ofMinutes(5)),false);
            if(!leased)throw new IOException("文件资源繁忙");
            bytes=store().readVerified(a);
            audit.append(principal.tenantId(),principal.principalId(),"SKILL_ARTIFACT_DOWNLOAD","SKILL_ARTIFACT",id.toString(),"STARTED","errorId="+errorId);
            return new Download(a,bytes,token,principal,errorId);
        }catch(IOException e){
            if(bytes!=null)Arrays.fill(bytes,(byte)0);
            try{if(leased)repository.release(a.tenantId(),id,token,false);}finally{downloads.release();}
            throw failure(principal,id,ApiErrorCode.SKILL_ARTIFACT_UNAVAILABLE,"文件存储、完整性校验或资源租约暂不可用",errorId,e);
        }catch(RuntimeException e){
            if(bytes!=null)Arrays.fill(bytes,(byte)0);
            try{if(leased)repository.release(a.tenantId(),id,token,false);}finally{downloads.release();}
            throw e;
        }
    }
    /** 已验证字节的独占租约，由 Controller 在传输 finally 中关闭，最长驻留五分钟。 */
    public final class Download implements AutoCloseable {
        /** 完整校验后的可信元数据。 */ private final SkillArtifact artifact;
        /** 有界明文，仅当前请求持有并在关闭时擦除。 */ private final byte[] bytes;
        /** 数据库独占租约标识。 */ private final UUID token;
        /** 当前认证主体，用于完成或中断审计。 */ private final PrincipalRef principal;
        /** 下载全程唯一关联编号。 */ private final String errorId;
        /** 只有全部写入并 flush 后才标记成功。 */ private boolean sent;
        /** 防止重复关闭释放另一请求的并发名额。 */ private boolean closed;
        private Download(SkillArtifact a,byte[] b,UUID t,PrincipalRef p,String error){artifact=a;bytes=b;token=t;principal=p;errorId=error;}
        /** @return 安全附件元数据 */ public SkillArtifact artifact(){return artifact;}
        /** @return 前后端及审计一致的下载关联编号 */ public String errorId(){return errorId;}
        /** 同步传输，不向外返回明文字节；中断保留安全日志，之后仍须关闭。 */
        public void write(OutputStream output)throws IOException{
            try{output.write(bytes);output.flush();sent=true;}
            catch(IOException e){failure(principal,artifact.id(),ApiErrorCode.SKILL_ARTIFACT_UNAVAILABLE,"文件传输中断",errorId,e);throw new IOException("文件传输中断");}
        }
        public void close(){
            if(closed)return;closed=true;
            try{audit.append(principal.tenantId(),principal.principalId(),"SKILL_ARTIFACT_DOWNLOAD","SKILL_ARTIFACT",artifact.id().toString(),sent?"SUCCEEDED":"INTERRUPTED","errorId="+errorId);}
            catch(RuntimeException e){throw failure(principal,artifact.id(),ApiErrorCode.AUDIT_UNAVAILABLE,"文件传输审计未完成，请联系管理员",errorId,e);}
            finally{Arrays.fill(bytes,(byte)0);try{repository.release(artifact.tenantId(),artifact.id(),token,false);}finally{downloads.release();}}
        }
    }
    private RunRecord authorizeRun(PrincipalRef p,UUID agent,UUID runId,UUID skill){
        requirePermission(p,"agent:read",runId);
        if(skill!=null)requirePermission(p,"skill:read",runId);
        var run=runs.findByTenantAndAgentAndId(p.tenantId(),agent,runId).filter(r->r.principalId().equals(p.principalId())).orElseThrow(()->notFound(p,runId));
        if(skill==null&&run.kind()==RunKind.TEST)throw notFound(p,runId);
        if(skill!=null){
            var trial=trials.find(p.tenantId(),runId).filter(t->t.skillId().equals(skill)&&t.createdBy().equals(p.principalId())&&t.agentId().equals(agent)).orElseThrow(()->notFound(p,runId));
            if(run.kind()!=RunKind.TEST)throw notFound(p,runId);
        }
        conversations.findByRun(p.tenantId(),runId).ifPresent(c->{if(!c.createdBy().equals(p.principalId())||!c.agentId().equals(agent))throw notFound(p,runId);});
        return run;
    }
    private void requirePermission(PrincipalRef p,String permission,UUID resource){
        if(!permissions.check(p,permission).allowed()){
            audit.append(p.tenantId(),p.principalId(),"SKILL_ARTIFACT_ACCESS","RUN",resource.toString(),"DENIED","权限不足");
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"无权读取生成文件");
        }
    }
    private SkillAccessException notFound(PrincipalRef p,UUID id){
        audit.append(p.tenantId(),p.principalId(),"SKILL_ARTIFACT_ACCESS","SKILL_ARTIFACT",id.toString(),"DENIED","资源不可见");
        return failure(p,id,ApiErrorCode.SKILL_ARTIFACT_NOT_FOUND,"生成文件或运行不存在",UUID.randomUUID().toString(),null);
    }
    private SkillAccessException failure(PrincipalRef p,UUID id,ApiErrorCode code,String message,String errorId,Exception cause){
        var result=new SkillAccessException(code,message,errorId,true);result.claimDiagnostic();
        String context="errorId="+errorId+" operation=SKILL_ARTIFACT errorCode="+code+" tenantId="+p.tenantId()+" principalId="+p.principalId()+" resourceType=SKILL_ARTIFACT resourceId="+id;
        if(cause!=null){
            var safe=new IllegalStateException("文件产物操作失败");safe.setStackTrace(cause.getStackTrace());log.error(context,safe);
        }else log.warn(context);
        return result;
    }
    /** 服务端维护入口；获取独占删除租约才可删文件，不操作存储根之外的任何路径。 */
    @Scheduled(fixedDelayString="#{@skillArtifactPolicy.cleanupInterval.toMillis()}")
    public void cleanup(){
        if(!policy.isEnabled())return;
        Instant now=Instant.now();
        try {
            for(var a:repository.cleanupCandidates(now,now.minus(Duration.ofDays(1)))){
                var run=runs.findByTenantAndAgentAndId(a.tenantId(),a.agentId(),a.runId()).orElse(null);
                if(a.status()==SkillArtifactStatus.PENDING && run!=null && run.status().isActive())continue;
                if(a.status()!=SkillArtifactStatus.READY)repository.discardCall(a.tenantId(),a.runId(),a.callId());
                UUID token=UUID.randomUUID();
                if(!repository.lease(a.tenantId(),a.id(),token,now,now.plus(Duration.ofMinutes(5)),true))continue;
                boolean deleted=false;
                try{store().delete(a);deleted=true;}
                catch(IOException e){var p=new PrincipalRef(a.tenantId(),a.principalId(),"",Set.of());failure(p,a.id(),ApiErrorCode.SKILL_ARTIFACT_UNAVAILABLE,"文件清理失败",UUID.randomUUID().toString(),e);}
                finally{repository.release(a.tenantId(),a.id(),token,deleted);}
            }
        }catch(RuntimeException e){
            var safe=new IllegalStateException("文件产物补偿扫描失败");safe.setStackTrace(e.getStackTrace());
            log.error("errorId={} operation=SKILL_ARTIFACT_CLEANUP errorCode=SKILL_ARTIFACT_UNAVAILABLE tenantId=- resourceType=SKILL_ARTIFACT resourceId=-",UUID.randomUUID(),safe);
        }
    }
    /**
     * 安全列表响应。
     * @param status 无文件、等待发布、失败或可用状态
     * @param files 已授权且有界的文件投影
     */
    public record Listing(String status,List<View> files){public Listing{files=List.copyOf(files);}}
    /**
     * 安全附件字段，不暴露内部密钥或路径。
     * @param id 不可预测文件标识
     * @param filename 安全展示名称
     * @param mediaType 媒体类型
     * @param sizeBytes 明文字节数
     * @param sha256 服务端摘要
     * @param expiresAt 失效时间
     * @param status READY 或 EXPIRED
     */
    public record View(UUID id,String filename,String mediaType,long sizeBytes,String sha256,Instant expiresAt,String status){}
}
