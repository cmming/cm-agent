package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Objects;

/** 项目历史资源的私有读取闸门；核验来源而不读取本机文件，归档历史仍可查看。 */
public final class LocalProjectResourceAccess {
    /** 长期固定来源，不依赖短时检查点。 */ private final LocalProjectBindingRepository bindings;
    /** 会话头与固定Agent/创建者。 */ private final ConversationRepository conversations;
    /** Run原始发起者及头见证。 */ private final RunRepository runs;
    /** 项目owner/RBAC与审计。 */ private final LocalProjectAccessService access;
    /** HTTP读取也必须来自明确本机入口；内部异步流程仍使用可信主体和来源。 */ private final LocalProjectRequestBoundary boundary;

    LocalProjectResourceAccess(LocalProjectBindingRepository bindings, ConversationRepository conversations, RunRepository runs,
            LocalProjectAccessService access, LocalProjectRequestBoundary boundary) {
        this.bindings = bindings; this.conversations = conversations; this.runs = runs; this.access = access; this.boundary = boundary;
    }

    /** 来源头损坏时仍把已知项目资源送入严格核验，不能以缺失绑定/降级头回退普通会话。 */
    boolean applies(Conversation conversation){return conversations.workspaceProtocol(conversation.tenantId(),conversation.agentId(),conversation.id())==LocalProjectBinding.PROTOCOL
            ||conversations.hasLocalProjectSource(conversation.tenantId(),conversation.agentId(),conversation.id());}

    /** 核验当前主体与完整来源，不因管理员权限或目录暂时离线而扩大/缩小历史owner边界。 */
    LocalProjectBinding conversation(PrincipalRef principal, Conversation conversation) {
        requireLocalRequest(principal, conversation.id());
        var binding = bindings.conversation(principal.tenantId(), principal.principalId(), conversation.id()).orElse(null);
        if (binding == null || !principal.tenantId().equals(conversation.tenantId()) || !principal.principalId().equals(conversation.createdBy())
                || !binding.agentId().equals(conversation.agentId()) || binding.runId() != null
                || conversations.workspaceProtocol(conversation.tenantId(), conversation.agentId(), conversation.id()) != LocalProjectBinding.PROTOCOL)
            throw missing(principal, conversation.id());
        LocalProject project = access.project(principal, binding.projectId(), "project:read");
        if (!matches(project, binding)) throw missing(principal, conversation.id());
        return binding;
    }

    /** 读取Run必须同时核对原会话来源，缺失任何见证不能回退DEFAULT或按当前目录猜测。 */
    LocalProjectBinding run(PrincipalRef principal, RunRecord run) {
        requireLocalRequest(principal, run.id());
        var binding = bindings.run(principal.tenantId(), principal.principalId(), run.id()).orElse(null);
        if (binding == null || !principal.tenantId().equals(run.tenantId()) || !principal.principalId().equals(run.principalId())
                || !binding.agentId().equals(run.agentId()) || !Objects.equals(binding.runId(), run.id())
                || runs.workspaceProtocol(run.tenantId(), run.agentId(), run.id()) != LocalProjectBinding.PROTOCOL)
            throw missing(principal, run.id());
        var origin = conversations.findByTenantAndAgentAndId(principal.tenantId(), run.agentId(), binding.conversationId()).orElse(null);
        if (origin == null) throw missing(principal, run.id());
        var source = conversation(principal, origin);
        if (!source.projectId().equals(binding.projectId()) || !source.rootBindingId().equals(binding.rootBindingId())
                || !source.rootIdentity().equals(binding.rootIdentity()) || !source.nodeId().equals(binding.nodeId())
                || source.projectVersion() != binding.projectVersion()) throw missing(principal, run.id());
        return binding;
    }

    private void requireLocalRequest(PrincipalRef principal, java.util.UUID id) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes && !boundary.permits(attributes.getRequest()))
            throw access.reject(principal, id, "PROJECT_LOCAL_REQUEST_REQUIRED", 403, "请从已配置的本机控制台读取项目历史");
    }
    private LocalProjectAccessException missing(PrincipalRef principal, java.util.UUID id) {
        return access.reject(principal, id, "PROJECT_RESOURCE_NOT_FOUND", 404, "项目资源不存在或不可访问");
    }
    private static boolean matches(LocalProject project, LocalProjectBinding binding) {
        return project.tenantId().equals(binding.tenantId()) && project.ownerId().equals(binding.ownerId())
                && project.rootBindingId().equals(binding.rootBindingId()) && project.rootIdentity().equals(binding.rootIdentity())
                && project.nodeId().equals(binding.nodeId());
    }
}
