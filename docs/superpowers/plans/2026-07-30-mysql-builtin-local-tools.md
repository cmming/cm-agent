# MySQL 内置 LOCAL 工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在非生产 MySQL profile 下提供可从工具治理页面显式安装并通过现有治理链路调试的固定 `echo`、`add` LOCAL 示例，同时准确展示普通 LOCAL 工具的运行时就绪状态。

**Architecture:** Server 内置固定租户、固定 ID 的示例定义和 Java 执行器，允许的 MySQL profile 启动时只注册执行器，不写数据库。独立安装服务在管理员显式请求时通过 JDBC 事务保存定义并严格审计；控制台查询目录、安装示例，并复用现有 `/api/tools/{id}/debug`。工具摘要新增 `runtimeReady` 快照字段，但执行时继续由 `GovernedToolExecutionService` 重新校验。

**Tech Stack:** Java 21、Maven 3.9+、Spring Boot 3.5.0、Spring Web MVC、Spring Security、Spring JDBC、Flyway、JUnit Jupiter 5.12.2、AssertJ 3.27.3、Mockito 5.17.0、Testcontainers 2.0.5、原生 HTML/CSS/JavaScript、Node test runner。

## Global Constraints

- 所有新增或修改的代码注释、JavaDoc、测试名称、文档和提交说明必须使用中文。
- Java 编译目标固定为 21；验证前必须执行 `java -version` 和 `mvn -v`，确认 Maven 运行在 JDK 21。
- 内置能力只在 `mysql` 且未激活 `prod`、`production`、`supabase` 时生效。
- 示例租户固定为 `00000000-0000-0000-0000-000000000001`；`echo`、`add` ID 分别固定为 `00000000-0000-0000-0000-000000000101`、`00000000-0000-0000-0000-000000000102`。
- 页面不能上传、编译或解释代码；正式业务 LOCAL 工具仍必须由 Java `ToolExecutor` 注册。
- 不新增数据库表、字段、Flyway 迁移、JPA、MyBatis 或新的数据库框架。
- Controller 只处理路由、认证主体、权限和响应；安装事务、冲突判断和审计编排放在 Service。
- 所有租户判断只使用认证主体中的 tenant，不接受客户端 tenant。
- `runtimeReady` 只是查询时快照，不替代调用时的状态、租户、ID、名称和授权复核。
- JDBC、Flyway、MySQL 和 Testcontainers 验证只能在 `ssh rocky` 的容器环境执行，不使用本机 Docker Desktop。
- 不输出或提交 JWT secret、数据库密码、模型 API Key、完整生产 JDBC URL 或其他真实凭据。
- 工作区已有用户修改必须保留；尤其是控制台 HTTP Schema 修复、`application*.yml` 和 `docs/release-notes.md` 的既有修改。提交前只暂存本任务新增的 hunk。
- 每项行为修改严格执行 RED → GREEN：先写测试并观察预期失败，再写最小实现。

---

## File Map

### 新增文件

- `cm-agent-server/src/main/java/com/cmagent/server/runtime/local/MysqlLocalExampleCatalog.java`：固定目录、定义、示例输入和持久化定义复制。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/local/EchoToolExecutor.java`：`echo` 输入校验与执行。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/local/AddToolExecutor.java`：`add` 输入校验与精确加法。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/local/MysqlLocalExampleRegistrationConfiguration.java`：允许 profile 下的进程内注册，不写数据库。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolRuntimeReadiness.java`：HTTP/LOCAL 运行时就绪判断。
- `cm-agent-server/src/main/java/com/cmagent/server/service/LocalToolExampleSummary.java`：目录 API 的稳定响应模型。
- `cm-agent-server/src/main/java/com/cmagent/server/service/MysqlLocalExampleService.java`：目录查询、显式安装、冲突、事务和审计。
- `cm-agent-server/src/main/java/com/cmagent/server/web/LocalToolExampleController.java`：目录和安装 HTTP 入口。
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/local/MysqlLocalExampleCatalogTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/local/MysqlLocalExampleRegistrationConfigurationTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/ToolRuntimeReadinessTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/service/MysqlLocalExampleServiceTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/service/MysqlLocalExampleServiceJdbcPersistenceTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/LocalToolExampleControllerTest.java`

### 修改文件

- `cm-agent-server/src/main/java/com/cmagent/server/service/ToolSummary.java`：增加 `runtimeReady`。
- `cm-agent-server/src/main/java/com/cmagent/server/service/ToolQueryService.java`：计算摘要就绪状态。
- `cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java`：在现有工具摘要响应中输出 `runtimeReady`。
- `cm-agent-server/src/test/java/com/cmagent/server/service/ToolQueryServiceTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/service/ToolDebugServiceTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/ToolControllerTest.java`
- `cm-agent-console/src/main/resources/META-INF/resources/index.html`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`
- `cm-agent-console/src/test/js/console-core.test.cjs`
- `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
- `README.md`
- `docs/tool-development-guide.md`
- `docs/release-notes.md`

---

### Task 1: 固定目录、执行器与 MySQL Profile 注册

**Files:**
- Create: `cm-agent-server/src/main/java/com/cmagent/server/runtime/local/MysqlLocalExampleCatalog.java`
- Create: `cm-agent-server/src/main/java/com/cmagent/server/runtime/local/EchoToolExecutor.java`
- Create: `cm-agent-server/src/main/java/com/cmagent/server/runtime/local/AddToolExecutor.java`
- Create: `cm-agent-server/src/main/java/com/cmagent/server/runtime/local/MysqlLocalExampleRegistrationConfiguration.java`
- Test: `cm-agent-server/src/test/java/com/cmagent/server/runtime/local/MysqlLocalExampleCatalogTest.java`
- Test: `cm-agent-server/src/test/java/com/cmagent/server/runtime/local/MysqlLocalExampleRegistrationConfigurationTest.java`

**Interfaces:**
- Consumes: `ToolDefinition`, `ToolExecutor`, `ToolExecutionRequest`, `ToolExecutionResult`, `ToolRegistry`。
- Produces:
  - `MysqlLocalExampleCatalog.list(): List<LocalExample>`
  - `MysqlLocalExampleCatalog.find(String): Optional<LocalExample>`
  - `LocalExample.persistentDefinition(String actor): ToolDefinition`
  - `LocalExample.executor(): ToolExecutor`
  - `LocalExample.sampleInput(): JsonNode`

- [ ] **Step 1: 写目录与执行器失败测试**

```java
@Test
void 目录只暴露固定echo和add且执行结果受控() {
    MysqlLocalExampleCatalog catalog = new MysqlLocalExampleCatalog(new ObjectMapper());

    assertThat(catalog.list()).extracting(MysqlLocalExampleCatalog.LocalExample::key)
            .containsExactly("echo", "add");
    assertThat(catalog.find("missing")).isEmpty();

    var echo = catalog.find("echo").orElseThrow();
    ToolExecutionResult echoResult = echo.executor().execute(
            new ToolExecutionRequest(echo.definition().id(), "{\"message\":\"你好\"}")
    );
    assertThat(echoResult.success()).isTrue();
    assertThat(echoResult.outputSummary()).isEqualTo("{\"message\":\"你好\"}");

    var add = catalog.find("add").orElseThrow();
    ToolExecutionResult addResult = add.executor().execute(
            new ToolExecutionRequest(add.definition().id(), "{\"left\":0.1,\"right\":0.2}")
    );
    assertThat(addResult.success()).isTrue();
    assertThat(addResult.outputSummary()).isEqualTo("{\"sum\":0.3}");
}

@ParameterizedTest
@ValueSource(strings = {
        "{}", "null", "[]", "{\"message\":\"\"}", "{\"message\":1}", "not-json"
})
void echo拒绝无效输入(String input) {
    var example = new MysqlLocalExampleCatalog(new ObjectMapper()).find("echo").orElseThrow();
    assertThat(example.executor().execute(new ToolExecutionRequest(example.definition().id(), input)).success())
            .isFalse();
}

@ParameterizedTest
@ValueSource(strings = {
        "{}", "null", "[]", "{\"left\":1}", "{\"left\":\"1\",\"right\":2}", "not-json"
})
void add拒绝无效输入(String input) {
    var example = new MysqlLocalExampleCatalog(new ObjectMapper()).find("add").orElseThrow();
    assertThat(example.executor().execute(new ToolExecutionRequest(example.definition().id(), input)).success())
            .isFalse();
}
```

- [ ] **Step 2: 写 Profile 注册失败测试**

```java
@Test
void mysql非生产Profile注册两个执行器但不触碰Repository() {
    try (AnnotationConfigApplicationContext context = context("mysql")) {
        ToolRegistry registry = context.getBean(ToolRegistry.class);
        assertThat(registry.snapshot(MysqlLocalExampleCatalog.ECHO_TOOL_ID)).isPresent();
        assertThat(registry.snapshot(MysqlLocalExampleCatalog.ADD_TOOL_ID)).isPresent();
        assertThat(context.getBeansOfType(ToolDefinitionRepository.class)).isEmpty();
    }
}

@ParameterizedTest
@ValueSource(strings = {"local", "test", "mysql,prod", "mysql,production", "mysql,supabase"})
void 其他或混合生产Profile不注册内置执行器(String profiles) {
    try (AnnotationConfigApplicationContext context = context(profiles.split(","))) {
        ToolRegistry registry = context.getBean(ToolRegistry.class);
        assertThat(registry.snapshot(MysqlLocalExampleCatalog.ECHO_TOOL_ID)).isEmpty();
        assertThat(registry.snapshot(MysqlLocalExampleCatalog.ADD_TOOL_ID)).isEmpty();
    }
}
```

测试辅助上下文只注册 `ObjectMapper`、`InMemoryToolRegistry`、目录和注册配置，避免加载 JDBC 或用户现有 MySQL 配置。

- [ ] **Step 3: 运行测试并确认 RED**

Run:

```powershell
mvn -pl cm-agent-server -am "-Dtest=MysqlLocalExampleCatalogTest,MysqlLocalExampleRegistrationConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: `testCompile` 因 `MysqlLocalExampleCatalog`、执行器和注册配置不存在而失败。

- [ ] **Step 4: 实现最小执行器**

`EchoToolExecutor.execute`：

```java
@Override
public ToolExecutionResult execute(ToolExecutionRequest request) {
    try {
        JsonNode input = objectMapper.readTree(request.inputJson());
        JsonNode message = input != null && input.isObject() ? input.get("message") : null;
        if (message == null || !message.isTextual() || message.textValue().isBlank()) {
            return ToolExecutionResult.failed("message 必须是非空字符串", null);
        }
        return ToolExecutionResult.succeeded(
                objectMapper.createObjectNode().put("message", message.textValue()).toString(),
                null
        );
    } catch (JsonProcessingException exception) {
        return ToolExecutionResult.failed("工具输入必须是合法 JSON 对象", null);
    }
}
```

`AddToolExecutor.execute`：

```java
@Override
public ToolExecutionResult execute(ToolExecutionRequest request) {
    try {
        JsonNode input = objectMapper.readTree(request.inputJson());
        JsonNode left = input != null && input.isObject() ? input.get("left") : null;
        JsonNode right = input != null && input.isObject() ? input.get("right") : null;
        if (left == null || right == null || !left.isNumber() || !right.isNumber()) {
            return ToolExecutionResult.failed("left 和 right 必须是数字", null);
        }
        BigDecimal sum = left.decimalValue().add(right.decimalValue());
        return ToolExecutionResult.succeeded(
                objectMapper.createObjectNode().put("sum", sum).toString(),
                null
        );
    } catch (JsonProcessingException exception) {
        return ToolExecutionResult.failed("工具输入必须是合法 JSON 对象", null);
    }
}
```

- [ ] **Step 5: 实现固定目录**

核心结构必须固定所有身份字段，且复制持久化定义时只能替换审计主体：

```java
@Component
public final class MysqlLocalExampleCatalog {
public static final UUID EXAMPLE_TENANT_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
public static final UUID ECHO_TOOL_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000101");
public static final UUID ADD_TOOL_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000102");

public record LocalExample(
        String key,
        ToolDefinition definition,
        JsonNode sampleInput,
        ToolExecutor executor
) {
    public ToolDefinition persistentDefinition(String actor) {
        return new ToolDefinition(
                definition.id(), definition.tenantId(), definition.name(), definition.description(),
                definition.type(), definition.inputSchema(), definition.riskLevel(), true,
                definition.endpoint(), actor, actor
        );
    }
}
}
```

使用不可变 `List.copyOf` 和按 key 构建的不可变 Map；`inputSchema` 必须使用设计文档中的完整 JSON Schema，`sampleInput` 必须使用 `JsonNode.deepCopy()` 返回，避免调用方修改共享节点。

- [ ] **Step 6: 实现 Profile 注册配置**

```java
@Configuration(proxyBeanMethods = false)
@Profile("mysql & !prod & !production & !supabase")
public class MysqlLocalExampleRegistrationConfiguration {

    @Bean
    InitializingBean registerMysqlLocalExamples(
            ToolRegistry registry,
            MysqlLocalExampleCatalog catalog
    ) {
        return () -> catalog.list().forEach(example ->
                registry.register(example.definition(), example.executor())
        );
    }
}
```

此配置不得注入 Repository、`DataSource` 或 `TransactionTemplate`。

- [ ] **Step 7: 运行测试并确认 GREEN**

```powershell
mvn -pl cm-agent-server -am "-Dtest=MysqlLocalExampleCatalogTest,MysqlLocalExampleRegistrationConfigurationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: 目录、输入校验、精确加法和所有 Profile 组合测试通过。

- [ ] **Step 8: 提交 Task 1**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/runtime/local cm-agent-server/src/test/java/com/cmagent/server/runtime/local
git diff --cached --check
git commit -m "feat: 添加 MySQL 内置 LOCAL 执行器"
```

---

### Task 2: 工具运行时就绪状态

**Files:**
- Create: `cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolRuntimeReadiness.java`
- Create: `cm-agent-server/src/test/java/com/cmagent/server/runtime/ToolRuntimeReadinessTest.java`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/service/ToolSummary.java:8-15`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/service/ToolQueryService.java:18-78`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java:241-253,336-354`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/service/ToolQueryServiceTest.java`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/web/ToolControllerTest.java`

**Interfaces:**
- Consumes: `ToolRegistry.snapshot(UUID)`, `ToolDefinition`, `HttpToolConfig`。
- Produces:
  - `ToolRuntimeReadiness.isReady(ToolDefinition, @Nullable HttpToolConfig): boolean`
  - `ToolSummary.runtimeReady(): boolean`
  - JSON 字段 `runtimeReady`

- [ ] **Step 1: 写就绪判断失败测试**

```java
@Test
void local只有注册身份完全匹配且启用时才就绪() {
    InMemoryToolRegistry registry = new InMemoryToolRegistry();
    ToolRuntimeReadiness readiness = new ToolRuntimeReadiness(registry);
    ToolDefinition stored = localTool(TOOL_ID, TENANT_ID, "echo", true);

    assertThat(readiness.isReady(stored, null)).isFalse();

    registry.register(stored, request -> ToolExecutionResult.succeeded("{}", null));
    assertThat(readiness.isReady(stored, null)).isTrue();
    assertThat(readiness.isReady(localTool(TOOL_ID, OTHER_TENANT_ID, "echo", true), null)).isFalse();
    assertThat(readiness.isReady(localTool(TOOL_ID, TENANT_ID, "renamed", true), null)).isFalse();
    assertThat(readiness.isReady(localTool(TOOL_ID, TENANT_ID, "echo", false), null)).isFalse();
}

@Test
void http要求配置身份和地址模板一致() {
    ToolRuntimeReadiness readiness = new ToolRuntimeReadiness(new InMemoryToolRegistry());
    ToolDefinition tool = httpTool(TOOL_ID, TENANT_ID, "https://api.example.test/orders", true);
    HttpToolConfig matching = httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/orders");

    assertThat(readiness.isReady(tool, matching)).isTrue();
    assertThat(readiness.isReady(tool, null)).isFalse();
    assertThat(readiness.isReady(tool, httpConfig(TOOL_ID, TENANT_ID, "https://api.example.test/other"))).isFalse();
}
```

- [ ] **Step 2: 扩展摘要测试并确认预期字段**

在 `ToolQueryServiceTest` 增加 `ToolRuntimeReadiness` mock：

```java
@Mock
private ToolRuntimeReadiness toolRuntimeReadiness;

when(toolRuntimeReadiness.isReady(httpTool, httpConfig)).thenReturn(true);
when(toolRuntimeReadiness.isReady(localTool, null)).thenReturn(false);

List<ToolSummary> summaries = new ToolQueryService(
        toolRepository, httpToolConfigRepository, mcpToolPublicationRepository, toolRuntimeReadiness
).listByTenant(TENANT_ID);

assertThat(summaries).extracting(ToolSummary::runtimeReady).containsExactly(true, false);
```

在 `ToolControllerTest` 的工具列表响应断言中加入：

```java
.andExpect(jsonPath("$[0].runtimeReady").value(true));
```

- [ ] **Step 3: 运行测试并确认 RED**

```powershell
mvn -pl cm-agent-server -am "-Dtest=ToolRuntimeReadinessTest,ToolQueryServiceTest,ToolControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: 新类型、构造参数、record 字段或 JSON 字段缺失导致编译或断言失败。

- [ ] **Step 4: 实现最小就绪判断**

```java
@Component
public class ToolRuntimeReadiness {
    private final ToolRegistry toolRegistry;

    public boolean isReady(ToolDefinition tool, @Nullable HttpToolConfig httpConfig) {
        if (tool == null || !tool.enabled()) {
            return false;
        }
        return switch (tool.type()) {
            case HTTP -> httpConfig != null
                    && tool.tenantId().equals(httpConfig.tenantId())
                    && tool.id().equals(httpConfig.toolId())
                    && Objects.equals(tool.endpoint(), httpConfig.urlTemplate());
            case LOCAL -> toolRegistry.snapshot(tool.id())
                    .map(ToolRegistry.ToolRegistrationSnapshot::definition)
                    .filter(registered -> tool.tenantId().equals(registered.tenantId()))
                    .filter(registered -> tool.id().equals(registered.id()))
                    .filter(registered -> tool.name().equals(registered.name()))
                    .isPresent();
            default -> false;
        };
    }
}
```

- [ ] **Step 5: 贯通 ToolSummary、查询服务和 Controller DTO**

`ToolSummary`：

```java
public record ToolSummary(
        ToolDefinition tool,
        HttpToolConfig httpConfig,
        boolean mcpPublished,
        boolean runtimeReady
) {
}
```

`ToolQueryService` 构造器新增 `ToolRuntimeReadiness`，创建摘要时调用：

```java
new ToolSummary(
        tool,
        httpConfig,
        publication != null && publication.enabled(),
        toolRuntimeReadiness.isReady(tool, httpConfig)
)
```

`ToolController.ToolSummaryResponse` 末尾增加 `boolean runtimeReady`，`toSummary` 传入 `summary.runtimeReady()`。这是只新增字段的向后兼容响应变化。

同步更新所有测试中的 `new ToolSummary(...)` 固定数据，使第四个参数明确表达预期就绪状态，避免只修目标断言而留下测试编译失败。

- [ ] **Step 6: 运行定向测试并确认 GREEN**

```powershell
mvn -pl cm-agent-server -am "-Dtest=ToolRuntimeReadinessTest,ToolQueryServiceTest,ToolControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: 所有就绪状态、批量查询和 Controller JSON 测试通过。

- [ ] **Step 7: 提交 Task 2**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolRuntimeReadiness.java cm-agent-server/src/main/java/com/cmagent/server/service/ToolSummary.java cm-agent-server/src/main/java/com/cmagent/server/service/ToolQueryService.java cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java cm-agent-server/src/test/java/com/cmagent/server/runtime/ToolRuntimeReadinessTest.java cm-agent-server/src/test/java/com/cmagent/server/service/ToolQueryServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/web/ToolControllerTest.java
git diff --cached --check
git commit -m "feat: 暴露工具运行时就绪状态"
```

---

### Task 3: 显式安装服务、API 与 JDBC 事务

**Files:**
- Create: `cm-agent-server/src/main/java/com/cmagent/server/service/LocalToolExampleSummary.java`
- Create: `cm-agent-server/src/main/java/com/cmagent/server/service/MysqlLocalExampleService.java`
- Create: `cm-agent-server/src/main/java/com/cmagent/server/web/LocalToolExampleController.java`
- Create: `cm-agent-server/src/test/java/com/cmagent/server/service/MysqlLocalExampleServiceTest.java`
- Create: `cm-agent-server/src/test/java/com/cmagent/server/service/MysqlLocalExampleServiceJdbcPersistenceTest.java`
- Create: `cm-agent-server/src/test/java/com/cmagent/server/web/LocalToolExampleControllerTest.java`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/service/ToolDebugServiceTest.java`

**Interfaces:**
- Consumes:
  - `MysqlLocalExampleCatalog.list()/find()`
  - `ToolDefinitionRepository`
  - `TransactionOperations`
  - `AuditAppender`
  - `ToolRuntimeReadiness`
- Produces:
  - `MysqlLocalExampleService.list(PrincipalRef): List<LocalToolExampleSummary>`
  - `MysqlLocalExampleService.install(PrincipalRef, String): LocalToolExampleSummary`
  - `GET /api/tools/local-examples`
  - `POST /api/tools/local-examples/{key}`

- [ ] **Step 1: 写安装服务失败测试**

使用内存 Repository 和立即执行的 `TransactionOperations` 测试：

```java
@Test
void 首次安装写入固定定义并审计且重复安装幂等() {
    LocalToolExampleSummary installed = service.install(PRINCIPAL, "echo");

    assertThat(installed.installed()).isTrue();
    assertThat(installed.runtimeReady()).isTrue();
    assertThat(repository.findByTenantAndId(TENANT_ID, MysqlLocalExampleCatalog.ECHO_TOOL_ID))
            .get().extracting(ToolDefinition::name).isEqualTo("echo");
    verify(auditAppender).append(
            TENANT_ID, "admin", "LOCAL_EXAMPLE_INSTALL", "TOOL",
            MysqlLocalExampleCatalog.ECHO_TOOL_ID.toString(), "SUCCEEDED", "内置 LOCAL 示例安装成功"
    );

    LocalToolExampleSummary repeated = service.install(PRINCIPAL, "echo");
    assertThat(repeated.toolId()).isEqualTo(installed.toolId());
    assertThat(repository.listByTenant(TENANT_ID)).hasSize(1);
    verifyNoMoreInteractions(auditAppender);
}

@Test
void 固定Id同名或定义漂移返回冲突且不覆盖() {
    repository.save(conflictingDefinition());

    assertThatThrownBy(() -> service.install(PRINCIPAL, "echo"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    verifyNoInteractions(auditAppender);
}

@Test
void 非示例租户未知Key和非允许状态返回空目录或404() {
    assertThat(service.list(OTHER_TENANT_PRINCIPAL)).isEmpty();
    assertThatThrownBy(() -> service.install(OTHER_TENANT_PRINCIPAL, "echo"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
    assertThatThrownBy(() -> service.install(PRINCIPAL, "missing"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
}
```

- [ ] **Step 2: 写 Controller 权限与空目录失败测试**

```java
@Test
void 未启用服务时目录为空且安装返回404() throws Exception {
    when(serviceProvider.getIfAvailable()).thenReturn(null);

    mvc.perform(get("/api/tools/local-examples").principal(authentication("tool:read")))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    mvc.perform(post("/api/tools/local-examples/echo").principal(authentication("tool:grant")))
            .andExpect(status().isNotFound());
}

@Test
void 查询和安装分别要求read与grant权限并记录拒绝审计() throws Exception {
    mvc.perform(get("/api/tools/local-examples").principal(authentication()))
            .andExpect(status().isForbidden());
    verify(auditAppender).accessDenied(
            any(PrincipalRef.class), eq("TOOL"), eq("local-examples"), eq("tool:read"), anyString()
    );

    mvc.perform(post("/api/tools/local-examples/echo").principal(authentication("tool:read")))
            .andExpect(status().isForbidden());
    verify(auditAppender).accessDenied(
            any(PrincipalRef.class), eq("TOOL"), eq("echo"), eq("tool:grant"), anyString()
    );
}

@Test
void 具备权限时返回目录并安装固定工具() throws Exception {
    when(serviceProvider.getIfAvailable()).thenReturn(service);
    when(service.list(any())).thenReturn(List.of(summary(false)));
    when(service.install(any(), eq("echo"))).thenReturn(summary(true));

    mvc.perform(get("/api/tools/local-examples").principal(authentication("tool:read")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].key").value("echo"))
            .andExpect(jsonPath("$[0].installed").value(false));
    mvc.perform(post("/api/tools/local-examples/echo").principal(authentication("tool:grant")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.installed").value(true));
}
```

- [ ] **Step 3: 写安装后现有调试链路失败测试**

在 `ToolDebugServiceTest` 使用实际 `MysqlLocalExampleCatalog`、`InMemoryToolRegistry` 和目录中的 `add` 定义：

```java
@Test
void 已安装内置add通过现有调试链路执行() {
    var example = new MysqlLocalExampleCatalog(objectMapper).find("add").orElseThrow();
    registry.register(example.definition(), example.executor());
    when(toolRepository.findByTenantAndId(TENANT_ID, example.definition().id()))
            .thenReturn(Optional.of(example.persistentDefinition("admin")));

    ToolDebugResponse response = service.debug(
            PRINCIPAL, example.definition().id(), "{\"left\":0.1,\"right\":0.2}", null
    );

    assertThat(response.success()).isTrue();
    assertThat(response.output()).isEqualTo("{\"sum\":0.3}");
}
```

- [ ] **Step 4: 运行非容器测试并确认 RED**

```powershell
mvn -pl cm-agent-server -am "-Dtest=MysqlLocalExampleServiceTest,LocalToolExampleControllerTest,ToolDebugServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: Service、摘要 record、Controller 和端点不存在，测试编译失败。

- [ ] **Step 5: 实现稳定响应模型**

```java
public record LocalToolExampleSummary(
        String key,
        UUID toolId,
        String name,
        String description,
        JsonNode inputSchema,
        JsonNode sampleInput,
        boolean installed,
        boolean runtimeReady
) {
    public LocalToolExampleSummary {
        inputSchema = inputSchema.deepCopy();
        sampleInput = sampleInput.deepCopy();
    }
}
```

- [ ] **Step 6: 实现安装服务**

```java
@Service
@Profile("mysql & !prod & !production & !supabase")
public class MysqlLocalExampleService {
    public List<LocalToolExampleSummary> list(PrincipalRef principal) {
        if (!MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID.equals(principal.tenantId())) {
            return List.of();
        }
        return catalog.list().stream().map(example -> summary(principal, example)).toList();
    }

    public LocalToolExampleSummary install(PrincipalRef principal, String key) {
        requireExampleTenant(principal);
        var example = catalog.find(key).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "内置 LOCAL 示例不存在"));
        ToolDefinition target = example.persistentDefinition(principal.principalId());
        ToolDefinition existing = toolRepository
                .findByTenantAndId(principal.tenantId(), target.id()).orElse(null);
        if (existing != null) {
            if (!sameManagedDefinition(existing, target)) {
                throw conflict();
            }
            return summary(principal, example);
        }
        boolean nameConflict = toolRepository.listByTenant(principal.tenantId()).stream()
                .anyMatch(tool -> tool.name().equals(target.name()) && !tool.id().equals(target.id()));
        if (nameConflict) {
            throw conflict();
        }
        try {
            transactionOperations.executeWithoutResult(status -> {
                toolRepository.save(target);
                auditAppender.append(
                        principal.tenantId(), principal.principalId(), "LOCAL_EXAMPLE_INSTALL", "TOOL",
                        target.id().toString(), "SUCCEEDED", "内置 LOCAL 示例安装成功"
                );
            });
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "内置 LOCAL 示例与现有工具冲突", exception);
        }
        return summary(principal, example);
    }
}
```

`sameManagedDefinition` 必须比较 ID、tenant、名称、描述、类型、完整 Schema、风险等级、启用状态和 endpoint；不得比较 `createdBy/updatedBy`。返回摘要时只有 Repository 中存在完全匹配定义才令 `installed=true`。

- [ ] **Step 7: 实现独立 Controller**

```java
@RestController
@RequestMapping("/api/tools/local-examples")
public class LocalToolExampleController {
    @GetMapping
    public List<LocalToolExampleSummary> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:read", "local-examples");
        MysqlLocalExampleService service = serviceProvider.getIfAvailable();
        return service == null ? List.of() : service.list(principal);
    }

    @PostMapping("/{key}")
    public LocalToolExampleSummary install(
            @PathVariable String key,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", key);
        MysqlLocalExampleService service = serviceProvider.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内置 LOCAL 示例不可用");
        }
        return service.install(principal, key);
    }
}
```

`principal` 必须只接受已认证的 `JwtService.JwtSession`；`authorize` 使用 `PermissionEvaluator`，拒绝时调用 `auditAppender.accessDenied`。

- [ ] **Step 8: 运行非容器测试并确认 GREEN**

```powershell
mvn -pl cm-agent-server -am "-Dtest=MysqlLocalExampleServiceTest,LocalToolExampleControllerTest,ToolDebugServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: 服务幂等、冲突、租户、权限、空目录和现有调试链路测试通过。

- [ ] **Step 9: 写 MySQL JDBC 事务失败测试**

`MysqlLocalExampleServiceJdbcPersistenceTest` 复用项目现有 Testcontainers 模式：

```java
@Testcontainers
class MysqlLocalExampleServiceJdbcPersistenceTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void mysql首次安装持久化且重建Registry后仍就绪() {
        DataSource dataSource = migratedAndSeededDataSource(mysql);
        TestFixture fixture = fixture(dataSource, false);
        fixture.service().install(PRINCIPAL, "echo");

        assertThat(fixture.tools().listByTenant(TENANT_ID))
                .extracting(ToolDefinition::id)
                .containsExactly(MysqlLocalExampleCatalog.ECHO_TOOL_ID);
        assertThat(fixture.auditEvents().listByTenant(TENANT_ID, 10))
                .extracting(AuditEvent::eventType)
                .containsExactly("LOCAL_EXAMPLE_INSTALL");

        TestFixture restarted = fixture(dataSource, false);
        assertThat(restarted.service().list(PRINCIPAL))
                .filteredOn(LocalToolExampleSummary::installed)
                .allMatch(LocalToolExampleSummary::runtimeReady);
    }

    @Test
    void mysql审计失败回滚工具定义() {
        DataSource dataSource = migratedAndSeededDataSource(mysql);
        TestFixture fixture = fixture(dataSource, true);

        assertThatThrownBy(() -> fixture.service().install(PRINCIPAL, "add"))
                .isInstanceOf(AuditPersistenceException.class);
        assertThat(fixture.tools().listByTenant(TENANT_ID)).isEmpty();
    }
}
```

`fixture` 必须对 MySQL 容器执行现有 Flyway 迁移、插入固定 tenant、创建 `JdbcToolDefinitionRepository`、`JdbcAuditEventRepository`、`TransactionTemplate`、目录、全新 `InMemoryToolRegistry` 并注册目录执行器。失败审计使用与 `ManagementCommandServiceJdbcPersistenceTest` 相同的事务内失败 Repository 模式。

- [ ] **Step 10: 提交 Task 3（暂不在本机运行容器测试）**

```powershell
git add -- cm-agent-server/src/main/java/com/cmagent/server/service/LocalToolExampleSummary.java cm-agent-server/src/main/java/com/cmagent/server/service/MysqlLocalExampleService.java cm-agent-server/src/main/java/com/cmagent/server/web/LocalToolExampleController.java cm-agent-server/src/test/java/com/cmagent/server/service/MysqlLocalExampleServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/service/MysqlLocalExampleServiceJdbcPersistenceTest.java cm-agent-server/src/test/java/com/cmagent/server/service/ToolDebugServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/web/LocalToolExampleControllerTest.java
git diff --cached --check
git commit -m "feat: 添加内置 LOCAL 工具安装接口"
```

---

### Task 4: 控制台安装、就绪展示与调用入口

**Files:**
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/index.html:105-170`
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js:47-96,199-210`
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js:1-494`
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`
- Modify: `cm-agent-console/src/test/js/console-core.test.cjs`
- Modify: `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`

**Interfaces:**
- Consumes:
  - `GET /api/tools/local-examples`
  - `POST /api/tools/local-examples/{key}`
  - `ToolSummaryResponse.runtimeReady`
  - 现有 `POST /api/tools/{id}/debug`
- Produces:
  - `core.canDebugTool(tool, confirmedToolName)`
  - `core.buildLocalExampleInstallPath(key)`
  - `core.formatJsonInput(value)`
  - 页面状态 `state.localExamples`

- [ ] **Step 1: 写核心逻辑失败测试**

保留工作区已有 HTTP Schema 测试，追加：

```javascript
test("LOCAL 工具只有运行时就绪后才能调试", () => {
    assert.equal(core.canDebugTool({
        type: "LOCAL", riskLevel: "LOW", name: "echo", runtimeReady: false
    }, ""), false);
    assert.equal(core.canDebugTool({
        type: "LOCAL", riskLevel: "LOW", name: "echo", runtimeReady: true
    }, ""), true);
});

test("内置示例安装路径编码key且示例输入格式化", () => {
    assert.equal(
        core.buildLocalExampleInstallPath("echo/add"),
        "/api/tools/local-examples/echo%2Fadd"
    );
    assert.equal(
        core.formatJsonInput({left: 0.1, right: 0.2}),
        "{\n  \"left\": 0.1,\n  \"right\": 0.2\n}"
    );
});
```

- [ ] **Step 2: 写资源契约失败测试**

在 `ConsoleResourceTest` 新增：

```java
@Test
void 控制台提供内置Local示例安装和运行时就绪提示() throws IOException {
    String html = resource("META-INF/resources/index.html");
    String core = resource("META-INF/resources/assets/console-core.js");
    String script = resource("META-INF/resources/assets/app.js");

    assertThat(html).contains(
            "id=\"localExampleSection\"", "id=\"localExampleList\"", "id=\"localExampleStatus\"",
            "普通 LOCAL 工具表单只保存治理元数据"
    );
    assertThat(core).contains(
            "buildLocalExampleInstallPath", "formatJsonInput", "runtimeReady"
    );
    assertThat(script).contains(
            "/api/tools/local-examples", "loadLocalExamples", "installLocalExample",
            "调用/调试", "未注册执行器"
    ).doesNotContain(".innerHTML");
}
```

- [ ] **Step 3: 运行测试并确认 RED**

```powershell
node --test cm-agent-console/src/test/js/console-core.test.cjs
mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: 新 helper、DOM ID、目录请求和状态文字缺失导致断言失败；已有 HTTP Schema 测试仍保持通过。

- [ ] **Step 4: 实现核心纯函数**

```javascript
function canDebugTool(tool, confirmedToolName) {
    if (!tool || (tool.type !== "HTTP" && tool.type !== "LOCAL") || tool.runtimeReady !== true) {
        return false;
    }
    return tool.riskLevel !== "HIGH" || confirmedToolName === tool.name;
}

function buildLocalExampleInstallPath(key) {
    return `/api/tools/local-examples/${encodeURIComponent(String(key || ""))}`;
}

function formatJsonInput(value) {
    return JSON.stringify(value ?? {}, null, 2);
}
```

把三个函数加入 `console-core.js` 的公开返回对象。不要改回用户当前已经修复的 HTTP Schema 默认值和校验。

- [ ] **Step 5: 添加页面结构与样式**

在工具治理页通用注册表单附近加入：

```html
<section id="localExampleSection" class="panel" hidden>
    <div class="section-heading">
        <div>
            <p class="eyebrow">MySQL 调试能力</p>
            <h3>内置 LOCAL 示例</h3>
        </div>
    </div>
    <p class="field-help">这里只安装项目内置的 echo/add；普通 LOCAL 工具表单只保存治理元数据，执行器必须由 Server 中的 Java 代码注册。</p>
    <div id="localExampleList" class="local-example-list"></div>
    <p id="localExampleStatus" class="form-status" role="status"></p>
</section>
```

样式只增加 `.local-example-list`、`.local-example-card`、`.runtime-ready`、`.runtime-unavailable` 和窄屏布局，不改现有整体视觉体系。

- [ ] **Step 6: 实现目录加载与安装**

状态：

```javascript
const state = {
    // 保留原字段
    localExamples: []
};
```

加载和渲染：

```javascript
async function loadLocalExamples() {
    try {
        const examples = await api.request("/api/tools/local-examples");
        state.localExamples = Array.isArray(examples) ? examples : [];
        renderLocalExamples();
    } catch (error) {
        state.localExamples = [];
        $("localExampleSection").hidden = false;
        $("localExampleList").replaceChildren(emptyState("内置 LOCAL 示例目录加载失败。"));
        setStatus($("localExampleStatus"), error.message, "error");
    }
}

function renderLocalExamples() {
    const section = $("localExampleSection");
    const container = $("localExampleList");
    section.hidden = state.localExamples.length === 0;
    container.replaceChildren();
    state.localExamples.forEach((example) => {
        const card = element("article", {className: "local-example-card"});
        card.append(element("strong", {text: example.name}));
        card.append(element("span", {text: example.description}));
        card.append(element("span", {
            text: example.installed
                ? (example.runtimeReady ? "已安装 · 运行时已就绪" : "已安装 · 未注册执行器")
                : "未安装"
        }));
        const button = element("button", {
            className: "button",
            type: "button",
            text: example.installed ? "已安装" : "添加示例工具"
        });
        button.disabled = Boolean(example.installed);
        button.addEventListener("click", () => installLocalExample(example, button));
        card.append(button);
        container.append(card);
    });
}
```

安装：

```javascript
async function installLocalExample(example, button) {
    await withSubmitState(button, async () => {
        const installed = await api.request(core.buildLocalExampleInstallPath(example.key), {
            method: "POST"
        });
        state.selectedToolId = installed.toolId;
        await Promise.all([loadLocalExamples(), loadTools()]);
        $("debugToolSelect").value = installed.toolId;
        $("debugInput").value = core.formatJsonInput(installed.sampleInput);
        $("debugToolForm").scrollIntoView({behavior: "smooth", block: "start"});
        $("debugInput").focus();
        setStatus($("localExampleStatus"), `示例“${installed.name}”已安装，可调用调试。`, "success");
    });
}
```

为安装操作增加按 `key` 的重复点击锁；写请求完成后再刷新目录和工具列表，避免旧响应覆盖新状态。

- [ ] **Step 7: 修复工具列表和调试入口**

`renderTools` 展示：

```javascript
if (tool.type === "LOCAL") {
    card.append(element("span", {
        className: tool.runtimeReady ? "runtime-ready" : "runtime-unavailable",
        text: tool.runtimeReady ? "运行时已就绪" : "未注册执行器"
    }));
}
```

只有 `core.canDebugTool(tool, "")` 可判断为普通低风险可调试，或工具为 HIGH 且 `runtimeReady=true` 时，才创建“调用/调试”按钮。按钮只负责：

```javascript
state.selectedToolId = tool.id;
$("debugToolSelect").value = tool.id;
$("debugToolForm").scrollIntoView({behavior: "smooth", block: "start"});
$("debugInput").focus();
```

`updateDebugToolOptions` 只加入 `tool.runtimeReady === true` 且类型为 HTTP/LOCAL 的工具。`debugTool()` 继续调用现有 API，并继续执行 HIGH 名称精确确认。

- [ ] **Step 8: 运行控制台测试并确认 GREEN**

```powershell
node --test cm-agent-console/src/test/js/console-core.test.cjs
mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: Node 测试和控制台资源测试全部通过，输出无语法错误。

- [ ] **Step 9: 只暂存本任务新增 hunk 并提交**

这些文件已有用户修改，不能直接整文件暂存。交互式检查每个 hunk，只选择内置 LOCAL 功能相关 hunk：

```powershell
git add -p -- cm-agent-console/src/main/resources/META-INF/resources/index.html cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js cm-agent-console/src/test/js/console-core.test.cjs
git add -- cm-agent-console/src/main/resources/META-INF/resources/assets/app.js cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java
git diff --cached --check
git diff --cached
git commit -m "feat: 支持控制台安装和调试 LOCAL 示例"
```

`git diff --cached` 必须确认不包含任务开始前已有的 HTTP Schema 修改。

---

### Task 5: 文档、全量回归与 Rocky MySQL 验证

**Files:**
- Modify: `README.md`
- Modify: `docs/tool-development-guide.md`
- Modify: `docs/release-notes.md`

**Interfaces:**
- Consumes: Task 1—4 的最终 API 和页面行为。
- Produces: 可复制的 MySQL profile 使用说明、正式 LOCAL 工具边界说明和发布记录。

- [ ] **Step 1: 更新中文生产文档**

`README.md` 在工具调试说明后增加以下信息：

```markdown
在非生产 `mysql` profile 下，工具治理页会展示固定的 `echo`、`add` 内置 LOCAL 示例。管理员点击“添加示例工具”后才会把定义写入 MySQL；安装成功后页面会自动填入示例输入并可通过现有调试入口调用。服务启动只注册固定 Java 执行器，不会自动写入数据库。

该入口不支持上传或编写执行代码。正式业务 LOCAL 工具仍需在同一 Server JVM 中实现并注册 `ToolExecutor`；页面中的“运行时已就绪”只表示当前注册快照，实际调用仍会重新执行治理校验。
```

`docs/tool-development-guide.md` 在“接入正式 CM Agent Server”后新增“通过 MySQL profile 安装内置示例”，包含：

```json
{"message":"你好，CM Agent"}
```

```json
{"left":0.1,"right":0.2}
```

并明确固定目录、profile 限制、权限 `tool:read/tool:grant/tool:debug`、重启行为、冲突返回 `409`、普通 LOCAL 工具仍需 Java 注册。

`docs/release-notes.md` 只追加本功能 hunk，不改写用户当前已有内容。

- [ ] **Step 2: 运行本机环境与非容器回归**

```powershell
java -version
mvn -v
node --test cm-agent-console/src/test/js/console-core.test.cjs
mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl cm-agent-server -am "-Dtest=MysqlLocalExampleCatalogTest,MysqlLocalExampleRegistrationConfigurationTest,ToolRuntimeReadinessTest,ToolQueryServiceTest,MysqlLocalExampleServiceTest,LocalToolExampleControllerTest,ToolDebugServiceTest,ToolControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q "-DskipTests" package
git diff --check
```

Expected:

- Java 和 Maven 均显示 JDK 21。
- Node、Console、Server 非容器定向测试全部通过。
- 多模块跳过测试打包退出码为 0。
- `git diff --check` 无空白错误。

- [ ] **Step 3: 将当前提交同步到 Rocky 独立验证工作区**

在本地 PowerShell 中：

```powershell
$validationBundle = Join-Path $env:TEMP "cm-agent-mysql-local-tools-validation.bundle"
git bundle create $validationBundle HEAD
scp $validationBundle rocky:/root/cm-agent-mysql-local-tools-validation.bundle
git rev-parse HEAD
```

远程创建独立 worktree，不能覆盖现有 `/root/cm-agent-dynamic-http-mcp-tools`：

```powershell
ssh rocky "test ! -e /root/cm-agent-mysql-local-tools-validation && git -C /root/cm-agent-dynamic-http-mcp-tools fetch /root/cm-agent-mysql-local-tools-validation.bundle HEAD && git -C /root/cm-agent-dynamic-http-mcp-tools worktree add --detach /root/cm-agent-mysql-local-tools-validation FETCH_HEAD && git -C /root/cm-agent-mysql-local-tools-validation rev-parse HEAD && git -C /root/cm-agent-mysql-local-tools-validation status --short"
```

Expected: 本地与远程 `HEAD` 完全一致，远程状态为空。若目标目录已存在，停止并先只读检查，不执行递归删除。

- [ ] **Step 4: 在 Rocky 容器确认 Docker、Maven 和 JDK**

```powershell
ssh rocky "docker version --format '{{.Server.Version}}' && docker run --rm maven:3.9.9-eclipse-temurin-21 mvn -v"
```

Expected: Docker Server 可用；Maven 为 3.9.9；Java 为 21。

- [ ] **Step 5: 在 Rocky 运行 MySQL/JDBC 定向测试**

```powershell
ssh rocky "docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /root/.m2:/root/.m2 -v /root/.testcontainers.properties:/root/.testcontainers.properties:ro -v /root/cm-agent-mysql-local-tools-validation:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -pl cm-agent-server -am -Dtest=MysqlLocalExampleServiceJdbcPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test"
```

Expected: MySQL 8.4 容器启动，首次安装、重启后就绪和审计回滚测试全部通过。

- [ ] **Step 6: 在 Rocky 运行全量测试**

```powershell
ssh rocky "docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /root/.m2:/root/.m2 -v /root/.testcontainers.properties:/root/.testcontainers.properties:ro -v /root/cm-agent-mysql-local-tools-validation:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q test"
```

Expected: 全部 Maven 模块测试通过，Testcontainers PostgreSQL 16-alpine 和 MySQL 8.4 测试无失败。

- [ ] **Step 7: 执行敏感信息与范围检查**

```powershell
git diff 4d3fccf..HEAD -- README.md docs/tool-development-guide.md docs/release-notes.md cm-agent-server cm-agent-console | rg 'sk-[A-Za-z0-9]{16,}|jdbc:(mysql|postgresql)://|password\s*[:=]\s*[^<$]'
git status --short
git diff --stat 4d3fccf..HEAD
```

Expected: 敏感信息扫描无输出；状态中只保留任务开始前的用户修改和有意未暂存 hunk。

- [ ] **Step 8: 只暂存文档新增 hunk并提交**

`README.md`、`docs/tool-development-guide.md` 可整文件暂存；`docs/release-notes.md` 已有用户修改，必须按 hunk 暂存：

```powershell
git add -- README.md docs/tool-development-guide.md
git add -p -- docs/release-notes.md
git diff --cached --check
git diff --cached
git commit -m "docs: 补充 MySQL LOCAL 示例使用说明"
```

提交后生成 `/root/cm-agent-mysql-local-tools-final-validation.bundle`，并使用新的 `/root/cm-agent-mysql-local-tools-final-validation` worktree 重复 Step 3—6，确保最终文档提交对应的远程 `HEAD` 与本地一致；若目标目录已存在则停止并只读检查，不能递归删除。即使只改变 Markdown，也必须让最终 `mvn -q test` 对应最终提交。

- [ ] **Step 9: 最终完成前验证**

```powershell
java -version
mvn -v
node --test cm-agent-console/src/test/js/console-core.test.cjs
mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl cm-agent-server -am "-Dtest=MysqlLocalExampleCatalogTest,MysqlLocalExampleRegistrationConfigurationTest,ToolRuntimeReadinessTest,ToolQueryServiceTest,MysqlLocalExampleServiceTest,LocalToolExampleControllerTest,ToolDebugServiceTest,ToolControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q "-DskipTests" package
git diff --check
git status --short
```

同时读取最后一次 Rocky 全量测试输出，确认 `mvn -q test` 退出码为 0。没有新鲜的本机和 Rocky 证据时，不得声称功能完成。

---

## 验收映射

- 页面显式安装、不在启动写库：Task 1、Task 3、Task 4。
- 固定 `echo/add` 与精确输入输出：Task 1。
- MySQL 且非生产 profile：Task 1、Task 3。
- 权限、租户、严格审计和事务回滚：Task 3、Task 5。
- 重启后仍可调试：Task 1 固定注册、Task 3 JDBC 重建测试。
- 普通 LOCAL 工具就绪状态：Task 2、Task 4。
- 复用现有调试治理链：Task 3 `ToolDebugServiceTest`、Task 4。
- 不支持动态代码和正式工具边界：Global Constraints、Task 5 文档。
- 无数据库迁移：File Map 与所有任务均不创建迁移。
- 保留用户现有修改：Global Constraints、Task 4/5 的 hunk 暂存步骤。
