package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已配对设备项目会话、Run 来源和固定工具快照。
 *
 * <p>工具定义每次从固定目录和当前 grant 重建；副作用工具仍必须经过原有
 * HIGH 审批。关闭功能或绑定损坏时不回退旧项目或普通会话。</p>
 */
public final class ClientProjectRunService {
    private final RunRepository runs;
    private final ClientProjectRepository projects;
    private final ClientProjectBindingRepository bindings;
    private final AgentDefinitionRepository agents;
    private final ConversationRepository conversations;
    private final ToolGrantRepository grants;
    private final ClientProjectToolCatalog catalog;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ClientProjectReadSnapshots snapshots;
    /** 生产装配时在 Run、来源和首代归属的同一事务内建立执行许可。 */
    private final ClientProjectRunOwnershipRepository ownership;
    private final ClientProjectRuntimeIdentity runtimeIdentity;
    private final Duration ownershipLease;
    /** HTTP 派生入口同样要求精确来源和专用行动头；内部异步调用仍只使用持久来源与可信主体。 */
    private ClientProjectRequestBoundary boundary;

    @org.springframework.beans.factory.annotation.Autowired
    void configureBoundary(org.springframework.beans.factory.ObjectProvider<ClientProjectRequestBoundary> provider) {
        boundary = provider.getIfAvailable();
    }

    ClientProjectRunService(RunRepository runs, ClientProjectRepository projects,
            ClientProjectBindingRepository bindings, AgentDefinitionRepository agents,
            ConversationRepository conversations, ToolGrantRepository grants,
            ClientProjectToolCatalog catalog, PermissionEvaluator permissions,
            AuditAppender audit, TransactionTemplate transaction, Clock clock,
            ClientProjectReadSnapshots snapshots) {
        this(runs, projects, bindings, agents, conversations, grants, catalog, permissions,
                audit, transaction, clock, snapshots, null, null, null);
    }

    /**
     * 生产构造器固定当前实例和租期，使协议 5 Run 来源与首代归属不可分离。
     *
     * @param ownership Run 执行归属仓储
     * @param runtimeIdentity 当前 Server 进程随机身份
     * @param ownershipLease 一秒至五分钟的首代租期
     */
    ClientProjectRunService(RunRepository runs, ClientProjectRepository projects,
            ClientProjectBindingRepository bindings, AgentDefinitionRepository agents,
            ConversationRepository conversations, ToolGrantRepository grants,
            ClientProjectToolCatalog catalog, PermissionEvaluator permissions,
            AuditAppender audit, TransactionTemplate transaction, Clock clock,
            ClientProjectReadSnapshots snapshots, ClientProjectRunOwnershipRepository ownership,
            ClientProjectRuntimeIdentity runtimeIdentity, Duration ownershipLease) {
        this.runs = runs;
        this.projects = projects;
        this.bindings = bindings;
        this.agents = agents;
        this.conversations = conversations;
        this.grants = grants;
        this.catalog = catalog;
        this.permissions = permissions;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
        this.snapshots = snapshots;
        this.ownership = ownership;
        this.runtimeIdentity = runtimeIdentity;
        this.ownershipLease = ownershipLease;
    }

    /** 在短事务外验证会话来源；返回带当前 Run 的固定绑定。 */
    ClientProjectBinding preflight(PrincipalRef principal, Conversation conversation, UUID runId) {
        var binding = bindings.conversation(principal.tenantId(), principal.principalId(),
                conversation.id()).orElse(null);
        if (binding == null) {
            if (conversations.workspaceProtocol(principal.tenantId(), conversation.agentId(),
                    conversation.id()) == ClientProjectBinding.PROTOCOL) {
                throw reject(principal, conversation.id(), "CLIENT_PROJECT_BINDING_MISSING", 409,
                        "设备项目会话来源绑定缺失");
            }
            return null;
        }
        ClientProject project = projects.find(principal.tenantId(), principal.principalId(),
                binding.projectId()).orElseThrow(() -> reject(principal, binding.projectId(),
                        "CLIENT_PROJECT_NOT_FOUND", 404, "设备项目不存在或不可访问"));
        if (project.state() != ClientProject.State.ACTIVE) {
            throw reject(principal, project.id(), "CLIENT_PROJECT_ARCHIVED", 409, "设备项目已归档");
        }
        authorize(principal, "project:read", project.id());
        authorize(principal, "agent:run", conversation.id());
        if (!binding.agentId().equals(conversation.agentId())
                || !binding.deviceId().equals(project.deviceId())
                || !binding.clientRootId().equals(project.clientRootId())
                || !binding.rootIdentity().equals(project.rootIdentity())) {
            throw reject(principal, conversation.id(), "CLIENT_PROJECT_BINDING_CONFLICT", 409,
                    "设备项目会话来源已变化，请新建会话");
        }
        return new ClientProjectBinding(binding.tenantId(), binding.ownerId(), binding.projectId(),
                binding.agentId(), binding.conversationId(), runId, binding.deviceId(),
                binding.clientRootId(), binding.rootIdentity(), binding.projectVersion(), clock.instant());
    }

    /** 历史读取核验长期来源；归档和执行快照清理不撤销原主体的历史读取权。 */
    void verifyConversation(PrincipalRef principal, Conversation conversation) {
        requireBrowserRequest(principal, conversation.id());
        if (!principal.tenantId().equals(conversation.tenantId())
                || !principal.principalId().equals(conversation.createdBy())) {
            throw reject(principal, conversation.id(), "CLIENT_PROJECT_CONVERSATION_NOT_FOUND", 404,
                    "设备项目会话不存在或不可访问");
        }
        if (conversations.workspaceProtocol(principal.tenantId(), conversation.agentId(),
                conversation.id()) != ClientProjectBinding.PROTOCOL) {
            throw reject(principal, conversation.id(), "CLIENT_PROJECT_BINDING_CORRUPT", 409,
                    "设备项目会话来源见证损坏");
        }
        var binding = bindings.conversation(principal.tenantId(), principal.principalId(), conversation.id())
                .orElseThrow(() -> reject(principal, conversation.id(), "CLIENT_PROJECT_BINDING_MISSING", 409,
                        "设备项目会话来源绑定缺失"));
        authorize(principal, "project:read", binding.projectId());
        var project = projects.find(principal.tenantId(), principal.principalId(), binding.projectId())
                .orElseThrow(() -> reject(principal, binding.projectId(), "CLIENT_PROJECT_NOT_FOUND", 404,
                        "设备项目不存在或不可访问"));
        if (!binding.agentId().equals(conversation.agentId()) || binding.runId() != null
                || !binding.conversationId().equals(conversation.id())
                || !project.deviceId().equals(binding.deviceId())
                || !project.clientRootId().equals(binding.clientRootId())
                || !project.rootIdentity().equals(binding.rootIdentity())) {
            throw reject(principal, conversation.id(), "CLIENT_PROJECT_BINDING_CONFLICT", 409,
                    "设备项目会话来源已变化");
        }
    }

    /** 事务内锁定项目并复核会话绑定。 */
    void lock(ClientProjectBinding binding) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("设备项目首发必须使用JDBC短事务");
        }
        ClientProject project = projects.lock(binding.tenantId(), binding.ownerId(),
                binding.projectId()).orElseThrow();
        if (project.state() != ClientProject.State.ACTIVE
                || !project.deviceId().equals(binding.deviceId())
                || !project.clientRootId().equals(binding.clientRootId())
                || !project.rootIdentity().equals(binding.rootIdentity())) {
            throw new IllegalStateException("设备项目首发来源已变化");
        }
    }

    /** 与Run及用户消息同一短事务保存协议5见证。 */
    void save(ClientProjectBinding binding) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !bindings.insertRun(binding)) {
            throw new IllegalStateException("设备项目Run来源无法原子保存");
        }
        snapshots.initialize(new PrincipalRef(binding.tenantId(), binding.ownerId(),
                binding.ownerId(), Set.of()), binding);
        if (ownership != null && runtimeIdentity != null && ownershipLease != null
                && ownership.insertInitial(binding, runtimeIdentity.instanceId(), ownershipLease).isEmpty()) {
            throw new IllegalStateException("设备项目Run首代执行归属无法原子保存");
        }
    }

    /** @return 当前Run匹配的设备项目和只读工具集合；普通Run为空 */
    ProjectRuntimeContext prepare(PrincipalRef principal, RunRecord run, UUID conversationId,
            List<ToolDefinition> authorized, boolean resume) {
        return prepare(principal, run, conversationId, authorized, null, resume);
    }

    /** 首发和审批恢复都只允许副作用前确认模式。 */
    ProjectRuntimeContext prepare(PrincipalRef principal, RunRecord run, UUID conversationId,
            List<ToolDefinition> authorized, OperationAuthorizationMode requestedMode,
            boolean resume) {
        var binding = bindings.run(principal.tenantId(), principal.principalId(), run.id())
                .orElse(null);
        if (binding == null) {
            if (runs.workspaceProtocol(run.tenantId(), run.agentId(), run.id())
                    == ClientProjectBinding.PROTOCOL) {
                throw reject(principal, run.id(), "CLIENT_PROJECT_BINDING_MISSING", 409,
                        "设备项目Run来源绑定缺失");
            }
            return null;
        }
        if (runs.workspaceProtocol(run.tenantId(), run.agentId(), run.id())
                != ClientProjectBinding.PROTOCOL) {
            throw reject(principal, run.id(), "CLIENT_PROJECT_BINDING_CORRUPT", 409,
                    "设备项目Run来源见证损坏");
        }
        authorize(principal, "project:read", binding.projectId());
        var project = projects.find(principal.tenantId(), principal.principalId(), binding.projectId())
                .orElseThrow(() -> reject(principal, binding.projectId(),
                        "CLIENT_PROJECT_NOT_FOUND", 404, "设备项目不存在或不可访问"));
        if (project.state() != ClientProject.State.ACTIVE
                || !project.deviceId().equals(binding.deviceId())
                || !project.clientRootId().equals(binding.clientRootId())
                || !project.rootIdentity().equals(binding.rootIdentity())) {
            throw reject(principal, run.id(), "CLIENT_PROJECT_BINDING_CONFLICT", 409,
                    "设备项目Run来源已变化");
        }
        if (!binding.agentId().equals(run.agentId())
                || !binding.conversationId().equals(conversationId)
                || !binding.ownerId().equals(run.principalId())) {
            throw reject(principal, run.id(), "CLIENT_PROJECT_BINDING_CONFLICT", 409,
                    "设备项目Run来源不一致");
        }
        var source = bindings.conversation(principal.tenantId(), principal.principalId(),
                conversationId).orElseThrow(() -> reject(principal, conversationId,
                        "CLIENT_PROJECT_BINDING_MISSING", 409, "设备项目会话来源绑定缺失"));
        if (!sameOrigin(source, binding)) {
            throw reject(principal, run.id(), "CLIENT_PROJECT_BINDING_CONFLICT", 409,
                    "设备项目Run与会话来源不一致");
        }
        if (requestedMode != null && requestedMode != OperationAuthorizationMode.ASK_ON_EFFECT) {
            throw reject(principal, run.id(), "CLIENT_PROJECT_AUTHORIZATION_MODE", 403,
                    "已配对设备项目只允许副作用前确认模式");
        }
        var allowed = catalog.select(principal.tenantId(), authorized);
        snapshots.initialize(principal, binding);
        return new ProjectRuntimeContext(binding,
                allowed.stream().map(ToolDefinition::id).collect(java.util.stream.Collectors.toSet()),
                OperationAuthorizationMode.ASK_ON_EFFECT);
    }

    /** @return 当前Run是否仍持有精确设备项目只读工具 */
    boolean allows(PrincipalRef principal, RunRecord run, UUID toolId) {
        var binding = bindings.run(principal.tenantId(), principal.principalId(), run.id())
                .orElse(null);
        if (binding == null || !binding.agentId().equals(run.agentId())
                || !ClientProjectToolCatalog.managed(principal.tenantId(), toolId)) return false;
        return projects.find(principal.tenantId(), principal.principalId(), binding.projectId())
                .filter(project -> project.state() == ClientProject.State.ACTIVE
                    && project.deviceId().equals(binding.deviceId())
                    && project.clientRootId().equals(binding.clientRootId())
                    && project.rootIdentity().equals(binding.rootIdentity()))
                .isPresent();
    }

    void cleanup(PrincipalRef principal, RunRecord run) {
        snapshots.cleanup(principal, run);
    }

    void retain(PrincipalRef principal, RunRecord run, ProjectRuntimeContext context) {
        snapshots.verify(principal, (ClientProjectBinding) context.sourceBinding());
    }

    OperationAuthorizationMode authorizationMode(PrincipalRef principal, RunRecord run) {
        return bindings.run(principal.tenantId(), principal.principalId(), run.id())
                .map(ignored -> OperationAuthorizationMode.ASK_ON_EFFECT).orElse(null);
    }

    void verifyResource(PrincipalRef principal, RunRecord run) {
        requireBrowserRequest(principal, run.id());
        if (!principal.tenantId().equals(run.tenantId())
                || !principal.principalId().equals(run.principalId())
                || runs.workspaceProtocol(run.tenantId(), run.agentId(), run.id()) != ClientProjectBinding.PROTOCOL) {
            throw reject(principal, run.id(), "CLIENT_PROJECT_BINDING_CORRUPT", 409,
                    "设备项目Run来源见证损坏");
        }
        var binding = bindings.run(principal.tenantId(), principal.principalId(), run.id())
                .orElseThrow(() -> reject(principal, run.id(), "CLIENT_PROJECT_BINDING_MISSING",
                        409, "设备项目Run来源绑定缺失"));
        if (!binding.agentId().equals(run.agentId()) || !run.id().equals(binding.runId())) {
            throw reject(principal, run.id(), "CLIENT_PROJECT_BINDING_CONFLICT", 409,
                    "设备项目Run来源不一致");
        }
        var conversation = conversations.findByTenantAndAgentAndId(principal.tenantId(), run.agentId(),
                binding.conversationId()).orElseThrow(() -> reject(principal, run.id(),
                        "CLIENT_PROJECT_BINDING_MISSING", 409, "设备项目Run原会话缺失"));
        verifyConversation(principal, conversation);
        var source = bindings.conversation(principal.tenantId(), principal.principalId(), conversation.id())
                .orElseThrow();
        if (!sameOrigin(source, binding)) {
            throw reject(principal, run.id(), "CLIENT_PROJECT_BINDING_CONFLICT", 409,
                    "设备项目Run来源已变化");
        }
        // 派生资源读取依赖长期见证；执行快照由 prepare/retain 单独核验，终态清理后仍可查历史。
    }

    public List<Conversation> listConversations(PrincipalRef principal, UUID projectId,
            ConversationPageRequest page) {
        requireProject(principal, projectId, "project:read");
        authorize(principal, "agent:read", projectId);
        return bindings.listConversations(principal.tenantId(), principal.principalId(),
                projectId, page);
    }

    Conversation conversation(PrincipalRef principal, UUID projectId, UUID conversationId) {
        requireProject(principal, projectId, "project:read");
        var binding = bindings.conversation(principal.tenantId(), principal.principalId(),
                conversationId).orElse(null);
        if (binding == null || !binding.projectId().equals(projectId)) {
            throw reject(principal, conversationId, "CLIENT_PROJECT_CONVERSATION_NOT_FOUND",
                    404, "设备项目会话不存在或不可访问");
        }
        return conversations.findByTenantAndAgentAndId(principal.tenantId(), binding.agentId(),
                conversationId).orElseThrow(() -> reject(principal, conversationId,
                        "CLIENT_PROJECT_CONVERSATION_NOT_FOUND", 404,
                        "设备项目会话不存在或不可访问"));
    }

    /**
     * 创建固定设备项目会话，并只授予固定目录中的读写工具；应用工具仍受 HIGH 审批。
     */
    public Conversation createConversation(PrincipalRef principal, UUID projectId, UUID agentId,
            UUID requestId) {
        ClientProject project = requireProject(principal, projectId, "project:read");
        authorize(principal, "agent:read", projectId);
        authorize(principal, "agent:run", projectId);
        authorize(principal, "project:manage", projectId);
        authorize(principal, "tool:grant", projectId);
        if (agents.findByTenantAndId(principal.tenantId(), agentId).isEmpty()) {
            throw reject(principal, projectId, "CLIENT_PROJECT_AGENT_NOT_FOUND", 404,
                    "Agent不存在或不可访问");
        }
        return transaction.execute(status -> {
            var existing = bindings.conversation(principal.tenantId(),
                    principal.principalId(), requestId).orElse(null);
            if (existing != null) {
                if (!existing.projectId().equals(projectId)
                        || !existing.agentId().equals(agentId)) {
                    throw reject(principal, requestId, "CLIENT_PROJECT_REQUEST_CONFLICT",
                            409, "同一请求编号已用于其他设备项目会话");
                }
                return conversations.findByTenantAndAgentAndId(principal.tenantId(), agentId,
                        requestId).orElseThrow(() -> reject(principal, requestId,
                                "CLIENT_PROJECT_CONVERSATION_NOT_FOUND", 404,
                                "设备项目会话不存在或不可访问"));
            }
            Instant now = clock.instant();
            ensureTools(principal, projectId, agentId);
            var conversation = new Conversation(requestId, principal.tenantId(), agentId,
                    Conversation.DEFAULT_TITLE, principal.principalId(), now, now);
            conversations.save(principal.tenantId(), conversation);
            ClientProjectBinding binding = new ClientProjectBinding(principal.tenantId(),
                    principal.principalId(), project.id(), agentId, conversation.id(), null,
                    project.deviceId(), project.clientRootId(), project.rootIdentity(),
                    project.version(), now);
            if (!bindings.insertConversation(binding)) {
                throw new IllegalStateException("设备项目会话绑定冲突");
            }
            audit(principal, projectId, "CLIENT_PROJECT_CONVERSATION_CREATE", "SUCCEEDED");
            return conversation;
        });
    }

    private void ensureTools(PrincipalRef principal, UUID projectId, UUID agentId) {
        List<ToolDefinition> available = catalog.install(principal);
        Set<UUID> granted = grants.listByTenantAndAgent(principal.tenantId(), agentId).stream()
                .filter(ToolGrant::granted).map(ToolGrant::toolId)
                .collect(java.util.stream.Collectors.toSet());
        for (ToolDefinition tool : available) {
            if (granted.contains(tool.id())) continue;
            var existing = grants.listByTenantAgentAndTool(principal.tenantId(), agentId,
                    tool.id()).stream().findFirst().orElse(null);
            if (existing != null) grants.delete(principal.tenantId(), agentId, tool.id());
            grants.save(new ToolGrant(principal.tenantId(), tool.id(), agentId, null, true));
            agents.addToolToAgent(principal.tenantId(), agentId, tool.id());
            audit.appendControlled(principal.tenantId(), principal.principalId(),
                    "CLIENT_PROJECT_AGENT_TOOL_GRANT", "TOOL", tool.id().toString(),
                    "SUCCEEDED", "project=" + projectId + " agent=" + agentId
                            + " tool=" + tool.name());
        }
    }

    private void requireBrowserRequest(PrincipalRef principal, UUID resourceId) {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes
                && (boundary == null || !boundary.permits(attributes.getRequest()))) {
            throw reject(principal, resourceId, "CLIENT_PROJECT_REQUEST_REQUIRED", 403,
                    "请从已配置的设备项目控制台读取项目资源");
        }
    }

    private ClientProject requireProject(PrincipalRef principal, UUID projectId, String permission) {
        authorize(principal, permission, projectId);
        return projects.find(principal.tenantId(), principal.principalId(), projectId)
                .orElseThrow(() -> reject(principal, projectId, "CLIENT_PROJECT_NOT_FOUND", 404,
                        "设备项目不存在或不可访问"));
    }

    private void authorize(PrincipalRef principal, String permission, UUID resourceId) {
        if (!permissions.check(principal, permission).allowed()) {
            throw reject(principal, resourceId, "CLIENT_PROJECT_FORBIDDEN", 403,
                    "当前账号缺少设备项目操作权限");
        }
    }

    private LocalProjectAccessException reject(PrincipalRef principal, UUID resourceId,
            String code, int status, String message) {
        String errorId = UUID.randomUUID().toString();
        audit.appendControlled(principal.tenantId(), principal.principalId(),
                "CLIENT_PROJECT_RUN_ACCESS", "CLIENT_PROJECT", resourceId.toString(), "DENIED",
                "errorCode=" + code + " errorId=" + errorId);
        return new LocalProjectAccessException(code, errorId, status, message);
    }

    private void audit(PrincipalRef principal, UUID resourceId, String action, String status) {
        audit.appendControlled(principal.tenantId(), principal.principalId(), action,
                "CLIENT_PROJECT", resourceId.toString(), status, "设备项目显式操作");
    }

    private static boolean sameOrigin(ClientProjectBinding first, ClientProjectBinding second) {
        return first.projectId().equals(second.projectId())
                && first.agentId().equals(second.agentId())
                && first.deviceId().equals(second.deviceId())
                && first.clientRootId().equals(second.clientRootId())
                && first.rootIdentity().equals(second.rootIdentity())
                && first.projectVersion() == second.projectVersion();
    }
}
