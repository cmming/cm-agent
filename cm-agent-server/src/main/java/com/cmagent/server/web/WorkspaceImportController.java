package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.server.runtime.WorkspaceImportService;
import com.cmagent.server.runtime.WorkspaceImportService.*;
import com.cmagent.server.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/**
 * 原生目录确认后的导入HTTP协议；不接收本机绝对路径、客户端身份或文件系统句柄。
 * 先验证JwtSession和权限/归属，再读取有界原始正文，避免MVC先解析multipart并落盘。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/workspace-imports")
public final class WorkspaceImportController {
    /** 跨仓储/私有存储的受控编排和唯一失败诊断边界。 */
    private final WorkspaceImportService imports;
    /**
     * @param imports 导入编排 */
    public WorkspaceImportController(WorkspaceImportService imports){this.imports=imports;}

    /** @return 只读能力，不携带文件名、manifest或正文 */
    @GetMapping("/capabilities")
    public Capabilities capabilities(@PathVariable("agentId") UUID agent,Authentication authentication,HttpServletRequest request){
        return call(authentication,agent,null,"CAPABILITIES",request,imports::capabilities);
    }
    /** @return 明确确认后的DRAFT及服务端生成的批次/文件标识；本操作不会绑定或启动模型 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@PathVariable("agentId") UUID agent,Authentication authentication,HttpServletRequest request){
        return call(authentication,agent,null,"CREATE",request,c->{
            imports.requireUploadEnabled(c);
            return imports.create(c,imports.json(c,request::getInputStream,request.getContentLengthLong(),request.getContentType(),Create.class,16384));
        });
    }
    /** @return 原主体核对批次结果，上传关闭后仍可读取 */
    @GetMapping("/{importId}")
    public View get(@PathVariable("agentId") UUID agent,@PathVariable("importId") UUID id,Authentication authentication,HttpServletRequest request){
        return call(authentication,agent,id,"GET",request,c->imports.get(c,id));
    }
    /** @return 创建响应丢失时按原确认关联标识找回，不触发重复创建或上传 */
    @GetMapping("/by-request/{requestId}")
    public View byRequest(@PathVariable("agentId") UUID agent,@PathVariable("requestId") UUID requestId,Authentication authentication,HttpServletRequest request){
        return call(authentication,agent,requestId,"GET_BY_REQUEST",request,c->imports.getByRequest(c,requestId));
    }
    /** @return 单个原始application/octet-stream文件的接收进度；写请求不自动重试 */
    @PostMapping("/{importId}/files/{fileId}")
    public View upload(@PathVariable("agentId") UUID agent,@PathVariable("importId") UUID id,@PathVariable("fileId") UUID file,
            @RequestParam("expectedVersion") long version,Authentication authentication,HttpServletRequest request){
        return call(authentication,agent,id,"UPLOAD",request,c->imports.upload(c,id,file,version,request::getInputStream,request.getContentLengthLong(),request.getContentType()));
    }
    /** @return 全文件真实密文复核后的READY，只有当前运行装配和部署策略允许时可绑定 */
    @PostMapping("/{importId}/complete")
    public View complete(@PathVariable("agentId") UUID agent,@PathVariable("importId") UUID id,Authentication authentication,HttpServletRequest request){
        return call(authentication,agent,id,"COMPLETE",request,c->{
            imports.requireOwner(c,id);
            return imports.complete(c,id,imports.json(c,request::getInputStream,request.getContentLengthLong(),request.getContentType(),Version.class,1024).expectedVersion());
        });
    }
    /** @return 先逻辑撤销的状态；物理回收遵循持久租约，上传关闭后仍允许原主体操作 */
    @DeleteMapping("/{importId}")
    public View revoke(@PathVariable("agentId") UUID agent,@PathVariable("importId") UUID id,@RequestParam("expectedVersion") long version,
            Authentication authentication,HttpServletRequest request){
        return call(authentication,agent,id,"REVOKE",request,c->imports.revoke(c,id,version));
    }
    private <T>T call(Authentication authentication,UUID agent,UUID resource,String operation,HttpServletRequest request,Action<T> action){
        // 唯一身份来源是JWT过滤器验证过的JwtSession，不接收HTTP中的tenant或owner覆盖。
        if(authentication==null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        var principal=new PrincipalRef(session.tenantId(),session.principalId(),session.displayName(),Set.copyOf(session.permissions()));
        return imports.execute(principal,agent,resource,operation,RequestCorrelationFilter.errorIdOf(request),action);
    }
}
