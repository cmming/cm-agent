package com.cmagent.server.config;

import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.repository.ToolCallRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerRepositoryConfigurationTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID RUN_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID RUN_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID TOOL_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID DEFAULT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEFAULT_MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Test
    void memoryModeProvidesTenantScopedDefaultModelConfig() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelConfigRepository.class);
                    ModelConfigRepository repository = context.getBean(ModelConfigRepository.class);

                    ModelConfig model = repository.findByTenantAndId(DEFAULT_TENANT_ID, DEFAULT_MODEL_ID)
                            .orElseThrow();
                    assertThat(model.id()).isEqualTo(DEFAULT_MODEL_ID);
                    assertThat(model.tenantId()).isEqualTo(DEFAULT_TENANT_ID);
                    assertThat(repository.findByTenantAndId(TENANT_B, DEFAULT_MODEL_ID)).isEmpty();
                });
    }

    @Test
    void memoryModeProvidesTenantScopedRuntimeRepositories() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasSingleBean(InMemoryPlatformStore.class);
                    InMemoryPlatformStore store = context.getBean(InMemoryPlatformStore.class);
                    store.saveTool(toolDefinition(TOOL_A, TENANT_A));
                    assertThat(context).hasSingleBean(RunRepository.class);
                    assertThat(context).hasSingleBean(ToolCallRepository.class);

                    RunRepository runRepository = context.getBean(RunRepository.class);
                    RunRecord run = RunRecord.create(
                            RUN_A, TENANT_A, AGENT_A, "principal-a", "input", Instant.parse("2026-07-14T00:00:00Z")
                    );
                    runRepository.save(TENANT_A, run);

                    assertThat(runRepository.findByTenantAndAgentAndId(TENANT_A, AGENT_A, RUN_A)).contains(run);
                    assertThat(runRepository.findByTenantAndAgentAndId(TENANT_B, AGENT_A, RUN_A)).isEmpty();
                    assertThat(runRepository.listByTenantAndAgent(TENANT_A, AGENT_A, new RunPageRequest(10, null, null)))
                            .containsExactly(run);
                    assertThat(runRepository.listByTenantAndAgent(TENANT_B, AGENT_A, new RunPageRequest(10, null, null)))
                            .isEmpty();

                    ToolCallRepository toolCallRepository = context.getBean(ToolCallRepository.class);
                    RunToolCall toolCall = new RunToolCall(
                            UUID.fromString("50000000-0000-0000-0000-000000000001"),
                            TENANT_A,
                            RUN_A,
                            TOOL_A,
                            "echo",
                            "input",
                            "output",
                            RunStatus.SUCCEEDED,
                            true,
                            1L,
                            "",
                            Instant.parse("2026-07-14T00:00:01Z")
                    );
                    RunToolCallBatch batch = new RunToolCallBatch(TENANT_A, List.of(toolCall));
                    toolCallRepository.saveAll(TENANT_A, batch);

                    assertThat(toolCallRepository.listByTenantAndRun(TENANT_A, RUN_A)).containsExactly(toolCall);
                    assertThat(toolCallRepository.listByTenantAndRun(TENANT_B, RUN_A)).isEmpty();
                    assertThatThrownBy(() -> toolCallRepository.saveAll(TENANT_B, batch))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("tenantId 与 toolCalls 批次不匹配");
                });
    }

    @Test
    void memoryModeProvidesTenantScopedHttpToolConfigurationRepositories() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasSingleBean(HttpToolConfigRepository.class);
                    assertThat(context).hasSingleBean(McpToolPublicationRepository.class);
                    HttpToolConfigRepository configurations = context.getBean(HttpToolConfigRepository.class);
                    McpToolPublicationRepository publications = context.getBean(McpToolPublicationRepository.class);
                    com.cmagent.core.domain.HttpToolConfig configuration = new com.cmagent.core.domain.HttpToolConfig(
                            TENANT_A, TOOL_A, com.cmagent.core.domain.HttpToolMethod.GET, "https://api.invalid/items",
                            "{}", List.of(), java.util.Map.of("X-Api-Key", "secret/http/tenant-a"), java.time.Duration.ofSeconds(1));
                    com.cmagent.core.domain.McpToolPublication publication = new com.cmagent.core.domain.McpToolPublication(
                            TENANT_A, TOOL_A, true, "tester");

                    configurations.save(configuration);
                    publications.save(publication);

                    assertThat(configurations.findByTenantAndToolId(TENANT_A, TOOL_A)).contains(configuration);
                    assertThat(configurations.findByTenantAndToolId(TENANT_B, TOOL_A)).isEmpty();
                    assertThat(configurations.findByTenantAndToolIds(TENANT_A, List.of(TOOL_A)))
                            .containsExactly(Map.entry(TOOL_A, configuration));
                    assertThat(configurations.findByTenantAndToolIds(TENANT_B, List.of(TOOL_A))).isEmpty();
                    assertThat(publications.listEnabledByTenant(TENANT_A)).containsExactly(publication);
                    assertThat(publications.listEnabledByTenant(TENANT_B)).isEmpty();
                    assertThat(publications.findByTenantAndToolIds(TENANT_A, List.of(TOOL_A)))
                            .containsExactly(Map.entry(TOOL_A, publication));
                    assertThat(publications.findByTenantAndToolIds(TENANT_B, List.of(TOOL_A))).isEmpty();

                    configurations.delete(TENANT_A, TOOL_A);
                    publications.delete(TENANT_A, TOOL_A);

                    assertThat(configurations.findByTenantAndToolId(TENANT_A, TOOL_A)).isEmpty();
                    assertThat(publications.findByTenantAndToolId(TENANT_A, TOOL_A)).isEmpty();
                });
    }

    @Test
    void memoryToolRepositoryKeepsTenantNameIndexConsistentAfterDelete() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    ToolDefinitionRepository repository = context.getBean(ToolDefinitionRepository.class);
                    ToolDefinition original = new ToolDefinition(
                            TOOL_A, TENANT_A, "unique-name", "", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                            true, "", "tester", "tester"
                    );
                    ToolDefinition duplicate = new ToolDefinition(
                            UUID.fromString("20000000-0000-0000-0000-000000000002"), TENANT_A,
                            "unique-name", "", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                            true, "", "tester", "tester"
                    );
                    ToolDefinition otherTenant = new ToolDefinition(
                            UUID.fromString("20000000-0000-0000-0000-000000000003"), TENANT_B,
                            "unique-name", "", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                            true, "", "tester", "tester"
                    );

                    repository.save(original);
                    assertThatThrownBy(() -> repository.save(duplicate))
                            .isInstanceOf(DuplicateKeyException.class);
                    repository.save(otherTenant);
                    repository.delete(TENANT_A, original.id());
                    repository.save(duplicate);

                    assertThat(repository.listByTenant(TENANT_A)).containsExactly(duplicate);
                    assertThat(repository.listByTenant(TENANT_B)).containsExactly(otherTenant);
                });
    }

    @Test
    void memoryRunRepositoryRejectsDuplicateIdWithoutReplacingOriginal() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    RunRepository repository = context.getBean(RunRepository.class);
                    RunRecord original = RunRecord.create(
                            RUN_A, TENANT_A, AGENT_A, "principal-a", "original", Instant.parse("2026-07-14T00:00:00Z")
                    );
                    RunRecord duplicate = RunRecord.create(
                            RUN_A, TENANT_A, AGENT_A, "principal-a", "replacement", Instant.parse("2026-07-14T00:00:01Z")
                    );

                    repository.save(TENANT_A, original);

                    assertThatThrownBy(() -> repository.save(TENANT_A, duplicate))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Run 已存在");
                    assertThat(repository.findByTenantAndAgentAndId(TENANT_A, AGENT_A, RUN_A))
                            .contains(original);
                });
    }

    @Test
    void memoryToolCallRepositoryValidatesRunScopeBeforeWritingBatch() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    InMemoryPlatformStore store = context.getBean(InMemoryPlatformStore.class);
                    RunRepository runRepository = context.getBean(RunRepository.class);
                    ToolCallRepository toolCallRepository = context.getBean(ToolCallRepository.class);
                    store.saveTool(toolDefinition(TOOL_A, TENANT_A));
                    runRepository.save(TENANT_A, RunRecord.create(
                            RUN_A, TENANT_A, AGENT_A, "principal-a", "input", Instant.parse("2026-07-14T00:00:00Z")
                    ));
                    runRepository.save(TENANT_B, RunRecord.create(
                            RUN_B, TENANT_B, AGENT_B, "principal-b", "input", Instant.parse("2026-07-14T00:00:00Z")
                    ));
                    RunToolCall valid = toolCall(
                            UUID.fromString("50000000-0000-0000-0000-000000000010"), TENANT_A, RUN_A,
                            Instant.parse("2026-07-14T00:00:01Z")
                    );
                    RunToolCall crossTenantRun = toolCall(
                            UUID.fromString("50000000-0000-0000-0000-000000000011"), TENANT_A, RUN_B,
                            Instant.parse("2026-07-14T00:00:02Z")
                    );
                    RunToolCall missingRun = toolCall(
                            UUID.fromString("50000000-0000-0000-0000-000000000013"), TENANT_A, UUID.fromString("30000000-0000-0000-0000-000000000099"),
                            Instant.parse("2026-07-14T00:00:03Z")
                    );

                    assertThatThrownBy(() -> toolCallRepository.saveAll(
                            TENANT_A, new RunToolCallBatch(TENANT_A, List.of(valid, crossTenantRun))))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("toolCall 的 run 不存在或 tenant 不匹配");
                    assertThatThrownBy(() -> toolCallRepository.saveAll(
                            TENANT_A, new RunToolCallBatch(TENANT_A, List.of(valid, missingRun))))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("toolCall 的 run 不存在或 tenant 不匹配");
                    assertThat(toolCallRepository.listByTenantAndRun(TENANT_A, RUN_A)).isEmpty();
                    assertThat(toolCallRepository.listByTenantAndRun(TENANT_B, RUN_B)).isEmpty();
                });
    }

    @Test
    void memoryToolCallRepositoryValidatesToolScopeBeforeWritingBatch() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    InMemoryPlatformStore store = context.getBean(InMemoryPlatformStore.class);
                    RunRepository runRepository = context.getBean(RunRepository.class);
                    ToolCallRepository toolCallRepository = context.getBean(ToolCallRepository.class);
                    runRepository.save(TENANT_A, RunRecord.create(
                            RUN_A, TENANT_A, AGENT_A, "principal-a", "input", Instant.parse("2026-07-14T00:00:00Z")
                    ));
                    store.saveTool(new ToolDefinition(
                            TOOL_A, TENANT_B, "cross-tenant", "", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                            true, "", "tester", "tester"
                    ));
                    RunToolCall crossTenantTool = toolCall(
                            UUID.fromString("50000000-0000-0000-0000-000000000014"), TENANT_A, RUN_A,
                            Instant.parse("2026-07-14T00:00:01Z")
                    );

                    assertThatThrownBy(() -> toolCallRepository.saveAll(
                            TENANT_A, new RunToolCallBatch(TENANT_A, List.of(crossTenantTool))))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("toolCall 的 tool 不存在或 tenant 不匹配");
                    assertThat(toolCallRepository.listByTenantAndRun(TENANT_A, RUN_A)).isEmpty();
                });
    }

    @Test
    void memoryToolCallRepositoryRejectsDuplicateIdWithoutReplacingOriginal() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    InMemoryPlatformStore store = context.getBean(InMemoryPlatformStore.class);
                    RunRepository runRepository = context.getBean(RunRepository.class);
                    ToolCallRepository toolCallRepository = context.getBean(ToolCallRepository.class);
                    store.saveTool(toolDefinition(TOOL_A, TENANT_A));
                    runRepository.save(TENANT_A, RunRecord.create(
                            RUN_A, TENANT_A, AGENT_A, "principal-a", "input", Instant.parse("2026-07-14T00:00:00Z")
                    ));
                    UUID toolCallId = UUID.fromString("50000000-0000-0000-0000-000000000012");
                    RunToolCall original = toolCall(toolCallId, TENANT_A, RUN_A, Instant.parse("2026-07-14T00:00:01Z"));
                    RunToolCall duplicate = toolCall(toolCallId, TENANT_A, RUN_A, Instant.parse("2026-07-14T00:00:02Z"));

                    toolCallRepository.saveAll(TENANT_A, new RunToolCallBatch(TENANT_A, List.of(original)));

                    assertThatThrownBy(() -> toolCallRepository.saveAll(
                            TENANT_A, new RunToolCallBatch(TENANT_A, List.of(duplicate))))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("ToolCall 已存在");
                    assertThat(toolCallRepository.listByTenantAndRun(TENANT_A, RUN_A))
                            .containsExactly(original);
                });
    }

    private static RunToolCall toolCall(UUID id, UUID tenantId, UUID runId, Instant createdAt) {
        return new RunToolCall(
                id, tenantId, runId, TOOL_A, "echo", "input", "output", RunStatus.SUCCEEDED,
                true, 1L, "", createdAt
        );
    }

    private static ToolDefinition toolDefinition(UUID toolId, UUID tenantId) {
        return new ToolDefinition(
                toolId, tenantId, "echo", "", ToolType.LOCAL, "{}", ToolRiskLevel.LOW,
                true, "", "tester", "tester"
        );
    }
}
