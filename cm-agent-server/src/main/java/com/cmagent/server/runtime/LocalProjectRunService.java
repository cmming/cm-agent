package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/** 固定项目会话/Run来源和本轮工具集合，审批恢复不接受换根或新增grant。 */
final class LocalProjectRunService {
    /** 与AgentState共用Run状态槽但使用独立key，终态/过期清理不留下原文。 */
    private static final String KEY = "local_project_runtime_v1", TYPE = "cm-agent-local-project-runtime-v1";
    /** 长期来源头，不能从当前Agent工作区推断。 */ private final RunRepository runs;
    /** 项目来源绑定只新增。 */ private final LocalProjectBindingRepository bindings;
    /** 首发锁定项目再会话/Run，防止归档/根检查后交错。 */ private final LocalProjectRepository projects;
    /** 可信历史归属检查，不读文件。 */ private final LocalProjectResourceAccess resources;
    /** 实时owner/节点/根身份和权限。 */ private final LocalProjectAccessService access;
    /** 项目存在运行中或未知副作用时，禁止创建另一轮继续写。 */ private final LocalProjectOperationRepository operations;
    /** 固定工具语义与精确ID。 */ private final LocalProjectToolCatalog catalog;
    /** 已配置持久主密钥与JSON序列化，不写路径。 */ private final ModelCredentialCipher cipher;
    private final ObjectMapper json;
    /** 审批暂停跨重启恢复原始工具集合。 */ private final RuntimeCheckpointRepository checkpoints;
    /** 可信固定期限，不随轮询或恢复续期。 */ private final Clock clock;
    private final Duration ttl;
    /** 生产固定首次文件版本；旧手工合同测试不装配时只验证工具快照。 */ private final LocalProjectReadSnapshots reads;

    LocalProjectRunService(RunRepository runs, LocalProjectBindingRepository bindings, LocalProjectRepository projects,
            LocalProjectResourceAccess resources, LocalProjectAccessService access, LocalProjectOperationRepository operations,
            LocalProjectToolCatalog catalog, ModelCredentialCipher cipher, ObjectMapper json, RuntimeCheckpointRepository checkpoints,
            Clock clock, Duration ttl) {
        this(runs,bindings,projects,resources,access,operations,catalog,cipher,json,checkpoints,clock,ttl,null);
    }
    LocalProjectRunService(RunRepository runs, LocalProjectBindingRepository bindings, LocalProjectRepository projects,
            LocalProjectResourceAccess resources, LocalProjectAccessService access, LocalProjectOperationRepository operations,
            LocalProjectToolCatalog catalog, ModelCredentialCipher cipher, ObjectMapper json, RuntimeCheckpointRepository checkpoints,
            Clock clock, Duration ttl,LocalProjectReadSnapshots reads) {
        this.runs = runs; this.bindings = bindings; this.projects = projects; this.resources = resources; this.access = access;
        this.operations = operations; this.catalog = catalog; this.cipher = cipher; this.json = json; this.checkpoints = checkpoints;
        this.clock = clock; this.ttl = ttl;
        this.reads=reads;
    }

    /** 首发前在事务外核对本机根；返回原会话来源，不因重命名或当前选择改变Agent/根。 */
    LocalProjectBinding preflight(PrincipalRef principal, Conversation conversation, UUID runId) {
        var binding = bindings.conversation(principal.tenantId(), principal.principalId(), conversation.id()).orElse(null);
        if (binding == null) {if(resources.applies(conversation))resources.conversation(principal,conversation);return null;}
        var source = resources.conversation(principal, conversation);
        access.scope(principal, source.projectId(), "project:read");
        access.authorize(principal, "agent:run", conversation.id());
        if (operations.hasUncertainOperation(principal.tenantId(), principal.principalId(), source.projectId(), null))
            throw access.reject(principal, source.projectId(), "PROJECT_OPERATION_UNCERTAIN", 409, "项目仍有运行中或结果未知的操作，请先核对原操作");
        return new LocalProjectBinding(source.tenantId(), source.ownerId(), source.projectId(), source.agentId(), source.conversationId(), runId,
                source.rootBindingId(), source.rootIdentity(), source.nodeId(), source.projectVersion(), clock.instant());
    }

    /** 元数据短事务内先锁项目；根/文件I/O已在preflight完成且真实执行前仍须重查。 */
    void lock(LocalProjectBinding binding) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("项目首发必须使用JDBC短事务");
        var project = projects.lock(binding.tenantId(), binding.ownerId(), binding.projectId()).orElseThrow();
        if (project.state() != LocalProject.State.ACTIVE || !project.rootBindingId().equals(binding.rootBindingId())
                || !project.rootIdentity().equals(binding.rootIdentity()) || !project.nodeId().equals(binding.nodeId()))
            throw new IllegalStateException("项目首发来源已变化");
    }
    /** 与最初Run/USER消息同事务将来源头固定为4，禁止外部在0来源已提交后补绑定。 */
    void save(LocalProjectBinding binding) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !bindings.insertRun(binding))
            throw new IllegalStateException("项目运行来源无法原子保存");
    }

    /** 首次建立工具集合；恢复只取旧密文，缺失、过期、授权撤销和固定定义变化均拒绝。 */
    ProjectRuntimeContext prepare(PrincipalRef principal, RunRecord run, UUID conversationId,
            List<ToolDefinition> authorized, boolean resume) {
        return prepare(principal, run, conversationId, authorized, null, resume);
    }

    /**
     * 首次准备把浏览器提交的授权方式写入原 Run 的加密快照；审批恢复只读取原快照。
     *
     * <p>模式本身不授予工具权限，工具集合仍由授权快照冻结；恢复时传入任何不同模式都会失败关闭。</p>
     *
     * @param principal 可信认证主体
     * @param run 当前并发运行
     * @param conversationId 会话标识
     * @param authorized 当前已授权工具
     * @param requestedMode 首发请求模式；恢复时必须为空
     * @param resume 是否审批恢复
     * @return 固定项目与工具上下文
     */
    ProjectRuntimeContext prepare(PrincipalRef principal, RunRecord run, UUID conversationId,
            List<ToolDefinition> authorized, OperationAuthorizationMode requestedMode, boolean resume) {
        if (runs.workspaceProtocol(run.tenantId(), run.agentId(), run.id()) != LocalProjectBinding.PROTOCOL
                && !runs.hasLocalProjectSource(run.tenantId(),run.agentId(),run.id())) return null;
        try {
            var binding = resources.run(principal, run);
            if (!Objects.equals(conversationId, binding.conversationId())) throw new IllegalStateException("项目运行会话不一致");
            access.scope(principal, binding.projectId(), "project:read");
            var allowed = catalog.select(principal.tenantId(), authorized);
            var row = checkpoints.find(run.tenantId(), user(run), run.id().toString(), KEY).orElse(null);
            Snapshot snapshot;
            if (row == null) {
                if (resume) throw new IllegalStateException("项目恢复快照缺失");
                snapshot = new Snapshot(binding,
                        allowed.stream().map(ToolDefinition::id).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        clock.instant(), requestedMode == null ? OperationAuthorizationMode.ASK_ON_RISK : requestedMode);
                var now = clock.instant();
                access.record(principal, run.id(), "PROJECT_RUN_PREPARE", "STARTED");
                access.record(principal, run.id(), "PROJECT_RUN_AUTHORIZATION",
                        "GRANTED_" + snapshot.authorizationMode().name());
                if(reads!=null)reads.initialize(principal,binding);
                checkpoints.save(new RuntimeCheckpoint(UUID.randomUUID(), run.tenantId(), user(run), run.id().toString(), KEY, TYPE, false,
                        cipher.encrypt(json.writeValueAsString(snapshot)), now.plus(ttl), now, now));
                access.record(principal, run.id(), "PROJECT_RUN_PREPARE", "SUCCEEDED");
            } else {
                if (row.listPayload() || !TYPE.equals(row.stateType()) || !row.expiresAt().isAfter(clock.instant()))
                    throw new IllegalStateException("项目恢复快照已失效");
                snapshot = json.readValue(cipher.decrypt(row.encryptedPayload()), Snapshot.class);
                if (!snapshot.binding().equals(binding) || !allowed.stream().map(ToolDefinition::id).collect(java.util.stream.Collectors.toSet()).containsAll(snapshot.toolIds()))
                    throw new IllegalStateException("项目运行授权已撤销或来源不一致");
                if (requestedMode != null && requestedMode != snapshot.authorizationMode())
                    throw new IllegalStateException("项目运行授权方式不一致");
                if(reads!=null)reads.verify(principal,binding);
            }
            return new ProjectRuntimeContext(binding, snapshot.toolIds(), snapshot.authorizationMode());
        } catch (LocalProjectAccessException failure) { throw failure; }
        catch (Exception failure) { throw access.infrastructure(principal, run.id(), "PROJECT_RUN_PREPARE", failure); }
    }

    /** 终态/停止清理只删除本组件状态，不删除长期项目/会话来源或变更恢复记录。 */
    void cleanup(PrincipalRef principal, RunRecord run) {
        try {
            var row = checkpoints.find(run.tenantId(), user(run), run.id().toString(), KEY).orElse(null);
            if (row == null) {
                if(reads!=null)reads.cleanup(principal,run);
                return;
            }
            Snapshot snapshot = json.readValue(cipher.decrypt(row.encryptedPayload()), Snapshot.class);
            cleanup(principal, run, snapshot.authorizationMode());
        }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (Exception failure) { throw access.infrastructure(principal, run.id(), "PROJECT_RUN_CLEANUP", failure); }
    }

    /** 运行编排已持有项目上下文时按可信模式直接撤销，即使检查点刚被竞争清理也不会漏审计。 */
    void cleanup(PrincipalRef principal, RunRecord run, OperationAuthorizationMode authorizationMode) {
        try {
            access.record(principal, run.id(), "PROJECT_RUN_AUTHORIZATION",
                    "REVOKED_" + authorizationMode.name());
            checkpoints.delete(run.tenantId(), user(run), run.id().toString(), KEY);
            if(reads!=null)reads.cleanup(principal,run);
        }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (Exception failure) { throw access.infrastructure(principal, run.id(), "PROJECT_RUN_CLEANUP", failure); }
    }
    /** 每次真实ASK创建新的审批期限，正文/工具集合不变化；轮询或普通恢复不能续期。 */
    void retain(PrincipalRef principal,RunRecord run,ProjectRuntimeContext context) {
        try {
            var row=checkpoints.find(run.tenantId(),user(run),run.id().toString(),KEY).orElseThrow();
            Snapshot snapshot=json.readValue(cipher.decrypt(row.encryptedPayload()),Snapshot.class);
            if(!snapshot.binding().equals(context.binding())||!snapshot.toolIds().equals(context.protectedToolIds()))throw new IllegalStateException();
            Instant now=clock.instant();
            checkpoints.save(new RuntimeCheckpoint(row.id(),row.tenantId(),row.userId(),row.sessionId(),KEY,TYPE,false,row.encryptedPayload(),now.plus(ttl),row.createdAt(),now));
            if(reads!=null)reads.retain(principal,context.binding(),now.plus(ttl));
        }catch(Exception failure){throw access.infrastructure(principal,run.id(),"PROJECT_APPROVAL_RETAIN",failure);}
    }
    /** 网关在真实调用前再次核对冻结工具集合，不能凭客户端或工具名称自报项目权限。 */
    boolean allows(PrincipalRef principal, RunRecord run, UUID toolId) {
        try {
            var binding = resources.run(principal, run);
            var row = checkpoints.find(run.tenantId(), user(run), run.id().toString(), KEY).orElse(null);
            if(row==null||row.listPayload()||!TYPE.equals(row.stateType())||!row.expiresAt().isAfter(clock.instant()))return false;
            Snapshot snapshot=json.readValue(cipher.decrypt(row.encryptedPayload()),Snapshot.class);
            return snapshot.binding().equals(binding)&&snapshot.toolIds().contains(toolId);
        } catch(LocalProjectAccessException failure){throw failure;}
        catch(Exception failure){throw access.infrastructure(principal,run.id(),"PROJECT_TOOL_SNAPSHOT",failure);}
    }

    /**
     * 读取当前 Run 不可变授权方式，供项目工具在实际副作用前再次复核。
     *
     * <p>快照缺失、过期、来源漂移或解密失败均失败关闭，不能回退为完全访问。</p>
     */
    OperationAuthorizationMode authorizationMode(PrincipalRef principal, RunRecord run) {
        try {
            var binding = resources.run(principal, run);
            var row = checkpoints.find(run.tenantId(), user(run), run.id().toString(), KEY).orElse(null);
            if (row == null || row.listPayload() || !TYPE.equals(row.stateType())
                    || !row.expiresAt().isAfter(clock.instant()))
                throw access.reject(principal, run.id(), "PROJECT_AUTHORIZATION_EXPIRED", 409,
                        "本轮项目授权已失效，请新建会话运行");
            Snapshot snapshot = json.readValue(cipher.decrypt(row.encryptedPayload()), Snapshot.class);
            if (!snapshot.binding().equals(binding))
                throw access.reject(principal, run.id(), "PROJECT_AUTHORIZATION_CONFLICT", 409,
                        "本轮项目授权来源已变化，请新建会话运行");
            return snapshot.authorizationMode();
        } catch (LocalProjectAccessException failure) {
            throw failure;
        } catch (Exception failure) {
            throw access.infrastructure(principal, run.id(), "PROJECT_AUTHORIZATION_READ", failure);
        }
    }
    /** 供原owner历史差异/输出面板使用，不能把恢复检查点的TTL当作历史读取授权。 */
    void verifyResource(PrincipalRef principal,RunRecord run){resources.run(principal,run);}
    private static String user(RunRecord run) { return run.tenantId() + ":" + run.principalId(); }

    /**
     * @param binding 长期固定来源
     * @param toolIds 首次已授权固定工具ID
     * @param createdAt 首次准备时间，不随恢复改变
     * @param authorizationMode 本轮不可变授权方式；旧快照缺省按高风险询问兼容
     */
    private record Snapshot(LocalProjectBinding binding, Set<UUID> toolIds, Instant createdAt,
                            OperationAuthorizationMode authorizationMode) {
        Snapshot {
            toolIds = Set.copyOf(toolIds);
            authorizationMode = authorizationMode == null
                    ? OperationAuthorizationMode.ASK_ON_RISK : authorizationMode;
        }
    }
}
