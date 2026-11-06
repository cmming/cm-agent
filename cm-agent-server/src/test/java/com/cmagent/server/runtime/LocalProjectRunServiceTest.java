package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.server.store.InMemoryRuntimeCheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalProjectRunServiceTest {
    private final UUID tenant=UUID.randomUUID(),agent=UUID.randomUUID(),runId=UUID.randomUUID(),conversation=UUID.randomUUID(),project=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-29T08:00:00Z");
    private final PrincipalRef principal=new PrincipalRef(tenant,"Owner","测试",Set.of("project:read","agent:run"));
    private final RunRecord run=RunRecord.create(runId,tenant,agent,"Owner","测试",now);
    private final RunRepository runs=mock(RunRepository.class);
    private final LocalProjectBindingRepository bindings=mock(LocalProjectBindingRepository.class);
    private final LocalProjectResourceAccess resources=mock(LocalProjectResourceAccess.class);
    private final LocalProjectAccessService access=mock(LocalProjectAccessService.class);
    private final LocalProjectToolCatalog catalog=mock(LocalProjectToolCatalog.class);
    private final LocalProjectOperationRepository operations=mock(LocalProjectOperationRepository.class);
    private final InMemoryRuntimeCheckpointRepository checkpoints=new InMemoryRuntimeCheckpointRepository();
    private ModelCredentialCipher cipher;
    private LocalProjectBinding binding;
    private LocalProjectRunService service;

    @BeforeEach void setup()throws Exception {
        var generator=javax.crypto.KeyGenerator.getInstance("AES");generator.init(256);cipher=new ModelCredentialCipher(generator.generateKey());
        binding=new LocalProjectBinding(tenant,"Owner",project,agent,conversation,runId,UUID.randomUUID(),"0".repeat(64),"node",1,now);
        when(runs.workspaceProtocol(tenant,agent,runId)).thenReturn(4);when(resources.run(principal,run)).thenReturn(binding);
        when(access.infrastructure(any(),any(),anyString(),any())).thenReturn(new LocalProjectAccessException("PROJECT_UNAVAILABLE","same-id",503,"项目恢复失败"));
        when(access.reject(any(),any(),anyString(),anyInt(),anyString())).thenAnswer(call->new LocalProjectAccessException(call.getArgument(2),"safe-id",call.getArgument(3),call.getArgument(4)));
        when(catalog.select(eq(tenant),anyList())).thenAnswer(call->call.getArgument(1));
        service=serviceAt(now);
    }

    @Test void 审批恢复跨服务重建保留原工具集合且不继承新增授权() {
        var first=tool();var added=tool();
        var prepared=service.prepare(principal,run,conversation,List.of(first),false);
        assertEquals(Set.of(first.id()),prepared.protectedToolIds());
        var restored=serviceAt(now.plusSeconds(30)).prepare(principal,run,conversation,List.of(first,added),true);
        assertEquals(prepared,restored);
        assertTrue(service.allows(principal,run,first.id()));assertFalse(service.allows(principal,run,added.id()));
        String slot=tenant+":Owner";
        var row=checkpoints.find(tenant,slot,runId.toString(),"local_project_runtime_v1").orElseThrow();
        assertTrue(row.encryptedPayload().startsWith("v1:"));assertFalse(row.encryptedPayload().contains(first.name()));
    }

    @Test void 项目授权方式随Run快照固定并在恢复与清理时复核审计() {
        var first=tool();
        var prepared=service.prepare(principal,run,conversation,List.of(first),
                OperationAuthorizationMode.FULL_ACCESS,false);
        assertEquals(OperationAuthorizationMode.FULL_ACCESS,prepared.authorizationMode());
        assertEquals(OperationAuthorizationMode.FULL_ACCESS,service.authorizationMode(principal,run));
        var restored=serviceAt(now.plusSeconds(30)).prepare(principal,run,conversation,List.of(first),true);
        assertEquals(OperationAuthorizationMode.FULL_ACCESS,restored.authorizationMode());
        assertThrows(LocalProjectAccessException.class,()->service.prepare(principal,run,conversation,List.of(first),
                OperationAuthorizationMode.ASK_ON_EFFECT,true));
        verify(access).record(principal,runId,"PROJECT_RUN_AUTHORIZATION","GRANTED_FULL_ACCESS");
        service.cleanup(principal,run);
        verify(access).record(principal,runId,"PROJECT_RUN_AUTHORIZATION","REVOKED_FULL_ACCESS");
    }

    @Test void 项目授权撤销审计失败时保留检查点并失败关闭() {
        service.prepare(principal,run,conversation,List.of(tool()),false);
        doThrow(new LocalProjectAccessException("PROJECT_UNAVAILABLE","revoke-audit-error",503,"审计不可用"))
                .when(access).record(principal,runId,"PROJECT_RUN_AUTHORIZATION","REVOKED_ASK_ON_RISK");

        var failure=assertThrows(LocalProjectAccessException.class,()->service.cleanup(principal,run));
        assertEquals("revoke-audit-error",failure.errorId());
        assertTrue(checkpoints.exists(tenant,tenant+":Owner",runId.toString()));
    }

    @Test void 缺失过期来源漂移或授权撤销均拒绝而不重新采纳当前工具() {
        var first=tool();
        assertThrows(LocalProjectAccessException.class,()->service.prepare(principal,run,conversation,List.of(first),true));
        service.prepare(principal,run,conversation,List.of(first),false);
        assertThrows(LocalProjectAccessException.class,()->service.prepare(principal,run,conversation,List.of(),true));
        assertThrows(LocalProjectAccessException.class,()->serviceAt(now.plusSeconds(901)).prepare(principal,run,conversation,List.of(first),true));
        var changed=new LocalProjectBinding(tenant,"Owner",project,agent,conversation,runId,UUID.randomUUID(),binding.rootIdentity(),"node",1,now);
        when(resources.run(principal,run)).thenReturn(changed);
        assertThrows(LocalProjectAccessException.class,()->service.prepare(principal,run,conversation,List.of(first),true));
        assertFalse(service.allows(principal,run,first.id()));
    }

    @Test void 旧来源不进入项目文件核验而UNKNOWN阻止另一轮首发() {
        when(runs.workspaceProtocol(tenant,agent,runId)).thenReturn(0);
        assertNull(service.prepare(principal,run,conversation,List.of(),false));
        verifyNoInteractions(resources);
        var source=new LocalProjectBinding(tenant,"Owner",project,agent,conversation,null,binding.rootBindingId(),binding.rootIdentity(),"node",1,now);
        var metadata=new Conversation(conversation,tenant,agent,"会话","Owner",now,now);
        when(bindings.conversation(tenant,"Owner",conversation)).thenReturn(Optional.of(source));
        when(resources.conversation(principal,metadata)).thenReturn(source);
        when(operations.hasUncertainOperation(tenant,"Owner",project,null)).thenReturn(true);
        assertEquals("PROJECT_OPERATION_UNCERTAIN",assertThrows(LocalProjectAccessException.class,()->service.preflight(principal,metadata,runId)).errorCode());
    }

    @Test void 终态清理只删短期工具快照不清长期来源() {
        service.prepare(principal,run,conversation,List.of(tool()),false);
        service.cleanup(principal,run);
        assertFalse(checkpoints.exists(tenant,tenant+":Owner",runId.toString()));
        verifyNoInteractions(bindings);
    }

    private LocalProjectRunService serviceAt(Instant time) {
        return new LocalProjectRunService(runs,bindings,mock(LocalProjectRepository.class),resources,access,operations,catalog,cipher,
                new ObjectMapper().findAndRegisterModules(),checkpoints,Clock.fixed(time,ZoneOffset.UTC),Duration.ofMinutes(15));
    }
    private ToolDefinition tool(){return new ToolDefinition(UUID.randomUUID(),tenant,"tool-"+UUID.randomUUID(),"测试",ToolType.LOCAL,"{}",ToolRiskLevel.LOW,true,null,"system","system");}
}
