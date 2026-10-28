package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.RuntimeCheckpointRepository;
import com.cmagent.core.runtime.WorkspaceContextException;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.config.AgentScopeRuntimeProperties;
import com.cmagent.server.config.WorkspaceContextProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/**
 * 以可信运行身份读取、冻结、加密并恢复只读资料；文件I/O和加密不持有数据库事务。
 * 原状态槽与审批生命周期共用，终态/过期/停止清理仍由宿主治理，不能回退为读取最新资料。
 */
@Service
public final class WorkspaceContextService {
    /** 与AgentScope业务状态隔离的宿主状态键；deleteSession仍同时清理两者。 */
    static final String KEY = "workspace_context_v1";
    /** 记录原运行曾启用资料；正文条目丢失且映射随后移除时仍拒绝恢复，不能误认为旧无资料运行。 */
    static final String GUARD = "workspace_context_guard_v1";
    /** 管理员策略，仅在服务端装配，不从请求获取。 */ private final WorkspaceContextProperties policy;
    /** 原有租户隔离加密检查点存储，不新增表。 */ private final RuntimeCheckpointRepository checkpoints;
    /** 宿主加密器，重建需保留相同密钥。 */ private final ModelCredentialCipher cipher;
    /** 仅用于内存JSON序列化。 */ private final ObjectMapper mapper;
    /** 资料准备和恢复必须记录严格元数据审计。 */ private final AuditAppender audit;
    /** 与审批Store一致的服务端期限来源。 */ private final Clock clock;
    /** 与AgentScope审批状态相同的TTL。 */ private final Duration ttl;
    /** 唯一有界本地文件读取入口。 */ private final WorkspaceContextFiles files;
    /** Spring 装配的长期选择来源；手工构造的旧合同测试仍可使用 legacy 映射。 */
    private WorkspaceSelectionService selections;
    @Autowired
    void configureSelections(WorkspaceSelectionService service) { this.selections = service; }
    /** IMPORTED只走私有输入存储，不伪造受信服务器目录Binding。 */
    private WorkspaceImportRuntime imported;
    @Autowired void configureImported(WorkspaceImportRuntime service){this.imported=service;}

    /** 日志只含安全身份/摘要/统计，原始异常消息可能包含主机路径。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkspaceContextService.class);

    @Autowired
    public WorkspaceContextService(WorkspaceContextProperties policy, RuntimeCheckpointRepository checkpoints,
            ModelCredentialCipher cipher, ObjectMapper mapper, AuditAppender audit, AgentScopeRuntimeProperties runtime) {
        this(policy, checkpoints, cipher, mapper, audit, Clock.systemUTC(), runtime.getApprovalTtl(), new WorkspaceContextFiles());
    }
    /** 测试固定时钟及合成保护目录；生产始终使用真实应用目录和宿主时间。 */
    WorkspaceContextService(WorkspaceContextProperties policy, RuntimeCheckpointRepository checkpoints, ModelCredentialCipher cipher,
            ObjectMapper mapper, AuditAppender audit, Clock clock, Duration ttl, WorkspaceContextFiles files) {
        this.policy = policy; this.checkpoints = checkpoints; this.cipher = cipher; this.mapper = mapper;
        this.audit = audit; this.clock = clock; this.ttl = ttl; this.files = files;
    }

    /** @return 当前可信Run是否有显式映射，不探测文件或创建检查点 */
    boolean applies(RunRecord run) { return imported(run) || binding(run) != null; }

    private boolean imported(RunRecord run){
        if(selections==null)return false;
        var fixed=selections.frozen(run);
        return selections.protocol(run)==3 || fixed!=null && fixed.selection().mode()==WorkspaceSelection.Mode.IMPORTED;
    }

    /**
     * 首次固定资料，恢复只读取原密文并复核映射；不使用客户端或模型选择的路径。
     * @param principal 原认证主体
     * @param run 已由服务端持久化的运行
     * @param resume 是否恢复原审批，缺失快照时禁止重新读取资料
     * @return 显式映射的冻结快照，无映射或默认关闭为null
     * @throws WorkspaceContextException 安全边界、预算、资料或严格基础设施失败
     */
    public WorkspaceContextSnapshot prepare(PrincipalRef principal, RunRecord run, boolean resume) {
        requireOwner(principal, run);
        // 关闭后 SELECTED 也必须先复核，不能静默当作无资料。
        if (selections == null && !policy.isEnabled() && !resume) return null;
        long start = System.nanoTime();
        try {
            policy.validate();
            if(imported(run))return prepareImported(run,resume,start);
            var row = checkpoints.find(run.tenantId(), user(run), run.id().toString(), KEY).orElse(null);
            var guard = checkpoints.find(run.tenantId(), user(run), run.id().toString(), GUARD).orElse(null);
            if(guard!=null && "cm-agent-workspace-guard-v3".equals(guard.stateType()))
                throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
            if ((row == null) != (guard == null)) throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
            if (guard != null && "cm-agent-workspace-guard-v2".equals(guard.stateType())
                    && (selections == null || !selections.hasRun(run)))
                throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
            var binding = binding(run);
            if (row == null && !applies(run)) return null;
            if (row == null && resume) throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
            WorkspaceContextSnapshot snapshot;
            if (row != null) {
                if (!applies(run)) throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_REVOKED);
                files.verifyAccess(policy, binding);
                if (!row.expiresAt().isAfter(clock.instant()) || row.listPayload()
                        || !WorkspaceContextSnapshot.class.getName().equals(row.stateType())) throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
                try { snapshot = mapper.readValue(cipher.decrypt(row.encryptedPayload()), WorkspaceContextSnapshot.class); }
                catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) { throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE); }
                if (!snapshot.tenantId().equals(run.tenantId()) || !snapshot.agentId().equals(run.agentId())
                        || !snapshot.runId().equals(run.id()) || !snapshot.principalId().equals(run.principalId()))
                    throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
                if (!snapshot.mappingDigest().equals(mappingDigest(binding))) throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_REVOKED);
                try {
                    if (!guard.expiresAt().isAfter(clock.instant()) || guard.listPayload() || !("cm-agent-workspace-guard-v1".equals(guard.stateType()) || "cm-agent-workspace-guard-v2".equals(guard.stateType()))
                            || !cipher.decrypt(guard.encryptedPayload()).equals(snapshot.mappingDigest()))
                        throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
                } catch (RuntimeException failure) { throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE); }
            } else {
                var resources = files.read(policy, binding);
                snapshot = new WorkspaceContextSnapshot(run.tenantId(), run.agentId(), run.id(), run.principalId(),
                        mappingDigest(binding), resources, WorkspaceContextSnapshot.version(resources), clock.instant());
                save(run, snapshot, null);
                var now = clock.instant();
                checkpoints.save(new RuntimeCheckpoint(UUID.randomUUID(), run.tenantId(), user(run), run.id().toString(), GUARD,
                        selections != null && selections.hasRun(run) ? "cm-agent-workspace-guard-v2" : "cm-agent-workspace-guard-v1",
                        false, cipher.encrypt(snapshot.mappingDigest()), now.plus(ttl), now, now));
            }
            if (snapshot.resources().size() > policy.getMaxFiles()
                    || snapshot.resources().stream().anyMatch(resource -> resource.sizeBytes() > policy.getMaxFileBytes())
                    || snapshot.resources().stream().mapToInt(WorkspaceContextResource::sizeBytes).sum() > policy.getMaxTotalBytes())
                throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_LIMIT_EXCEEDED);
            audit.append(run.tenantId(), run.principalId(), resume ? "WORKSPACE_CONTEXT_RESTORE" : "WORKSPACE_CONTEXT_PREPARE",
                    "RUN", run.id().toString(), "SUCCEEDED", "version=" + snapshot.sha256() + " files=" + snapshot.resources().size()
                            + " bytes=" + snapshot.resources().stream().mapToInt(WorkspaceContextResource::sizeBytes).sum()
                            + " durationMillis=" + (System.nanoTime() - start) / 1_000_000);
            // 严格审计结束后再检查一次Windows权限，避免审计等待期间权限漂移后仍交付快照。
            files.verifyAccess(policy, binding);
            return snapshot;
        } catch (Exception failure) { throw failure(run, failure); }
    }

    /** 每次正常ASK刷新原冻结快照期限，不能重读文件或替换摘要。 */
    public void retain(RunRecord run, WorkspaceContextSnapshot snapshot) {
        if (snapshot == null) return;
        try {
            if(imported(run)){retainImported(run,snapshot);return;}
            var current = binding(run);
            if (current == null || !snapshot.mappingDigest().equals(mappingDigest(current)))
                throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_REVOKED);
            files.verifyAccess(policy, current);
            var row = checkpoints.find(run.tenantId(), user(run), run.id().toString(), KEY)
                    .orElseThrow(() -> controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE));
            save(run, snapshot, row);
            var guard = checkpoints.find(run.tenantId(), user(run), run.id().toString(), GUARD)
                    .orElseThrow(() -> controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE));
            // 旧 v1 守卫允许迁移前无元数据的 DEFAULT；v2 必须保留固定选择，不能按旧路径降级。
            if ("cm-agent-workspace-guard-v2".equals(guard.stateType()) && (selections == null || !selections.hasRun(run)))
                throw controlled(run, ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
            var now = clock.instant();
            checkpoints.save(new RuntimeCheckpoint(guard.id(), run.tenantId(), user(run), run.id().toString(), GUARD,
                    guard.stateType(), false, guard.encryptedPayload(), now.plus(ttl), guard.createdAt(), now));
        } catch (Exception failure) { throw failure(run, failure); }
    }

    /** 新协议要求Run见证、长期选择和v3守卫三方一致，不能将丢失元数据解释为旧DEFAULT。 */
    private WorkspaceBinding importedSelection(RunRecord run){
        var fixed=selections==null?null:selections.frozen(run);
        if(imported==null || selections==null || selections.protocol(run)!=3 || fixed==null
                || fixed.selection().mode()!=WorkspaceSelection.Mode.IMPORTED)
            throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
        return fixed;
    }

    private WorkspaceContextSnapshot prepareImported(RunRecord run,boolean resume,long start) throws Exception {
        var fixed=importedSelection(run);
        var row=checkpoints.find(run.tenantId(),user(run),run.id().toString(),KEY).orElse(null);
        var guard=checkpoints.find(run.tenantId(),user(run),run.id().toString(),GUARD).orElse(null);
        if((row==null)!=(guard==null) || row==null && resume)throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
        try(var read=imported.open(run,fixed)){
            WorkspaceContextSnapshot snapshot;
            if(row==null){
                var resources=read.resources();
                snapshot=new WorkspaceContextSnapshot(run.tenantId(),run.agentId(),run.id(),run.principalId(),fixed.mappingDigest(),resources,
                        WorkspaceContextSnapshot.version(resources),clock.instant());
                saveImported(run,snapshot,null,null,read.expiresAt());
            }else{
                if(!row.expiresAt().isAfter(clock.instant()) || row.listPayload() || !WorkspaceContextSnapshot.class.getName().equals(row.stateType()))
                    throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
                try{snapshot=mapper.readValue(cipher.decrypt(row.encryptedPayload()),WorkspaceContextSnapshot.class);}
                catch(RuntimeException | com.fasterxml.jackson.core.JsonProcessingException invalid){throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);}
                if(!snapshot.tenantId().equals(run.tenantId()) || !snapshot.agentId().equals(run.agentId()) || !snapshot.runId().equals(run.id())
                        || !snapshot.principalId().equals(run.principalId()) || !snapshot.mappingDigest().equals(fixed.mappingDigest()))
                    throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
                verifyImportedGuard(run,guard,snapshot.mappingDigest());
                // 这里只复核GCM/清单与源对象仍在，不把源对象重新读出的正文替换原checkpoint。
                read.verify(snapshot);
            }
            audit.appendControlled(run.tenantId(),run.principalId(),resume?"WORKSPACE_CONTEXT_RESTORE":"WORKSPACE_CONTEXT_PREPARE","RUN",run.id().toString(),"SUCCEEDED",
                    "mode=IMPORTED version="+snapshot.sha256()+" files="+snapshot.resources().size()+" bytes="+snapshot.resources().stream().mapToInt(WorkspaceContextResource::sizeBytes).sum()
                            +" durationMillis="+(System.nanoTime()-start)/1_000_000);
            read.finish();
            return snapshot;
        }
    }
    private void verifyImportedGuard(RunRecord run,RuntimeCheckpoint guard,String digest){
        try{
            if(guard==null || !guard.expiresAt().isAfter(clock.instant()) || guard.listPayload() || !"cm-agent-workspace-guard-v3".equals(guard.stateType())
                    || !cipher.decrypt(guard.encryptedPayload()).equals(digest))throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
        }catch(RuntimeException invalid){throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);}
    }
    private void retainImported(RunRecord run,WorkspaceContextSnapshot snapshot) throws Exception {
        var fixed=importedSelection(run);var batch=imported.require(run,fixed);
        if(!snapshot.runId().equals(run.id()) || !snapshot.tenantId().equals(run.tenantId()) || !snapshot.agentId().equals(run.agentId())
                || !snapshot.principalId().equals(run.principalId()) || !snapshot.mappingDigest().equals(batch.manifestDigest()))
            throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
        var row=checkpoints.find(run.tenantId(),user(run),run.id().toString(),KEY).orElseThrow(()->controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE));
        var guard=checkpoints.find(run.tenantId(),user(run),run.id().toString(),GUARD).orElseThrow(()->controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE));
        // ASK不能延长已失效或类型不符的旧正文，否则会把过期检查点重新变成可恢复资料。
        if(!row.expiresAt().isAfter(clock.instant()) || row.listPayload() || !WorkspaceContextSnapshot.class.getName().equals(row.stateType()))
            throw controlled(run,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
        verifyImportedGuard(run,guard,snapshot.mappingDigest());imported.match(run,batch,snapshot);saveImported(run,snapshot,row,guard,batch.expiresAt());
    }
    private void saveImported(RunRecord run,WorkspaceContextSnapshot snapshot,RuntimeCheckpoint row,RuntimeCheckpoint guard,Instant inputExpiry) throws Exception {
        var now=clock.instant();var expires=now.plus(ttl).isBefore(inputExpiry)?now.plus(ttl):inputExpiry;
        checkpoints.save(new RuntimeCheckpoint(row==null?UUID.randomUUID():row.id(),run.tenantId(),user(run),run.id().toString(),KEY,WorkspaceContextSnapshot.class.getName(),false,
                cipher.encrypt(mapper.writeValueAsString(snapshot)),expires,row==null?now:row.createdAt(),now));
        checkpoints.save(new RuntimeCheckpoint(guard==null?UUID.randomUUID():guard.id(),run.tenantId(),user(run),run.id().toString(),GUARD,"cm-agent-workspace-guard-v3",false,
                cipher.encrypt(snapshot.mappingDigest()),expires,guard==null?now:guard.createdAt(),now));
    }

    /** 删除当前可信Run的资料键，清理失败阻止交付成功；不清理其他运行或扫描宿主目录。 */
    public void cleanup(PrincipalRef principal, RunRecord run) {
        requireOwner(principal, run);
        try {
            checkpoints.delete(run.tenantId(), user(run), run.id().toString(), KEY);
            checkpoints.delete(run.tenantId(), user(run), run.id().toString(), GUARD);
        }
        catch (RuntimeException failure) { throw failure(run, failure); }
    }
    private void save(RunRecord run, WorkspaceContextSnapshot snapshot, RuntimeCheckpoint previous) throws com.fasterxml.jackson.core.JsonProcessingException {
        var now = clock.instant();
        checkpoints.save(new RuntimeCheckpoint(previous == null ? UUID.randomUUID() : previous.id(), run.tenantId(), user(run), run.id().toString(), KEY,
                WorkspaceContextSnapshot.class.getName(), false, cipher.encrypt(mapper.writeValueAsString(snapshot)), now.plus(ttl),
                previous == null ? now : previous.createdAt(), now));
    }
    private WorkspaceContextProperties.Binding binding(RunRecord run) {
        if (selections != null) return selections.resolve(run);
        if (!policy.isEnabled()) return null;
        return policy.getBindings().stream().filter(binding -> binding.tenantId().equals(run.tenantId()) && binding.agentId().equals(run.agentId())).findFirst().orElse(null);
    }
    private String mappingDigest(WorkspaceContextProperties.Binding binding) {
        return WorkspaceContextResource.digest(policy.getRootDirectory() + "\n" + binding.tenantId() + "\n" + binding.agentId()
                + "\n" + binding.directory() + "\n" + String.join("\n", binding.files()));
    }
    private static String user(RunRecord run) { return run.tenantId() + ":" + run.principalId(); }
    private static void requireOwner(PrincipalRef principal, RunRecord run) {
        if (!principal.tenantId().equals(run.tenantId()) || !principal.principalId().equals(run.principalId()))
            throw new IllegalArgumentException("工作区运行不属于当前可信主体");
    }
    private WorkspaceContextException failure(RunRecord run, Exception cause) {
        ApiErrorCode code = cause instanceof WorkspaceContextException known ? known.code()
                : cause instanceof WorkspaceContextFiles.Limit ? ApiErrorCode.WORKSPACE_CONTEXT_LIMIT_EXCEEDED
                : cause instanceof WorkspaceContextFiles.Invalid ? ApiErrorCode.WORKSPACE_CONTEXT_INVALID
                : cause instanceof AuditPersistenceException ? ApiErrorCode.AUDIT_UNAVAILABLE
                : cause instanceof DataAccessException ? ApiErrorCode.PERSISTENCE_UNAVAILABLE : ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE;
        var failure = controlled(run, code);
        if (cause instanceof WorkspaceContextFiles.Invalid invalid)
            failure = new WorkspaceContextException(code, "工作区" + invalid.getMessage() + "，请联系管理员修正绑定资料", run.id().toString());
        else if (cause instanceof java.nio.file.NoSuchFileException)
            failure = new WorkspaceContextException(code, "已绑定的工作区参考文件不存在，请联系管理员检查资料", run.id().toString());
        else if (cause instanceof java.nio.file.AccessDeniedException)
            failure = new WorkspaceContextException(code, "工作区资料读取权限不足，请联系管理员检查私有目录权限", run.id().toString());
        else if (cause instanceof WindowsWorkspaceAclPolicy.Unavailable unavailable)
            failure = new WorkspaceContextException(code, unavailable.getMessage(), run.id().toString());
        if (!(cause instanceof AuditPersistenceException)) {
            try {
                // 拒绝/失败审计只存错误分类与可信Run编号；审计故障优先且不再递归尝试写失败审计。
                String status = code == ApiErrorCode.WORKSPACE_CONTEXT_REVOKED || code == ApiErrorCode.WORKSPACE_CONTEXT_INVALID
                        || code == ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE ? "DENIED" : "FAILED";
                audit.appendControlled(run.tenantId(), run.principalId(), "WORKSPACE_CONTEXT_FAILURE", "RUN", run.id().toString(), status,
                        "code=" + code + " errorId=" + run.id());
            } catch (RuntimeException unavailable) {
                var strict = controlled(run, ApiErrorCode.AUDIT_UNAVAILABLE); strict.addSuppressed(failure);
                failure = strict; code = ApiErrorCode.AUDIT_UNAVAILABLE; cause = unavailable;
            }
        }
        String context = "errorId=" + run.id() + " operation=WORKSPACE_CONTEXT errorCode=" + code + " tenantId=" + run.tenantId()
                + " principalId=" + run.principalId() + " resourceType=AGENT resourceId=" + run.agentId() + " runId=" + run.id();
        if (cause instanceof WindowsWorkspaceAclPolicy.Unavailable unavailable) context += " reason=" + unavailable.reason();
        failure.claimDiagnostic();
        if (code == ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE || code == ApiErrorCode.PERSISTENCE_UNAVAILABLE || code == ApiErrorCode.AUDIT_UNAVAILABLE) {
            var safe = new IllegalStateException(failure.getMessage()); safe.setStackTrace(cause.getStackTrace()); log.error(context, safe);
        } else log.warn(context);
        return failure;
    }
    private static WorkspaceContextException controlled(RunRecord run, ApiErrorCode code) {
        String message = switch (code) {
            case WORKSPACE_CONTEXT_INVALID -> "工作区资料路径、权限或文本格式不符合只读边界，请联系管理员修正";
            case WORKSPACE_CONTEXT_LIMIT_EXCEEDED -> "工作区参考资料超过运行预算，请联系管理员减少资料";
            case WORKSPACE_CONTEXT_REVOKED -> "工作区资料映射已停用或变更，请重新发起运行";
            case WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE -> "原运行参考快照缺失、过期或损坏，不能使用最新资料恢复，请重新发起运行";
            case AUDIT_UNAVAILABLE -> "工作区资料审计服务不可用，请联系管理员";
            case PERSISTENCE_UNAVAILABLE -> "工作区资料状态服务不可用，请联系管理员";
            default -> "工作区参考资料不可用或读取超时，请联系管理员检查绑定资料";
        };
        return new WorkspaceContextException(code, message, run.id().toString());
    }
}
