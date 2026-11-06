package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalProjectToolsTest {
    private final UUID tenant=UUID.randomUUID(),agent=UUID.randomUUID(),runId=UUID.randomUUID(),conversation=UUID.randomUUID(),projectId=UUID.randomUUID(),operationId=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-29T08:00:00Z");
    private final PrincipalRef owner=new PrincipalRef(tenant,"Owner","测试",Set.of("project:read","project:write","agent:run"));
    private final RunRecord run=RunRecord.create(runId,tenant,agent,"Owner","测试",now);
    private final LocalProjectOperationRepository operations=mock(LocalProjectOperationRepository.class);
    private final ToolApprovalRepository approvals=mock(ToolApprovalRepository.class);
    private final LocalProjectRunService runtime=mock(LocalProjectRunService.class);
    private final LocalProjectAccessService access=mock(LocalProjectAccessService.class);
    private final LocalProjectChangeService changes=mock(LocalProjectChangeService.class);
    private final LocalProjectCommandService commands=mock(LocalProjectCommandService.class);
    private final LocalProjectService projects=mock(LocalProjectService.class);
    private final LocalProjectReadSnapshots reads=mock(LocalProjectReadSnapshots.class);
    private final LocalProjectToolCatalog catalog=mock(LocalProjectToolCatalog.class);
    private final RunRepository runs=mock(RunRepository.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private LocalProjectBinding binding;
    private LocalProjectTools tools;
    private LocalProjectOperation proposed;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        var key=javax.crypto.KeyGenerator.getInstance("AES");key.init(256);
        var cipher=new ModelCredentialCipher(key.generateKey());
        binding=new LocalProjectBinding(tenant,"Owner",projectId,agent,conversation,runId,UUID.randomUUID(),"0".repeat(64),"node",1,now);
        var project=new LocalProject(projectId,tenant,"Owner","测试","node",binding.rootBindingId(),binding.rootIdentity(),LocalProject.State.ACTIVE,1,now,now);
        var root=new LocalProjectDirectoryTickets.Root(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(),"native");
        when(access.run(owner,runId,"project:read")).thenReturn(new LocalProjectAccessService.Scope(owner,project,root,binding));
        when(access.reject(any(),any(),anyString(),anyInt(),anyString())).thenAnswer(call->new LocalProjectAccessException(call.getArgument(2),"same-error",call.getArgument(3),call.getArgument(4)));
        when(access.infrastructure(any(),any(),anyString(),any())).thenReturn(new LocalProjectAccessException("PROJECT_UNAVAILABLE","fatal-error",503,"服务暂不可用"));
        when(runs.findByTenantAndAgentAndId(tenant,agent,runId)).thenReturn(Optional.of(run));
        when(catalog.valid(any())).thenReturn(true);when(runtime.allows(eq(owner),eq(run),any())).thenReturn(true);
        proposed=new LocalProjectOperation(operationId,tenant,"Owner",projectId,agent,conversation,runId,LocalProjectOperation.Kind.FILE_CHANGE,
                "1".repeat(64),cipher.encrypt("test-only-encrypted-intent"),LocalProjectOperation.State.PROPOSED,1,now,now);
        when(operations.operation(tenant,"Owner",operationId)).thenReturn(Optional.of(proposed));
        var transaction=mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        tools=new LocalProjectTools(runs,operations,approvals,catalog,runtime,access,projects,changes,commands,mock(LocalProjectStateLock.class),json,
                new SensitiveDataRedactor(),transaction,Clock.fixed(now,ZoneOffset.UTC),mock(ObjectProvider.class),reads);
    }

    @Test void 模型正文与普通轨迹摘要分离且未冻结工具不能访问文件() {
        var definition=definition(LocalProjectToolCatalog.Kind.READ);
        when(reads.read(eq(owner),any(),eq("a.txt"),eq(0),eq(65536),isNull())).thenReturn(new LocalProjectService.Text("a.txt","0".repeat(64),4,0,4,false,false,"私有正文"));
        var result=tools.invoke(request(definition,"{\"path\":\"a.txt\"}"),definition);
        assertTrue(result.success());assertTrue(result.protectedPayload());assertTrue(result.output().contains("私有正文"));
        assertFalse(result.displayOutput().contains("私有正文"));assertFalse(result.toString().contains("私有正文"));
        clearInvocations(projects,reads);when(runtime.allows(owner,run,definition.id())).thenReturn(false);
        assertFalse(tools.invoke(request(definition,"{}"),definition).authorized());verifyNoInteractions(projects,reads);
    }

    @Test void 模型正文搜索仅走本轮冻结通道且普通摘要不含搜索片段() {
        var definition=definition(LocalProjectToolCatalog.Kind.SEARCH);
        when(reads.search(eq(owner),any(),eq("关键字"),eq(true))).thenReturn(new LocalProjectService.Search(
                List.of(new LocalProjectService.Match("a.txt",true,"私有搜索片段")),false,1,0));
        var result=tools.invoke(request(definition,"{\"query\":\"关键字\",\"content\":true}"),definition);
        assertTrue(result.success());assertTrue(result.protectedPayload());assertTrue(result.output().contains("私有搜索片段"));
        assertFalse(result.displayOutput().contains("私有搜索片段"));verifyNoInteractions(projects);
        verify(reads).search(eq(owner),any(),eq("关键字"),eq(true));
    }

    @Test void 专用预览的未知及异运行操作返回同号404而不调用正文或执行器() {
        when(operations.operation(tenant,"Owner",operationId)).thenReturn(Optional.empty());
        var missing=assertThrows(LocalProjectAccessException.class,()->tools.changesPreview(owner,projectId,runId,operationId));
        assertEquals(404,missing.status());assertEquals("PROJECT_OPERATION_NOT_FOUND",missing.errorCode());assertEquals("same-error",missing.errorId());
        var other=new LocalProjectOperation(operationId,tenant,"Owner",projectId,agent,conversation,UUID.randomUUID(),
                LocalProjectOperation.Kind.COMMAND,proposed.digest(),proposed.encryptedPayload(),proposed.state(),1,now,now);
        when(operations.operation(tenant,"Owner",operationId)).thenReturn(Optional.of(other));
        var hidden=assertThrows(LocalProjectAccessException.class,()->tools.commandStatus(owner,projectId,runId,operationId));
        assertEquals(404,hidden.status());verifyNoInteractions(changes,commands);
    }

    @Test void 超过保留期限的差异和命令预览返回410且不解密正文() {
        for(var kind:LocalProjectOperation.Kind.values()){
            var expired=new LocalProjectOperation(operationId,tenant,"Owner",projectId,agent,conversation,runId,kind,
                    proposed.digest(),proposed.encryptedPayload(),proposed.state(),1,now.minus(Duration.ofDays(7)),now);
            when(operations.operation(tenant,"Owner",operationId)).thenReturn(Optional.of(expired));
            var failure=assertThrows(LocalProjectAccessException.class,()->{
                if(kind==LocalProjectOperation.Kind.FILE_CHANGE)tools.changesPreview(owner,projectId,runId,operationId);
                else tools.commandPreview(owner,projectId,runId,operationId);
            });
            assertEquals(410,failure.status());assertEquals("PROJECT_OPERATION_EXPIRED",failure.errorCode());assertEquals("same-error",failure.errorId());
            assertFalse(failure.getMessage().contains("v1:"));
        }
        verifyNoInteractions(changes,commands);
    }

    @Test void 没有真实精确批准或包含批准布尔值时绝不推进意图或修改文件() throws Exception {
        var definition=definition(LocalProjectToolCatalog.Kind.APPLY_CHANGE);
        when(approvals.listHistory(eq(tenant),eq(agent),eq(conversation),any())).thenReturn(List.of());
        assertFalse(tools.invoke(request(definition,input()),definition).success());
        assertFalse(tools.invoke(request(definition,input().replace("}",",\"approved\":true}")),definition).success());
        verify(changes,never()).apply(any(),any(),any(),any(),anyLong());verify(operations,never()).advance(any(),any(),any(),anyLong(),any(),any());
    }

    @Test void 真实同Run同Call同哈希批准仅把精确原版本推进后交给单次执行器() throws Exception {
        var definition=definition(LocalProjectToolCatalog.Kind.APPLY_CHANGE);
        when(approvals.listHistory(eq(tenant),eq(agent),eq(conversation),any())).thenReturn(List.of(approval(definition,hashInput())));
        var waiting=proposed.advance(LocalProjectOperation.State.WAITING_APPROVAL,now);
        var prepared=waiting.advance(LocalProjectOperation.State.PREPARED,now);
        when(operations.advance(tenant,"Owner",operationId,1,LocalProjectOperation.State.WAITING_APPROVAL,now)).thenReturn(Optional.of(waiting));
        when(operations.advance(tenant,"Owner",operationId,2,LocalProjectOperation.State.PREPARED,now)).thenReturn(Optional.of(prepared));
        when(changes.apply(any(),any(),any(),eq(operationId),eq(3L))).thenReturn(new LocalProjectChangeService.View(operationId,LocalProjectOperation.State.SUCCEEDED,5,proposed.digest(),1,now));
        assertTrue(tools.invoke(request(definition,input()),definition).success());
        verify(changes).apply(eq(owner),eq(binding),any(),eq(operationId),eq(3L));
        when(operations.operation(tenant,"Owner",operationId)).thenReturn(Optional.of(prepared));
        assertFalse(tools.invoke(request(definition,input()),definition).success());
        verify(changes,times(1)).apply(any(),any(),any(),any(),anyLong());
    }

    @Test void 完全访问仅凭原Run自动授权审计推进精确提案而不伪造人工批准() throws Exception {
        var definition=definition(LocalProjectToolCatalog.Kind.APPLY_CHANGE);
        when(runtime.authorizationMode(owner,run)).thenReturn(OperationAuthorizationMode.FULL_ACCESS);
        var waiting=proposed.advance(LocalProjectOperation.State.WAITING_APPROVAL,now);
        var prepared=waiting.advance(LocalProjectOperation.State.PREPARED,now);
        when(operations.advance(tenant,"Owner",operationId,1,LocalProjectOperation.State.WAITING_APPROVAL,now)).thenReturn(Optional.of(waiting));
        when(operations.advance(tenant,"Owner",operationId,2,LocalProjectOperation.State.PREPARED,now)).thenReturn(Optional.of(prepared));
        when(changes.apply(any(),any(),any(),eq(operationId),eq(3L)))
                .thenReturn(new LocalProjectChangeService.View(operationId,LocalProjectOperation.State.SUCCEEDED,5,proposed.digest(),1,now));

        assertTrue(tools.invoke(request(definition,input()),definition).success());
        verify(access).record(owner,operationId,"PROJECT_AUTO_AUTHORIZATION","AUTHORIZED_FULL_ACCESS");
        verifyNoInteractions(approvals);
        verify(changes).apply(eq(owner),eq(binding),any(),eq(operationId),eq(3L));
    }

    @Test void 自动授权审计失败时不推进提案也不执行文件修改() throws Exception {
        var definition=definition(LocalProjectToolCatalog.Kind.APPLY_CHANGE);
        when(runtime.authorizationMode(owner,run)).thenReturn(OperationAuthorizationMode.FULL_ACCESS);
        doThrow(new LocalProjectAccessException("PROJECT_UNAVAILABLE","audit-error",503,"审计不可用"))
                .when(access).record(owner,operationId,"PROJECT_AUTO_AUTHORIZATION","AUTHORIZED_FULL_ACCESS");

        var failure=assertThrows(ToolInvocationInfrastructureException.class,
                ()->tools.invoke(request(definition,input()),definition));
        assertTrue(failure.getMessage().contains("audit-error"));
        verify(operations,never()).advance(any(),any(),any(),anyLong(),any(),any());
        verify(changes,never()).apply(any(),any(),any(),any(),anyLong());
    }

    @Test void 停止命令在询问模式要求精确批准而完全访问记录自动审计() throws Exception {
        var definition=definition(LocalProjectToolCatalog.Kind.CANCEL_COMMAND);
        var commandInput="{\"operationId\":\""+operationId+"\"}";
        when(runtime.authorizationMode(owner,run)).thenReturn(OperationAuthorizationMode.ASK_ON_RISK);
        when(approvals.listHistory(eq(tenant),eq(agent),eq(conversation),any())).thenReturn(List.of());
        assertFalse(tools.invoke(request(definition,commandInput),definition).success());
        verify(commands,never()).cancel(any(),any(),any());

        when(runtime.authorizationMode(owner,run)).thenReturn(OperationAuthorizationMode.FULL_ACCESS);
        assertTrue(tools.invoke(request(definition,commandInput),definition).success());
        verify(access).record(owner,operationId,"PROJECT_AUTO_AUTHORIZATION","AUTHORIZED_FULL_ACCESS");
        verify(commands).cancel(owner,binding,operationId);
    }

    @Test void 批准后零写入冲突作为安全工具失败交给模型且保留诊断编号() throws Exception {
        var definition=definition(LocalProjectToolCatalog.Kind.APPLY_CHANGE);
        when(approvals.listHistory(eq(tenant),eq(agent),eq(conversation),any())).thenReturn(List.of(approval(definition,hashInput())));
        var waiting=proposed.advance(LocalProjectOperation.State.WAITING_APPROVAL,now);
        var prepared=waiting.advance(LocalProjectOperation.State.PREPARED,now);
        when(operations.advance(tenant,"Owner",operationId,1,LocalProjectOperation.State.WAITING_APPROVAL,now)).thenReturn(Optional.of(waiting));
        when(operations.advance(tenant,"Owner",operationId,2,LocalProjectOperation.State.PREPARED,now)).thenReturn(Optional.of(prepared));
        when(changes.apply(any(),any(),any(),eq(operationId),eq(3L)))
                .thenThrow(new LocalProjectChangeException(LocalProjectChangeException.Reason.CONFLICT));
        var result=tools.invoke(request(definition,input()),definition);
        assertFalse(result.success());assertTrue(result.protectedPayload());
        assertTrue(result.errorMessage().contains("PROJECT_CHANGE_CONFLICT"),result.errorMessage());
        assertTrue(result.errorMessage().contains("same-error"));
        assertFalse(result.errorMessage().contains("v1:"));
        verify(access).reject(eq(owner),eq(runId),eq("PROJECT_CHANGE_CONFLICT"),eq(409),contains("项目文件已变化"));
        verify(reads,never()).afterApply(any(),any(),any());
    }

    @Test void 审批哈希变化拒绝且基础设施失败会中止Runtime() throws Exception {
        var definition=definition(LocalProjectToolCatalog.Kind.APPLY_CHANGE);
        when(approvals.listHistory(eq(tenant),eq(agent),eq(conversation),any())).thenReturn(List.of(approval(definition,"0".repeat(64))));
        assertFalse(tools.invoke(request(definition,input()),definition).success());verify(changes,never()).apply(any(),any(),any(),any(),anyLong());
        when(approvals.listHistory(eq(tenant),eq(agent),eq(conversation),any())).thenThrow(new IllegalStateException("password=test-only-fixture"));
        var failure=assertThrows(ToolInvocationInfrastructureException.class,()->tools.invoke(request(definition,input()),definition));
        assertInstanceOf(LocalProjectAccessException.class,failure.getCause());assertTrue(failure.getMessage().contains("fatal-error"));assertFalse(failure.toString().contains("fixture"));
    }

    private String input(){return "{\"operationId\":\""+operationId+"\",\"version\":1,\"digest\":\""+proposed.digest()+"\"}";}
    private String hashInput()throws Exception{return LocalProjectRootCipher.digest(json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsString(json.readValue(input(),Map.class)));}
    private ToolDefinition definition(LocalProjectToolCatalog.Kind kind){return new ToolDefinition(LocalProjectToolCatalog.id(tenant,kind),tenant,kind.toolName(),"固定工具",ToolType.LOCAL,"{}",kind==LocalProjectToolCatalog.Kind.APPLY_CHANGE?ToolRiskLevel.HIGH:ToolRiskLevel.LOW,true,null,"system","system");}
    private ToolInvocationRequest request(ToolDefinition definition,String input){return new ToolInvocationRequest(tenant,agent,owner,runId,"call-1",definition.id(),definition.name(),input);}
    private ToolApprovalRequest approval(ToolDefinition definition,String hash){
        UUID approvalId=UUID.randomUUID();
        var item=new ToolApprovalItem(UUID.randomUUID(),approvalId,"call-1",definition.id(),definition.name(),ToolRiskLevel.HIGH,"受保护操作",hash,ToolApprovalPolicy.EACH_CALL,1,ToolApprovalDecision.APPROVE);
        return new ToolApprovalRequest(approvalId,tenant,agent,conversation,runId,"Owner","测试",ToolApprovalStatus.APPROVED,now.plusSeconds(60),2,"checkpoint",now,now,"Owner","测试",now,List.of(item));
    }
}
