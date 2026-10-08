package com.cmagent.server.web;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.*;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.runtime.SandboxCredentials;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.SandboxEndpointService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 端点管理入口；tenant来自JWT，密文与原始认证材料只写不读，不返回领域对象。 */
@RestController
@RequestMapping("/api/skill-sandbox-endpoints")
public class SandboxEndpointController {
    /** 端点编排服务。 */ private final SandboxEndpointService service;
    /** 服务端权限，不以页面按钮代替。 */ private final PermissionEvaluator permissions;
    /** 严格审计。 */ private final AuditAppender audit;
    /** 最终诊断边界与响应使用同一编号。 */ private final ErrorDiagnosticLogger diagnostics;
    /** @param service 服务 @param permissions 权限 @param audit 审计 @param diagnostics 日志 */
    public SandboxEndpointController(SandboxEndpointService service,PermissionEvaluator permissions,AuditAppender audit,ErrorDiagnosticLogger diagnostics){
        this.service=service;this.permissions=permissions;this.audit=audit;this.diagnostics=diagnostics;
    }
    /** @param authentication JWT @param request HTTP上下文 @return 本租户必要的管理元数据 */
    @GetMapping public Listing list(Authentication authentication,HttpServletRequest request){
        var principal=principal(authentication,request);authorize(principal,"sandbox:read","list");
        return boundary(principal,"list",request,()->{
            UUID selected=service.defaultId(principal.tenantId()).orElse(null);
            return new Listing(service.enabled(),service.credentialReady(),service.list(principal.tenantId()).stream().map(e->view(e,selected)).toList(),selected);
        });
    }
    /** @param id 端点 @param authentication JWT @param request HTTP @return 同租户投影 */
    @GetMapping("/{id}") public EndpointView get(@PathVariable("id") UUID id,Authentication authentication,HttpServletRequest request){
        var p=principal(authentication,request);authorize(p,"sandbox:read",id.toString());
        return boundary(p,id.toString(),request,()->view(service.get(p.tenantId(),id),service.defaultId(p.tenantId()).orElse(null)));
    }
    /** @param body 写入输入 @param authentication JWT @param request HTTP @return 不含凭据的新端点 */
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public EndpointView create(@Valid @RequestBody EndpointWrite body,Authentication authentication,HttpServletRequest request){
        var p=principal(authentication,request);authorize(p,"sandbox:write","create");credentialPermission(p,body.credentials(),"create");
        return boundary(p,"create",request,()->view(service.create(p,body.displayName(),body.backend(),body.mode(),body.host(),body.port(),body.username(),body.enabled(),body.credentials(),body.sshAuthType()),null));
    }
    /** @param id 端点 @param body CAS更新 @param authentication JWT @param request HTTP @return 新版本 */
    @PutMapping("/{id}") public EndpointView update(@PathVariable("id") UUID id,@Valid @RequestBody EndpointWrite body,Authentication authentication,HttpServletRequest request){
        var p=principal(authentication,request);authorize(p,"sandbox:write",id.toString());credentialPermission(p,body.credentials(),id.toString());
        return boundary(p,id.toString(),request,()->{
            // 切换为本地模式会删除旧认证材料，不能绕过独立的凭据写权限。
            if(body.mode()==SandboxConnectionMode.LOCAL && service.get(p.tenantId(),id).mode()!=SandboxConnectionMode.LOCAL)
                authorize(p,"sandbox:credential:write",id.toString());
            // 认证类型属于凭据安全边界，不能仅用普通配置写权限切换；归属仍来自JWT tenant。
            if(body.sshAuthType()!=service.get(p.tenantId(),id).sshAuthType())authorize(p,"sandbox:credential:write",id.toString());
            return view(service.update(p,id,body.revision(),body.displayName(),body.backend(),body.mode(),body.host(),body.port(),body.username(),body.enabled(),body.credentials(),body.sshAuthType()),service.defaultId(p.tenantId()).orElse(null));
        });
    }
    /** @param id 端点 @param revision 旧版本 @param authentication JWT @param request HTTP */
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") UUID id,@RequestParam("revision") long revision,Authentication authentication,HttpServletRequest request){
        var p=principal(authentication,request);authorize(p,"sandbox:delete",id.toString());
        boundary(p,id.toString(),request,()->{service.delete(p,id,revision);return null;});
    }
    /** @param body 默认选择 @param authentication JWT @param request HTTP */
    @PutMapping("/default") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefault(@Valid @RequestBody DefaultWrite body,Authentication authentication,HttpServletRequest request){
        var p=principal(authentication,request);authorize(p,"sandbox:write","default");
        boundary(p,"default",request,()->{service.setDefault(p,body.endpointId(),body.revision());return null;});
    }
    /** @param id 已保存端点 @param body 配置版本 @param authentication JWT @param request HTTP @return 安全探测结果 */
    @PostMapping("/{id}/probe") public EndpointView probe(@PathVariable("id") UUID id,@Valid @RequestBody ProbeWrite body,Authentication authentication,HttpServletRequest request){
        var p=principal(authentication,request);authorize(p,"sandbox:test",id.toString());
        return boundary(p,id.toString(),request,()->view(service.probe(p,id,body.revision()),service.defaultId(p.tenantId()).orElse(null)));
    }
    private PrincipalRef principal(Authentication authentication,HttpServletRequest request){
        // JWT会话提供可信租户，任何请求中的tenantId均不参与归属选择。
        if(authentication==null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"未登录或令牌无效");
        // 权限入口的严格审计也可能失败，提前给统一异常边界提供可信主体，不能从请求体取tenant。
        request.setAttribute(ErrorDiagnosticLogger.DiagnosticContext.class.getName(),new ErrorDiagnosticLogger.DiagnosticContext(
            RequestCorrelationFilter.errorIdOf(request),"SANDBOX_MANAGEMENT","INTERNAL_ERROR",session.tenantId().toString(),session.principalId(),"-","-","-","-","CONSOLE"));
        return new PrincipalRef(session.tenantId(),session.principalId(),session.displayName(),Set.copyOf(session.permissions()));
    }
    private void authorize(PrincipalRef p,String permission,String id){
        var decision=permissions.check(p,permission);
        if(!decision.allowed()){audit.accessDenied(p,"SANDBOX_ENDPOINT",id,permission,decision.reason());throw new ResponseStatusException(HttpStatus.FORBIDDEN,"缺少沙箱管理权限");}
    }
    private void credentialPermission(PrincipalRef p,SandboxCredentials c,String id){if(c!=null)authorize(p,"sandbox:credential:write",id);}
    private <T>T boundary(PrincipalRef p,String id,HttpServletRequest request,Supplier<T> operation){
        String errorId=RequestCorrelationFilter.errorIdOf(request);
        var context=new ErrorDiagnosticLogger.DiagnosticContext(errorId,"SANDBOX_MANAGEMENT","INTERNAL_ERROR",
            p.tenantId().toString(),p.principalId(),"-","-",id,"-","CONSOLE");
        request.setAttribute(ErrorDiagnosticLogger.DiagnosticContext.class.getName(),context);
        try{return operation.get();}
        catch(SkillAccessException failure){
            var logger=org.slf4j.LoggerFactory.getLogger(SandboxEndpointController.class);
            if(Set.of(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE,ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED,ApiErrorCode.SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE).contains(failure.code()))
                logger.error("沙箱管理失败 errorId={} operation=SANDBOX_MANAGEMENT errorCode={} tenantId={} principalId={} resourceType=SANDBOX_ENDPOINT resourceId={} reason={}",errorId,failure.code(),p.tenantId(),p.principalId(),id,failure.safeMessage());
            else logger.warn("沙箱管理拒绝 errorId={} operation=SANDBOX_MANAGEMENT errorCode={} tenantId={} principalId={} resourceType=SANDBOX_ENDPOINT resourceId={} reason={}",errorId,failure.code(),p.tenantId(),p.principalId(),id,failure.safeMessage());
            throw new SkillAccessException(failure.code(),failure.safeMessage(),errorId,false);
        }catch(ResponseStatusException denied){
            // 凭据权限可在读取当前协议后判断；保留已审计的权限拒绝状态，不能转换成500。
            throw denied;
        }catch(RuntimeException failure){
            ApiErrorCode code=failure instanceof AuditPersistenceException?ApiErrorCode.AUDIT_UNAVAILABLE:
                failure instanceof org.springframework.dao.DataAccessException?ApiErrorCode.PERSISTENCE_UNAVAILABLE:ApiErrorCode.INTERNAL_ERROR;
            // 原始客户端异常可能含内部端点或材料；保留发生位置堆栈但不传播异常消息及cause。
            var safe=new RuntimeException("沙箱管理内部依赖失败");safe.setStackTrace(failure.getStackTrace());
            diagnostics.error(new ErrorDiagnosticLogger.DiagnosticContext(errorId,"SANDBOX_MANAGEMENT",code.name(),
                p.tenantId().toString(),p.principalId(),"-","-","-","-","CONSOLE"),safe,"SANDBOX_ENDPOINT",id);
            throw new SkillAccessException(code,code==ApiErrorCode.AUDIT_UNAVAILABLE?"审计服务暂不可用":code==ApiErrorCode.PERSISTENCE_UNAVAILABLE?"数据服务暂不可用":"沙箱管理服务异常，请凭错误编号联系管理员",errorId,false);
        }
    }
    private EndpointView view(SandboxEndpoint e,UUID selected){
        return new EndpointView(e.id(),e.displayName(),e.backend(),e.mode(),e.host(),e.port(),e.username(),e.enabled(),
            e.mode()==SandboxConnectionMode.LOCAL || !e.encryptedCredential().isBlank(),e.credentialVersion(),e.revision(),e.probeRevision(),
            e.probedAt(),e.probeStatus(),e.id().equals(selected),e.createdAt(),e.updatedAt(),e.sshAuthType());
    }
    /**
     * @param displayName 名称 @param backend 注册后端 @param mode 协议 @param host 主机 @param port 端口
     * @param username SSH用户 @param enabled 启用 @param revision 更新旧版本，创建为零 @param credentials 只写认证材料，省略则保留 @param sshAuthType SSH认证类型，旧请求省略KEY
     */
    public record EndpointWrite(@NotBlank @Size(max=160)String displayName,@NotBlank @Size(max=64)String backend,
        @NotNull SandboxConnectionMode mode,@NotNull @Size(max=255)String host,@Min(0)@Max(65535)int port,
        @NotNull @Size(max=80)String username,boolean enabled,@Min(0)long revision,SandboxCredentials credentials,SandboxSshAuthType sshAuthType){
        public EndpointWrite {sshAuthType=sshAuthType==null?SandboxSshAuthType.KEY:sshAuthType;}
    }
    /** @param endpointId 默认端点，空值取消 @param revision 当前端点版本 */
    public record DefaultWrite(UUID endpointId,@Min(0)long revision){}
    /** @param revision 探测版本 */ public record ProbeWrite(@Min(1)long revision){}
    /** @param enabled 部署总开关 @param credentialKeyConfigured 主密钥就绪 @param items 租户端点 @param defaultEndpointId 当前默认 */
    public record Listing(boolean enabled,boolean credentialKeyConfigured,List<EndpointView> items,UUID defaultEndpointId){}
    /**
     * @param id 端点 @param displayName 名称 @param backend 后端 @param mode 协议 @param host 本租户管理地址
     * @param port 端口 @param username 用户 @param enabled 启用 @param hasCredential 认证就绪
     * @param credentialVersion 材料版本 @param revision 配置版本 @param probeRevision 成功探测版本
     * @param probedAt 探测时间 @param probeStatus 安全状态 @param defaultEndpoint 是否默认 @param createdAt 创建时间 @param updatedAt 更新时间 @param sshAuthType SSH认证类型，不包含密码
     */
    public record EndpointView(UUID id,String displayName,String backend,SandboxConnectionMode mode,String host,int port,String username,
        boolean enabled,boolean hasCredential,long credentialVersion,long revision,long probeRevision,Instant probedAt,String probeStatus,
        boolean defaultEndpoint,Instant createdAt,Instant updatedAt,SandboxSshAuthType sshAuthType){}
}
