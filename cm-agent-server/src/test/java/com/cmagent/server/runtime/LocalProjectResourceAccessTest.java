package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LocalProjectResourceAccessTest {
    private final UUID tenant=UUID.randomUUID(),agent=UUID.randomUUID(),conversationId=UUID.randomUUID(),runId=UUID.randomUUID(),projectId=UUID.randomUUID();
    private final Instant now=Instant.now();
    private final PrincipalRef owner=new PrincipalRef(tenant,"Owner","测试",Set.of("project:read"));
    private final Conversation conversation=new Conversation(conversationId,tenant,agent,"会话","Owner",now,now);
    private final RunRecord run=RunRecord.create(runId,tenant,agent,"Owner","测试",now);
    private final LocalProjectBindingRepository bindings=mock(LocalProjectBindingRepository.class);
    private final ConversationRepository conversations=mock(ConversationRepository.class);
    private final RunRepository runs=mock(RunRepository.class);
    private final LocalProjectAccessService access=mock(LocalProjectAccessService.class);
    private LocalProjectResourceAccess resources;
    private LocalProjectBinding source,fixed;

    @BeforeEach void setup() {
        UUID root=UUID.randomUUID();
        source=new LocalProjectBinding(tenant,"Owner",projectId,agent,conversationId,null,root,"0".repeat(64),"node",1,now);
        fixed=new LocalProjectBinding(tenant,"Owner",projectId,agent,conversationId,runId,root,source.rootIdentity(),"node",1,now);
        when(bindings.conversation(tenant,"Owner",conversationId)).thenReturn(Optional.of(source));
        when(bindings.run(tenant,"Owner",runId)).thenReturn(Optional.of(fixed));
        when(conversations.workspaceProtocol(tenant,agent,conversationId)).thenReturn(4);when(runs.workspaceProtocol(tenant,agent,runId)).thenReturn(4);
        when(conversations.findByTenantAndAgentAndId(tenant,agent,conversationId)).thenReturn(Optional.of(conversation));
        when(access.project(owner,projectId,"project:read")).thenReturn(new LocalProject(projectId,tenant,"Owner","已归档","node",root,source.rootIdentity(),LocalProject.State.ARCHIVED,2,now,now));
        when(access.reject(any(),any(),anyString(),anyInt(),anyString())).thenAnswer(call->new LocalProjectAccessException(call.getArgument(2),"safe-id",call.getArgument(3),call.getArgument(4)));
        resources=new LocalProjectResourceAccess(bindings,conversations,runs,access,mock(LocalProjectRequestBoundary.class));
    }

    @Test void 原owner可读归档历史但不打开文件根() {
        assertEquals(source,resources.conversation(owner,conversation));assertEquals(fixed,resources.run(owner,run));
        verify(access,never()).scope(any(),any(),anyString());
    }
    @Test void 精确owner缺失绑定来源头或Agent不一致均拒绝() {
        var other=new PrincipalRef(tenant,"owner","其他",Set.of("project:read"));
        assertEquals(404,assertThrows(LocalProjectAccessException.class,()->resources.conversation(other,conversation)).status());
        when(bindings.run(tenant,"Owner",runId)).thenReturn(Optional.empty());
        assertThrows(LocalProjectAccessException.class,()->resources.run(owner,run));
        when(bindings.run(tenant,"Owner",runId)).thenReturn(Optional.of(fixed));when(conversations.workspaceProtocol(tenant,agent,conversationId)).thenReturn(0);
        assertThrows(LocalProjectAccessException.class,()->resources.run(owner,run));
        var wrongAgent=new Conversation(conversationId,tenant,UUID.randomUUID(),"会话","Owner",now,now);
        assertThrows(LocalProjectAccessException.class,()->resources.conversation(owner,wrongAgent));
    }
}
