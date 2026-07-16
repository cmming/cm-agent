# 阶段 3：接入真实 AgentScope Runtime 实施计划

> **面向执行智能体：** 必须使用 `superpowers:subagent-driven-development` 按任务执行；每个实现子智能体必须使用 `superpowers:test-driven-development`，先确认红测原因正确，再写最小实现，最后重构。每个任务完成后执行规格符合性与代码质量两阶段审查。

**目标：** 将 CM Agent 的 `AgentRuntime` 接入 AgentScope Java `2.0.0` GA，支持租户级模型配置、外部密钥、真实 ReActAgent、受治理工具调用、超时中断、事件与结果映射，同时保持现有权限、审计、多租户和 JDBC/Flyway 基线。

**架构：** `cm-agent-server` 继续负责编排、租户数据加载、工具授权和审计；`cm-agent-agentscope-adapter` 只把稳定 Core 请求转换为 AgentScope 模型、消息、Toolkit、RuntimeContext 和事件结果。模型元数据来自 tenant-scoped Repository，模型密钥通过 `ModelCredentialProvider` 从外部 Secret 映射解析，真实工具执行统一经过 `ToolInvocationGateway`。

**技术栈：** Java 21、Maven 3.9+、Spring Boot 3.5.0、AgentScope Java 2.0.0、Reactor、Jackson、Spring JdbcClient、JUnit Jupiter 5、AssertJ、Mockito、MockMvc、Testcontainers、PostgreSQL 16-alpine、MySQL 8.4。

## 全局约束

- 根 POM 的 `maven.compiler.release` 保持 `21`，`agentscope.version` 必须精确为 `2.0.0`。
- 新增或修改的注释、JavaDoc、README、docs、测试说明、计划、ledger 和提交说明全部使用中文。
- Controller 不访问数据库；Repository 不依赖 Web/Security 类型；所有模型、工具和运行查询必须携带 tenant 条件。
- 不新增 JPA、MyBatis、AgentScope Harness、自有 AgentScope 状态库或自动 endpoint 工具调用。
- 不修改任何既有 Flyway 迁移；本阶段不新增 schema 迁移。
- 真实密钥不得进入 Git、DTO、日志、审计、异常、OpenAPI 示例或测试输出。
- local/test 默认保留 fake runtime；production/prod/supabase 禁止 fake runtime。
- Adapter 中 AgentScope Core、OpenAI 和 DashScope 依赖保持 optional；Starter 不传递 AgentScope 依赖。
- Docker、JDBC、Flyway 和 Testcontainers 验证必须通过 `ssh rocky`，容器镜像固定为 `maven:3.9.9-eclipse-temurin-21`。
- 远程验证前，使用 Git bundle 把本地提交同步到 `/tmp/cm-agent-phase3`，确认两端 `git rev-parse HEAD` 完全一致。
- 原工作区未提交的 `application.yml`、`application-mysql.yml` 属于用户修改，不得覆盖、暂存或带入功能分支。
- 既有 `.worktrees/phase-3-agentscope-runtime` 与 `.worktrees/phase-3-agentscope-runtime-v2` 及其分支保留不动；本计划使用 `codex/phase-3-agentscope-runtime-ga`。

## 文件职责图

### 新增文件

- `cm-agent-core/src/main/java/com/cmagent/core/repository/ModelConfigRepository.java`：租户模型配置查询契约。
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/ModelCredential.java`：默认脱敏的模型密钥值对象。
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/ModelCredentialProvider.java`：外部密钥解析 SPI。
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/ModelCredentialUnavailableException.java`：固定消息的凭据缺失异常。
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/ToolInvocationGateway.java`：受治理工具调用 SPI。
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/ToolInvocationRequest.java`：工具运行上下文。
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/ToolInvocationResult.java`：受控工具结果。
- `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcModelConfigRepository.java`：JDBC 模型配置查询。
- `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcModelConfigRepositoryTest.java`：PostgreSQL tenant 隔离测试。
- `cm-agent-server/src/main/java/com/cmagent/server/config/AgentScopeRuntimeProperties.java`：真实 Runtime 配置绑定与校验。
- `cm-agent-server/src/main/java/com/cmagent/server/config/AgentScopeRuntimeConfiguration.java`：真实 Runtime 条件装配。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/ExternalModelCredentialProvider.java`：属性型外部密钥 Provider。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/GovernedToolInvocationService.java`：每次工具调用重新授权与审计。
- `cm-agent-server/src/test/java/com/cmagent/server/config/AgentScopeRuntimeConfigurationTest.java`：条件装配测试。
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/ExternalModelCredentialProviderTest.java`：密钥复合键与脱敏测试。
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/GovernedToolInvocationServiceTest.java`：工具治理测试。
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeOptions.java`：Adapter 运行参数。
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeModelFactory.java`：Provider 模型创建。
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeToolBridge.java`：动态 AgentTool 与 ToolCall 采集。
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeExecutor.java`：可替换执行缝。
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeExecutionResult.java`：Adapter 内部执行结果。
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java`：真实 ReActAgent 执行器。
- `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeModelFactoryTest.java`：模型工厂测试。
- `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeToolBridgeTest.java`：工具桥接测试。
- `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeRuntimeContractTest.java`：本地 HTTP Stub 真实合同测试。
- `docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md`：持续进度、证据、问题和修复记录。

### 修改文件

- `pom.xml`：AgentScope 2.0.0 与 Provider 依赖管理。
- `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java`：完整运行请求。
- `cm-agent-core/src/main/java/com/cmagent/core/tool/ToolExecutionRequest.java`：增加运行上下文并保留兼容构造器。
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/FakeAgentRuntime.java`：适配新请求。
- `cm-agent-core/src/test/java/com/cmagent/core/runtime/FakeAgentRuntimeTest.java`：更新请求构造。
- `cm-agent-core/src/test/java/com/cmagent/core/tool/InMemoryToolRegistryTest.java`：上下文兼容测试。
- `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcRuntimeRepositoryMySqlTest.java`：MySQL 模型配置查询契约。
- `cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java`：memory 模型配置存取。
- `cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java`：memory ModelConfigRepository。
- `cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`：JDBC ModelConfigRepository。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`：租户模型加载和完整请求组装。
- `cm-agent-server/src/main/java/com/cmagent/server/security/ProfileSafetyValidator.java`：real/fake 互斥与严格 profile。
- `cm-agent-server/src/main/resources/application.yml`：公共 AgentScope 属性映射。
- `cm-agent-server/src/main/resources/application-production.yml`：生产启用真实 Runtime。
- `cm-agent-server/src/main/resources/application-supabase.yml`：Supabase 启用真实 Runtime。
- `cm-agent-server/pom.xml`：Server 显式运行依赖。
- `cm-agent-agentscope-adapter/pom.xml`：正式 Provider 扩展 optional 依赖。
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRunSpec.java`：完整运行规格。
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeAdapter.java`：真实执行结果映射。
- `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeRuntimeAdapterTest.java`：替换占位失败测试。
- `cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`：严格 profile 真实 Runtime 测试。
- `cm-agent-server/src/test/java/com/cmagent/server/config/ServerRepositoryConfigurationTest.java`：memory 模型配置测试。
- `cm-agent-server/src/test/java/com/cmagent/server/config/JdbcPersistenceConfigurationTest.java`：JDBC Bean 测试。
- `cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerTest.java`：模型配置与真实结果语义。
- `cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerJdbcPersistenceTest.java`：JDBC 两段式链路。
- `README.md`、`docs/configuration.md`、`docs/deployment.md`、`docs/operations.md`、`docs/roadmap.md`、`docs/release-notes.md`：中文生产说明。

---

### Task 1：升级依赖并建立 Core 运行契约

**文件：**

- 修改：`pom.xml`
- 修改：`cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java`
- 修改：`cm-agent-core/src/main/java/com/cmagent/core/tool/ToolExecutionRequest.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/runtime/ModelCredential.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/runtime/ModelCredentialProvider.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/runtime/ModelCredentialUnavailableException.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/runtime/ToolInvocationGateway.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/runtime/ToolInvocationRequest.java`
- 新建：`cm-agent-core/src/main/java/com/cmagent/core/runtime/ToolInvocationResult.java`
- 修改：`cm-agent-core/src/test/java/com/cmagent/core/runtime/FakeAgentRuntimeTest.java`
- 修改：`cm-agent-core/src/test/java/com/cmagent/core/tool/InMemoryToolRegistryTest.java`
- 新建：`cm-agent-core/src/test/java/com/cmagent/core/domain/AgentRunRequestTest.java`
- 新建：`cm-agent-core/src/test/java/com/cmagent/core/runtime/ModelCredentialTest.java`
- 新建：`cm-agent-core/src/test/java/com/cmagent/core/runtime/ToolInvocationRequestTest.java`

**接口：**

- 产出：`AgentRunRequest(UUID runId, UUID tenantId, AgentDefinition agent, ModelConfig modelConfig, PrincipalRef principal, String input, List<ToolDefinition> tools)`。
- 产出：`ModelCredentialProvider.resolve(UUID tenantId, UUID modelConfigId)`。
- 产出：`ModelCredentialUnavailableException`，只使用固定中文消息，供 Adapter 精确分类凭据缺失。
- 产出：`ToolInvocationGateway.invoke(ToolInvocationRequest request)`。
- 产出：`ToolExecutionRequest` 的完整上下文构造器与旧 `(UUID toolId, String inputJson)` 构造器。

- [ ] **Step 1：创建 progress ledger**

创建 `docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md`，初始内容必须为：

```markdown
# 阶段 3 AgentScope Runtime 进度记录

| 计划项 | 状态 | 证据 | 发现的问题 | 修复结果 |
|---|---|---|---|---|
| Task 1：Core 运行契约 | 进行中 | 尚无 | 尚无 | 尚无 |
| Task 2：模型配置仓储 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 3：外部模型凭据 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 4：工具治理网关 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 5：AgentScope 模型与工具桥接 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 6：真实 ReAct 执行器 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 7：Server 装配与运行链路 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 8：文档与整体验证 | 未开始 | 尚无 | 尚无 | 尚无 |
```

- [ ] **Step 2：写 Core 红测**

`AgentRunRequestTest` 至少包含：

```java
@Test
void rejectsCrossTenantModelConfig() {
    UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    AgentDefinition agent = agent(tenantId);
    ModelConfig model = model(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    assertThatThrownBy(() -> new AgentRunRequest(
            UUID.randomUUID(), tenantId, agent, model, principal(tenantId), "你好", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("模型配置不属于当前租户");
}

@Test
void copiesAuthorizedTools() {
    List<ToolDefinition> tools = new ArrayList<>(List.of(tool(TENANT_ID)));
    AgentRunRequest request = new AgentRunRequest(
            RUN_ID, TENANT_ID, agent(TENANT_ID), model(TENANT_ID), principal(TENANT_ID), "你好", tools);
    tools.clear();
    assertThat(request.tools()).hasSize(1);
    assertThatThrownBy(() -> request.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
}
```

上述测试类内必须声明完整 helper，避免依赖未定义的共享 fixture：

```java
private static AgentDefinition agent(UUID tenantId) {
    return new AgentDefinition(
            AGENT_ID, tenantId, "企业助手", "", "你是企业助手", MODEL_ID,
            "agent-model", 0.2, 5, true, List.of(), "tester", "tester");
}

private static ModelConfig model(UUID tenantId) {
    return new ModelConfig(
            MODEL_ID, tenantId, ModelProviderType.OPENAI_COMPATIBLE,
            "测试模型", "https://example.invalid/v1", "default-model", true);
}

private static PrincipalRef principal(UUID tenantId) {
    return new PrincipalRef(tenantId, "principal", "测试主体", Set.of("agent:run"));
}

private static ToolDefinition tool(UUID tenantId) {
    return new ToolDefinition(
            TOOL_ID, tenantId, "echo", "回显", ToolType.LOCAL,
            "{\"type\":\"object\"}", ToolRiskLevel.LOW, true, "", "tester", "tester");
}
```

`ModelCredentialTest` 必须断言：

```java
@Test
void neverPrintsApiKey() {
    ModelCredential credential = new ModelCredential("unit-test-model-key");
    assertThat(credential.apiKey()).isEqualTo("unit-test-model-key");
    assertThat(credential.toString()).isEqualTo("ModelCredential[apiKey=<已脱敏>]")
            .doesNotContain("unit-test-model-key");
}
```

`ToolInvocationRequestTest` 必须覆盖 principal tenant 不一致；`InMemoryToolRegistryTest` 必须覆盖旧构造器仍可执行。

- [ ] **Step 3：运行红测并确认失败原因**

运行：

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-core -am -Dtest=AgentRunRequestTest,ModelCredentialTest,ToolInvocationRequestTest,InMemoryToolRegistryTest,FakeAgentRuntimeTest test
```

预期：编译失败，缺少 `ModelCredential`、`ToolInvocationRequest`，且 `AgentRunRequest` 构造器签名不匹配；不能是 JDK 版本错误。

- [ ] **Step 4：升级 POM 并写最小 Core 实现**

根 POM 使用：

```xml
<agentscope.version>2.0.0</agentscope.version>
```

并在 `dependencyManagement` 添加两个工件：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

`ModelCredential` 完整实现：

```java
public final class ModelCredential {
    private final String apiKey;

    public ModelCredential(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("模型 API Key 不能为空");
        }
        this.apiKey = apiKey;
    }

    public String apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "ModelCredential[apiKey=<已脱敏>]";
    }
}
```

`ToolInvocationResult` 完整实现：

```java
public record ToolInvocationResult(String output, boolean success, boolean authorized, String errorMessage) {
    public ToolInvocationResult {
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static ToolInvocationResult succeeded(String output) {
        return new ToolInvocationResult(output, true, true, "");
    }

    public static ToolInvocationResult failed(String errorMessage) {
        return new ToolInvocationResult("", false, true, errorMessage);
    }

    public static ToolInvocationResult denied(String reason) {
        return new ToolInvocationResult("", false, false, reason);
    }
}
```

凭据缺失异常完整实现：

```java
public final class ModelCredentialUnavailableException extends RuntimeException {
    public ModelCredentialUnavailableException() {
        super("模型凭据不可用");
    }

    public ModelCredentialUnavailableException(Throwable cause) {
        super("模型凭据不可用", cause);
    }
}
```

`AgentRunRequest` 紧凑构造器必须 `Objects.requireNonNull` 所有单值、执行 `List.copyOf`，并逐一校验 agent、modelConfig、principal、tools 的 tenant；新增：

```java
public UUID agentId() {
    return agent.id();
}
```

`ToolExecutionRequest` 增加完整上下文字段，并保留：

```java
public ToolExecutionRequest(UUID toolId, String inputJson) {
    this(null, null, null, null, null, toolId, inputJson);
}

public boolean hasRuntimeContext() {
    return tenantId != null && agentId != null && principal != null && runId != null
            && toolCallId != null && !toolCallId.isBlank();
}
```

- [ ] **Step 5：运行 Core 测试并确认通过**

运行 Task 1 Step 3 的完整命令。

预期：退出码 `0`，指定测试均无 failure/error。

- [ ] **Step 6：更新 ledger 并提交**

将 Task 1 状态改为“已完成”，证据写入实际命令和测试数；Task 2 改为“进行中”。提交：

```powershell
git add pom.xml cm-agent-core docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md
git commit -m "feat: 扩展真实运行核心契约"
```

---

### Task 2：实现租户级 ModelConfig Repository

**文件：**

- 新建：`cm-agent-core/src/main/java/com/cmagent/core/repository/ModelConfigRepository.java`
- 新建：`cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcModelConfigRepository.java`
- 新建：`cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcModelConfigRepositoryTest.java`
- 修改：`cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcRuntimeRepositoryMySqlTest.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/config/ServerRepositoryConfigurationTest.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/config/JdbcPersistenceConfigurationTest.java`

**接口：**

- 消费：`ModelConfig` 和现有 `model_configs` 表。
- 产出：`Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId)`，供 Task 7 的 `RunExecutionService` 使用。

- [ ] **Step 1：写 PostgreSQL/MySQL 和装配红测**

PostgreSQL 测试核心断言：

```java
@Test
void findsOnlyModelConfigOwnedByTenant() {
    ModelConfig own = repository.findByTenantAndId(TENANT_A, MODEL_A).orElseThrow();
    assertThat(own.providerType()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
    assertThat(own.baseUrl()).isEqualTo("https://model-a.invalid/v1");
    assertThat(repository.findByTenantAndId(TENANT_B, MODEL_A)).isEmpty();
}
```

MySQL 测试使用同一数据和断言。`ServerRepositoryConfigurationTest` 必须断言 memory Bean 能读取默认 tenant 的默认模型，其他 tenant 读取为空；`JdbcPersistenceConfigurationTest` 必须断言 Bean 是 `JdbcModelConfigRepository`。

- [ ] **Step 2：提交红测并同步 Rocky**

```powershell
git add cm-agent-persistence/src/test cm-agent-server/src/test
git commit -m "test: 添加模型配置租户隔离红测"
git bundle create target/cm-agent-phase3-red.bundle codex/phase-3-agentscope-runtime-ga
scp target/cm-agent-phase3-red.bundle rocky:/tmp/cm-agent-phase3-red.bundle
ssh rocky 'set -euo pipefail; rm -rf /tmp/cm-agent-phase3-red; git clone /tmp/cm-agent-phase3-red.bundle /tmp/cm-agent-phase3-red; cd /tmp/cm-agent-phase3-red; docker info >/dev/null; git rev-parse HEAD; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3-red:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-persistence -am -Dtest=JdbcModelConfigRepositoryTest,JdbcRuntimeRepositoryMySqlTest test'
```

预期：远程 HEAD 等于本地 HEAD；测试编译失败，缺少 `JdbcModelConfigRepository` 或 `ModelConfigRepository`。

- [ ] **Step 3：写 Repository 最小实现**

Core 接口：

```java
public interface ModelConfigRepository {
    Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId);
}
```

JDBC 查询固定为：

```java
return jdbcClient.sql("""
                SELECT id, tenant_id, provider_type, display_name, base_url, model_name, enabled
                FROM model_configs
                WHERE tenant_id = :tenantId AND id = :id
                """)
        .param("tenantId", tenantId.toString())
        .param("id", modelConfigId.toString())
        .query((rs, rowNum) -> new ModelConfig(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                ModelProviderType.valueOf(rs.getString("provider_type")),
                rs.getString("display_name"),
                rs.getString("base_url"),
                rs.getString("model_name"),
                rs.getBoolean("enabled")))
        .optional();
```

`InMemoryPlatformStore` 增加 `ConcurrentHashMap<UUID, ModelConfig>`、`saveModelConfig`、`findModelConfig(tenantId,id)`。`ServerRepositoryConfiguration` 初始化以下默认配置并返回 tenant-filtered Repository：

```java
new ModelConfig(
        UUID.fromString("00000000-0000-0000-0000-000000000301"),
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        ModelProviderType.OPENAI_COMPATIBLE,
        "默认模型",
        "https://example.invalid",
        "qwen-max",
        true)
```

`JdbcPersistenceConfiguration` 增加：

```java
@Bean
ModelConfigRepository jdbcModelConfigRepository(JdbcClient cmAgentJdbcClient) {
    return new JdbcModelConfigRepository(cmAgentJdbcClient);
}
```

- [ ] **Step 4：本地执行非容器装配测试**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=ServerRepositoryConfigurationTest,JdbcPersistenceConfigurationTest test
```

预期：退出码 `0`。

- [ ] **Step 5：提交实现并远程执行绿测**

```powershell
git add cm-agent-core cm-agent-persistence cm-agent-server docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md
git commit -m "feat: 添加租户模型配置仓储"
git bundle create target/cm-agent-phase3-model-repo.bundle codex/phase-3-agentscope-runtime-ga
scp target/cm-agent-phase3-model-repo.bundle rocky:/tmp/cm-agent-phase3-model-repo.bundle
ssh rocky 'set -euo pipefail; rm -rf /tmp/cm-agent-phase3-model-repo; git clone /tmp/cm-agent-phase3-model-repo.bundle /tmp/cm-agent-phase3-model-repo; cd /tmp/cm-agent-phase3-model-repo; docker info >/dev/null; git rev-parse HEAD; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3-model-repo:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-persistence -am -Dtest=JdbcModelConfigRepositoryTest,JdbcRuntimeRepositoryMySqlTest test'
```

预期：远程 HEAD 等于本地 HEAD，指定 PostgreSQL/MySQL 测试通过。ledger 记录实际容器输出摘要。

---

### Task 3：实现外部模型凭据配置

**文件：**

- 新建：`cm-agent-server/src/main/java/com/cmagent/server/config/AgentScopeRuntimeProperties.java`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/runtime/ExternalModelCredentialProvider.java`
- 新建：`cm-agent-server/src/test/java/com/cmagent/server/runtime/ExternalModelCredentialProviderTest.java`

**接口：**

- 消费：Task 1 的 `ModelCredentialProvider`。
- 产出：`AgentScopeRuntimeProperties` 的 enabled、modelTimeout、toolTimeout、modelMaxAttempts、credentials；Task 7 用于 Spring 装配。

- [ ] **Step 1：写配置与凭据红测**

必须覆盖：同一 modelConfigId 在不同 tenant 下可以配置不同密钥；跨 tenant 解析失败；重复复合键失败；空密钥失败；异常和 `toString()` 不包含密钥。

```java
@Test
void resolvesCredentialByTenantAndModelConfig() {
    AgentScopeRuntimeProperties properties = properties(
            credential(TENANT_A, MODEL_ID, "tenant-a-key"),
            credential(TENANT_B, MODEL_ID, "tenant-b-key"));
    ExternalModelCredentialProvider provider = new ExternalModelCredentialProvider(properties);

    assertThat(provider.resolve(TENANT_A, MODEL_ID).apiKey()).isEqualTo("tenant-a-key");
    assertThat(provider.resolve(TENANT_B, MODEL_ID).apiKey()).isEqualTo("tenant-b-key");
}
```

测试类中的 helper 必须完整声明为：

```java
private static AgentScopeRuntimeProperties properties(
        AgentScopeRuntimeProperties.CredentialProperties... credentials) {
    AgentScopeRuntimeProperties properties = new AgentScopeRuntimeProperties();
    properties.setEnabled(true);
    properties.setCredentials(List.of(credentials));
    return properties;
}

private static AgentScopeRuntimeProperties.CredentialProperties credential(
        UUID tenantId, UUID modelConfigId, String apiKey) {
    AgentScopeRuntimeProperties.CredentialProperties credential =
            new AgentScopeRuntimeProperties.CredentialProperties();
    credential.setTenantId(tenantId);
    credential.setModelConfigId(modelConfigId);
    credential.setApiKey(apiKey);
    return credential;
}
```

- [ ] **Step 2：运行红测**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=ExternalModelCredentialProviderTest test
```

预期：编译失败，目标类不存在。

- [ ] **Step 3：写最小配置实现**

`AgentScopeRuntimeProperties` 使用 `@ConfigurationProperties(prefix = "cm-agent.agentscope")`，默认值：

```java
private boolean enabled;
private Duration modelTimeout = Duration.ofSeconds(60);
private Duration toolTimeout = Duration.ofSeconds(30);
private int modelMaxAttempts = 2;
private List<CredentialProperties> credentials = List.of();
```

`validate(boolean fakeRuntimeEnabled)` 必须检查 timeout 为正、attempts 为 1..5、真实与 fake 不可同时启用，并验证每个 credential 的 tenantId、modelConfigId、apiKey。`setCredentials` 必须 `List.copyOf`。

`ExternalModelCredentialProvider` 构造时把列表复制为 `Map<CredentialKey, ModelCredential>`；重复键抛出 `IllegalStateException("模型凭据配置重复")`；解析缺失时抛出 `ModelCredentialUnavailableException`。内部 `CredentialKey` 只包含两个 UUID。

- [ ] **Step 4：运行测试并提交**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=ExternalModelCredentialProviderTest test
git add cm-agent-server docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md
git commit -m "feat: 支持租户外部模型凭据"
```

预期：测试通过；ledger 中 Task 3 已完成、Task 4 进行中。

---

### Task 4：实现每次调用重新授权的工具治理网关

**文件：**

- 新建：`cm-agent-server/src/main/java/com/cmagent/server/runtime/GovernedToolInvocationService.java`
- 新建：`cm-agent-server/src/test/java/com/cmagent/server/runtime/GovernedToolInvocationServiceTest.java`

**接口：**

- 消费：Task 1 的 `ToolInvocationGateway`、`ToolInvocationRequest`、`ToolInvocationResult`。
- 消费：现有 `SensitiveDataRedactor`，执行器异常日志必须先脱敏，审计只写固定中文摘要。
- 产出：Task 5 动态 AgentTool 的唯一执行入口。

- [ ] **Step 1：写工具治理红测**

测试必须使用 Mockito，覆盖：

```java
@Test
void deniedInvocationNeverExecutesToolAndWritesAudit() {
    when(toolRepository.findByTenantAndId(TENANT_ID, TOOL_ID)).thenReturn(Optional.of(tool));
    when(grantRepository.listByTenantAndAgent(TENANT_ID, AGENT_ID)).thenReturn(List.of());
    when(policy.check(principal, AGENT_ID, tool, List.of()))
            .thenReturn(AuthorizationDecision.deny("Agent 未获得工具授权 echo"));

    ToolInvocationResult result = service.invoke(request());

    assertThat(result.authorized()).isFalse();
    verifyNoInteractions(toolRegistry);
    verify(auditAppender).accessDenied(principal, "TOOL", TOOL_ID.toString(),
            "tool:invoke", "Agent 未获得工具授权 echo");
}

@Test
void auditFailureBeforeInvocationPreventsSideEffect() {
    arrangeAllowedTool();
    doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException()))
            .when(auditAppender).append(any(), any(), eq("TOOL_CALL_STARTED"), any(), any(), any(), any());
    assertThatThrownBy(() -> service.invoke(request())).isInstanceOf(AuditPersistenceException.class);
    verify(toolRegistry, never()).execute(any());
}
```

还要覆盖跨租户工具、数据库定义与注册定义不一致、成功执行、执行器返回失败、执行器抛异常以及成功/失败完成审计。

- [ ] **Step 2：运行红测**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=GovernedToolInvocationServiceTest test
```

预期：编译失败，目标 Service 不存在。

- [ ] **Step 3：写最小网关实现**

`GovernedToolInvocationService` 依赖 Tool Repository、Grant Repository、`ToolAuthorizationPolicy`、`ToolRegistry`、`AuditAppender` 和 `SensitiveDataRedactor`。核心顺序固定为：tenant 查询 → grants 查询 → policy → registry 定义核对 → 开始审计 → execute → 完成审计。

注册执行请求必须为：

```java
new ToolExecutionRequest(
        request.tenantId(), request.agentId(), request.principal(), request.runId(),
        request.toolCallId(), request.toolId(), request.inputJson())
```

审计事件固定：

- `TOOL_CALL_STARTED` / `RUNNING` / “工具调用已开始”。
- `TOOL_CALL_COMPLETED` / `SUCCEEDED` / “工具调用完成”。
- `TOOL_CALL_FAILED` / `FAILED` / “工具调用失败”。

权限拒绝调用 `accessDenied(..., "tool:invoke", decision.reason())`。数据库工具缺失、跨租户、注册定义不一致均返回 `ToolInvocationResult.failed("工具不可用")`，不得自动调用 endpoint。执行器异常先写失败审计，再返回 `failed("工具执行失败")`；`AuditPersistenceException` 必须原样抛出。

- [ ] **Step 4：运行测试并提交**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=GovernedToolInvocationServiceTest test
git add cm-agent-server docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md
git commit -m "feat: 工具调用接入授权与审计网关"
```

预期：所有工具治理测试通过。

---

### Task 5：实现 AgentScope 模型工厂与动态工具桥接

**文件：**

- 修改：`cm-agent-agentscope-adapter/pom.xml`
- 新建：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeOptions.java`
- 新建：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeModelFactory.java`
- 新建：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeToolBridge.java`
- 新建：`cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeModelFactoryTest.java`
- 新建：`cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeToolBridgeTest.java`

**接口：**

- 消费：Task 1 的模型凭据和工具网关。
- 产出：Task 6 使用的 `Model create(ModelConfig, AgentDefinition, ModelCredential)` 和 AgentScope `AgentTool`。
- 产出：`AgentScopeToolBridge(AgentRunRequest request, ToolDefinition tool, ToolInvocationGateway gateway, ObjectMapper objectMapper)`。

- [ ] **Step 1：写 Provider 与工具桥接红测**

模型工厂测试必须断言：

```java
@Test
void createsOpenAiCompatibleModel() {
    Model model = factory.create(openAiConfig(), agent(), new ModelCredential("test-key"));
    assertThat(model).isInstanceOf(OpenAIChatModel.class);
    assertThat(((OpenAIChatModel) model).getModelName()).isEqualTo("agent-model");
}

@Test
void createsDashScopeNativeModel() {
    Model model = factory.create(dashScopeConfig(), agent(), new ModelCredential("test-key"));
    assertThat(model).isInstanceOf(DashScopeChatModel.class);
    assertThat(((DashScopeChatModel) model).getModelName()).isEqualTo("agent-model");
}
```

工具桥接测试必须构造 `ToolCallParam`，覆盖合法 JSON Schema、非法 Schema、成功、失败、拒绝、并发记录和异常不泄露。拒绝断言：

```java
when(gateway.invoke(any())).thenReturn(ToolInvocationResult.denied("没有工具权限"));
ToolResultBlock block = bridge.callAsync(toolCallParam()).block();
assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
assertThat(bridge.records()).singleElement().satisfies(record -> {
    assertThat(record.status()).isEqualTo(RunStatus.DENIED);
    assertThat(record.authorized()).isFalse();
});
```

`toolCallParam()` 必须在测试类内完整构造，不依赖 Agent 实例：

```java
private static ToolCallParam toolCallParam() {
    Map<String, Object> input = Map.of("value", "hello");
    ToolUseBlock toolUse = ToolUseBlock.builder()
            .id("tool-call-1")
            .name("echo")
            .input(input)
            .build();
    return ToolCallParam.builder()
            .toolUseBlock(toolUse)
            .input(input)
            .runtimeContext(RuntimeContext.builder().sessionId(RUN_ID.toString()).build())
            .build();
}
```

- [ ] **Step 2：运行红测**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-agentscope-adapter -am -Dtest=AgentScopeModelFactoryTest,AgentScopeToolBridgeTest test
```

预期：编译失败，正式 Provider 依赖和目标类缺失。

- [ ] **Step 3：添加 optional Provider 依赖**

Adapter POM 添加：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <optional>true</optional>
</dependency>
```

- [ ] **Step 4：写模型工厂与运行参数**

`AgentScopeRuntimeOptions`：

```java
public record AgentScopeRuntimeOptions(Duration modelTimeout, Duration toolTimeout, int modelMaxAttempts) {
    public AgentScopeRuntimeOptions {
        Objects.requireNonNull(modelTimeout, "modelTimeout 不能为空");
        Objects.requireNonNull(toolTimeout, "toolTimeout 不能为空");
        if (modelTimeout.isZero() || modelTimeout.isNegative()) throw new IllegalArgumentException("模型超时必须大于 0");
        if (toolTimeout.isZero() || toolTimeout.isNegative()) throw new IllegalArgumentException("工具超时必须大于 0");
        if (modelMaxAttempts < 1 || modelMaxAttempts > 5) throw new IllegalArgumentException("模型最大尝试次数必须在 1 到 5 之间");
    }
}
```

`AgentScopeModelFactory.create` 使用 Agent 的 `modelName`，为空时回退 `ModelConfig.modelName`；使用 `GenerateOptions.builder().temperature(agent.temperature()).build()`。OpenAI 使用 `.generateOptions(options)`，DashScope 使用 `.defaultOptions(options)`；两者都设置 apiKey、baseUrl、modelName、stream true。

模型工厂测试类中的配置 helper 必须明确构造：

```java
private static ModelConfig openAiConfig() {
    return new ModelConfig(MODEL_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
            "OpenAI兼容", "https://example.invalid/v1", "default-model", true);
}

private static ModelConfig dashScopeConfig() {
    return new ModelConfig(MODEL_ID, TENANT_ID, ModelProviderType.DASHSCOPE_NATIVE,
            "DashScope", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", true);
}

private static AgentDefinition agent() {
    return new AgentDefinition(AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手",
            MODEL_ID, "agent-model", 0.2, 5, true, List.of(), "tester", "tester");
}
```

- [ ] **Step 5：写动态 AgentTool**

`AgentScopeToolBridge implements AgentTool`，字段包含 `AgentRunRequest`、`ToolDefinition`、`ToolInvocationGateway`、解析后的 `Map<String,Object> parameters` 和线程安全 records。

`callAsync` 必须：

1. 从 `ToolUseBlock` 读取 ID 和输入 Map。
2. 用 Jackson 把输入序列化为 JSON。
3. 调用 gateway。
4. 用 `System.nanoTime()` 计算 Duration。
5. 生成 `ToolCallRecord`。
6. success 返回 `ToolResultBlock.text(output)`，failure/denied 返回 `ToolResultBlock.error(errorMessage)`。

`records()` 返回 `List.copyOf(records)`。Schema 根节点不是 object 时构造器抛出 `IllegalArgumentException("工具输入 Schema 必须是 object")`。

- [ ] **Step 6：运行测试、核对依赖并提交**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-agentscope-adapter -am -Dtest=AgentScopeModelFactoryTest,AgentScopeToolBridgeTest test
mvn -pl cm-agent-agentscope-adapter dependency:tree
git add cm-agent-agentscope-adapter docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md
git commit -m "feat: 构建AgentScope模型与工具桥接"
```

预期：测试通过；dependency tree 中三个 AgentScope 工件均为 `2.0.0`，不存在 RC 版本。

---

### Task 6：实现真实 ReActAgent 执行与 Runtime 结果映射

**文件：**

- 修改：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRunSpec.java`
- 新建：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeExecutor.java`
- 新建：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeExecutionResult.java`
- 新建：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java`
- 修改：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeAdapter.java`
- 修改：`cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeRuntimeAdapterTest.java`
- 新建：`cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeRuntimeContractTest.java`

**接口：**

- 消费：Task 5 的模型工厂、工具桥接和运行参数。
- 产出：可由 Task 7 装配的真实 `AgentRuntime`。
- 产出：`AgentScopeRuntimeAdapter.create(ModelCredentialProvider, ToolInvocationGateway, AgentScopeRuntimeOptions, Clock)` 作为 Server 的公开工厂；测试专用 Executor 构造器保持包内可见。

- [ ] **Step 1：把占位失败测试替换为真实结果红测**

Adapter 单元测试使用 fake `AgentScopeExecutor`：

```java
@Test
void mapsSuccessfulExecutionToCoreResult() {
    AgentScopeExecutor executor = (spec, credential, gateway) ->
            AgentScopeExecutionResult.succeeded("真实回答", List.of());
    ModelCredentialProvider credentialProvider =
            (tenantId, modelConfigId) -> new ModelCredential("unit-test-key");
    ToolInvocationGateway toolGateway = request -> ToolInvocationResult.succeeded("ok");
    AgentRuntime runtime = new AgentScopeRuntimeAdapter(
            credentialProvider, toolGateway, executor,
            Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));

    AgentRunResult result = runtime.run(request());

    assertThat(result.runId()).isEqualTo(RUN_ID);
    assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(result.output()).isEqualTo("真实回答");
}
```

测试类中的 `request()` 必须完整返回 Task 1 定义的请求：

```java
private static AgentRunRequest request() {
    AgentDefinition agent = new AgentDefinition(
            AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手", MODEL_ID,
            "test-model", 0.2, 5, true, List.of(), "tester", "tester");
    ModelConfig model = new ModelConfig(
            MODEL_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
            "测试模型", "http://127.0.0.1:1/v1", "test-model", true);
    PrincipalRef principal = new PrincipalRef(
            TENANT_ID, "principal", "测试主体", Set.of("agent:run"));
    return new AgentRunRequest(RUN_ID, TENANT_ID, agent, model, principal, "你好", List.of());
}
```

另测 denied 强制 Run `DENIED`、timeout 返回“Agent 运行超时”、凭据缺失不泄露。

合同测试启动 `HttpServer`，OpenAI-compatible 第一个场景返回：

```json
{"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"真实运行成功"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
```

第二个场景第一次返回 `tool_calls`，第二次返回最终文本；断言真实 `ReActAgent.streamEvents()` 调用了 gateway 且输出最终文本。

- [ ] **Step 2：运行红测**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-agentscope-adapter -am -Dtest=AgentScopeRuntimeAdapterTest,AgentScopeRuntimeContractTest test
```

预期：编译失败，Executor 和 ExecutionResult 不存在，原 Adapter 构造器不匹配。

- [ ] **Step 3：实现内部执行结果与 Adapter**

`AgentScopeExecutionResult` 包含 `RunStatus status`、output、`List<ToolCallRecord>`、errorMessage，并提供 `succeeded`、`failed`、`denied` 工厂；只允许终态。

`AgentScopeRunSpec` 精确设计为：

```java
public record AgentScopeRunSpec(AgentRunRequest request) {
    public AgentScopeRunSpec {
        Objects.requireNonNull(request, "request 不能为空");
    }

    public UUID runId() { return request.runId(); }
    public UUID tenantId() { return request.tenantId(); }
    public UUID agentId() { return request.agent().id(); }
    public String principalId() { return request.principal().principalId(); }
    public String userInput() { return request.input(); }
}
```

`AgentScopeExecutor` 精确签名为：

```java
interface AgentScopeExecutor {
    AgentScopeExecutionResult execute(
            AgentScopeRunSpec spec,
            ModelCredential credential,
            ToolInvocationGateway toolGateway);
}
```

`AgentScopeRuntimeAdapter.run`：

```java
Instant startedAt = clock.instant();
try {
    ModelCredential credential = credentialProvider.resolve(
            request.tenantId(), request.modelConfig().id());
    AgentScopeExecutionResult execution = executor.execute(toRunSpec(request), credential, toolGateway);
    return new AgentRunResult(
            request.runId(), execution.status(), execution.output(), execution.toolCalls(),
            startedAt, clock.instant(), execution.errorMessage());
} catch (ModelCredentialUnavailableException ex) {
    return new AgentRunResult(
            request.runId(), RunStatus.FAILED, "", List.of(), startedAt, clock.instant(), "模型凭据不可用");
}
```

只捕获明确的凭据不可用异常；未知异常继续抛出。

Adapter 提供公开静态工厂，隔离真实 Executor 类型：

```java
public static AgentScopeRuntimeAdapter create(
        ModelCredentialProvider credentialProvider,
        ToolInvocationGateway toolGateway,
        AgentScopeRuntimeOptions options,
        Clock clock) {
    return new AgentScopeRuntimeAdapter(
            credentialProvider,
            toolGateway,
            new AgentScopeReActExecutor(options, new AgentScopeModelFactory()),
            clock);
}
```

- [ ] **Step 4：实现真实 ReAct 执行器**

`AgentScopeReActExecutor` 为每次调用创建 Toolkit、bridge 列表、Model、ExecutionConfig、RuntimeContext 和 ReActAgent：

```java
ExecutionConfig modelConfig = ExecutionConfig.builder()
        .timeout(options.modelTimeout())
        .maxAttempts(options.modelMaxAttempts())
        .build();
ExecutionConfig toolConfig = ExecutionConfig.builder()
        .timeout(options.toolTimeout())
        .maxAttempts(1)
        .build();
RuntimeContext context = RuntimeContext.builder()
        .userId(spec.tenantId() + ":" + spec.principalId())
        .sessionId(spec.runId().toString())
        .put("tenantId", spec.tenantId().toString())
        .put("agentId", spec.agentId().toString())
        .put("principalId", spec.principalId())
        .put("runId", spec.runId().toString())
        .build();
```

构造 ReActAgent 时使用 name、sysPrompt、model、toolkit、maxIters、modelExecutionConfig、toolExecutionConfig，且不启用 meta tool/state store。通过：

```java
AtomicReference<Msg> finalMessage = new AtomicReference<>();
agent.streamEvents(new UserMessage(spec.userInput()), context)
        .doOnNext(event -> {
            if (event instanceof AgentResultEvent resultEvent) {
                finalMessage.set(resultEvent.getResult());
            }
        })
        .blockLast();
```

在 `finally` 中 `agent.close()`。cause 链包含 `TimeoutException` 时先 `agent.interrupt(context)` 再返回 timeout；`ModelException` 或 `HttpTransportException` 返回固定“Agent 运行失败”；其他 RuntimeException 原样抛出。`ModelHttpException` 是状态码能力接口，不放入 catch 类型列表。汇总所有 bridge records；任何 denied record 强制执行结果为 DENIED。

- [ ] **Step 5：运行真实合同测试并核对运行期兼容**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-agentscope-adapter -am -Dtest=AgentScopeRuntimeAdapterTest,AgentScopeRuntimeContractTest test
```

预期：退出码 `0`，本地 Stub 收到真实 `/chat/completions` 请求；不得出现 `NoSuchMethodError`、`ClassNotFoundException` 或网络访问。

- [ ] **Step 6：提交**

```powershell
git add cm-agent-agentscope-adapter docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md
git commit -m "feat: 接入真实AgentScope ReAct运行"
```

ledger 记录真实合同测试请求次数和最终文本断言。

---

### Task 7：装配真实 Runtime 并接入 Server 运行链路

**文件：**

- 修改：`cm-agent-server/pom.xml`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/config/AgentScopeRuntimeConfiguration.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/security/ProfileSafetyValidator.java`
- 修改：`cm-agent-server/src/main/resources/application.yml`
- 修改：`cm-agent-server/src/main/resources/application-production.yml`
- 修改：`cm-agent-server/src/main/resources/application-supabase.yml`
- 新建：`cm-agent-server/src/test/java/com/cmagent/server/config/AgentScopeRuntimeConfigurationTest.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/config/ApplicationProfileConfigurationTest.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerTest.java`
- 修改：`cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerJdbcPersistenceTest.java`

**接口：**

- 消费：前六个任务的全部契约和 Bean。
- 产出：production/supabase 默认真实 AgentScope Runtime；同步 REST Run 真实执行链路。

- [ ] **Step 1：写装配和运行链路红测**

`AgentScopeRuntimeConfigurationTest` 必须使用 `ApplicationContextRunner` 覆盖：

```java
@Test
void enabledConfigurationProvidesRealRuntime() {
    contextRunner.withPropertyValues(
            "cm-agent.fake-runtime-enabled=false",
            "cm-agent.agentscope.enabled=true",
            "cm-agent.agentscope.credentials[0].tenant-id=" + TENANT_ID,
            "cm-agent.agentscope.credentials[0].model-config-id=" + MODEL_ID,
            "cm-agent.agentscope.credentials[0].api-key=unit-test-key")
            .run(context -> assertThat(context).hasSingleBean(AgentScopeRuntimeAdapter.class));
}

@Test
void fakeAndRealRuntimeCannotBeEnabledTogether() {
    contextRunner.withPropertyValues(
            "cm-agent.fake-runtime-enabled=true",
            "cm-agent.agentscope.enabled=true")
            .run(context -> assertThat(context).hasFailed()
                    .getFailure().hasMessageContaining("fake runtime 与 AgentScope runtime 不能同时启用"));
}
```

`RunControllerTest` 增加模型配置禁用/跨租户不调用 runtime；成功请求捕获 `AgentRunRequest` 并断言 runId 等于持久化 Run、Agent 和 ModelConfig tenant 正确。

- [ ] **Step 2：运行红测**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=AgentScopeRuntimeConfigurationTest,ApplicationProfileConfigurationTest,RunControllerTest test
```

预期：编译失败，配置类不存在，RunExecutionService 尚未注入 ModelConfigRepository。

- [ ] **Step 3：添加 Server 显式依赖与配置映射**

Server POM 添加 `cm-agent-agentscope-adapter`、`agentscope-core`、两个 Provider 工件。公共 YAML 增加：

```yaml
  agentscope:
    enabled: ${cm-agent.config.agentscope-enabled:false}
    model-timeout: ${cm-agent.config.agentscope-model-timeout:60s}
    tool-timeout: ${cm-agent.config.agentscope-tool-timeout:30s}
    model-max-attempts: ${cm-agent.config.agentscope-model-max-attempts:2}
```

production 和 supabase 的 `cm-agent.config` 增加：

```yaml
agentscope-enabled: true
```

不在仓库 YAML 中写 credentials；生产由外部 YAML/Secret Manager 注入列表或提供自定义 `ModelCredentialProvider` Bean。

- [ ] **Step 4：实现条件装配**

`AgentScopeRuntimeConfiguration` 使用 `@EnableConfigurationProperties(AgentScopeRuntimeProperties.class)` 和 `@ConditionalOnProperty(prefix="cm-agent.agentscope", name="enabled", havingValue="true")`。

Bean：

```java
@Bean
@ConditionalOnMissingBean({AgentRuntime.class, ModelCredentialProvider.class})
ModelCredentialProvider externalModelCredentialProvider(AgentScopeRuntimeProperties properties) {
    return new ExternalModelCredentialProvider(properties);
}

@Bean
@ConditionalOnMissingBean(AgentRuntime.class)
AgentRuntime agentScopeRuntime(
        AgentScopeRuntimeProperties properties,
        ObjectProvider<ModelCredentialProvider> credentialProvider,
        ToolInvocationGateway gateway) {
    properties.validate(false);
    ModelCredentialProvider provider = Objects.requireNonNull(
            credentialProvider.getIfAvailable(), "启用 AgentScope runtime 时必须配置 ModelCredentialProvider");
    AgentScopeRuntimeOptions options = new AgentScopeRuntimeOptions(
            properties.getModelTimeout(), properties.getToolTimeout(), properties.getModelMaxAttempts());
    return AgentScopeRuntimeAdapter.create(provider, gateway, options, Clock.systemUTC());
}
```

同时增加 `InitializingBean`，读取 `cm-agent.fake-runtime-enabled` 并调用 properties.validate。

- [ ] **Step 5：修改 RunExecutionService**

构造器增加 `ModelConfigRepository`。在 `persistenceService.start` 之前：

```java
ModelConfig modelConfig = modelConfigRepository
        .findByTenantAndId(principal.tenantId(), agent.modelProviderId())
        .filter(ModelConfig::enabled)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型配置不可用"));
```

运行请求改为：

```java
runtime.run(new AgentRunRequest(
        runningRun.id(), principal.tenantId(), agent, modelConfig, principal, input, authorizedTools));
```

保持现有失败收口、persistent ID 替换、ToolCall 脱敏和完成事务不变。

- [ ] **Step 6：修改严格 profile 校验**

`ProfileSafetyValidator` 增加 `agentscopeEnabled`。任何 profile 同时 fake=true 与 agentscope=true 均失败；严格 profile 仍要求最终存在非 Fake 的 `AgentRuntime`。自定义真实 Runtime 保持允许。

- [ ] **Step 7：运行 Server 测试**

```powershell
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=AgentScopeRuntimeConfigurationTest,ApplicationProfileConfigurationTest,RunControllerTest test
```

预期：退出码 `0`；不访问公网。

- [ ] **Step 8：提交并远程验证 JDBC 运行链路**

先把 ledger Task 7 更新为已完成，再提交：

```powershell
git add cm-agent-server docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md
git commit -m "feat: 服务端启用真实AgentScope运行时"
git bundle create target/cm-agent-phase3-server.bundle codex/phase-3-agentscope-runtime-ga
scp target/cm-agent-phase3-server.bundle rocky:/tmp/cm-agent-phase3-server.bundle
ssh rocky 'set -euo pipefail; rm -rf /tmp/cm-agent-phase3-server; git clone /tmp/cm-agent-phase3-server.bundle /tmp/cm-agent-phase3-server; cd /tmp/cm-agent-phase3-server; docker info >/dev/null; git rev-parse HEAD; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3-server:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -pl cm-agent-server -am -Dtest=RunControllerJdbcPersistenceTest test'
```

预期：远程 HEAD 一致，JDBC 两段式 Run/ToolCall/Audit 测试通过。

---

### Task 8：更新生产文档并完成整体验证

**文件：**

- 修改：`README.md`
- 修改：`docs/configuration.md`
- 修改：`docs/deployment.md`
- 修改：`docs/operations.md`
- 修改：`docs/roadmap.md`
- 修改：`docs/release-notes.md`
- 修改：`docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md`

**接口：**

- 消费：全部实现行为和实际测试证据。
- 产出：可供生产部署、运维、审阅和 PR 描述复用的中文说明。

- [ ] **Step 1：写文档红检查**

```powershell
rg -n "AgentScope 2.0.0|ModelCredentialProvider|credentials|模型凭据|工具调用|幂等|同步单轮" README.md docs/configuration.md docs/deployment.md docs/operations.md docs/roadmap.md docs/release-notes.md
```

预期：至少有一个主题无匹配，说明阶段 3 文档尚未完成。

- [ ] **Step 2：更新中文文档**

必须写明：

- `agentscope.version=2.0.0` 和两种 Provider。
- 启用真实 Runtime 必须设置 fake=false、agentscope=true。
- credentials 使用 tenantId + modelConfigId，密钥仅用 `${MODEL_API_KEY}` 占位符。
- model_configs 保存 Provider/baseUrl/modelName，不把数据库兼容字段当明文密钥。
- 工具每次调用重新授权，endpoint 不会自动执行。
- timeout、Provider 故障、审计失败和副作用幂等语义。
- 本阶段只支持同步单轮，不承诺多轮持久化、流式 REST、HITL 或手动取消。
- 阶段 3 已交付，阶段 4/5 仍未交付。

- [ ] **Step 3：运行依赖核对与本地 JDK 21 验证**

```powershell
java -version
mvn -v
mvn -pl cm-agent-agentscope-adapter dependency:tree
mvn -pl cm-agent-server dependency:tree
mvn -q -pl cm-agent-core -am test
mvn -q -pl cm-agent-agentscope-adapter -am test
mvn -q -Dsurefire.failIfNoSpecifiedTests=false -pl cm-agent-server -am -Dtest=AgentScopeRuntimeConfigurationTest,ApplicationProfileConfigurationTest,RunControllerTest,GovernedToolInvocationServiceTest,ExternalModelCredentialProviderTest test
```

预期：Java 和 Maven 均显示 JDK 21；AgentScope 工件全部为 2.0.0；三个受影响模块测试退出码均为 0。

- [ ] **Step 4：提交文档与最终 ledger**

```powershell
git add README.md docs
git commit -m "docs: 发布阶段3真实运行说明"
```

ledger Task 8 暂记“验证中”，记录本地命令实际结果。

- [ ] **Step 5：同步最终提交并在 Rocky 执行全量验证**

```powershell
git bundle create target/cm-agent-phase3-final.bundle codex/phase-3-agentscope-runtime-ga
scp target/cm-agent-phase3-final.bundle rocky:/tmp/cm-agent-phase3-final.bundle
ssh rocky 'set -euo pipefail; rm -rf /tmp/cm-agent-phase3; git clone /tmp/cm-agent-phase3-final.bundle /tmp/cm-agent-phase3; cd /tmp/cm-agent-phase3; docker info >/dev/null; git rev-parse HEAD; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 java -version; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -v; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q test; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -DskipTests package'
```

预期：远程 HEAD 与本地 HEAD 完全一致；Docker 可用；JDK 21；全量测试和打包均退出 0。

- [ ] **Step 6：执行 Git、范围和敏感信息检查**

```powershell
git diff master...HEAD --check
git status --short
git diff --stat master...HEAD
rg -n "2\.0\.0-RC3|api-key:\s*[^$<]|password:\s*[^$<]|Bearer\s+[A-Za-z0-9]" pom.xml cm-agent-* README.md docs -g '!**/target/**'
```

预期：diff check 无输出；状态只包含预期文件；不存在 RC3；敏感扫描只命中测试假值或说明文本，不命中可用凭据。

- [ ] **Step 7：完成 ledger 并提交证据**

把 Task 8 改为“已完成”，记录远程 HEAD、全量测试、打包、diff check 和敏感扫描摘要：

```powershell
git add docs/superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md
git commit -m "docs: 记录阶段3验证证据"
```

- [ ] **Step 8：让最终 HEAD 再接受远程全量验证**

```powershell
git bundle create target/cm-agent-phase3-evidence.bundle codex/phase-3-agentscope-runtime-ga
scp target/cm-agent-phase3-evidence.bundle rocky:/tmp/cm-agent-phase3-evidence.bundle
ssh rocky 'set -euo pipefail; rm -rf /tmp/cm-agent-phase3-evidence; git clone /tmp/cm-agent-phase3-evidence.bundle /tmp/cm-agent-phase3-evidence; cd /tmp/cm-agent-phase3-evidence; docker info >/dev/null; git rev-parse HEAD; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3-evidence:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q test; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3-evidence:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q -DskipTests package'
```

预期：远程 HEAD 等于包含最终 ledger 的本地 HEAD；全量测试和打包再次退出 0。只有这次结果可以作为完成声明的最终证据。

## 计划自查

- Task 1 覆盖版本、Core 请求、密钥与工具执行上下文。
- Task 2 覆盖 memory/JDBC 模型配置、PostgreSQL/MySQL 和 tenant 隔离，不修改 Flyway。
- Task 3 覆盖外部 Secret 复合键、配置校验和密钥脱敏。
- Task 4 覆盖每次工具调用重新授权、审计严格语义和不自动 endpoint 联网。
- Task 5 覆盖两种 Provider 和动态 AgentTool。
- Task 6 覆盖真实 ReActAgent、RuntimeContext、streamEvents、timeout/interrupt、close 和结果映射。
- Task 7 覆盖 Spring 条件装配、strict profile、RunExecutionService 和 JDBC 两段式链路。
- Task 8 覆盖中文文档、依赖冲突、JDK 21、全量测试、打包、Git 和敏感信息检查。
- 类型名保持一致：`ModelConfigRepository`、`ModelCredentialProvider`、`ToolInvocationGateway`、`AgentScopeRuntimeOptions`、`AgentScopeExecutor`、`AgentScopeExecutionResult`。
- 所有实现任务都有红测、失败原因、最小实现、绿测、ledger 和中文提交。
- 原工作区两处用户配置修改不在功能分支范围内。
