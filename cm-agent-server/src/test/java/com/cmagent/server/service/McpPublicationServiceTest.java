package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpPublicationServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock
    private ToolDefinitionRepository toolRepository;
    @Mock
    private HttpToolConfigRepository httpToolConfigRepository;
    @Mock
    private McpToolPublicationRepository publicationRepository;
    @Mock
    private ToolRegistry registry;
    @Mock
    private AuditAppender auditAppender;

    private McpPublicationService service;
    private PrincipalRef principal;

    @BeforeEach
    void setUp() {
        service = new McpPublicationService(toolRepository, httpToolConfigRepository, publicationRepository, registry, auditAppender, null);
        principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));
    }

    @Test
    void publishHttpToolWithMatchingEndpointAndAudit() {
        ToolDefinition tool = httpTool(TOOL_ID, "orders_v1", "https://api.example.test/orders");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(httpToolConfigRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(config(tool)));
        when(publicationRepository.listEnabledByTenant(TENANT_ID)).thenReturn(List.of());
        when(publicationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        McpToolPublication publication = service.publish(principal, TOOL_ID);

        assertThat(publication).isEqualTo(new McpToolPublication(TENANT_ID, TOOL_ID, true, "admin"));
        verify(auditAppender).append(TENANT_ID, "admin", "MCP_TOOL_PUBLISHED", "TOOL", TOOL_ID.toString(), "SUCCEEDED", "MCP 工具已发布");
    }

    @Test
    void publishRejectsInvalidMcpNameAndDuplicateEnabledName() {
        ToolDefinition invalid = httpTool(TOOL_ID, "invalid name", "https://api.example.test/orders");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(invalid));

        assertThatThrownBy(() -> service.publish(principal, TOOL_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        ToolDefinition valid = httpTool(TOOL_ID, "orders_v1", "https://api.example.test/orders");
        ToolDefinition duplicate = httpTool(OTHER_TOOL_ID, "orders_v1", "https://api.example.test/other");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(valid));
        when(httpToolConfigRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(config(valid)));
        when(publicationRepository.listEnabledByTenant(TENANT_ID)).thenReturn(List.of(new McpToolPublication(TENANT_ID, OTHER_TOOL_ID, true, "other")));
        when(toolRepository.listByTenant(TENANT_ID)).thenReturn(List.of(valid, duplicate));

        assertThatThrownBy(() -> service.publish(principal, TOOL_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(publicationRepository, never()).save(any());
    }

    @Test
    void publishRejectsLocalToolWhenRegistryDefinitionDoesNotExactlyMatch() {
        ToolDefinition stored = localTool(TOOL_ID, "local_tool");
        ToolDefinition registered = localTool(TOOL_ID, "other_name");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(stored));
        when(registry.snapshot(TOOL_ID)).thenReturn(Optional.of(new ToolRegistry.ToolRegistrationSnapshot(registered, request -> null)));

        assertThatThrownBy(() -> service.publish(principal, TOOL_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(publicationRepository, auditAppender);
    }

    @Test
    void auditFailureCompensatesMemoryPublicationAndUnpublicationState() {
        ToolDefinition tool = httpTool(TOOL_ID, "orders_v1", "https://api.example.test/orders");
        McpToolPublication old = new McpToolPublication(TENANT_ID, TOOL_ID, false, "old-admin");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(httpToolConfigRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(config(tool)));
        when(publicationRepository.listEnabledByTenant(TENANT_ID)).thenReturn(List.of());
        when(publicationRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(old));
        AuditPersistenceException failure = new AuditPersistenceException("审计写入失败", new IllegalStateException());
        doThrow(failure).when(auditAppender).append(TENANT_ID, "admin", "MCP_TOOL_PUBLISHED", "TOOL", TOOL_ID.toString(), "SUCCEEDED", "MCP 工具已发布");

        assertThatThrownBy(() -> service.publish(principal, TOOL_ID)).isSameAs(failure);
        verify(publicationRepository).save(new McpToolPublication(TENANT_ID, TOOL_ID, true, "admin"));
        verify(publicationRepository).save(old);

        doThrow(failure).when(auditAppender).append(TENANT_ID, "admin", "MCP_TOOL_UNPUBLISHED", "TOOL", TOOL_ID.toString(), "SUCCEEDED", "MCP 工具已取消发布");
        assertThatThrownBy(() -> service.unpublish(principal, TOOL_ID)).isSameAs(failure);
        verify(publicationRepository).delete(TENANT_ID, TOOL_ID);
        verify(publicationRepository, org.mockito.Mockito.times(2)).save(old);
    }

    @Test
    void unpublishIsIdempotentAndAudited() {
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(httpTool(TOOL_ID, "orders_v1", "https://api.example.test/orders")));
        when(publicationRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID)).thenReturn(Optional.empty());

        service.unpublish(principal, TOOL_ID);

        verify(publicationRepository).delete(TENANT_ID, TOOL_ID);
        verify(auditAppender).append(TENANT_ID, "admin", "MCP_TOOL_UNPUBLISHED", "TOOL", TOOL_ID.toString(), "SUCCEEDED", "MCP 工具已取消发布");
    }

    private static ToolDefinition httpTool(UUID id, String name, String endpoint) {
        return new ToolDefinition(id, TENANT_ID, name, "HTTP 工具", ToolType.HTTP, "{}", ToolRiskLevel.LOW,
                true, endpoint, "admin", "admin");
    }

    private static ToolDefinition localTool(UUID id, String name) {
        return new ToolDefinition(id, TENANT_ID, name, "本地工具", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                true, "", "admin", "admin");
    }

    private static HttpToolConfig config(ToolDefinition tool) {
        return new HttpToolConfig(TENANT_ID, tool.id(), HttpToolMethod.POST, tool.endpoint(), "{}", List.of(), java.util.Map.of(), Duration.ofSeconds(1));
    }
}
