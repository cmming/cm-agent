package com.cmagent.server.runtime;
import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.store.InMemorySkillArtifactRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillArtifactServiceTest {
    @TempDir Path root;
    private final InMemorySkillArtifactRepository rows=new InMemorySkillArtifactRepository();
    private final RunRepository runs=mock(RunRepository.class);
    private final AuditAppender audit=mock(AuditAppender.class);
    private final SkillReadRequest request=DockerSkillSandboxIntegrationTest.request();
    private SkillArtifactService service;
    private RunRecord running;
    @BeforeEach void setup(){
        var properties=new SkillProperties();var policy=properties.getSandbox().getArtifacts();policy.setEnabled(true);policy.setRootDirectory(root.toString());policy.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        var storage=new FileSystemSkillArtifactStorage(policy);
        @SuppressWarnings("unchecked") ObjectProvider<SkillArtifactStorage> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(storage);
        running=RunRecord.create(request.runId(),request.principal().tenantId(),request.agentId(),request.principal().principalId(),"",Instant.now());
        when(runs.findByTenantAndAgentAndId(request.principal().tenantId(),request.agentId(),request.runId())).thenReturn(Optional.of(running));
        service=new SkillArtifactService(rows,provider,runs,mock(SkillTrialRepository.class),mock(ConversationRepository.class),(p,permission)->p.permissions().contains(permission)?AuthorizationDecision.allow():AuthorizationDecision.deny("拒绝"),audit,properties);
    }
    private PrincipalRef owner(){return new PrincipalRef(request.principal().tenantId(),request.principal().principalId(),"测试",Set.of("agent:read"));}
    private UUID collect()throws Exception{
        try(var collection=service.begin(request)){collection.accept("结果.docx",SkillArtifactStorageTest.docx().length,new ByteArrayInputStream(SkillArtifactStorageTest.docx()));collection.complete();}
        return rows.list(request.principal().tenantId(),request.runId()).getFirst().id();
    }
    private void succeed(){var success=running.complete(RunStatus.SUCCEEDED,"","",Instant.now());when(runs.findByTenantAndAgentAndId(success.tenantId(),success.agentId(),success.id())).thenReturn(Optional.of(success));service.finalizeRun(success);}
    @Test void 审批等待不可下载成功发布后完整回传()throws Exception{
        UUID id=collect();assertThat(service.list(owner(),request.agentId(),request.runId(),null).files()).isEmpty();
        assertThatThrownBy(()->service.download(owner(),id)).isInstanceOf(SkillAccessException.class);
        succeed();assertThat(service.list(owner(),request.agentId(),request.runId(),null).files()).hasSize(1);
        try(var download=service.download(owner(),id)){var out=new ByteArrayOutputStream();download.write(out);assertThat(out.toByteArray()).isEqualTo(SkillArtifactStorageTest.docx());}
        assertThat(rows.find(owner().tenantId(),id).orElseThrow().leaseToken()).isNull();
    }
    @Test void 失败和审计拒绝不会发布且清理释放配额()throws Exception{
        UUID id=collect();doThrow(new IllegalStateException("审计不可用")).when(audit).append(any(),any(),eq("SKILL_ARTIFACT_PUBLISH"),any(),any(),any(),any());
        assertThatThrownBy(this::succeed).isInstanceOf(IllegalStateException.class);assertThat(rows.find(owner().tenantId(),id).orElseThrow().status()).isEqualTo(SkillArtifactStatus.PENDING);
        service.finalizeRun(running.complete(RunStatus.FAILED,"","失败",Instant.now()));service.cleanup();assertThat(rows.find(owner().tenantId(),id).orElseThrow().status()).isEqualTo(SkillArtifactStatus.DELETED);
    }
    @Test void 跨租户跨主体和缺权限拒绝()throws Exception{
        UUID id=collect();succeed();
        for(var foreign:List.of(new PrincipalRef(UUID.randomUUID(),owner().principalId(),"外租户",Set.of("agent:read")),new PrincipalRef(owner().tenantId(),"other","其他主体",Set.of("agent:read"))))
            assertThatThrownBy(()->service.download(foreign,id)).isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_ARTIFACT_NOT_FOUND));
        assertThatThrownBy(()->service.download(request.principal(),id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void 流中断同编号审计和释放租约()throws Exception{
        UUID id=collect();succeed();String error;
        try(var download=service.download(owner(),id)){error=download.errorId();assertThatThrownBy(()->download.write(new OutputStream(){public void write(int value)throws IOException{throw new IOException("秘密路径");}})).hasMessage("文件传输中断");}
        verify(audit).append(owner().tenantId(),owner().principalId(),"SKILL_ARTIFACT_DOWNLOAD","SKILL_ARTIFACT",id.toString(),"INTERRUPTED","errorId="+error);
        assertThat(rows.find(owner().tenantId(),id).orElseThrow().leaseToken()).isNull();
    }
    @Test void 未完成调用只补偿自身文件()throws Exception{
        UUID id=collect();var second=new SkillReadRequest(request.principal(),request.agentId(),request.runId(),"second",UUID.randomUUID(),request.skillId(),request.versionId(),request.path());
        try(var collection=service.begin(second)){collection.accept("x.txt",1,new ByteArrayInputStream(new byte[]{1}));}
        assertThat(rows.find(owner().tenantId(),id).orElseThrow().status()).isEqualTo(SkillArtifactStatus.PENDING);
        assertThat(rows.list(owner().tenantId(),request.runId()).getLast().status()).isEqualTo(SkillArtifactStatus.DELETING);
    }
}
