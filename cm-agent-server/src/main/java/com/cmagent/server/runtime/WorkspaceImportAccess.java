package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.WorkspaceContextException;
import com.cmagent.server.audit.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * IMPORTED派生资源的统一读取闸门；只补充新来源owner约束，不改变原三态租户权限。
 * 主体必须来自认证JwtSession；不存在、跨tenant/Agent/owner均返回同一404，不先返回标题或回答。
 */
@Component
public final class WorkspaceImportAccess {
    /** 长期来源元数据，不能用当前catalog猜测私有性。 */
    private final WorkspaceSelectionRepository selections;
    /** 可信会话及Run关联。 */
    private final ConversationRepository conversations;
    /** 可信运行，包含原发起人。 */
    private final RunRepository runs;
    /** 严格拒绝审计，失败必须上抛。 */
    private final AuditAppender audit;
    /** 固定上下文日志不包含模型、消息或文件名。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkspaceImportAccess.class);
    /**
     * @param selections 来源仓储
     * @param conversations 会话仓储
     * @param runs 运行仓储
     * @param audit 严格审计 */
    public WorkspaceImportAccess(WorkspaceSelectionRepository selections, ConversationRepository conversations, RunRepository runs, AuditAppender audit) {
        this.selections = selections; this.conversations = conversations; this.runs = runs; this.audit = audit;
    }
    /** 只接收仓储核实的会话，用户输入不能构造可信归属。 */
    public void conversation(PrincipalRef principal, Conversation value) {
        if (!principal.tenantId().equals(value.tenantId()) || !selections.visibleConversation(value, principal.principalId())
                || conversations.workspaceProtocol(value.tenantId(),value.agentId(),value.id())==3 && !principal.principalId().equals(value.createdBy())
                || !runs.visibleConversationFromWorkspace(value,principal.principalId())) denied(principal, value.id());
    }
    /** 审批等独立服务入口须自己核对，不能依赖某一个Controller曾经做过校验。 */
    public void conversation(PrincipalRef principal, UUID agent, UUID id) {
        var value = conversations.findByTenantAndAgentAndId(principal.tenantId(), agent, id).orElse(null);
        if (value == null) denied(principal, id); else conversation(principal, value);
    }
    /** 同时校验固定Run来源及派生会话，避免通过运行详情/技能记录入口旁路。 */
    public void run(PrincipalRef principal, RunRecord value) {
        var conversation = conversations.findByRun(principal.tenantId(), value.id()).orElse(null);
        if (!principal.tenantId().equals(value.tenantId()) || runs.workspaceProtocol(value.tenantId(),value.agentId(),value.id())==3 && !principal.principalId().equals(value.principalId())
                || !selections.visibleRun(value, conversation, principal.principalId())
                || conversation!=null && (conversations.workspaceProtocol(conversation.tenantId(),conversation.agentId(),conversation.id())==3
                    && !principal.principalId().equals(conversation.createdBy())
                    || !runs.visibleConversationFromWorkspace(conversation,principal.principalId()))) denied(principal, value.id());
    }
    /** @return 不含正文的可信运行；越界与缺失均采用相同拒绝合同 */
    public RunRecord run(PrincipalRef principal, UUID agent, UUID id) {
        var value = runs.findByTenantAndAgentAndId(principal.tenantId(), agent, id).orElse(null);
        if (value == null) denied(principal, id); else run(principal, value); return value;
    }
    private void denied(PrincipalRef principal, UUID id) {
        String errorId = UUID.randomUUID().toString();
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new DeferredWorkspaceFailure(()->reject(principal,id,errorId));
        throw reject(principal,id,errorId);
    }
    private WorkspaceContextException reject(PrincipalRef principal,UUID id,String errorId){
        try { audit.appendControlled(principal.tenantId(), principal.principalId(), "WORKSPACE_IMPORT_ACCESS", "WORKSPACE_RESOURCE", id.toString(), "DENIED",
                "code=WORKSPACE_RESOURCE_NOT_FOUND errorId=" + errorId); }
        catch (AuditPersistenceException failure) {
            var safe = new IllegalStateException("导入访问拒绝审计不可用"); safe.setStackTrace(failure.getStackTrace());
            log.error("errorId={} operation=WORKSPACE_IMPORT_ACCESS errorCode=AUDIT_UNAVAILABLE tenantId={} resourceType=WORKSPACE_RESOURCE resourceId={}", errorId, principal.tenantId(), id, safe);
            var rejected = new WorkspaceContextException(ApiErrorCode.AUDIT_UNAVAILABLE, "审计服务暂不可用，请凭错误编号联系管理员", errorId); rejected.claimDiagnostic(); return rejected;
        }
        log.warn("errorId={} operation=WORKSPACE_IMPORT_ACCESS errorCode=WORKSPACE_RESOURCE_NOT_FOUND tenantId={} principalId={} resourceType=WORKSPACE_RESOURCE resourceId={}", errorId, principal.tenantId(), principal.principalId(), id);
        var rejected = new WorkspaceContextException(ApiErrorCode.WORKSPACE_RESOURCE_NOT_FOUND, "请求资源不存在", errorId); rejected.claimDiagnostic(); return rejected;
    }
}
