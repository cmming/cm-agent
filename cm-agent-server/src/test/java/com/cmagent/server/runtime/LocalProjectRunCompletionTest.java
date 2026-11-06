package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.RunCancellation;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 模拟模型先返回而受控Job许可尚未释放，验证Run终态与审批不早于真实副作用退出。 */
class LocalProjectRunCompletionTest {
    private final UUID tenant=UUID.randomUUID(),agentId=UUID.randomUUID(),modelId=UUID.randomUUID(),runId=UUID.randomUUID(),conversationId=UUID.randomUUID();
    private final Instant started=Instant.now();
    private final PrincipalRef owner=new PrincipalRef(tenant,"owner","测试",Set.of("agent:run","project:read"));
    private final RunRecord run=RunRecord.create(runId,tenant,agentId,"owner","测试",started);
    private final AgentRuntime runtime=mock(AgentRuntime.class);
    private final AgentDefinitionRepository agents=mock(AgentDefinitionRepository.class);
    private final ModelConfigRepository models=mock(ModelConfigRepository.class);
    private final ToolDefinitionRepository tools=mock(ToolDefinitionRepository.class);
    private final ToolGrantRepository grants=mock(ToolGrantRepository.class);
    private final RunPersistenceService persistence=mock(RunPersistenceService.class);
    private final LocalProjectRunService projects=mock(LocalProjectRunService.class);
    private final SkillRuntimeService skills=mock(SkillRuntimeService.class);
    private final AtomicReference<RunCancellation.Invocation> invocation=new AtomicReference<>();
    private final CountDownLatch entered=new CountDownLatch(1);
    private RunExecutionService service;
    private RunControlRegistry.Execution control;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var agent=new AgentDefinition(agentId,tenant,"测试Agent","测试","安全提示",modelId,"测试模型",0.0,3,true,List.of(),"owner","owner");
        var model=new ModelConfig(modelId,tenant,ModelProviderType.OPENAI_COMPATIBLE,"测试模型","https://example.invalid","测试模型",true);
        when(agents.findByTenantAndId(tenant,agentId)).thenReturn(Optional.of(agent));
        when(models.findByTenantAndId(tenant,modelId)).thenReturn(Optional.of(model));
        when(grants.listByTenantAndAgent(tenant,agentId)).thenReturn(List.of());
        when(persistence.complete(eq(owner),eq(run),any(),anyList())).thenAnswer(call->{
            AgentRunResult value=call.getArgument(2);
            return run.complete(value.status(),value.output(),value.errorMessage(),Instant.now());
        });
        var binding=new LocalProjectBinding(tenant,"owner",UUID.randomUUID(),agentId,conversationId,runId,UUID.randomUUID(),"0".repeat(64),"node",1,started);
        when(projects.prepare(eq(owner),eq(run),eq(conversationId),anyList(),eq(false)))
                .thenReturn(new ProjectRuntimeContext(binding,Set.of()));
        service=new RunExecutionService(runtime,agents,models,tools,grants,mock(ToolAuthorizationPolicy.class),persistence,
                new SensitiveDataRedactor(),mock(ErrorDiagnosticLogger.class),skills);
        ObjectProvider<LocalProjectRunService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(projects);service.configureProjectRuns(provider);
        control=service.controls().register(run,conversationId);
    }

    @AfterEach void release() {
        var value=invocation.get();if(value!=null)value.close();
    }

    @Test void 项目模型提前完成时等待Job退出再清理并持久成功() throws Exception {
        runtimeReturns(success());
        var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(this::execute);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->future.get(200,TimeUnit.MILLISECONDS));
            assertNotNull(service.controls().find(runId));
            verify(persistence,never()).complete(any(),any(),any(),anyList());verify(projects,never()).cleanup(any(),any());
            invocation.get().close();
            assertEquals(RunStatus.SUCCEEDED,future.get(3,TimeUnit.SECONDS).run().status());
            verify(projects).cleanup(owner,run,com.cmagent.core.security.OperationAuthorizationMode.ASK_ON_RISK);
            verify(persistence).complete(eq(owner),eq(run),any(),anyList());
        } finally {release();executor.shutdownNow();}
    }

    @Test void 项目运行停止后仍等待Job许可退出才能持久取消() throws Exception {
        runtimeReturns(success());
        var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(this::execute);
            assertTrue(entered.await(3,TimeUnit.SECONDS));control.cancellation().request();
            assertThrows(TimeoutException.class,()->future.get(200,TimeUnit.MILLISECONDS));
            verify(persistence,never()).complete(any(),any(),any(),anyList());
            invocation.get().close();
            assertEquals(RunStatus.CANCELLED,future.get(3,TimeUnit.SECONDS).run().status());
        } finally {release();executor.shutdownNow();}
    }

    @Test void 项目再次等待审批前也先等待已有Job退出() throws Exception {
        var waiting=new AgentRunResult(runId,RunStatus.WAITING_APPROVAL,"",List.of(),started,null,"");
        var pending=new RuntimePendingApproval("reply",List.of(new RuntimePendingApproval.RuntimePendingToolCall("call",UUID.randomUUID(),"测试工具",ToolRiskLevel.HIGH,"安全摘要","a".repeat(64))));
        runtimeReturns(new AgentRuntimeResult(waiting,null,pending));
        when(persistence.waitForApproval(eq(owner),eq(run),anyList(),anyList())).thenReturn(
                new RunRecord(runId,tenant,agentId,"owner",RunStatus.WAITING_APPROVAL,"测试","","",started,null));
        var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(this::execute);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->future.get(200,TimeUnit.MILLISECONDS));
            verify(projects,never()).retain(any(),any(),any());verify(persistence,never()).waitForApproval(any(),any(),anyList(),anyList());
            invocation.get().close();
            assertEquals(RunStatus.WAITING_APPROVAL,future.get(3,TimeUnit.SECONDS).run().status());
            verify(projects).retain(eq(owner),eq(run),any());
        } finally {release();executor.shutdownNow();}
    }

    @Test void 非项目运行不因旧自定义Runtime持有许可而改变原完成语义() throws Exception {
        when(projects.prepare(eq(owner),eq(run),eq(conversationId),anyList(),eq(false))).thenReturn(null);
        runtimeReturns(success());
        var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(this::execute);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertEquals(RunStatus.SUCCEEDED,future.get(3,TimeUnit.SECONDS).run().status());
            verify(projects,never()).cleanup(any(),any());
        } finally {release();executor.shutdownNow();}
    }

    private AgentRuntimeResult execute() {return service.runPrepared(owner,agentId,run,"测试",conversationId,ignored->{},ignored->{});}
    private AgentRuntimeResult success() {return new AgentRuntimeResult(new AgentRunResult(runId,RunStatus.SUCCEEDED,"完成",List.of(),started,Instant.now(),""),null);}
    private void runtimeReturns(AgentRuntimeResult result) {
        when(runtime.runStructured(any(),any(),any())).thenAnswer(call->{
            AgentRunRequest request=call.getArgument(0);invocation.set(request.cancellation().enter());entered.countDown();return result;
        });
    }
}
