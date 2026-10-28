package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.WorkspaceContextException;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.WorkspaceContextProperties;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/**
 * 编排受信目录、会话绑定和运行选择；不读资料正文，不接受客户端路径或 tenant。
 * 写方法由宿主在会话控制锁和短事务中调用；模型及文件 I/O 不进入事务。
 */
@Service
public final class WorkspaceSelectionService {
    /** 导入批次的归属、READY与固定期限验证，不信任客户端路径或ID命名空间。 */
    private WorkspaceImportService imports;
    @org.springframework.beans.factory.annotation.Autowired
    void configureImports(WorkspaceImportService service){this.imports=service;}
    /** 来源见证与Run最初INSERT共存，不能仅以选择行/短期检查点推断是否为旧记录。 */
    private RunRepository runs;
    @org.springframework.beans.factory.annotation.Autowired
    void configureRuns(RunRepository repository){this.runs=repository;}
    /** @return Run的不可变协议，旧手工装配仅能沿用原目录合同 */
    int protocol(RunRecord run){return runs==null?0:runs.workspaceProtocol(run.tenantId(),run.agentId(),run.id());}
    /** @return 长期固定元数据，不补写或按当前映射推断 */
    WorkspaceBinding frozen(RunRecord run){return rows.run(run.tenantId(),run.agentId(),run.id()).orElse(null);}
    /** 管理员配置的唯一目录来源。 */ private final WorkspaceContextProperties policy;
    /** 长期选择元数据，不随资料检查点清理。 */ private final WorkspaceSelectionRepository rows;
    /** 用于空会话及资源归属检查。 */ private final ConversationRepository conversations;
    /** 是否已有消息以服务端存储为准，不能信任页面 editable。 */ private final ConversationMessageRepository messages;
    /** 目录选择与失败均使用严格元数据审计。 */ private final AuditAppender audit;
    /** 仅用于校验目录列表的 Agent 归属。 */ private final AgentDefinitionRepository agents;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkspaceSelectionService.class);

    /**
     * @param policy 受信部署配置
     * @param rows 选择仓储
     * @param conversations 会话仓储
     *
     * @param messages 消息仓储
     * @param audit 严格审计
     * @param agents Agent 仓储 */
    public WorkspaceSelectionService(WorkspaceContextProperties policy, WorkspaceSelectionRepository rows,
            ConversationRepository conversations, ConversationMessageRepository messages, AuditAppender audit,
            AgentDefinitionRepository agents) {
        this.policy=policy; this.rows=rows; this.conversations=conversations; this.messages=messages; this.audit=audit; this.agents=agents;
    }

    /**
     * 仅返回可信 tenant/Agent 的登记目录；可选不代表文件已读取成功。
     *
     * @param principal 已认证主体
     * @param agentId 已校验权限的 Agent
     * @return 有界目录清单
     */
    public Catalog catalog(PrincipalRef principal, UUID agentId) {
        if (agents.findByTenantAndId(principal.tenantId(),agentId).isEmpty())
            throw failure(principal,agentId,ApiErrorCode.WORKSPACE_NOT_FOUND,"Agent 不存在，请刷新列表",null);
        var items = policy.isEnabled() ? policy.getCatalog().stream()
                .filter(e -> e.tenantId().equals(principal.tenantId()) && e.agentIds().contains(agentId))
                .map(e -> new Directory(e.id(),e.name(),e.directory(),e.enabled())).toList() : List.<Directory>of();
        return new Catalog(policy.isEnabled(),true,items,policy.getMaxFiles(),policy.getMaxTotalBytes());
    }

    /** 首次创建前验证并审计；memory 模式先完成可失败审计，避免失败后留下半个新会话。 */
    public WorkspaceBinding newConversation(PrincipalRef principal, UUID agentId, UUID id, WorkspaceSelection selection) {
        var binding = make(principal,agentId,id,selection,null,1,false);
        audit.append(principal.tenantId(),principal.principalId(),"WORKSPACE_SELECT","CONVERSATION",id.toString(),"SUCCEEDED",
                "mode="+binding.selection().mode()+" workspaceId="+binding.selection().workspaceId());
        return binding;
    }
    /** 仅在会话创建成功后保存已验证绑定；调用方在 JDBC 短事务内执行。 */
    public void saveNewConversation(WorkspaceBinding binding) {
        if (!rows.saveConversation(binding,0)) throw new IllegalStateException("新会话工作区绑定冲突");
    }

    /** memory 创建失败时补偿预存元数据；补偿故障继续抛出，不伪装创建成功。 */
    public void abandonNewConversation(WorkspaceBinding binding) { rows.abandonConversation(binding); }

    /**
     * 在持有会话锁的短事务中改绑，数据库行锁同时保护首次消息创建。
     *
     * @param principal 可信主体
     * @param conversation 服务端归属会话
     * @param selection 新选择
     *
     * @param expectedVersion 页面最后读取的版本，旧无绑定会话为 0
     * @return 保存后的安全视图
     */
    public View update(PrincipalRef principal, Conversation conversation, WorkspaceSelection selection, long expectedVersion) {
        var locked = conversations.lock(principal.tenantId(),conversation.agentId(),conversation.id()).orElseThrow();
        if (!locked.createdBy().equals(principal.principalId()))
            throw failure(principal,conversation.id(),ApiErrorCode.FORBIDDEN,"仅会话创建者可以选择目录",null);
        if (!empty(locked)) throw failure(principal,conversation.id(),ApiErrorCode.WORKSPACE_SELECTION_CONFLICT,
                "会话已经开始，请在新会话中选择其他目录",null);
        var old = rows.conversation(principal.tenantId(),locked.agentId(),locked.id()).orElse(null);
        if ((old == null ? 0 : old.version()) != expectedVersion)
            throw failure(principal,locked.id(),ApiErrorCode.WORKSPACE_SELECTION_CONFLICT,"工作区选择已变化，请刷新后重试",null);
        var binding = make(principal,locked.agentId(),locked.id(),selection,old,expectedVersion+1,false);
        audit.append(principal.tenantId(),principal.principalId(),"WORKSPACE_SELECT","CONVERSATION",locked.id().toString(),"SUCCEEDED",
                "mode="+binding.selection().mode()+" workspaceId="+binding.selection().workspaceId());
        if (!rows.saveConversation(binding,expectedVersion))
            throw failure(principal,locked.id(),ApiErrorCode.WORKSPACE_SELECTION_CONFLICT,"工作区选择已变化，请刷新后重试",null);
        if(conversations.supportsWorkspaceProtocol())conversations.updateWorkspaceProtocol(binding.tenantId(),binding.agentId(),binding.resourceId(),
                binding.selection().mode()==WorkspaceSelection.Mode.IMPORTED?3:0);
        return view(binding,true);
    }

    /**
     * @return 当前绑定，失效仅显示不可用，不自动换目录；原会话读取权限由入口保持 */
    public View describe(PrincipalRef principal, Conversation conversation) {
        var binding = rows.conversation(principal.tenantId(),conversation.agentId(),conversation.id()).orElse(null);
        if(conversations.supportsWorkspaceProtocol() && conversations.workspaceProtocol(principal.tenantId(),conversation.agentId(),conversation.id())==3
                && (binding==null || binding.selection().mode()!=WorkspaceSelection.Mode.IMPORTED))
            throw failure(principal,conversation.id(),ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE,"原会话导入选择元数据缺失，请新建会话",null);
        boolean editable = conversation.createdBy().equals(principal.principalId()) && empty(conversation);
        return binding == null ? new View(WorkspaceSelection.defaults(),"沿用 Agent 配置","",0,editable,true)
                : view(binding,editable);
    }

    /**
     * @return Run 固定的安全元数据，无历史记录时表示旧 DEFAULT */
    public View describeRun(UUID tenant, UUID agent, UUID run) {
        var fixed=rows.run(tenant,agent,run);
        if(runs!=null && runs.workspaceProtocol(tenant,agent,run)==3 && (fixed.isEmpty() || fixed.get().selection().mode()!=WorkspaceSelection.Mode.IMPORTED))
            throw new WorkspaceContextException(ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE,"原运行导入选择元数据缺失，不能回退默认资料",run.toString());
        return fixed.map(binding -> view(binding,false,true))
                .orElse(new View(WorkspaceSelection.defaults(),"沿用 Agent 配置","",0,false,true));
    }

    /** 在首发事务内读取会话选择，已选目录失效必须阻止创建模型调用。 */
    public WorkspaceBinding forConversation(PrincipalRef principal, Conversation conversation, UUID runId) {
        var old = rows.conversation(principal.tenantId(),conversation.agentId(),conversation.id()).orElse(null);
        if(conversations.supportsWorkspaceProtocol() && conversations.workspaceProtocol(principal.tenantId(),conversation.agentId(),conversation.id())==3
                && (old==null || old.selection().mode()!=WorkspaceSelection.Mode.IMPORTED))
            throw failure(principal,runId,ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE,"原会话导入选择元数据缺失，不能回退默认资料",runId.toString());
        if (old != null && old.selection().mode() == WorkspaceSelection.Mode.SELECTED) requireCurrent(principal,old,runId.toString());
        var result=make(principal,conversation.agentId(),runId,old == null ? null : old.selection(),null,1,true);
        // 会话首发后不允许把原批次身份替换为同ID的其他清单；固定身份必须与绑定时一致。
        if(old!=null && old.selection().mode()==WorkspaceSelection.Mode.IMPORTED && !old.mappingDigest().equals(result.mappingDigest()))
            throw failure(principal,runId,ApiErrorCode.WORKSPACE_CONTEXT_REVOKED,"原导入资料身份已变化，请新建会话",runId.toString());
        return result;
    }

    /** 在启动审计/运行前固定选择元数据；存储异常向上抛出，不能降级无资料。 */
    public void saveRun(WorkspaceBinding binding) {
        try {
            audit.append(binding.tenantId(),binding.principalId(),"WORKSPACE_RUN_SELECT","RUN",binding.resourceId().toString(),"SUCCEEDED",
                    "mode="+binding.selection().mode()+" workspaceId="+binding.selection().workspaceId());
            rows.saveRun(binding);
        } catch (RuntimeException cause) {
            // 已创建 Run 的存储/审计失败沿用 runId；只复制安全堆栈位置，不记录 SQL、URL 或凭据消息。
            ApiErrorCode code = cause instanceof com.cmagent.server.audit.AuditPersistenceException ? ApiErrorCode.AUDIT_UNAVAILABLE
                    : cause instanceof org.springframework.dao.DataAccessException ? ApiErrorCode.PERSISTENCE_UNAVAILABLE : ApiErrorCode.INTERNAL_ERROR;
            String message = code == ApiErrorCode.AUDIT_UNAVAILABLE ? "工作区选择审计服务不可用，请联系管理员"
                    : code == ApiErrorCode.PERSISTENCE_UNAVAILABLE ? "工作区选择状态服务不可用，请联系管理员" : "工作区选择未能固定，请凭错误编号联系管理员";
            var safe = new IllegalStateException(message); safe.setStackTrace(cause.getStackTrace());
            log.error("errorId={} operation=WORKSPACE_RUN_SELECT errorCode={} tenantId={} principalId={} resourceType=RUN resourceId={}",
                    binding.resourceId(),code,binding.tenantId(),binding.principalId(),binding.resourceId(),safe);
            var failure = new WorkspaceContextException(code,message,binding.resourceId().toString()); failure.claimDiagnostic();
            throw failure;
        }
    }

    /**
     * @return 是否仍有该 Run 固定元数据；仅用于加密守卫复核，不推断 DEFAULT */
    public boolean hasRun(RunRecord run) { return rows.run(run.tenantId(),run.agentId(),run.id()).isPresent(); }

    /** 为独立单轮运行预先解析选择，不在 HTTP 层拼装路径。 */
    public WorkspaceBinding forRun(PrincipalRef principal, UUID agent, UUID run, WorkspaceSelection selection) {
        return make(principal,agent,run,selection,null,1,true);
    }

    /**
     * 依据已固定的 Run 身份解析只读映射；恢复不能用最新目录替换原身份。
     *
     * @param run 可信运行
     * @return NONE/原无资料运行为空；旧无元数据 Run 沿用旧映射
     */
    public WorkspaceContextProperties.Binding resolve(RunRecord run) {
        var stored = rows.run(run.tenantId(),run.agentId(),run.id()).orElse(null);
        if (stored == null) return legacy(run.tenantId(),run.agentId());
        if (!stored.principalId().equals(run.principalId())) throw unavailable(run);
        // 导入必须经专用密文入口，不能把manifest路径当服务器目录；prepare会在此之前显式分派。
        if (stored.selection().mode() == WorkspaceSelection.Mode.IMPORTED) throw unavailable(run);
        if (stored.selection().mode() == WorkspaceSelection.Mode.NONE) return null;
        WorkspaceContextProperties.Binding binding;
        if (stored.selection().mode() == WorkspaceSelection.Mode.SELECTED) {
            var entry = policy.getCatalog().stream().filter(e -> e.id().equals(stored.selection().workspaceId())
                    && e.tenantId().equals(run.tenantId()) && e.agentIds().contains(run.agentId()) && e.enabled()).findFirst().orElse(null);
            if (!policy.isEnabled() || entry == null) throw unavailable(run);
            binding = entry.binding(run.agentId());
        } else {
            if (stored.mappingDigest().isEmpty()) return null;
            binding = legacy(run.tenantId(),run.agentId());
        }
        if (binding == null || !digest(binding).equals(stored.mappingDigest())) {
            if (stored.selection().mode() == WorkspaceSelection.Mode.DEFAULT)
                throw new WorkspaceContextException(ApiErrorCode.WORKSPACE_CONTEXT_REVOKED,
                        "工作区资料映射已停用或变更，请重新发起运行",run.id().toString());
            throw unavailable(run);
        }
        return binding;
    }

    private WorkspaceBinding make(PrincipalRef principal, UUID agent, UUID id, WorkspaceSelection choice,
            WorkspaceBinding previous, long version, boolean freezeDefault) {
        var selection = choice == null ? WorkspaceSelection.defaults() : choice;
        if (selection.mode() == WorkspaceSelection.Mode.IMPORTED) {
            if(imports==null || runs==null || !runs.supportsWorkspaceProtocol() || !conversations.supportsWorkspaceProtocol())
                throw failure(principal,id,ApiErrorCode.WORKSPACE_UNAVAILABLE,"当前运行装配未支持本机导入资料",null);
            var batch=imports.requireReady(principal,agent,selection.workspaceId());
            var now=Instant.now();if(previous!=null && now.isBefore(previous.createdAt()))now=previous.createdAt();
            return new WorkspaceBinding(principal.tenantId(),agent,id,selection,batch.manifestDigest(),batch.displayName(),"",version,
                    principal.principalId(),previous==null?now:previous.createdAt(),now);
        }
        String name = selection.mode() == WorkspaceSelection.Mode.NONE ? "不使用工作区" : "沿用 Agent 配置";
        WorkspaceContextProperties.Binding binding = null;
        if (selection.mode() == WorkspaceSelection.Mode.SELECTED) {
            var entry = policy.getCatalog().stream().filter(e -> e.id().equals(selection.workspaceId())
                    && e.tenantId().equals(principal.tenantId()) && e.agentIds().contains(agent)).findFirst()
                    .orElseThrow(() -> failure(principal,id,ApiErrorCode.WORKSPACE_NOT_FOUND,"目录不存在或不在可选范围，请刷新目录列表",null));
            if (!policy.isEnabled() || !entry.enabled())
                throw failure(principal,id,ApiErrorCode.WORKSPACE_UNAVAILABLE,"所选目录已停用，请联系管理员或选择其他目录",null);
            binding=entry.binding(agent); name=entry.name();
        } else if (freezeDefault && selection.mode() == WorkspaceSelection.Mode.DEFAULT) binding=legacy(principal.tenantId(),agent);
        Instant now=Instant.now();
        // MySQL 时间舍入或多实例微小时钟偏差可能使创建时间略超出本机当前值；版本才是并发依据，不能因此拒绝合法改绑。
        if (previous != null && now.isBefore(previous.createdAt())) now = previous.createdAt();
        return new WorkspaceBinding(principal.tenantId(),agent,id,selection,binding == null ? "" : digest(binding),name,
                binding == null ? "" : binding.directory(),version,principal.principalId(),previous == null ? now : previous.createdAt(),now);
    }
    private WorkspaceContextProperties.Binding requireCurrent(PrincipalRef principal, WorkspaceBinding stored, String errorId) {
        var entry = policy.getCatalog().stream().filter(e -> e.id().equals(stored.selection().workspaceId())
                && e.tenantId().equals(stored.tenantId()) && e.agentIds().contains(stored.agentId())).findFirst().orElse(null);
        if (!policy.isEnabled() || entry == null || !entry.enabled() || !digest(entry.binding(stored.agentId())).equals(stored.mappingDigest()))
            throw failure(principal,stored.resourceId(),ApiErrorCode.WORKSPACE_UNAVAILABLE,"原工作区已停用或登记发生变化，请联系管理员并新建会话",errorId);
        return entry.binding(stored.agentId());
    }
    private WorkspaceContextProperties.Binding legacy(UUID tenant, UUID agent) {
        return !policy.isEnabled() ? null : policy.getBindings().stream()
                .filter(b -> b.tenantId().equals(tenant) && b.agentId().equals(agent)).findFirst().orElse(null);
    }
    /**
     * @return 与原快照一致的声明身份；正文改动不会改变它，根或文件集合改变则撤销 */
    public String digest(WorkspaceContextProperties.Binding binding) {
        return WorkspaceContextResource.digest(policy.getRootDirectory()+"\n"+binding.tenantId()+"\n"+binding.agentId()
                +"\n"+binding.directory()+"\n"+String.join("\n",binding.files()));
    }
    private boolean empty(Conversation conversation) {
        return messages.list(conversation.tenantId(),conversation.id(),new MessagePageRequest(1,0)).isEmpty();
    }
    private View view(WorkspaceBinding binding, boolean editable) {
        return view(binding,editable,false);
    }
    private View view(WorkspaceBinding binding,boolean editable,boolean frozenRun) {
        boolean available = binding.selection().mode() != WorkspaceSelection.Mode.IMPORTED;
        if(binding.selection().mode()==WorkspaceSelection.Mode.IMPORTED && imports!=null)
            available=imports.available(new WorkspaceImportScope(binding.tenantId(),binding.principalId(),binding.agentId()),binding.selection().workspaceId(),binding.mappingDigest(),frozenRun);
        if (binding.selection().mode() == WorkspaceSelection.Mode.SELECTED) {
            available = policy.isEnabled() && policy.getCatalog().stream().anyMatch(e -> e.enabled()
                    && e.id().equals(binding.selection().workspaceId()) && e.tenantId().equals(binding.tenantId())
                    && e.agentIds().contains(binding.agentId()) && digest(e.binding(binding.agentId())).equals(binding.mappingDigest()));
        }
        return new View(binding.selection(),binding.displayName(),binding.directory(),binding.version(),editable,available);
    }
    private WorkspaceContextException unavailable(RunRecord run) {
        // Run 内的失败统一由 WorkspaceContextService 诊断和审计，避免在解析层重复记录。
        return new WorkspaceContextException(ApiErrorCode.WORKSPACE_UNAVAILABLE,
                "原运行工作区已变更，不能使用新资料恢复，请新建运行",run.id().toString());
    }
    private WorkspaceContextException failure(PrincipalRef principal, UUID resource, ApiErrorCode code, String message, String suppliedId) {
        String id = suppliedId == null ? UUID.randomUUID().toString() : suppliedId;
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new DeferredWorkspaceFailure(()->failure(principal,resource,code,message,id));
        try{audit.appendControlled(principal.tenantId(),principal.principalId(),"WORKSPACE_SELECT_FAILURE","WORKSPACE",resource.toString(),"DENIED",
                "code="+code+" errorId="+id);}
        catch(com.cmagent.server.audit.AuditPersistenceException cause){
            var safe=new IllegalStateException("工作区选择拒绝审计不可用");safe.setStackTrace(cause.getStackTrace());
            log.error("errorId={} operation=WORKSPACE_SELECT errorCode=AUDIT_UNAVAILABLE tenantId={} principalId={} resourceType=WORKSPACE resourceId={}",id,principal.tenantId(),principal.principalId(),resource,safe);
            var failed=new WorkspaceContextException(ApiErrorCode.AUDIT_UNAVAILABLE,"工作区选择审计服务暂不可用",id);failed.claimDiagnostic();return failed;
        }
        log.warn("errorId={} operation=WORKSPACE_SELECT errorCode={} tenantId={} principalId={} resourceType=WORKSPACE resourceId={}",
                id,code,principal.tenantId(),principal.principalId(),resource);
        var failure = new WorkspaceContextException(code,message,id); failure.claimDiagnostic(); return failure;
    }

    /**
     * @param id 逻辑标识
     * @param name 展示名称
     * @param directory 已授权相对位置
     * @param available 是否登记启用 */
    public record Directory(UUID id,String name,String directory,boolean available) {}
    /**
     * @param enabled 功能开关
     * @param readOnly 固定只读
     * @param items 可见登记目录
     * @param maxFiles 单轮文件数
     * @param maxTotalBytes 总字节预算 */
    public record Catalog(boolean enabled,boolean readOnly,List<Directory> items,int maxFiles,int maxTotalBytes) {}
    /**
     * @param selection 选择模式
     * @param displayName 选择时名称
     * @param directory 安全相对目录
     * @param version 乐观版本
     * @param editable 是否允许修改
     * @param available 当前是否可继续使用 */
    public record View(WorkspaceSelection selection,String displayName,String directory,long version,boolean editable,boolean available) {}
}
