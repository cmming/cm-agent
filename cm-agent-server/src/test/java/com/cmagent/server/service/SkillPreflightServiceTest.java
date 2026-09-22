package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillDependency;
import com.cmagent.core.domain.SkillDependencyMapping;
import com.cmagent.core.domain.SkillPreflightItem;
import com.cmagent.core.domain.SkillPreflightItemStatus;
import com.cmagent.core.domain.SkillPreflightCheck;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.domain.SkillPreflightStatus;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillDependencyMappingRepository;
import com.cmagent.core.repository.SkillDependencyRepository;
import com.cmagent.core.repository.SkillPreflightRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.server.runtime.ToolRuntimeReadiness;
import com.cmagent.server.runtime.http.HttpToolProperties;
import com.cmagent.server.support.HttpToolTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖技能依赖两层预检的解析、授权和结果汇总。
 *
 * <p>这些用例使用内存替身，不连接数据库，也不调用任何工具执行器；预检只读取
 * 映射、工具定义、HTTP 配置和 Agent 授权事实。</p>
 */
class SkillPreflightServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SKILL_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private FakeDependencyRepository dependencies;
    private FakeMappingRepository mappings;
    private FakePreflightRepository preflights;
    private FakeToolRepository tools;
    private FakeHttpConfigRepository httpConfigs;
    private FakeGrantRepository grants;
    private FakeDefinitionRepository definitions;
    private FakeAgentRepository agents;
    private FakeBindingRepository bindings;
    private InMemoryToolRegistry registry;
    private HttpToolProperties httpProperties;
    private SkillPreflightService service;
    private PrincipalRef principal;

    @BeforeEach
    void setUp() {
        dependencies = new FakeDependencyRepository();
        mappings = new FakeMappingRepository();
        preflights = new FakePreflightRepository();
        tools = new FakeToolRepository();
        httpConfigs = new FakeHttpConfigRepository();
        grants = new FakeGrantRepository();
        definitions = new FakeDefinitionRepository();
        agents = new FakeAgentRepository();
        bindings = new FakeBindingRepository();
        registry = new InMemoryToolRegistry();
        httpProperties = new HttpToolProperties();
        principal = new PrincipalRef(TENANT_ID, "admin", "管理员", java.util.Set.of("skill:write"));
        service = new SkillPreflightService(
                definitions, dependencies, mappings, preflights,
                tools, httpConfigs, grants, agents, bindings,
                new ToolRuntimeReadiness(registry, httpProperties), new DirectWorkUnit());
    }

    @Test
    void 无依赖的技能结构预检直接通过() {
        definitions.definitions.put(SKILL_ID, skill(0));

        SkillPreflightResult result = service.preflight(
                principal, SKILL_ID, VERSION_ID, SkillPreflightScope.STRUCTURAL, null);

        assertThat(result.check().status()).isEqualTo(SkillPreflightStatus.PASSED);
        assertThat(result.check().mappingRevision()).isEqualTo(0);
        assertThat(result.items()).isEmpty();
        assertThat(preflights.saved).hasSize(1);
    }

    @Test
    void 必需依赖缺映射时结构预检失败() {
        definitions.definitions.put(SKILL_ID, skill(0));
        dependencies.items.add(dependency("order-query", true));

        SkillPreflightResult result = service.preflight(
                principal, SKILL_ID, VERSION_ID, SkillPreflightScope.STRUCTURAL, null);

        assertThat(result.check().status()).isEqualTo(SkillPreflightStatus.FAILED);
        assertThat(result.items()).extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.MAPPING_MISSING);
        assertThat(result.items()).extracting(SkillPreflightItem::errorCode)
                .containsExactly(ApiErrorCode.SKILL_DEPENDENCY_UNMAPPED);
    }

    @Test
    void 可选依赖缺映射时仅告警() {
        definitions.definitions.put(SKILL_ID, skill(0));
        dependencies.items.add(dependency("customer-profile", false));

        SkillPreflightResult result = service.preflight(
                principal, SKILL_ID, VERSION_ID, SkillPreflightScope.STRUCTURAL, null);

        assertThat(result.check().status()).isEqualTo(SkillPreflightStatus.PASSED_WITH_WARNINGS);
        assertThat(result.items()).extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.OPTIONAL_WARNING);
    }

    @Test
    void 映射工具跨租户或不存在的工具被拒绝() {
        definitions.definitions.put(SKILL_ID, skill(0));
        dependencies.items.add(dependency("order-query", true));
        mappings.mappings.put("order-query", mapping("order-query", TOOL_ID));
        tools.tools.put(TOOL_ID, tool(TOOL_ID, OTHER_TENANT_ID, ToolType.HTTP));

        SkillPreflightResult foreign = service.preflight(
                principal, SKILL_ID, VERSION_ID, SkillPreflightScope.STRUCTURAL, null);
        assertThat(foreign.check().status()).isEqualTo(SkillPreflightStatus.FAILED);
        assertThat(foreign.items()).extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.TOOL_UNAVAILABLE);

        tools.tools.clear();
        SkillPreflightResult missing = service.preflight(
                principal, SKILL_ID, VERSION_ID, SkillPreflightScope.STRUCTURAL, null);
        assertThat(missing.items()).extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.TOOL_UNAVAILABLE);
        assertThat(missing.items()).extracting(SkillPreflightItem::errorCode)
                .containsExactly(ApiErrorCode.SKILL_DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void 工具停用或HTTP配置不完整时不就绪() {
        definitions.definitions.put(SKILL_ID, skill(0));
        dependencies.items.add(dependency("order-query", true));
        mappings.mappings.put("order-query", mapping("order-query", TOOL_ID));
        tools.tools.put(TOOL_ID, tool(TOOL_ID, TENANT_ID, ToolType.HTTP));

        assertThat(service.preflight(principal, SKILL_ID, VERSION_ID,
                SkillPreflightScope.STRUCTURAL, null).items())
                .extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.TOOL_UNAVAILABLE);

        httpProperties.setEnabled(true);
        httpConfigs.configs.put(TOOL_ID, httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/orders"));
        tools.tools.put(TOOL_ID, tool(TOOL_ID, TENANT_ID, ToolType.HTTP, false));
        assertThat(service.preflight(principal, SKILL_ID, VERSION_ID,
                SkillPreflightScope.STRUCTURAL, null).items())
                .extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.TOOL_UNAVAILABLE);

        tools.tools.put(TOOL_ID, tool(TOOL_ID, TENANT_ID, ToolType.HTTP, true));
        assertThat(service.preflight(principal, SKILL_ID, VERSION_ID,
                SkillPreflightScope.STRUCTURAL, null).items())
                .extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.READY);
    }

    @Test
    void 本地工具未注册或注册身份不一致时不就绪() {
        definitions.definitions.put(SKILL_ID, skill(0));
        dependencies.items.add(dependency("echo", true));
        mappings.mappings.put("echo", mapping("echo", TOOL_ID));
        tools.tools.put(TOOL_ID, tool(TOOL_ID, TENANT_ID, ToolType.LOCAL));

        assertThat(service.preflight(principal, SKILL_ID, VERSION_ID,
                SkillPreflightScope.STRUCTURAL, null).items())
                .extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.TOOL_UNAVAILABLE);

        registry.register(tool(TOOL_ID, TENANT_ID, ToolType.LOCAL, true),
                request -> ToolExecutionResult.succeeded("{}", null));
        assertThat(service.preflight(principal, SKILL_ID, VERSION_ID,
                SkillPreflightScope.STRUCTURAL, null).items())
                .extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.READY);

        registry.register(tool(TOOL_ID, TENANT_ID, ToolType.LOCAL, true), request ->
                ToolExecutionResult.succeeded("{}", null));
        tools.tools.put(TOOL_ID, renamedLocalTool(TOOL_ID, TENANT_ID));
        assertThat(service.preflight(principal, SKILL_ID, VERSION_ID,
                SkillPreflightScope.STRUCTURAL, null).items())
                .extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.TOOL_UNAVAILABLE);
    }

    @Test
    void Agent预检缺少ToolGrant时失败并记录受检Agent() {
        definitions.definitions.put(SKILL_ID, skill(0));
        dependencies.items.add(dependency("order-query", true));
        mappings.mappings.put("order-query", mapping("order-query", TOOL_ID));
        tools.tools.put(TOOL_ID, tool(TOOL_ID, TENANT_ID, ToolType.LOCAL));
        registry.register(tool(TOOL_ID, TENANT_ID, ToolType.LOCAL, true),
                request -> ToolExecutionResult.succeeded("{}", null));
        agents.agents.put(AGENT_ID, agent(AGENT_ID, TENANT_ID));

        SkillPreflightResult result = service.preflight(
                principal, SKILL_ID, VERSION_ID, SkillPreflightScope.AGENT, AGENT_ID);

        assertThat(result.check().status()).isEqualTo(SkillPreflightStatus.FAILED);
        assertThat(result.items()).extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.GRANT_MISSING);
        assertThat(result.items()).extracting(SkillPreflightItem::agentId)
                .containsExactly(AGENT_ID);
        assertThat(result.items()).extracting(SkillPreflightItem::errorCode)
                .containsExactly(ApiErrorCode.SKILL_AGENT_GRANT_MISSING);
    }

    @Test
    void Agent预检跨租户Agent或已停用Agent被拒绝() {
        definitions.definitions.put(SKILL_ID, skill(0));
        agents.agents.put(AGENT_ID, agent(AGENT_ID, OTHER_TENANT_ID));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.preflight(
                        principal, SKILL_ID, VERSION_ID, SkillPreflightScope.AGENT, AGENT_ID))
                .isInstanceOfSatisfying(com.cmagent.core.runtime.SkillAccessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ApiErrorCode.SKILL_AGENT_GRANT_MISSING));

        agents.agents.put(AGENT_ID, disabledAgent(AGENT_ID, TENANT_ID));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.preflight(
                        principal, SKILL_ID, VERSION_ID, SkillPreflightScope.AGENT, AGENT_ID))
                .isInstanceOfSatisfying(com.cmagent.core.runtime.SkillAccessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ApiErrorCode.SKILL_AGENT_GRANT_MISSING));
    }

    @Test
    void 预检结果记录权威映射修订且持久化前后一致() {
        definitions.definitions.put(SKILL_ID, skill(7));
        dependencies.items.add(dependency("order-query", true));
        mappings.mappings.put("order-query", mapping("order-query", TOOL_ID));
        tools.tools.put(TOOL_ID, tool(TOOL_ID, TENANT_ID, ToolType.LOCAL));
        registry.register(tool(TOOL_ID, TENANT_ID, ToolType.LOCAL, true),
                request -> ToolExecutionResult.succeeded("{}", null));
        agents.agents.put(AGENT_ID, agent(AGENT_ID, TENANT_ID));
        grants.grants.add(new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true));

        SkillPreflightResult result = service.preflight(
                principal, SKILL_ID, VERSION_ID, SkillPreflightScope.AGENT, AGENT_ID);

        assertThat(result.check().mappingRevision()).isEqualTo(7);
        assertThat(result.check().status()).isEqualTo(SkillPreflightStatus.PASSED);
        assertThat(result.items()).extracting(SkillPreflightItem::status)
                .containsExactly(SkillPreflightItemStatus.READY);
        assertThat(result.items()).extracting(SkillPreflightItem::toolId)
                .containsExactly(TOOL_ID);
        assertThat(preflights.savedItems).extracting(SkillPreflightItem::checkId)
                .containsExactly(result.check().id());
    }

    @Test
    void 发布预检为每个跟随型Agent生成独立明细() {
        definitions.definitions.put(SKILL_ID, skill(1));
        dependencies.items.add(dependency("order-query", true));
        mappings.mappings.put("order-query", mapping("order-query", TOOL_ID));
        tools.tools.put(TOOL_ID, tool(TOOL_ID, TENANT_ID, ToolType.LOCAL));
        registry.register(tool(TOOL_ID, TENANT_ID, ToolType.LOCAL, true),
                request -> ToolExecutionResult.succeeded("{}", null));
        UUID secondAgent = UUID.randomUUID();
        agents.agents.put(AGENT_ID, agent(AGENT_ID, TENANT_ID));
        agents.agents.put(secondAgent, agent(secondAgent, TENANT_ID));
        bindings.followers.add(AGENT_ID);
        bindings.followers.add(secondAgent);
        grants.grants.add(new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true));

        SkillPreflightResult result = service.preflight(
                principal, SKILL_ID, VERSION_ID, SkillPreflightScope.PUBLISH, null);

        assertThat(result.check().agentId()).isNull();
        assertThat(result.check().status()).isEqualTo(SkillPreflightStatus.FAILED);
        assertThat(result.items()).extracting(SkillPreflightItem::agentId)
                .containsExactlyInAnyOrder(AGENT_ID, secondAgent);
        assertThat(result.items()).extracting(SkillPreflightItem::status)
                .containsExactlyInAnyOrder(SkillPreflightItemStatus.READY,
                        SkillPreflightItemStatus.GRANT_MISSING);
    }

    private SkillDefinition skill(long mappingRevision) {
        Instant now = Instant.now();
        return new SkillDefinition(SKILL_ID, TENANT_ID, "order-skill", VERSION_ID, null,
                false, 0, mappingRevision, "admin", "admin", now, now);
    }

    private SkillDependency dependency(String logicalKey, boolean required) {
        return new SkillDependency(TENANT_ID, SKILL_ID, VERSION_ID, logicalKey, required, "", 0);
    }

    private SkillDependencyMapping mapping(String logicalKey, UUID toolId) {
        return new SkillDependencyMapping(TENANT_ID, SKILL_ID, logicalKey, toolId, "admin", Instant.now());
    }

    private ToolDefinition tool(UUID id, UUID tenantId, ToolType type) {
        return tool(id, tenantId, type, true);
    }

    private ToolDefinition tool(UUID id, UUID tenantId, ToolType type, boolean enabled) {
        String endpoint = type == ToolType.HTTP ? "https://api.example.test/orders" : "";
        return new ToolDefinition(id, tenantId, type == ToolType.HTTP ? "orders" : "echo",
                "测试工具", type, "{}", ToolRiskLevel.LOW, enabled, endpoint, "admin", "admin");
    }

    private ToolDefinition renamedLocalTool(UUID id, UUID tenantId) {
        return new ToolDefinition(id, tenantId, "echo-renamed", "测试工具", ToolType.LOCAL,
                "{}", ToolRiskLevel.LOW, true, "", "admin", "admin");
    }

    private HttpToolConfig httpConfig(UUID toolId, UUID tenantId, String urlTemplate) {
        return new HttpToolConfig(tenantId, toolId, HttpToolMethod.POST, urlTemplate,
                HttpToolTestData.singleOptionalQueryParameter(), Map.of(), Duration.ofSeconds(1));
    }

    private AgentDefinition agent(UUID id, UUID tenantId) {
        return new AgentDefinition(id, tenantId, "订单助手", "", "", UUID.randomUUID(), null,
                0.2, 3, true, List.of(), "admin", "admin");
    }

    private AgentDefinition disabledAgent(UUID id, UUID tenantId) {
        AgentDefinition source = agent(id, tenantId);
        return new AgentDefinition(id, tenantId, source.name(), source.description(), source.systemPrompt(),
                source.modelProviderId(), null, source.temperature(), source.maxIterations(), false,
                List.of(), "admin", "admin");
    }

    /** 只读替身，验证预检不会修改依赖声明。 */
    /** 无暂存的直通工作单元，与服务端测试替身对应的内存仓储配合使用。 */
    private static final class DirectWorkUnit implements SkillUnitOfWork {
        @Override
        public <T> T execute(java.util.function.Supplier<T> operation) {
            return operation.get();
        }
    }

    private static final class FakeDependencyRepository implements SkillDependencyRepository {
        private final List<SkillDependency> items = new ArrayList<>();

        @Override
        public void insertAll(List<SkillDependency> dependencies) {
            throw new UnsupportedOperationException("预检不应写入依赖声明");
        }

        @Override
        public List<SkillDependency> list(UUID tenantId, UUID skillId, UUID versionId) {
            return items.stream()
                    .filter(item -> item.tenantId().equals(tenantId))
                    .filter(item -> item.skillId().equals(skillId))
                    .filter(item -> item.versionId().equals(versionId))
                    .sorted((left, right) -> Integer.compare(left.position(), right.position()))
                    .toList();
        }
    }

    private static final class FakeMappingRepository implements SkillDependencyMappingRepository {
        private final Map<String, SkillDependencyMapping> mappings = new HashMap<>();

        @Override
        public java.util.Optional<SkillDependencyMapping> find(UUID tenantId, UUID skillId, String logicalKey) {
            SkillDependencyMapping mapping = mappings.get(logicalKey);
            return mapping != null && mapping.tenantId().equals(tenantId) && mapping.skillId().equals(skillId)
                    ? java.util.Optional.of(mapping) : java.util.Optional.empty();
        }

        @Override
        public List<SkillDependencyMapping> list(UUID tenantId, UUID skillId) {
            return mappings.values().stream()
                    .filter(item -> item.tenantId().equals(tenantId) && item.skillId().equals(skillId))
                    .sorted(java.util.Comparator.comparing(SkillDependencyMapping::logicalKey))
                    .toList();
        }

        @Override
        public SkillDependencyMapping save(SkillDependencyMapping mapping) {
            mappings.put(mapping.logicalKey(), mapping);
            return mapping;
        }

        @Override
        public boolean delete(UUID tenantId, UUID skillId, String logicalKey) {
            return mappings.remove(logicalKey) != null;
        }

        @Override
        public void lockSkill(UUID tenantId, UUID skillId) {
            // 内存替身没有行锁语义，预检路径本身不调用该方法。
        }
    }

    private static final class FakePreflightRepository implements SkillPreflightRepository {
        private final List<SkillPreflightCheck> saved = new ArrayList<>();
        private final List<SkillPreflightItem> savedItems = new ArrayList<>();

        @Override
        public SkillPreflightCheck save(SkillPreflightCheck check, List<SkillPreflightItem> items) {
            saved.add(check);
            savedItems.addAll(items);
            return check;
        }

        @Override
        public java.util.Optional<SkillPreflightCheck> find(UUID tenantId, UUID checkId) {
            return saved.stream().filter(item -> item.tenantId().equals(tenantId) && item.id().equals(checkId))
                    .findFirst();
        }

        @Override
        public List<SkillPreflightItem> listItems(UUID tenantId, UUID checkId) {
            return savedItems.stream().filter(item -> item.checkId().equals(checkId)).toList();
        }

        @Override
        public List<SkillPreflightCheck> list(UUID tenantId, UUID skillId, UUID versionId) {
            return saved.stream()
                    .filter(item -> item.tenantId().equals(tenantId) && item.skillId().equals(skillId)
                            && item.versionId().equals(versionId))
                    .toList();
        }
    }

    private static final class FakeToolRepository implements ToolDefinitionRepository {
        private final Map<UUID, ToolDefinition> tools = new HashMap<>();

        @Override
        public ToolDefinition save(ToolDefinition tool) {
            tools.put(tool.id(), tool);
            return tool;
        }

        @Override
        public ToolDefinition update(ToolDefinition tool) {
            tools.put(tool.id(), tool);
            return tool;
        }

        @Override
        public java.util.Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
            ToolDefinition tool = tools.get(toolId);
            return tool != null && tool.tenantId().equals(tenantId)
                    ? java.util.Optional.of(tool) : java.util.Optional.empty();
        }

        @Override
        public List<ToolDefinition> listByTenant(UUID tenantId) {
            return tools.values().stream().filter(tool -> tool.tenantId().equals(tenantId)).toList();
        }

        @Override
        public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
            return false;
        }

        @Override
        public void delete(UUID tenantId, UUID toolId) {
            tools.remove(toolId);
        }
    }

    private static final class FakeHttpConfigRepository implements HttpToolConfigRepository {
        private final Map<UUID, HttpToolConfig> configs = new HashMap<>();

        @Override
        public HttpToolConfig save(HttpToolConfig config) {
            configs.put(config.toolId(), config);
            return config;
        }

        @Override
        public java.util.Optional<HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId) {
            HttpToolConfig config = configs.get(toolId);
            return config != null && config.tenantId().equals(tenantId)
                    ? java.util.Optional.of(config) : java.util.Optional.empty();
        }

        @Override
        public Map<UUID, HttpToolConfig> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds) {
            Map<UUID, HttpToolConfig> result = new HashMap<>();
            for (UUID toolId : toolIds) {
                findByTenantAndToolId(tenantId, toolId).ifPresent(config -> result.put(toolId, config));
            }
            return result;
        }

        @Override
        public void delete(UUID tenantId, UUID toolId) {
            configs.remove(toolId);
        }
    }

    private static final class FakeGrantRepository implements ToolGrantRepository {
        private final List<ToolGrant> grants = new ArrayList<>();

        @Override
        public ToolGrant save(ToolGrant grant) {
            grants.add(grant);
            return grant;
        }

        @Override
        public List<ToolGrant> listByTenant(UUID tenantId) {
            return grants.stream().filter(grant -> grant.tenantId().equals(tenantId)).toList();
        }

        @Override
        public List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId) {
            return grants.stream()
                    .filter(grant -> grant.tenantId().equals(tenantId) && grant.agentId().equals(agentId))
                    .toList();
        }

        @Override
        public List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId) {
            return listByTenantAndAgent(tenantId, agentId).stream()
                    .filter(grant -> grant.toolId().equals(toolId)).toList();
        }

        @Override
        public void delete(UUID tenantId, UUID agentId, UUID toolId) {
            grants.removeIf(grant -> grant.tenantId().equals(tenantId)
                    && grant.agentId().equals(agentId) && grant.toolId().equals(toolId));
        }

        @Override
        public void deleteByTenantAndToolId(UUID tenantId, UUID toolId) {
            grants.removeIf(grant -> grant.tenantId().equals(tenantId) && grant.toolId().equals(toolId));
        }
    }

    private static final class FakeDefinitionRepository implements SkillDefinitionRepository {
        private final Map<UUID, SkillDefinition> definitions = new HashMap<>();

        @Override
        public java.util.Optional<SkillDefinition> find(UUID tenantId, UUID skillId) {
            SkillDefinition definition = definitions.get(skillId);
            return definition != null && definition.tenantId().equals(tenantId)
                    ? java.util.Optional.of(definition) : java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<SkillDefinition> findByName(UUID tenantId, String name) {
            return definitions.values().stream()
                    .filter(item -> item.tenantId().equals(tenantId) && item.name().equals(name)).findFirst();
        }

        @Override
        public com.cmagent.api.ApiPageResponse<SkillDefinition> list(
                UUID tenantId, String query, Boolean enabled, com.cmagent.api.ApiPageRequest page) {
            throw new UnsupportedOperationException("预检不使用分页列表");
        }

        @Override
        public SkillDefinition insert(SkillDefinition definition) {
            definitions.put(definition.id(), definition);
            return definition;
        }

        @Override
        public SkillDefinition lock(UUID tenantId, UUID skillId) {
            return find(tenantId, skillId).orElseThrow();
        }

        @Override
        public boolean updateCurrent(SkillDefinition next, UUID expectedVersionId) {
            definitions.put(next.id(), next);
            return true;
        }

        @Override
        public boolean updatePointers(SkillDefinition next, UUID expectedCandidateId, UUID expectedPublishedId) {
            definitions.put(next.id(), next);
            return true;
        }

        @Override
        public boolean updateDependencyMappingRevision(SkillDefinition next, long expectedRevision) {
            definitions.put(next.id(), next);
            return true;
        }

        @Override
        public void updateEnabled(SkillDefinition next) {
            definitions.put(next.id(), next);
        }
    }

    private static final class FakeAgentRepository implements AgentDefinitionRepository {
        private final Map<UUID, AgentDefinition> agents = new HashMap<>();

        @Override
        public AgentDefinition save(AgentDefinition agent) {
            agents.put(agent.id(), agent);
            return agent;
        }

        @Override
        public java.util.Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
            AgentDefinition agent = agents.get(agentId);
            return agent != null && agent.tenantId().equals(tenantId)
                    ? java.util.Optional.of(agent) : java.util.Optional.empty();
        }

        @Override
        public List<AgentDefinition> listByTenant(UUID tenantId) {
            return agents.values().stream().filter(agent -> agent.tenantId().equals(tenantId)).toList();
        }

        @Override
        public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
            throw new UnsupportedOperationException("预检不修改 Agent 工具关联");
        }

        @Override
        public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
            throw new UnsupportedOperationException("预检不修改 Agent 工具关联");
        }
    }

    private static final class FakeBindingRepository implements AgentSkillBindingRepository {
        private final List<UUID> followers = new ArrayList<>();

        @Override
        public List<com.cmagent.core.domain.AgentSkillBinding> list(UUID tenantId, UUID agentId) {
            return List.of();
        }

        @Override
        public java.util.Optional<com.cmagent.core.domain.AgentSkillBinding> find(
                UUID tenantId, UUID agentId, UUID skillId) {
            return java.util.Optional.empty();
        }

        @Override
        public void insert(com.cmagent.core.domain.AgentSkillBinding binding) {
            throw new UnsupportedOperationException("预检不建立绑定");
        }

        @Override
        public boolean delete(UUID tenantId, UUID agentId, UUID skillId) {
            return false;
        }

        @Override
        public long countBySkill(UUID tenantId, UUID skillId) {
            return followers.size();
        }

        @Override
        public void lockAgent(UUID tenantId, UUID agentId) {
            // 内存替身没有锁语义。
        }

        @Override
        public List<UUID> listFollowerAgentIds(UUID tenantId, UUID skillId) {
            return List.copyOf(followers);
        }
    }
}
