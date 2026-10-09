package com.cmagent.server.web;
import com.cmagent.api.PrincipalRef;
import com.cmagent.server.runtime.SkillArtifactService;
import com.cmagent.server.security.JwtService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** 当前会话鉴权文件入口；所有归属由服务端元数据和 Run 校验，禁止公开静态存储目录。 */
@RestController
public class SkillArtifactController {
    /** 同一权限/完整性/租约编排入口。 */
    private final SkillArtifactService artifacts;
    public SkillArtifactController(SkillArtifactService artifacts){this.artifacts=artifacts;}
    /** 正式单轮或会话运行列表；普通运行历史可见性不自动授权文件读取。 */
    @GetMapping("/api/agents/{agentId}/runs/{runId}/artifacts")
    public SkillArtifactService.Listing run(@PathVariable("agentId")UUID agent,@PathVariable("runId")UUID run,Authentication auth){
        return artifacts.list(principal(auth),agent,run,null);
    }
    /** TEST 列表沿用创建者边界，不授予正式会话权限。 */
    @GetMapping("/api/skills/{skillId}/trials/{runId}/artifacts")
    public SkillArtifactService.Listing trial(@PathVariable("skillId")UUID skill,@PathVariable("runId")UUID run,Authentication auth){
        PrincipalRef principal=principal(auth);
        return artifacts.listTrial(principal,skill,run);
    }
    /** 完整校验在响应提交前完成；流结束后才记录成功，客户端断开仍释放租约。 */
    @GetMapping("/api/skill-artifacts/{artifactId}/content")
    public void content(@PathVariable("artifactId")UUID id,Authentication auth,HttpServletResponse response)throws IOException{
        try(var download=artifacts.download(principal(auth),id)){
            var a=download.artifact();
            String name=a.filename().replace('/','_').replace('\\','_').replace('"','_');
            response.setContentType(a.mediaType());
            response.setContentLengthLong(a.sizeBytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL,"private, no-store");
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("X-Error-Id",download.errorId());
            download.write(response.getOutputStream());
        }
    }
    /** tenant 与 principal 只来自已经通过 Spring Security 校验的会话，不接受请求覆盖。 */
    private PrincipalRef principal(Authentication auth){
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof JwtService.JwtSession session))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"未登录或令牌无效");
        return new PrincipalRef(session.tenantId(),session.principalId(),session.displayName(),Set.copyOf(session.permissions()));
    }
}
