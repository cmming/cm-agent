package com.cmagent.server.runtime;
import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.service.SandboxEndpointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ManagedSkillSandboxTest {
    private final UUID tenant=UUID.randomUUID();
    private final SkillReadRequest request=new SkillReadRequest(new PrincipalRef(tenant,"tester","测试",Set.of()),UUID.randomUUID(),UUID.randomUUID(),"call",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"main.py");
    private SandboxEndpoint endpoint(UUID id,boolean enabled,long revision){
        var now=Instant.now();return new SandboxEndpoint(id,tenant,"测试","docker",SandboxConnectionMode.LOCAL,"",0,"",enabled,"",0,revision,revision,now,"PASSED",false,now,now);
    }
    @Test void 默认切换不改变执行连接但停用原端点拒绝交付(){
        var properties=new SkillProperties();properties.getSandbox().setEnabled(true);
        var endpoints=mock(SandboxEndpointService.class);var backend=mock(SkillSandboxBackend.class);
        var delegate=mock(SkillSandboxBackend.Execution.class);UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        when(backend.backendId()).thenReturn("docker");when(backend.open(eq(request),anyMap())).thenReturn(delegate);
        when(delegate.execute(anyMap(),anyString())).thenReturn("结果");
        when(endpoints.defaultId(tenant)).thenReturn(Optional.of(first));
        when(endpoints.get(tenant,first)).thenReturn(endpoint(first,true,1));
        when(endpoints.connection(any())).thenReturn(Map.of("mode","LOCAL"));
        var managed=new ManagedSkillSandbox(List.of(backend),properties,endpoints,mock(AuditAppender.class),new ObjectMapper());
        try(var execution=managed.open(request,Map.of())){
            when(endpoints.defaultId(tenant)).thenReturn(Optional.of(second));
            assertThat(execution.execute(Map.of("main.py","print(1)"),"")).isEqualTo("结果");
            verify(endpoints,times(1)).defaultId(tenant);
            when(endpoints.get(tenant,first)).thenReturn(endpoint(first,false,2));
            assertThatThrownBy(execution::verifyAccess).isInstanceOfSatisfying(SkillAccessException.class,e->{
                assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_ACCESS_REVOKED);
                assertThat(e.errorId()).isEqualTo(request.attemptId().toString());
            });
            execution.close();execution.close();
        }
        verify(delegate,times(1)).close();
    }
    @Test void 扩展后端仍受全局并发输入输出限额约束(){
        var p=new SkillProperties();p.getSandbox().setEnabled(true);p.getSandbox().setBackend("test");
        p.getSandbox().setMaxConcurrent(1);p.getSandbox().setMaxInputBytes(2);p.getSandbox().setMaxOutputBytes(2);
        var endpoints=mock(SandboxEndpointService.class);when(endpoints.defaultId(tenant)).thenReturn(Optional.empty());when(endpoints.deploymentConnection()).thenReturn(Map.of());
        var backend=mock(SkillSandboxBackend.class);when(backend.backendId()).thenReturn("test");
        var delegate=mock(SkillSandboxBackend.Execution.class);when(backend.open(any(),anyMap())).thenReturn(delegate);when(delegate.execute(anyMap(),anyString())).thenReturn("过量输出");
        var managed=new ManagedSkillSandbox(List.of(backend),p,endpoints,mock(AuditAppender.class),new ObjectMapper());
        try(var execution=managed.open(request,Map.of())){
            assertThatThrownBy(()->managed.open(request,Map.of())).isInstanceOf(SkillAccessException.class);
            assertThatThrownBy(()->execution.execute(Map.of(),"超量输入")).isInstanceOf(SkillAccessException.class);
            verify(delegate,never()).execute(anyMap(),anyString());
            assertThatThrownBy(()->execution.execute(Map.of(),"")).isInstanceOf(SkillAccessException.class);
        }
        try(var next=managed.open(request,Map.of())){assertThat(next).isNotNull();}
    }
    @Test void 清理失败保留全局并发名额(){
        var p=new SkillProperties();p.getSandbox().setEnabled(true);p.getSandbox().setMaxConcurrent(1);
        var endpoints=mock(SandboxEndpointService.class);when(endpoints.defaultId(tenant)).thenReturn(Optional.empty());when(endpoints.deploymentConnection()).thenReturn(Map.of());
        var backend=mock(SkillSandboxBackend.class);when(backend.backendId()).thenReturn("docker");
        var delegate=mock(SkillSandboxBackend.Execution.class);when(backend.open(any(),anyMap())).thenReturn(delegate);
        doThrow(new SkillAccessException(ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED,"测试清理中断",UUID.randomUUID().toString(),true)).when(delegate).close();
        var managed=new ManagedSkillSandbox(List.of(backend),p,endpoints,mock(AuditAppender.class),new ObjectMapper());
        assertThatThrownBy(()->managed.open(request,Map.of()).close()).isInstanceOf(SkillAccessException.class);
        assertThatThrownBy(()->managed.open(request,Map.of())).isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED));
    }
    @Test void 未知或重复后端启动时拒绝(){
        var p=new SkillProperties();var backend=mock(SkillSandboxBackend.class);when(backend.backendId()).thenReturn("extension");
        var endpoints=mock(SandboxEndpointService.class);var audit=mock(AuditAppender.class);var mapper=new ObjectMapper();
        assertThatThrownBy(()->new ManagedSkillSandbox(List.of(backend),p,endpoints,audit,mapper)).isInstanceOf(IllegalStateException.class);
        p.getSandbox().setBackend("extension");
        assertThatThrownBy(()->new ManagedSkillSandbox(List.of(backend,backend),p,endpoints,audit,mapper)).isInstanceOf(IllegalStateException.class);
    }
    @Test void 扩展执行超时中断后可确认停止并释放全局配额(){
        var p=new SkillProperties();p.getSandbox().setEnabled(true);p.getSandbox().setMaxConcurrent(1);
        p.getSandbox().setTimeout(java.time.Duration.ofSeconds(1));
        var endpoints=mock(SandboxEndpointService.class);when(endpoints.defaultId(tenant)).thenReturn(Optional.empty());when(endpoints.deploymentConnection()).thenReturn(Map.of());
        var backend=mock(SkillSandboxBackend.class);when(backend.backendId()).thenReturn("docker");
        var delegate=mock(SkillSandboxBackend.Execution.class);when(backend.open(any(),anyMap())).thenReturn(delegate);
        var stopped=new java.util.concurrent.CountDownLatch(1);
        when(delegate.execute(anyMap(),anyString())).thenAnswer(call->{try{Thread.sleep(10000);return "不可交付";}finally{stopped.countDown();}});
        var managed=new ManagedSkillSandbox(List.of(backend),p,endpoints,mock(AuditAppender.class),new ObjectMapper());
        assertThatThrownBy(()->managed.execute(request,Map.of("main.py",""),""))
            .isInstanceOfSatisfying(SkillAccessException.class,e->{assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_TIMEOUT);assertThat(e.errorId()).isEqualTo(request.attemptId().toString());});
        assertThat(stopped.getCount()).isZero();
        try(var next=managed.open(request,Map.of())){assertThat(next).isNotNull();}
    }
}
