package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.runtime.http.HttpToolConfigValidator;
import com.cmagent.server.store.InMemoryPlatformStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementCommandServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ToolDefinitionRepository toolRepository;

    @Mock
    private ToolGrantRepository grantRepository;

    @Mock
    private HttpToolConfigRepository httpToolConfigRepository;

    @Mock
    private McpToolPublicationRepository mcpToolPublicationRepository;

    @Mock
    private AuditAppender auditAppender;

    @Test
    void memoryFallbackDoesNotPersistAgentWhenAuditFails() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        AgentDefinitionRepository agentRepository = new AgentDefinitionRepository() {
            @Override
            public AgentDefinition save(AgentDefinition agent) {
                return store.saveAgent(agent);
            }

            @Override
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return store.findAgent(tenantId, agentId);
            }

            @Override
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return store.listAgents(tenantId);
            }

            @Override
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.addToolToAgent(tenantId, agentId, toolId);
            }
        };
        doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException("database unavailable")))
                .when(auditAppender)
                .append(any(), any(), any(), any(), any(), any(), any());

        ManagementCommandService service = new ManagementCommandService(
                agentRepository,
                toolRepository,
                httpToolConfigRepository,
                mcpToolPublicationRepository,
                grantRepository,
                auditAppender,
                httpToolConfigValidator(),
                null
        );
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("agent:write"));

        assertThatThrownBy(() -> service.createAgent(principal, "助手", "系统提示", "qwen-max"))
                .isInstanceOf(AuditPersistenceException.class);

        assertThat(store.listAgents(TENANT_ID)).isEmpty();
        verify(auditAppender).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void memoryFallbackDoesNotPersistToolOrSuccessAuditWhenHttpConfigurationIsInvalid() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ManagementCommandService service = memoryBackedService(store);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));
        HttpToolCreateSpec invalidSpec = new HttpToolCreateSpec(
                HttpToolMethod.POST,
                "https://api.example.test/orders",
                "{}",
                List.of(new HttpParameterMapping("", HttpParameterLocation.BODY, "", "/payload", true, "")),
                java.util.Map.of("X-Api-Key", "not-a-secret-reference"),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> service.createTool(
                principal, "invalid-http", "无效配置", ToolType.HTTP, ToolRiskLevel.LOW, invalidSpec, true
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(store.listTools(TENANT_ID)).isEmpty();
        assertThat(store.listAuditEvents(TENANT_ID)).isEmpty();
        assertThat(store.listEnabledMcpToolPublications(TENANT_ID)).isEmpty();
    }

    @Test
    void httpCreationValidatesSchemaBeforeAnyPersistence() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ManagementCommandService service = memoryBackedService(store);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));
        HttpToolCreateSpec invalidSpec = new HttpToolCreateSpec(
                HttpToolMethod.POST,
                "https://api.example.test/orders",
                "{\"type\":\"array\"}",
                List.of(),
                java.util.Map.of(),
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(() -> service.createTool(
                principal, "invalid-schema", "无效 Schema", ToolType.HTTP, ToolRiskLevel.LOW, invalidSpec, false
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object");

        assertThat(store.listTools(TENANT_ID)).isEmpty();
        assertThat(store.listAuditEvents(TENANT_ID)).isEmpty();
    }

    @Test
    void duplicateNameConstraintIsMappedToConflict() {
        when(toolRepository.listByTenant(TENANT_ID)).thenReturn(List.of());
        when(toolRepository.save(any())).thenThrow(new DuplicateKeyException(
                "duplicate key value violates unique constraint ux_tool_definitions_tenant_name"
        ));
        ManagementCommandService service = new ManagementCommandService(
                emptyAgentRepository(), toolRepository, httpToolConfigRepository, mcpToolPublicationRepository,
                grantRepository, auditAppender, httpToolConfigValidator(), null
        );
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));

        assertThatThrownBy(() -> service.createTool(
                principal, "duplicate", "重复", ToolType.LOCAL, ToolRiskLevel.LOW
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void concurrentMemoryCreatesWithSameTenantAndNameProduceOneConflict() throws Exception {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ToolDefinitionRepository tools = new BarrierToolDefinitionRepository(memoryToolRepository(store), 2);
        ManagementCommandService first = memoryBackedService(store, tools);
        ManagementCommandService second = memoryBackedService(store, tools);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstResult = executor.submit(() -> createWithStatus(first, principal));
            var secondResult = executor.submit(() -> createWithStatus(second, principal));

            assertThat(List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(store.listTools(TENANT_ID)).hasSize(1);
    }

    private static int createWithStatus(ManagementCommandService service, PrincipalRef principal) {
        try {
            service.createTool(principal, "concurrent-memory-name", "并发测试", ToolType.LOCAL, ToolRiskLevel.LOW);
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private ManagementCommandService memoryBackedService(InMemoryPlatformStore store) {
        return memoryBackedService(store, memoryToolRepository(store));
    }

    private ManagementCommandService memoryBackedService(InMemoryPlatformStore store, ToolDefinitionRepository memoryTools) {
        HttpToolConfigRepository memoryHttpConfigs = new HttpToolConfigRepository() {
            @Override
            public com.cmagent.core.domain.HttpToolConfig save(com.cmagent.core.domain.HttpToolConfig config) {
                return store.saveHttpToolConfig(config);
            }

            @Override
            public Optional<com.cmagent.core.domain.HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId) {
                return store.findHttpToolConfig(tenantId, toolId);
            }

            @Override
            public java.util.Map<UUID, com.cmagent.core.domain.HttpToolConfig> findByTenantAndToolIds(
                    UUID tenantId, List<UUID> toolIds
            ) {
                return toolIds.stream().map(toolId -> store.findHttpToolConfig(tenantId, toolId))
                        .flatMap(Optional::stream)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                com.cmagent.core.domain.HttpToolConfig::toolId, config -> config
                        ));
            }

            @Override
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteHttpToolConfig(tenantId, toolId);
            }
        };
        McpToolPublicationRepository memoryPublications = new McpToolPublicationRepository() {
            @Override
            public com.cmagent.core.domain.McpToolPublication save(com.cmagent.core.domain.McpToolPublication publication) {
                return store.saveMcpToolPublication(publication);
            }

            @Override
            public Optional<com.cmagent.core.domain.McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId) {
                return store.findMcpToolPublication(tenantId, toolId);
            }

            @Override
            public java.util.Map<UUID, com.cmagent.core.domain.McpToolPublication> findByTenantAndToolIds(
                    UUID tenantId, List<UUID> toolIds
            ) {
                return toolIds.stream().map(toolId -> store.findMcpToolPublication(tenantId, toolId))
                        .flatMap(Optional::stream)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                com.cmagent.core.domain.McpToolPublication::toolId, publication -> publication
                        ));
            }

            @Override
            public List<com.cmagent.core.domain.McpToolPublication> listEnabledByTenant(UUID tenantId) {
                return store.listEnabledMcpToolPublications(tenantId);
            }

            @Override
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteMcpToolPublication(tenantId, toolId);
            }
        };
        return new ManagementCommandService(
                emptyAgentRepository(), memoryTools, memoryHttpConfigs, memoryPublications, grantRepository,
                new AuditAppender(store), httpToolConfigValidator(), null
        );
    }

    private static HttpToolConfigValidator httpToolConfigValidator() {
        return new HttpToolConfigValidator(new ObjectMapper());
    }

    private static ToolDefinitionRepository memoryToolRepository(InMemoryPlatformStore store) {
        return new ToolDefinitionRepository() {
            @Override
            public ToolDefinition save(ToolDefinition tool) {
                return store.saveTool(tool);
            }

            @Override
            public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
                return store.findTool(tenantId, toolId);
            }

            @Override
            public List<ToolDefinition> listByTenant(UUID tenantId) {
                return store.listTools(tenantId);
            }

            @Override
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteTool(tenantId, toolId);
            }
        };
    }

    private static final class BarrierToolDefinitionRepository implements ToolDefinitionRepository {
        private final ToolDefinitionRepository delegate;
        private final CountDownLatch listBarrier;

        private BarrierToolDefinitionRepository(ToolDefinitionRepository delegate, int callers) {
            this.delegate = delegate;
            this.listBarrier = new CountDownLatch(callers);
        }

        @Override
        public ToolDefinition save(ToolDefinition tool) {
            return delegate.save(tool);
        }

        @Override
        public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
            return delegate.findByTenantAndId(tenantId, toolId);
        }

        @Override
        public List<ToolDefinition> listByTenant(UUID tenantId) {
            List<ToolDefinition> tools = delegate.listByTenant(tenantId);
            listBarrier.countDown();
            try {
                if (!listBarrier.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发名称检查未同时到达");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发名称检查被中断", exception);
            }
            return tools;
        }

        @Override
        public void delete(UUID tenantId, UUID toolId) {
            delegate.delete(tenantId, toolId);
        }
    }

    private AgentDefinitionRepository emptyAgentRepository() {
        return new AgentDefinitionRepository() {
            @Override
            public AgentDefinition save(AgentDefinition agent) {
                return agent;
            }

            @Override
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return Optional.empty();
            }

            @Override
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return List.of();
            }

            @Override
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
