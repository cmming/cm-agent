package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementCommandServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final PrincipalRef PRINCIPAL =
            new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant", "tool:delete"));

    @Mock
    private AgentDefinitionRepository agentRepository;

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
    /**
     * 验证或支持 {@code memoryUpdateAuditFailureRestoresDefinitionHttpConfigurationAndMcpPublication} 所描述的测试场景。
     */
    void memoryUpdateAuditFailureRestoresDefinitionHttpConfigurationAndMcpPublication() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ToolDefinition existing = httpTool("orders-old");
        HttpToolConfig existingConfiguration = httpConfiguration(existing, validHttpSpec());
        McpToolPublication existingPublication =
                new McpToolPublication(TENANT_ID, TOOL_ID, true, "publisher");
        store.saveTool(existing);
        store.saveHttpToolConfig(existingConfiguration);
        store.saveMcpToolPublication(existingPublication);
        doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException("memory unavailable")))
                .when(auditAppender).append(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> statefulMemoryService(store, auditAppender).updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        "orders_v2",
                        "不应保留",
                        ToolType.HTTP,
                        ToolRiskLevel.HIGH,
                        false,
                        updatedHttpSpec(),
                        false
                )
        )).isInstanceOf(AuditPersistenceException.class);

        assertThat(store.findTool(TENANT_ID, TOOL_ID)).contains(existing);
        assertThat(store.findHttpToolConfig(TENANT_ID, TOOL_ID)).contains(existingConfiguration);
        assertThat(store.findMcpToolPublication(TENANT_ID, TOOL_ID)).contains(existingPublication);
    }

    @Test
    /**
     * 验证或支持 {@code memoryDeleteAuditFailureRestoresToolAndAllAttachedData} 所描述的测试场景。
     */
    void memoryDeleteAuditFailureRestoresToolAndAllAttachedData() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ToolDefinition existing = httpTool("orders");
        HttpToolConfig existingConfiguration = httpConfiguration(existing, validHttpSpec());
        McpToolPublication existingPublication =
                new McpToolPublication(TENANT_ID, TOOL_ID, true, "publisher");
        AgentDefinition agent = agent(List.of());
        ToolGrant residualGrant = new com.cmagent.core.domain.ToolGrant(
                TENANT_ID, TOOL_ID, AGENT_ID, null, true
        );
        store.saveTool(existing);
        store.saveAgent(agent);
        store.saveHttpToolConfig(existingConfiguration);
        store.saveMcpToolPublication(existingPublication);
        store.saveGrant(residualGrant);
        doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException("memory unavailable")))
                .when(auditAppender).append(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> statefulMemoryService(store, auditAppender).deleteTool(PRINCIPAL, TOOL_ID))
                .isInstanceOf(AuditPersistenceException.class);

        assertThat(store.findTool(TENANT_ID, TOOL_ID)).contains(existing);
        assertThat(store.findHttpToolConfig(TENANT_ID, TOOL_ID)).contains(existingConfiguration);
        assertThat(store.findMcpToolPublication(TENANT_ID, TOOL_ID)).contains(existingPublication);
        assertThat(store.listGrants(TENANT_ID, AGENT_ID, TOOL_ID)).containsExactly(residualGrant);
    }

    @Test
    /**
     * 验证或支持 {@code memoryRevokeAuditFailureRestoresGrantAndAgentAssociation} 所描述的测试场景。
     */
    void memoryRevokeAuditFailureRestoresGrantAndAgentAssociation() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ToolDefinition existing = httpTool("orders");
        AgentDefinition existingAgent = agent(List.of(TOOL_ID));
        com.cmagent.core.domain.ToolGrant existingGrant = new com.cmagent.core.domain.ToolGrant(
                TENANT_ID, TOOL_ID, AGENT_ID, null, true
        );
        store.saveTool(existing);
        store.saveAgent(existingAgent);
        store.saveGrant(existingGrant);
        doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException("memory unavailable")))
                .when(auditAppender).append(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> statefulMemoryService(store, auditAppender)
                .revokeTool(PRINCIPAL, TOOL_ID, AGENT_ID))
                .isInstanceOf(AuditPersistenceException.class);

        assertThat(store.findAgent(TENANT_ID, AGENT_ID)).contains(existingAgent);
        assertThat(store.listGrants(TENANT_ID, AGENT_ID, TOOL_ID)).containsExactly(existingGrant);
    }

    @Test
    /**
     * 验证或支持 {@code concurrentMemoryGrantAndRevokeForDifferentToolsKeepBothChanges} 所描述的测试场景。
     */
    void concurrentMemoryGrantAndRevokeForDifferentToolsKeepBothChanges() throws Exception {
        UUID removedToolId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ToolDefinition addedTool = httpTool("orders");
        ToolDefinition removedTool = new ToolDefinition(
                removedToolId,
                TENANT_ID,
                "inventory",
                "库存工具",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "creator",
                "previous-editor"
        );
        store.saveTool(addedTool);
        store.saveTool(removedTool);
        store.saveAgent(agent(List.of(removedToolId)));
        store.saveGrant(new ToolGrant(TENANT_ID, removedToolId, AGENT_ID, null, true));
        ManagementCommandService grantService =
                statefulMemoryService(store, new AuditAppender(store));
        ManagementCommandService revokeService =
                statefulMemoryService(store, new AuditAppender(store));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var grantResult = executor.submit(() -> {
                awaitLatch(start, "并发授权未开始");
                return grantService.grantTool(PRINCIPAL, TOOL_ID, AGENT_ID);
            });
            var revokeResult = executor.submit(() -> {
                awaitLatch(start, "并发撤销未开始");
                return revokeService.revokeTool(PRINCIPAL, removedToolId, AGENT_ID);
            });

            start.countDown();

            assertThat(grantResult.get(5, TimeUnit.SECONDS).toolId()).isEqualTo(TOOL_ID);
            assertThat(revokeResult.get(5, TimeUnit.SECONDS).toolIds()).doesNotContain(removedToolId);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(store.findAgent(TENANT_ID, AGENT_ID)).get()
                .extracting(AgentDefinition::toolIds)
                .asList()
                .containsExactly(TOOL_ID);
        assertThat(store.listGrants(TENANT_ID, AGENT_ID, TOOL_ID)).hasSize(1);
        assertThat(store.listGrants(TENANT_ID, AGENT_ID, removedToolId)).isEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code concurrentGrantCannotLeaveAgentPointingToDeletedTool} 所描述的测试场景。
     */
    void concurrentGrantCannotLeaveAgentPointingToDeletedTool() throws Exception {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ToolDefinition existing = httpTool("orders");
        AgentDefinition existingAgent = agent(List.of());
        store.saveTool(existing);
        store.saveAgent(existingAgent);
        AgentDefinitionRepository delegate = memoryAgentRepository(store);
        CountDownLatch deleteReadReferences = new CountDownLatch(1);
        CountDownLatch continueDelete = new CountDownLatch(1);
        CountDownLatch grantReachedRepository = new CountDownLatch(1);
        AgentDefinitionRepository coordinatedAgents = new AgentDefinitionRepository() {
            @Override
            /**
             * 验证或支持 {@code save} 所描述的测试场景。
             *
             * @param agent 测试 Agent 定义
             */
            public AgentDefinition save(AgentDefinition agent) {
                return delegate.save(agent);
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndId} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             */
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                grantReachedRepository.countDown();
                return delegate.findByTenantAndId(tenantId, agentId);
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             */
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                List<AgentDefinition> snapshot = delegate.listByTenant(tenantId);
                deleteReadReferences.countDown();
                awaitLatch(continueDelete, "删除流程未获准继续");
                return snapshot;
            }

            @Override
            /**
             * 验证或支持 {@code addToolToAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return delegate.addToolToAgent(tenantId, agentId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code removeToolFromAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return delegate.removeToolFromAgent(tenantId, agentId, toolId);
            }
        };
        ManagementCommandService deleteService = statefulMemoryService(
                store, coordinatedAgents, new AuditAppender(store)
        );
        ManagementCommandService grantService = statefulMemoryService(
                store, coordinatedAgents, new AuditAppender(store)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var deleteResult = executor.submit(() -> {
                deleteService.deleteTool(PRINCIPAL, TOOL_ID);
                return 204;
            });
            assertThat(deleteReadReferences.await(5, TimeUnit.SECONDS)).isTrue();
            var grantResult = executor.submit(() -> {
                try {
                    grantService.grantTool(PRINCIPAL, TOOL_ID, AGENT_ID);
                    return 200;
                } catch (ResponseStatusException exception) {
                    return exception.getStatusCode().value();
                }
            });

            if (grantReachedRepository.await(500, TimeUnit.MILLISECONDS)) {
                assertThat(grantResult.get(5, TimeUnit.SECONDS)).isEqualTo(200);
            }
            continueDelete.countDown();

            assertThat(deleteResult.get(5, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(grantResult.get(5, TimeUnit.SECONDS)).isEqualTo(404);
        } finally {
            continueDelete.countDown();
            executor.shutdownNow();
        }
        assertThat(store.findTool(TENANT_ID, TOOL_ID)).isEmpty();
        assertThat(store.findAgent(TENANT_ID, AGENT_ID)).get()
                .extracting(AgentDefinition::toolIds)
                .asList()
                .doesNotContain(TOOL_ID);
    }

    @Test
    /**
     * 验证或支持 {@code successfulMemoryDeleteRemovesExistingMcpPublicationBeforeToolDefinition} 所描述的测试场景。
     */
    void successfulMemoryDeleteRemovesExistingMcpPublicationBeforeToolDefinition() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ToolDefinition existing = httpTool("orders");
        store.saveTool(existing);
        store.saveHttpToolConfig(httpConfiguration(existing, validHttpSpec()));
        store.saveMcpToolPublication(new McpToolPublication(TENANT_ID, TOOL_ID, true, "publisher"));

        statefulMemoryService(store, new AuditAppender(store)).deleteTool(PRINCIPAL, TOOL_ID);

        assertThat(store.findMcpToolPublication(TENANT_ID, TOOL_ID)).isEmpty();
        assertThat(store.findTool(TENANT_ID, TOOL_ID)).isEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code memoryInFlightToolCallCanPersistAfterRevokeAndDelete} 所描述的测试场景。
     */
    void memoryInFlightToolCallCanPersistAfterRevokeAndDelete() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ToolDefinition existing = httpTool("in-flight-orders");
        AgentDefinition existingAgent = agent(List.of(TOOL_ID));
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        Instant startedAt = Instant.parse("2026-07-31T00:00:00Z");
        store.saveTool(existing);
        store.saveAgent(existingAgent);
        store.saveGrant(new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true));
        store.save(TENANT_ID, RunRecord.create(runId, TENANT_ID, AGENT_ID, PRINCIPAL.principalId(), "执行", startedAt));
        ManagementCommandService service = statefulMemoryService(store, new AuditAppender(store));

        service.revokeTool(PRINCIPAL, TOOL_ID, AGENT_ID);
        service.deleteTool(PRINCIPAL, TOOL_ID);
        RunToolCall call = new RunToolCall(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                TENANT_ID,
                runId,
                TOOL_ID,
                existing.name(),
                "输入",
                "输出",
                RunStatus.SUCCEEDED,
                true,
                10L,
                "",
                startedAt.plusSeconds(1)
        );

        store.saveAll(TENANT_ID, new RunToolCallBatch(TENANT_ID, List.of(call)));

        assertThat(store.findTool(TENANT_ID, TOOL_ID)).isEmpty();
        assertThat(store.listTools(TENANT_ID)).isEmpty();
        assertThat(store.listByTenantAndRun(TENANT_ID, runId)).containsExactly(call);
        assertThat(store.hasToolCallHistory(TENANT_ID, TOOL_ID)).isTrue();
    }

    @Test
    /**
     * 验证或支持 {@code updateHttpToolReplacesEditableDefinitionConfigurationAndMcpState} 所描述的测试场景。
     */
    void updateHttpToolReplacesEditableDefinitionConfigurationAndMcpState() {
        ToolDefinition existing = httpTool("orders-old");
        HttpToolCreateSpec replacement = new HttpToolCreateSpec(
                HttpToolMethod.POST,
                "https://api.example.test/v2/orders",
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}",
                List.of(new HttpParameterMapping(
                        "/orderId", HttpParameterLocation.QUERY, "orderId", "", true, ""
                )),
                java.util.Map.of("X-Api-Key", "secret/tools/orders"),
                Duration.ofSeconds(3)
        );
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));
        when(toolRepository.listByTenant(TENANT_ID)).thenReturn(List.of(existing));
        when(toolRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(httpToolConfigRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mcpToolPublicationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManagementCommandService.ToolUpdateResult result = mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        "orders_v2",
                        "新版订单工具",
                        ToolType.HTTP,
                        ToolRiskLevel.HIGH,
                        false,
                        replacement,
                        true
                )
        );
        ToolDefinition updated = result.tool();

        assertThat(updated.id()).isEqualTo(existing.id());
        assertThat(updated.tenantId()).isEqualTo(existing.tenantId());
        assertThat(updated.type()).isEqualTo(existing.type());
        assertThat(updated.createdBy()).isEqualTo(existing.createdBy());
        assertThat(updated.name()).isEqualTo("orders_v2");
        assertThat(updated.description()).isEqualTo("新版订单工具");
        assertThat(updated.inputSchema()).isEqualTo(replacement.inputSchema());
        assertThat(updated.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.endpoint()).isEqualTo(replacement.urlTemplate());
        assertThat(updated.updatedBy()).isEqualTo(PRINCIPAL.principalId());
        assertThat(result.httpToolConfig()).isNotNull();
        assertThat(result.mcpPublished()).isTrue();

        ArgumentCaptor<HttpToolConfig> configurationCaptor = ArgumentCaptor.forClass(HttpToolConfig.class);
        verify(httpToolConfigRepository).save(configurationCaptor.capture());
        assertThat(configurationCaptor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(configurationCaptor.getValue().toolId()).isEqualTo(TOOL_ID);
        assertThat(configurationCaptor.getValue().urlTemplate()).isEqualTo(replacement.urlTemplate());
        assertThat(configurationCaptor.getValue().secretHeaders()).isEqualTo(replacement.secretHeaders());
        verify(mcpToolPublicationRepository).save(
                new McpToolPublication(TENANT_ID, TOOL_ID, true, PRINCIPAL.principalId())
        );
        verify(auditAppender).append(
                TENANT_ID, PRINCIPAL.principalId(), "TOOL_UPDATE", "TOOL",
                TOOL_ID.toString(), "SUCCEEDED", "工具更新成功"
        );
    }

    @Test
    /**
     * 验证或支持 {@code updateLocalToolRejectsRenameWithoutWritingAnything} 所描述的测试场景。
     */
    void updateLocalToolRejectsRenameWithoutWritingAnything() {
        ToolDefinition existing = localTool("local_search");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        "renamed_local_search",
                        "本地搜索",
                        ToolType.LOCAL,
                        ToolRiskLevel.MEDIUM,
                        true,
                        null,
                        false
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("LOCAL 工具名称不可修改");

        verify(toolRepository, never()).update(any());
        verify(auditAppender, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code updatePublishedLocalToolPreservesPublicationWithoutHttpConfiguration} 所描述的测试场景。
     */
    void updatePublishedLocalToolPreservesPublicationWithoutHttpConfiguration() {
        ToolDefinition existing = localTool("local_search");
        McpToolPublication publication =
                new McpToolPublication(TENANT_ID, TOOL_ID, true, "publisher");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));
        when(toolRepository.listByTenant(TENANT_ID)).thenReturn(List.of(existing));
        when(toolRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mcpToolPublicationRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID))
                .thenReturn(Optional.of(publication));

        ManagementCommandService.ToolUpdateResult result = mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        existing.name(),
                        "更新后的本地搜索",
                        ToolType.LOCAL,
                        ToolRiskLevel.MEDIUM,
                        true,
                        null,
                        true
                )
        );

        assertThat(result.tool().description()).isEqualTo("更新后的本地搜索");
        assertThat(result.httpToolConfig()).isNull();
        assertThat(result.mcpPublished()).isTrue();
        verify(httpToolConfigRepository, never()).save(any());
        verify(mcpToolPublicationRepository, never()).save(any());
        verify(mcpToolPublicationRepository, never()).delete(any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code updatePublishedLocalToolCanCancelPublication} 所描述的测试场景。
     */
    void updatePublishedLocalToolCanCancelPublication() {
        ToolDefinition existing = localTool("local_search");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));
        when(toolRepository.listByTenant(TENANT_ID)).thenReturn(List.of(existing));
        when(toolRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mcpToolPublicationRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID))
                .thenReturn(Optional.of(new McpToolPublication(TENANT_ID, TOOL_ID, true, "publisher")));

        mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        existing.name(),
                        existing.description(),
                        ToolType.LOCAL,
                        existing.riskLevel(),
                        existing.enabled(),
                        null,
                        false
                )
        );

        verify(httpToolConfigRepository, never()).save(any());
        verify(mcpToolPublicationRepository).delete(TENANT_ID, TOOL_ID);
    }

    @Test
    /**
     * 验证或支持 {@code updateUnpublishedLocalToolCannotBypassDedicatedPublicationValidation} 所描述的测试场景。
     */
    void updateUnpublishedLocalToolCannotBypassDedicatedPublicationValidation() {
        ToolDefinition existing = localTool("local_search");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));
        when(mcpToolPublicationRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        existing.name(),
                        existing.description(),
                        ToolType.LOCAL,
                        existing.riskLevel(),
                        existing.enabled(),
                        null,
                        true
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
            assertThat(exception.getStatusCode().value()).isEqualTo(400);
            assertThat(exception.getReason()).contains("MCP 发布操作");
        });

        verify(toolRepository, never()).update(any());
        verify(mcpToolPublicationRepository, never()).save(any());
    }

    @Test
    /**
     * 验证或支持 {@code updateDisabledLocalPublicationCannotBeMistakenForPublishedState} 所描述的测试场景。
     */
    void updateDisabledLocalPublicationCannotBeMistakenForPublishedState() {
        ToolDefinition existing = localTool("local_search");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));
        when(mcpToolPublicationRepository.findByTenantAndToolId(TENANT_ID, TOOL_ID))
                .thenReturn(Optional.of(new McpToolPublication(TENANT_ID, TOOL_ID, false, "publisher")));

        assertThatThrownBy(() -> mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        existing.name(),
                        existing.description(),
                        ToolType.LOCAL,
                        existing.riskLevel(),
                        existing.enabled(),
                        null,
                        true
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("MCP 发布操作");

        verify(toolRepository, never()).update(any());
        verify(mcpToolPublicationRepository, never()).save(any());
    }

    @Test
    /**
     * 验证或支持 {@code updateToolRejectsTypeChangeWithoutWritingAnything} 所描述的测试场景。
     */
    void updateToolRejectsTypeChangeWithoutWritingAnything() {
        ToolDefinition existing = localTool("local_search");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        existing.name(),
                        existing.description(),
                        ToolType.HTTP,
                        existing.riskLevel(),
                        existing.enabled(),
                        validHttpSpec(),
                        false
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("工具类型不可修改");

        verify(toolRepository, never()).update(any());
    }

    @Test
    /**
     * 验证或支持 {@code updateToolRejectsAnotherToolWithSameTenantName} 所描述的测试场景。
     */
    void updateToolRejectsAnotherToolWithSameTenantName() {
        ToolDefinition existing = httpTool("orders-old");
        ToolDefinition duplicate = new ToolDefinition(
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                TENANT_ID,
                "orders_v2",
                "已存在工具",
                ToolType.HTTP,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "https://api.example.test/existing",
                "creator",
                "creator"
        );
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));
        when(toolRepository.listByTenant(TENANT_ID)).thenReturn(List.of(existing, duplicate));

        assertThatThrownBy(() -> mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        duplicate.name(),
                        "重复名称",
                        ToolType.HTTP,
                        ToolRiskLevel.MEDIUM,
                        true,
                        validHttpSpec(),
                        false
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(409))
                .hasMessageContaining("工具名称已存在");

        verify(toolRepository, never()).update(any());
        verify(httpToolConfigRepository, never()).save(any());
    }

    @Test
    /**
     * 验证或支持 {@code updateHttpToolRejectsMissingHttpConfiguration} 所描述的测试场景。
     */
    void updateHttpToolRejectsMissingHttpConfiguration() {
        ToolDefinition existing = httpTool("orders");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        existing.name(),
                        existing.description(),
                        ToolType.HTTP,
                        existing.riskLevel(),
                        existing.enabled(),
                        null,
                        false
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("HTTP 工具必须提供配置");

        verify(toolRepository, never()).update(any());
        verify(httpToolConfigRepository, never()).save(any());
    }

    @Test
    /**
     * 验证或支持 {@code updateLocalToolRejectsHttpConfiguration} 所描述的测试场景。
     */
    void updateLocalToolRejectsHttpConfiguration() {
        ToolDefinition existing = localTool("local_search");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        existing.name(),
                        existing.description(),
                        ToolType.LOCAL,
                        existing.riskLevel(),
                        existing.enabled(),
                        validHttpSpec(),
                        false
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("仅 HTTP 工具可以提供 HTTP 配置");

        verify(toolRepository, never()).update(any());
        verify(httpToolConfigRepository, never()).save(any());
    }

    @Test
    /**
     * 验证或支持 {@code updateToolDoesNotSeeAnotherTenantResource} 所描述的测试场景。
     */
    void updateToolDoesNotSeeAnotherTenantResource() {
        UUID otherTenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mockedService().updateTool(
                PRINCIPAL,
                TOOL_ID,
                new ManagementCommandService.ToolUpdateSpec(
                        "orders",
                        "跨租户工具",
                        ToolType.HTTP,
                        ToolRiskLevel.LOW,
                        true,
                        validHttpSpec(),
                        false
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(404));

        verify(toolRepository).findByTenantAndId(TENANT_ID, TOOL_ID);
        verify(toolRepository, never()).findByTenantAndId(otherTenantId, TOOL_ID);
        verify(toolRepository, never()).update(any());
    }

    @Test
    /**
     * 验证或支持 {@code deleteToolDoesNotSeeAnotherTenantResource} 所描述的测试场景。
     */
    void deleteToolDoesNotSeeAnotherTenantResource() {
        UUID otherTenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mockedService().deleteTool(PRINCIPAL, TOOL_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(404));

        verify(toolRepository).findByTenantAndId(TENANT_ID, TOOL_ID);
        verify(toolRepository, never()).findByTenantAndId(otherTenantId, TOOL_ID);
        verify(toolRepository, never()).delete(any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code revokeToolDoesNotSeeAnotherTenantResource} 所描述的测试场景。
     */
    void revokeToolDoesNotSeeAnotherTenantResource() {
        UUID otherTenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mockedService().revokeTool(PRINCIPAL, TOOL_ID, AGENT_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(404));

        verify(toolRepository).findByTenantAndId(TENANT_ID, TOOL_ID);
        verify(toolRepository, never()).findByTenantAndId(otherTenantId, TOOL_ID);
        verify(grantRepository, never()).delete(any(), any(), any());
        verify(agentRepository, never()).removeToolFromAgent(any(), any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code deleteReferencedToolReturnsConflictWithoutSideEffects} 所描述的测试场景。
     */
    void deleteReferencedToolReturnsConflictWithoutSideEffects() {
        ToolDefinition tool = httpTool("orders");
        AgentDefinition agent = agent(List.of(TOOL_ID));
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(agentRepository.listByTenant(TENANT_ID)).thenReturn(List.of(agent));

        assertThatThrownBy(() -> mockedService().deleteTool(PRINCIPAL, TOOL_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(409))
                .hasMessageContaining("请先解除关联");

        verify(httpToolConfigRepository, never()).delete(TENANT_ID, TOOL_ID);
        verify(mcpToolPublicationRepository, never()).delete(TENANT_ID, TOOL_ID);
        verify(grantRepository, never()).deleteByTenantAndToolId(TENANT_ID, TOOL_ID);
        verify(toolRepository, never()).delete(TENANT_ID, TOOL_ID);
        verify(auditAppender, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code deleteToolWithCallHistoryReturnsExplicitConflictWithoutSideEffects} 所描述的测试场景。
     */
    void deleteToolWithCallHistoryReturnsExplicitConflictWithoutSideEffects() {
        ToolDefinition tool = httpTool("orders");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(agentRepository.listByTenant(TENANT_ID)).thenReturn(List.of());
        when(toolRepository.hasToolCallHistory(TENANT_ID, TOOL_ID)).thenReturn(true);

        assertThatThrownBy(() -> mockedService().deleteTool(PRINCIPAL, TOOL_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(409);
                    assertThat(exception.getReason()).isEqualTo("工具已有调用历史，为保留运行记录不能删除");
                });

        verify(httpToolConfigRepository, never()).delete(TENANT_ID, TOOL_ID);
        verify(mcpToolPublicationRepository, never()).delete(TENANT_ID, TOOL_ID);
        verify(grantRepository, never()).deleteByTenantAndToolId(TENANT_ID, TOOL_ID);
        verify(toolRepository, never()).delete(TENANT_ID, TOOL_ID);
        verify(auditAppender, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    /**
     * 验证或支持 {@code deleteUnreferencedToolCleansAttachedDataAndWritesAudit} 所描述的测试场景。
     */
    void deleteUnreferencedToolCleansAttachedDataAndWritesAudit() {
        ToolDefinition tool = httpTool("orders");
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(agentRepository.listByTenant(TENANT_ID)).thenReturn(List.of());

        mockedService().deleteTool(PRINCIPAL, TOOL_ID);

        InOrder order = inOrder(
                httpToolConfigRepository,
                mcpToolPublicationRepository,
                grantRepository,
                toolRepository,
                auditAppender
        );
        order.verify(httpToolConfigRepository).delete(TENANT_ID, TOOL_ID);
        order.verify(mcpToolPublicationRepository).delete(TENANT_ID, TOOL_ID);
        order.verify(grantRepository).deleteByTenantAndToolId(TENANT_ID, TOOL_ID);
        order.verify(toolRepository).delete(TENANT_ID, TOOL_ID);
        order.verify(auditAppender).append(
                TENANT_ID, PRINCIPAL.principalId(), "TOOL_DELETE", "TOOL",
                TOOL_ID.toString(), "SUCCEEDED", "工具删除成功"
        );
    }

    @Test
    /**
     * 验证或支持 {@code revokeToolRemovesGrantAndAgentAssociationAndWritesAudit} 所描述的测试场景。
     */
    void revokeToolRemovesGrantAndAgentAssociationAndWritesAudit() {
        ToolDefinition tool = httpTool("orders");
        AgentDefinition existingAgent = agent(List.of(TOOL_ID));
        AgentDefinition updatedAgent = agent(List.of());
        when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
        when(agentRepository.findByTenantAndId(TENANT_ID, AGENT_ID)).thenReturn(Optional.of(existingAgent));
        when(agentRepository.removeToolFromAgent(TENANT_ID, AGENT_ID, TOOL_ID)).thenReturn(updatedAgent);

        AgentDefinition updated = mockedService().revokeTool(PRINCIPAL, TOOL_ID, AGENT_ID);

        assertThat(updated.toolIds()).doesNotContain(TOOL_ID);
        InOrder order = inOrder(grantRepository, agentRepository, auditAppender);
        order.verify(grantRepository).delete(TENANT_ID, AGENT_ID, TOOL_ID);
        order.verify(agentRepository).removeToolFromAgent(TENANT_ID, AGENT_ID, TOOL_ID);
        order.verify(auditAppender).append(
                TENANT_ID, PRINCIPAL.principalId(), "TOOL_GRANT_REVOKE", "TOOL",
                TOOL_ID.toString(), "SUCCEEDED", "已解除 Agent " + AGENT_ID + " 的工具授权"
        );
    }

    @Test
    /**
     * 验证或支持 {@code memoryFallbackDoesNotPersistAgentWhenAuditFails} 所描述的测试场景。
     */
    void memoryFallbackDoesNotPersistAgentWhenAuditFails() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        AgentDefinitionRepository agentRepository = new AgentDefinitionRepository() {
            @Override
            /**
             * 验证或支持 {@code save} 所描述的测试场景。
             *
             * @param agent 测试 Agent 定义
             */
            public AgentDefinition save(AgentDefinition agent) {
                return store.saveAgent(agent);
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndId} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             */
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return store.findAgent(tenantId, agentId);
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             */
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return store.listAgents(tenantId);
            }

            @Override
            /**
             * 验证或支持 {@code addToolToAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.addToolToAgent(tenantId, agentId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code removeToolFromAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.removeToolFromAgent(tenantId, agentId, toolId);
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
    /**
     * 验证或支持 {@code memoryFallbackDoesNotPersistToolOrSuccessAuditWhenHttpConfigurationIsInvalid} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code httpCreationValidatesSchemaBeforeAnyPersistence} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code initialMcpPublicationRejectsInvalidToolNameBeforePersistence} 所描述的测试场景。
     */
    void initialMcpPublicationRejectsInvalidToolNameBeforePersistence() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ManagementCommandService service = memoryBackedService(store);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));

        assertThatThrownBy(() -> service.createTool(
                principal, "invalid mcp name", "非法 MCP 名称", ToolType.HTTP, ToolRiskLevel.LOW,
                validHttpSpec(), true
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(400));

        assertThat(store.listTools(TENANT_ID)).isEmpty();
        assertThat(store.listEnabledMcpToolPublications(TENANT_ID)).isEmpty();
        assertThat(store.listAuditEvents(TENANT_ID)).isEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code initialMcpPublicationWritesCreateAndPublicationAudits} 所描述的测试场景。
     */
    void initialMcpPublicationWritesCreateAndPublicationAudits() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        ManagementCommandService service = memoryBackedService(store);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));

        ToolDefinition created = service.createTool(
                principal, "valid_mcp_name", "初始发布", ToolType.HTTP, ToolRiskLevel.LOW,
                validHttpSpec(), true
        );

        assertThat(store.findMcpToolPublication(TENANT_ID, created.id())).isPresent();
        assertThat(store.listAuditEvents(TENANT_ID))
                .extracting(com.cmagent.core.audit.AuditEvent::eventType)
                .containsExactlyInAnyOrder("TOOL_CREATE", "MCP_TOOL_PUBLISHED");
    }

    @Test
    /**
     * 验证或支持 {@code initialMcpPublicationAuditFailureRollsBackCreatedResources} 所描述的测试场景。
     */
    void initialMcpPublicationAuditFailureRollsBackCreatedResources() {
        when(toolRepository.listByTenant(TENANT_ID)).thenReturn(List.of());
        when(toolRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(httpToolConfigRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mcpToolPublicationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new AuditPersistenceException(
                "审计写入失败", new IllegalStateException("database unavailable")
        )).when(auditAppender).appendAll(any());
        ManagementCommandService service = new ManagementCommandService(
                emptyAgentRepository(), toolRepository, httpToolConfigRepository, mcpToolPublicationRepository,
                grantRepository, auditAppender, httpToolConfigValidator(), null
        );
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));

        assertThatThrownBy(() -> service.createTool(
                principal, "valid_mcp_name", "发布审计失败", ToolType.HTTP, ToolRiskLevel.LOW,
                validHttpSpec(), true
        )).isInstanceOf(AuditPersistenceException.class);

        verify(mcpToolPublicationRepository).delete(org.mockito.ArgumentMatchers.eq(TENANT_ID), any());
        verify(httpToolConfigRepository).delete(org.mockito.ArgumentMatchers.eq(TENANT_ID), any());
        verify(toolRepository).delete(org.mockito.ArgumentMatchers.eq(TENANT_ID), any());
    }

    @Test
    /**
     * 验证或支持 {@code memoryInitialMcpPublicationAuditFailureDoesNotLeaveToolCreateAudit} 所描述的测试场景。
     */
    void memoryInitialMcpPublicationAuditFailureDoesNotLeaveToolCreateAudit() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        AuditAppender failingAuditAppender = new AuditAppender(new com.cmagent.core.audit.AuditEventRepository() {
            @Override
            /**
             * 验证或支持 {@code append} 所描述的测试场景。
             *
             * @param event 测试审计事件
             */
            public void append(com.cmagent.core.audit.AuditEvent event) {
                if ("MCP_TOOL_PUBLISHED".equals(event.eventType())) {
                    throw new AuditPersistenceException(
                            "审计写入失败", new IllegalStateException("memory audit unavailable")
                    );
                }
                store.append(event);
            }

            @Override
            /**
             * 验证或支持 {@code appendAll} 所描述的测试场景。
             *
             * @param events 测试审计事件集合
             */
            public void appendAll(List<com.cmagent.core.audit.AuditEvent> events) {
                throw new IllegalStateException("memory audit unavailable");
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param limit 测试辅助方法使用的 limit 参数
             */
            public List<com.cmagent.core.audit.AuditEvent> listByTenant(UUID tenantId, int limit) {
                return store.listByTenant(tenantId, limit);
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param pageRequest 测试辅助方法使用的 pageRequest 参数
             */
            public List<com.cmagent.core.audit.AuditEvent> listByTenant(
                    UUID tenantId, com.cmagent.core.audit.AuditPageRequest pageRequest
            ) {
                return store.listByTenant(tenantId, pageRequest);
            }
        });
        ManagementCommandService service = memoryBackedService(store, memoryToolRepository(store), failingAuditAppender);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));

        assertThatThrownBy(() -> service.createTool(
                principal, "valid_mcp_name", "发布审计失败", ToolType.HTTP, ToolRiskLevel.LOW,
                validHttpSpec(), true
        )).isInstanceOf(AuditPersistenceException.class);

        assertThat(store.listTools(TENANT_ID)).isEmpty();
        assertThat(store.listEnabledMcpToolPublications(TENANT_ID)).isEmpty();
        assertThat(store.listAuditEvents(TENANT_ID)).isEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code duplicateNameConstraintIsMappedToConflict} 所描述的测试场景。
     */
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
    /**
     * 验证或支持 {@code concurrentMemoryCreatesWithSameTenantAndNameProduceOneConflict} 所描述的测试场景。
     */
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

    /**
     * 验证或支持 {@code createWithStatus} 所描述的测试场景。
     *
     * @param service 测试辅助方法使用的 service 参数
     * @param principal 测试认证主体
     */
    private static int createWithStatus(ManagementCommandService service, PrincipalRef principal) {
        try {
            service.createTool(principal, "concurrent-memory-name", "并发测试", ToolType.LOCAL, ToolRiskLevel.LOW);
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    /**
     * 验证或支持 {@code mockedService} 所描述的测试场景。
     */
    private ManagementCommandService mockedService() {
        return new ManagementCommandService(
                agentRepository,
                toolRepository,
                httpToolConfigRepository,
                mcpToolPublicationRepository,
                grantRepository,
                auditAppender,
                httpToolConfigValidator(),
                null
        );
    }

    /**
     * 验证或支持 {@code httpTool} 所描述的测试场景。
     *
     * @param name 测试对象名称
     */
    private static ToolDefinition httpTool(String name) {
        return new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                name,
                "旧版订单工具",
                ToolType.HTTP,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "https://api.example.test/v1/orders",
                "creator",
                "previous-editor"
        );
    }

    /**
     * 验证或支持 {@code localTool} 所描述的测试场景。
     *
     * @param name 测试对象名称
     */
    private static ToolDefinition localTool(String name) {
        return new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                name,
                "本地搜索工具",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "creator",
                "previous-editor"
        );
    }

    /**
     * 构造测试 Agent 定义。
     *
     * @param toolIds 测试辅助方法使用的 toolIds 参数
     */
    private static AgentDefinition agent(List<UUID> toolIds) {
        return new AgentDefinition(
                AGENT_ID,
                TENANT_ID,
                "订单助手",
                "",
                "处理订单",
                MODEL_PROVIDER_ID,
                "qwen-max",
                0.2d,
                6,
                true,
                toolIds,
                "creator",
                "previous-editor"
        );
    }

    /**
     * 验证或支持 {@code statefulMemoryService} 所描述的测试场景。
     *
     * @param store 内存测试存储
     * @param memoryAuditAppender 测试辅助方法使用的 memoryAuditAppender 参数
     */
    private ManagementCommandService statefulMemoryService(
            InMemoryPlatformStore store,
            AuditAppender memoryAuditAppender
    ) {
        return statefulMemoryService(store, memoryAgentRepository(store), memoryAuditAppender);
    }

    /**
     * 验证或支持 {@code statefulMemoryService} 所描述的测试场景。
     *
     * @param store 内存测试存储
     * @param agents 测试辅助方法使用的 agents 参数
     * @param memoryAuditAppender 测试辅助方法使用的 memoryAuditAppender 参数
     */
    private ManagementCommandService statefulMemoryService(
            InMemoryPlatformStore store,
            AgentDefinitionRepository agents,
            AuditAppender memoryAuditAppender
    ) {
        return new ManagementCommandService(
                agents,
                memoryToolRepository(store),
                memoryHttpConfigRepository(store),
                memoryMcpPublicationRepository(store),
                memoryGrantRepository(store),
                memoryAuditAppender,
                httpToolConfigValidator(),
                null
        );
    }

    /**
     * 验证或支持 {@code memoryBackedService} 所描述的测试场景。
     *
     * @param store 内存测试存储
     */
    private ManagementCommandService memoryBackedService(InMemoryPlatformStore store) {
        return memoryBackedService(store, memoryToolRepository(store));
    }

    /**
     * 验证或支持 {@code memoryBackedService} 所描述的测试场景。
     *
     * @param store 内存测试存储
     * @param memoryTools 测试辅助方法使用的 memoryTools 参数
     */
    private ManagementCommandService memoryBackedService(InMemoryPlatformStore store, ToolDefinitionRepository memoryTools) {
        return memoryBackedService(store, memoryTools, new AuditAppender(store));
    }

    /**
     * 验证或支持 {@code memoryBackedService} 所描述的测试场景。
     *
     * @param store 内存测试存储
     * @param memoryTools 测试辅助方法使用的 memoryTools 参数
     * @param memoryAuditAppender 测试辅助方法使用的 memoryAuditAppender 参数
     */
    private ManagementCommandService memoryBackedService(
            InMemoryPlatformStore store,
            ToolDefinitionRepository memoryTools,
            AuditAppender memoryAuditAppender
    ) {
        return new ManagementCommandService(
                emptyAgentRepository(),
                memoryTools,
                memoryHttpConfigRepository(store),
                memoryMcpPublicationRepository(store),
                grantRepository,
                memoryAuditAppender, httpToolConfigValidator(), null
        );
    }

    /**
     * 验证或支持 {@code httpToolConfigValidator} 所描述的测试场景。
     */
    private static HttpToolConfigValidator httpToolConfigValidator() {
        return new HttpToolConfigValidator(new ObjectMapper());
    }

    /**
     * 验证或支持 {@code validHttpSpec} 所描述的测试场景。
     */
    private static HttpToolCreateSpec validHttpSpec() {
        return new HttpToolCreateSpec(
                HttpToolMethod.POST,
                "https://api.example.test/orders",
                "{\"type\":\"object\"}",
                List.of(),
                java.util.Map.of(),
                Duration.ofSeconds(1)
        );
    }

    /**
     * 验证或支持 {@code updatedHttpSpec} 所描述的测试场景。
     */
    private static HttpToolCreateSpec updatedHttpSpec() {
        return new HttpToolCreateSpec(
                HttpToolMethod.POST,
                "https://api.example.test/v2/orders",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",
                List.of(new HttpParameterMapping("/id", HttpParameterLocation.QUERY, "id", "", true, "")),
                java.util.Map.of("X-Api-Key", "secret/tools/orders-v2"),
                Duration.ofSeconds(2)
        );
    }

    /**
     * 验证或支持 {@code httpConfiguration} 所描述的测试场景。
     *
     * @param tool 测试工具定义
     * @param spec 测试辅助方法使用的 spec 参数
     */
    private static HttpToolConfig httpConfiguration(ToolDefinition tool, HttpToolCreateSpec spec) {
        return new HttpToolConfig(
                tool.tenantId(),
                tool.id(),
                spec.method(),
                spec.urlTemplate(),
                spec.inputSchema(),
                spec.parameterMappings(),
                spec.secretHeaders(),
                spec.timeout()
        );
    }

    /**
     * 验证或支持 {@code awaitLatch} 所描述的测试场景。
     *
     * @param latch 协调并发测试的同步器
     * @param failureMessage 测试辅助方法使用的 failureMessage 参数
     */
    private static void awaitLatch(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(failureMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, exception);
        }
    }

    /**
     * 验证或支持 {@code memoryAgentRepository} 所描述的测试场景。
     *
     * @param store 内存测试存储
     */
    private static AgentDefinitionRepository memoryAgentRepository(InMemoryPlatformStore store) {
        return new AgentDefinitionRepository() {
            @Override
            /**
             * 验证或支持 {@code save} 所描述的测试场景。
             *
             * @param agent 测试 Agent 定义
             */
            public AgentDefinition save(AgentDefinition agent) {
                return store.saveAgent(agent);
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndId} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             */
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return store.findAgent(tenantId, agentId);
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             */
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return store.listAgents(tenantId);
            }

            @Override
            /**
             * 验证或支持 {@code addToolToAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.addToolToAgent(tenantId, agentId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code removeToolFromAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.removeToolFromAgent(tenantId, agentId, toolId);
            }
        };
    }

    /**
     * 验证或支持 {@code memoryHttpConfigRepository} 所描述的测试场景。
     *
     * @param store 内存测试存储
     */
    private static HttpToolConfigRepository memoryHttpConfigRepository(InMemoryPlatformStore store) {
        return new HttpToolConfigRepository() {
            @Override
            /**
             * 验证或支持 {@code save} 所描述的测试场景。
             *
             * @param config 测试配置
             */
            public HttpToolConfig save(HttpToolConfig config) {
                return store.saveHttpToolConfig(config);
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndToolId} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolId 测试工具标识
             */
            public Optional<HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId) {
                return store.findHttpToolConfig(tenantId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndToolIds} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolIds 测试辅助方法使用的 toolIds 参数
             */
            public java.util.Map<UUID, HttpToolConfig> findByTenantAndToolIds(
                    UUID tenantId, List<UUID> toolIds
            ) {
                return toolIds.stream().map(toolId -> store.findHttpToolConfig(tenantId, toolId))
                        .flatMap(Optional::stream)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                HttpToolConfig::toolId, config -> config
                        ));
            }

            @Override
            /**
             * 验证或支持 {@code delete} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolId 测试工具标识
             */
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteHttpToolConfig(tenantId, toolId);
            }
        };
    }

    /**
     * 验证或支持 {@code memoryMcpPublicationRepository} 所描述的测试场景。
     *
     * @param store 内存测试存储
     */
    private static McpToolPublicationRepository memoryMcpPublicationRepository(InMemoryPlatformStore store) {
        return new McpToolPublicationRepository() {
            @Override
            /**
             * 验证或支持 {@code save} 所描述的测试场景。
             *
             * @param publication 测试辅助方法使用的 publication 参数
             */
            public McpToolPublication save(McpToolPublication publication) {
                return store.saveMcpToolPublication(publication);
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndToolId} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolId 测试工具标识
             */
            public Optional<McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId) {
                return store.findMcpToolPublication(tenantId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndToolIds} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolIds 测试辅助方法使用的 toolIds 参数
             */
            public java.util.Map<UUID, McpToolPublication> findByTenantAndToolIds(
                    UUID tenantId, List<UUID> toolIds
            ) {
                return toolIds.stream().map(toolId -> store.findMcpToolPublication(tenantId, toolId))
                        .flatMap(Optional::stream)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                McpToolPublication::toolId, publication -> publication
                        ));
            }

            @Override
            /**
             * 验证或支持 {@code listEnabledByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             */
            public List<McpToolPublication> listEnabledByTenant(UUID tenantId) {
                return store.listEnabledMcpToolPublications(tenantId);
            }

            @Override
            /**
             * 验证或支持 {@code delete} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolId 测试工具标识
             */
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteMcpToolPublication(tenantId, toolId);
            }
        };
    }

    /**
     * 验证或支持 {@code memoryGrantRepository} 所描述的测试场景。
     *
     * @param store 内存测试存储
     */
    private static ToolGrantRepository memoryGrantRepository(InMemoryPlatformStore store) {
        return new ToolGrantRepository() {
            @Override
            /**
             * 验证或支持 {@code save} 所描述的测试场景。
             *
             * @param grant 测试辅助方法使用的 grant 参数
             */
            public com.cmagent.core.domain.ToolGrant save(com.cmagent.core.domain.ToolGrant grant) {
                return store.saveGrant(grant);
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             */
            public List<com.cmagent.core.domain.ToolGrant> listByTenant(UUID tenantId) {
                return store.listGrants(tenantId);
            }

            @Override
            /**
             * 验证或支持 {@code listByTenantAndAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             */
            public List<com.cmagent.core.domain.ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId) {
                return store.listGrants(tenantId, agentId);
            }

            @Override
            /**
             * 验证或支持 {@code listByTenantAgentAndTool} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public List<com.cmagent.core.domain.ToolGrant> listByTenantAgentAndTool(
                    UUID tenantId, UUID agentId, UUID toolId
            ) {
                return store.listGrants(tenantId, agentId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code delete} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public void delete(UUID tenantId, UUID agentId, UUID toolId) {
                store.deleteGrant(tenantId, agentId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code deleteByTenantAndToolId} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolId 测试工具标识
             */
            public void deleteByTenantAndToolId(UUID tenantId, UUID toolId) {
                store.deleteGrantsByTenantAndToolId(tenantId, toolId);
            }
        };
    }

    /**
     * 验证或支持 {@code memoryToolRepository} 所描述的测试场景。
     *
     * @param store 内存测试存储
     */
    private static ToolDefinitionRepository memoryToolRepository(InMemoryPlatformStore store) {
        return new ToolDefinitionRepository() {
            @Override
            /**
             * 验证或支持 {@code save} 所描述的测试场景。
             *
             * @param tool 测试工具定义
             */
            public ToolDefinition save(ToolDefinition tool) {
                return store.saveTool(tool);
            }

            @Override
            /**
             * 验证或支持 {@code restoreDeletedToolForCompensation} 所描述的测试场景。
             *
             * @param tool 测试工具定义
             */
            public boolean restoreDeletedToolForCompensation(ToolDefinition tool) {
                return store.restoreDeletedToolForCompensation(tool);
            }

            @Override
            /**
             * 验证或支持 {@code update} 所描述的测试场景。
             *
             * @param tool 测试工具定义
             */
            public ToolDefinition update(ToolDefinition tool) {
                return store.updateTool(tool);
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndId} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolId 测试工具标识
             */
            public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
                return store.findTool(tenantId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             */
            public List<ToolDefinition> listByTenant(UUID tenantId) {
                return store.listTools(tenantId);
            }

            @Override
            /**
             * 验证或支持 {@code hasToolCallHistory} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolId 测试工具标识
             */
            public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
                return store.hasToolCallHistory(tenantId, toolId);
            }

            @Override
            /**
             * 验证或支持 {@code delete} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param toolId 测试工具标识
             */
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteTool(tenantId, toolId);
            }
        };
    }

    private static final class BarrierToolDefinitionRepository implements ToolDefinitionRepository {
        private final ToolDefinitionRepository delegate;
        private final CountDownLatch listBarrier;

        /**
         * 创建 {@code BarrierToolDefinitionRepository} 测试辅助实例。
         *
         * @param delegate 测试辅助方法使用的 delegate 参数
         * @param callers 测试辅助方法使用的 callers 参数
         */
        private BarrierToolDefinitionRepository(ToolDefinitionRepository delegate, int callers) {
            this.delegate = delegate;
            this.listBarrier = new CountDownLatch(callers);
        }

        @Override
        /**
         * 验证或支持 {@code save} 所描述的测试场景。
         *
         * @param tool 测试工具定义
         */
        public ToolDefinition save(ToolDefinition tool) {
            return delegate.save(tool);
        }

        @Override
        /**
         * 验证或支持 {@code update} 所描述的测试场景。
         *
         * @param tool 测试工具定义
         */
        public ToolDefinition update(ToolDefinition tool) {
            return delegate.update(tool);
        }

        @Override
        /**
         * 验证或支持 {@code findByTenantAndId} 所描述的测试场景。
         *
         * @param tenantId 测试租户标识
         * @param toolId 测试工具标识
         */
        public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
            return delegate.findByTenantAndId(tenantId, toolId);
        }

        @Override
        /**
         * 验证或支持 {@code listByTenant} 所描述的测试场景。
         *
         * @param tenantId 测试租户标识
         */
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
        /**
         * 验证或支持 {@code hasToolCallHistory} 所描述的测试场景。
         *
         * @param tenantId 测试租户标识
         * @param toolId 测试工具标识
         */
        public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
            return delegate.hasToolCallHistory(tenantId, toolId);
        }

        @Override
        /**
         * 验证或支持 {@code delete} 所描述的测试场景。
         *
         * @param tenantId 测试租户标识
         * @param toolId 测试工具标识
         */
        public void delete(UUID tenantId, UUID toolId) {
            delegate.delete(tenantId, toolId);
        }
    }

    /**
     * 验证或支持 {@code emptyAgentRepository} 所描述的测试场景。
     */
    private AgentDefinitionRepository emptyAgentRepository() {
        return new AgentDefinitionRepository() {
            @Override
            /**
             * 验证或支持 {@code save} 所描述的测试场景。
             *
             * @param agent 测试 Agent 定义
             */
            public AgentDefinition save(AgentDefinition agent) {
                return agent;
            }

            @Override
            /**
             * 验证或支持 {@code findByTenantAndId} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             */
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return Optional.empty();
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             */
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return List.of();
            }

            @Override
            /**
             * 验证或支持 {@code addToolToAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                throw new UnsupportedOperationException();
            }

            @Override
            /**
             * 验证或支持 {@code removeToolFromAgent} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param agentId 测试 Agent 标识
             * @param toolId 测试工具标识
             */
            public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
