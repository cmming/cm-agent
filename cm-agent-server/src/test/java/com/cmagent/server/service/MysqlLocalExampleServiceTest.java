package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.ToolRuntimeReadiness;
import com.cmagent.server.runtime.http.HttpToolProperties;
import com.cmagent.server.runtime.local.MysqlLocalExampleCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MysqlLocalExampleServiceTest {
    private static final UUID TENANT_ID = MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID;
    private static final PrincipalRef PRINCIPAL = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));
    private static final PrincipalRef OTHER_TENANT_PRINCIPAL = new PrincipalRef(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), "other", "其他管理员", Set.of("tool:grant")
    );

    @Mock
    private ToolDefinitionRepository repository;
    @Mock
    private TransactionOperations transactionOperations;
    @Mock
    private AuditAppender auditAppender;

    private final List<ToolDefinition> saved = new ArrayList<>();
    private MysqlLocalExampleService service;

    @BeforeEach
    void setUp() {
        MysqlLocalExampleCatalog catalog = new MysqlLocalExampleCatalog(new ObjectMapper());
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        catalog.list().forEach(example -> registry.register(example.definition(), example.executor()));
        service = new MysqlLocalExampleService(repository, transactionOperations, auditAppender, catalog,
                new ToolRuntimeReadiness(registry, new HttpToolProperties()), new ObjectMapper());
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(null);
            return null;
        }).when(transactionOperations).executeWithoutResult(any());
        lenient().when(repository.findByTenantAndId(any(), any())).thenAnswer(invocation -> saved.stream()
                .filter(tool -> tool.tenantId().equals(invocation.getArgument(0)) && tool.id().equals(invocation.getArgument(1)))
                .findFirst());
        lenient().when(repository.listByTenant(TENANT_ID)).thenAnswer(invocation -> List.copyOf(saved));
        lenient().when(repository.save(any())).thenAnswer(invocation -> {
            ToolDefinition tool = invocation.getArgument(0);
            saved.add(tool);
            return tool;
        });
    }

    @Test
    void 首次安装写入固定定义并审计且重复安装幂等() {
        LocalToolExampleSummary installed = service.install(PRINCIPAL, "echo");

        assertThat(installed.installed()).isTrue();
        assertThat(installed.runtimeReady()).isTrue();
        assertThat(saved).singleElement().extracting(ToolDefinition::name).isEqualTo("echo");
        verify(auditAppender).append(TENANT_ID, "admin", "LOCAL_EXAMPLE_INSTALL", "TOOL",
                MysqlLocalExampleCatalog.ECHO_TOOL_ID.toString(), "SUCCEEDED", "内置 LOCAL 示例安装成功");

        LocalToolExampleSummary repeated = service.install(PRINCIPAL, "echo");
        assertThat(repeated.toolId()).isEqualTo(installed.toolId());
        assertThat(saved).hasSize(1);
        verifyNoMoreInteractions(auditAppender);
    }

    @Test
    void 固定Id同名或定义漂移返回冲突且不覆盖() {
        ToolDefinition expected = new MysqlLocalExampleCatalog(new ObjectMapper()).find("echo").orElseThrow()
                .persistentDefinition("admin");
        saved.add(new ToolDefinition(expected.id(), expected.tenantId(), expected.name(), "已修改", expected.type(),
                expected.inputSchema(), expected.riskLevel(), expected.enabled(), expected.endpoint(), "admin", "admin"));

        assertThatThrownBy(() -> service.install(PRINCIPAL, "echo"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(saved).hasSize(1);
        verifyNoInteractions(auditAppender);
    }

    @Test
    void 非示例租户未知Key和非允许状态返回空目录或未找到() {
        assertThat(service.list(OTHER_TENANT_PRINCIPAL)).isEmpty();
        assertThatThrownBy(() -> service.install(OTHER_TENANT_PRINCIPAL, "echo"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.install(PRINCIPAL, "missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void 同名不同Id返回冲突且不覆盖用户定义() {
        saved.add(new ToolDefinition(UUID.randomUUID(), TENANT_ID, "echo", "用户工具", ToolType.LOCAL, "{}",
                ToolRiskLevel.LOW, true, "", "admin", "admin"));

        assertThatThrownBy(() -> service.install(PRINCIPAL, "echo"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(saved).hasSize(1);
        verifyNoInteractions(auditAppender);
    }
}
