# LOCAL 与 HTTP 工具开发指南及示例工程实施计划

> **供执行者使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐项执行本计划；所有步骤使用复选框（`- [ ]`）跟踪。

**Goal:** 提供包含 `echo`、精确加法 `add` 的 LOCAL 示例、通过公开 REST API 创建和调试工具的 HTTP 示例，以及可直接指导开发者接入的中文文档。

**Architecture:** LOCAL 示例使用 Starter 提供的 `ToolRegistry`，将工具定义、执行器、注册与演示调用分离；HTTP 示例是独立的无 Web 服务 Spring Boot 客户端，只通过 `/api/tools` 和 `/api/tools/{id}/debug` 接入 Server。生产模块、数据库和现有 API 均不改动。

**Tech Stack:** Java 21、Maven 3.9+、Spring Boot 3.5.0、Jackson、Spring `RestClient`、JUnit Jupiter 5.12.2、AssertJ 3.27.3、Spring MockRestServiceServer。

## Global Constraints

- 所有新增或修改的注释、JavaDoc、README、docs、测试说明与提交说明使用中文。
- 保持包名在 `com.cmagent` 下，示例代码按定义、执行器、注册器、客户端和配置职责拆分。
- 不修改 `cm-agent-core`、`cm-agent-server`、`cm-agent-persistence`、Flyway 迁移或生产配置。
- 不保存或输出真实 JWT、数据库密码、模型 API Key、Secret 实际值或完整生产 JDBC URL。
- HTTP 示例不得关闭或绕过 Host 白名单、SSRF、协议、DNS、重定向、超时和响应大小限制。
- 保留工作区中与本任务无关的现有修改，不将其暂存或提交。
- 本次不涉及 Docker、Docker Compose、Testcontainers、JDBC Repository 或 Flyway，不需要 Rocky Linux 容器验证。

---

## 文件结构

### 修改

- `cm-agent-examples/pom.xml`：聚合新的 HTTP 示例模块。
- `cm-agent-examples/starter-local-tool/pom.xml`：增加 Jackson 和测试依赖。
- `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/LocalToolExampleApplication.java`：只保留启动入口和示例调用。
- `README.md`：增加工具开发指南入口。
- `docs/release-notes.md`：记录新增开发指南与示例。

### 新增

- `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local/LocalToolDefinitions.java`：集中提供 `echo`、`add` 定义和固定示例标识。
- `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local/EchoToolExecutor.java`：回显业务逻辑。
- `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local/AddToolExecutor.java`：`BigDecimal` 精确加法业务逻辑。
- `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local/LocalToolRegistration.java`：注册两个工具。
- `cm-agent-examples/starter-local-tool/src/test/java/com/cmagent/examples/local/EchoToolExecutorTest.java`：回显执行器测试。
- `cm-agent-examples/starter-local-tool/src/test/java/com/cmagent/examples/local/AddToolExecutorTest.java`：加法执行器测试。
- `cm-agent-examples/starter-local-tool/src/test/java/com/cmagent/examples/LocalToolExampleApplicationTest.java`：Spring 上下文与双工具注册测试。
- `cm-agent-examples/http-tool-client/pom.xml`：HTTP 示例模块依赖。
- `cm-agent-examples/http-tool-client/src/main/resources/application.yml`：关闭 Web Server 并提供安全默认值。
- `cm-agent-examples/http-tool-client/src/main/java/com/cmagent/examples/http/HttpToolExampleApplication.java`：HTTP 示例启动入口。
- `cm-agent-examples/http-tool-client/src/main/java/com/cmagent/examples/http/HttpToolExampleProperties.java`：外部运行参数。
- `cm-agent-examples/http-tool-client/src/main/java/com/cmagent/examples/http/CmAgentToolClient.java`：创建与调试 REST 调用。
- `cm-agent-examples/http-tool-client/src/main/java/com/cmagent/examples/http/HttpToolExampleRunner.java`：按开关执行示例。
- `cm-agent-examples/http-tool-client/src/test/java/com/cmagent/examples/http/CmAgentToolClientTest.java`：请求和错误语义测试。
- `cm-agent-examples/http-tool-client/src/test/java/com/cmagent/examples/http/HttpToolExampleRunnerTest.java`：禁用和配置校验测试。
- `docs/tool-development-guide.md`：LOCAL 与 HTTP 工具开发指南。

---

### Task 1: LOCAL 多工具示例

**Files:**
- Modify: `cm-agent-examples/starter-local-tool/pom.xml`
- Modify: `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/LocalToolExampleApplication.java`
- Create: `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local/LocalToolDefinitions.java`
- Create: `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local/EchoToolExecutor.java`
- Create: `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local/AddToolExecutor.java`
- Create: `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local/LocalToolRegistration.java`
- Test: `cm-agent-examples/starter-local-tool/src/test/java/com/cmagent/examples/local/EchoToolExecutorTest.java`
- Test: `cm-agent-examples/starter-local-tool/src/test/java/com/cmagent/examples/local/AddToolExecutorTest.java`
- Test: `cm-agent-examples/starter-local-tool/src/test/java/com/cmagent/examples/LocalToolExampleApplicationTest.java`

**Interfaces:**
- Produces: `LocalToolDefinitions.echo()` 与 `LocalToolDefinitions.add()`，均返回 `ToolDefinition`。
- Produces: `EchoToolExecutor implements ToolExecutor`。
- Produces: `AddToolExecutor implements ToolExecutor`，输入 `{"left":number,"right":number}`，成功输出 `{"sum":number}`。
- Produces: `LocalToolRegistration.register(ToolRegistry)`，注册两个定义与对应执行器。

- [ ] **Step 1: 增加测试依赖并写执行器失败测试**

在 POM 中增加 Jackson 与 Spring Boot 测试依赖。创建测试并覆盖精确小数、负数和无效输入：

```java
@Test
void shouldAddDecimalsExactly() throws Exception {
    ToolExecutionResult result = executor.execute(request("""
            {"left":0.1,"right":0.2}
            """));

    assertThat(result.success()).isTrue();
    assertThat(objectMapper.readTree(result.outputSummary()).path("sum").decimalValue())
            .isEqualByComparingTo("0.3");
}

@Test
void shouldRejectMissingOperand() {
    ToolExecutionResult result = executor.execute(request("""
            {"left":1}
            """));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).isEqualTo("left 和 right 必须是数字");
}
```

`EchoToolExecutorTest` 同时覆盖合法 `message`、非法 JSON、字段缺失和非字符串字段。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
mvn -pl cm-agent-examples/starter-local-tool -am "-Dtest=EchoToolExecutorTest,AddToolExecutorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL，原因是执行器类尚不存在。

- [ ] **Step 3: 实现两个独立执行器**

执行器使用构造器注入 `ObjectMapper`，只返回受控错误，不回显原始输入：

```java
public final class AddToolExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;

    public AddToolExecutor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        try {
            JsonNode input = objectMapper.readTree(request.inputJson());
            if (input == null || !input.isObject()
                    || !input.path("left").isNumber() || !input.path("right").isNumber()) {
                return ToolExecutionResult.failed("left 和 right 必须是数字", null);
            }
            BigDecimal sum = input.path("left").decimalValue().add(input.path("right").decimalValue());
            ObjectNode output = objectMapper.createObjectNode().put("sum", sum);
            return ToolExecutionResult.succeeded(objectMapper.writeValueAsString(output), null);
        } catch (JsonProcessingException exception) {
            return ToolExecutionResult.failed("工具输入必须是合法 JSON 对象", null);
        }
    }
}
```

`EchoToolExecutor` 使用相同模式读取非空字符串 `message`，成功输出 `{"message":"..."}`。

- [ ] **Step 4: 运行执行器测试确认通过**

Run:

```powershell
mvn -pl cm-agent-examples/starter-local-tool -am "-Dtest=EchoToolExecutorTest,AddToolExecutorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: PASS。

- [ ] **Step 5: 写双工具注册失败测试**

测试启动 Spring 上下文后，使用固定 ID 查询两个工具并执行：

```java
@SpringBootTest(properties = "spring.main.web-application-type=none")
class LocalToolExampleApplicationTest {
    @Autowired
    private ToolRegistry registry;

    @Test
    void shouldRegisterEchoAndAddTools() {
        assertThat(registry.find(LocalToolDefinitions.ECHO_TOOL_ID)).isPresent();
        assertThat(registry.find(LocalToolDefinitions.ADD_TOOL_ID)).isPresent();
        ToolExecutionResult result = registry.execute(new ToolExecutionRequest(
                LocalToolDefinitions.ADD_TOOL_ID, "{\"left\":2,\"right\":3}"
        ));
        assertThat(result.success()).isTrue();
        assertThat(result.outputSummary()).isEqualTo("{\"sum\":5}");
    }
}
```

- [ ] **Step 6: 运行注册测试确认失败**

Run:

```powershell
mvn -pl cm-agent-examples/starter-local-tool -am -Dtest=LocalToolExampleApplicationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是第二个工具尚未注册。

- [ ] **Step 7: 实现定义、注册与启动调用**

`LocalToolDefinitions` 使用同一示例 tenant，并为两个工具使用不同固定 ID；`add` Schema 必须声明 `left`、`right` 为必填 number：

```java
public static ToolDefinition add() {
    return new ToolDefinition(
            ADD_TOOL_ID,
            EXAMPLE_TENANT_ID,
            "add",
            "对两个数字执行精确加法",
            ToolType.LOCAL,
            """
            {"type":"object","properties":{"left":{"type":"number"},"right":{"type":"number"}},"required":["left","right"],"additionalProperties":false}
            """.strip(),
            ToolRiskLevel.LOW,
            true,
            "",
            "example",
            "example"
    );
}
```

`LocalToolRegistration` 注册 `echo` 和 `add`；应用启动任务分别执行一次示例调用并仅输出工具名称、成功状态和结果。

- [ ] **Step 8: 运行 LOCAL 模块全部测试**

Run:

```powershell
mvn -pl cm-agent-examples/starter-local-tool -am test
```

Expected: PASS。

- [ ] **Step 9: 提交 LOCAL 示例**

```powershell
git add -- cm-agent-examples/starter-local-tool
git commit -m "feat: 完善 LOCAL 多工具示例"
```

---

### Task 2: HTTP 工具 REST 客户端示例

**Files:**
- Modify: `cm-agent-examples/pom.xml`
- Create: `cm-agent-examples/http-tool-client/pom.xml`
- Create: `cm-agent-examples/http-tool-client/src/main/resources/application.yml`
- Create: `cm-agent-examples/http-tool-client/src/main/java/com/cmagent/examples/http/HttpToolExampleApplication.java`
- Create: `cm-agent-examples/http-tool-client/src/main/java/com/cmagent/examples/http/HttpToolExampleProperties.java`
- Create: `cm-agent-examples/http-tool-client/src/main/java/com/cmagent/examples/http/CmAgentToolClient.java`
- Create: `cm-agent-examples/http-tool-client/src/main/java/com/cmagent/examples/http/HttpToolExampleRunner.java`
- Test: `cm-agent-examples/http-tool-client/src/test/java/com/cmagent/examples/http/CmAgentToolClientTest.java`
- Test: `cm-agent-examples/http-tool-client/src/test/java/com/cmagent/examples/http/HttpToolExampleRunnerTest.java`

**Interfaces:**
- Produces: `HttpToolExampleProperties`，属性为 `enabled`、`baseUrl`、`jwt`、`toolName`、`targetUrl`、`secretHeaderName`、`secretRef`、`message`。
- Produces: `CmAgentToolClient.createAndDebug()`，返回 `ExampleResult(UUID toolId, JsonNode debugResponse)`。
- Produces: `HttpToolExampleRunner.run(ApplicationArguments)`，禁用时不发请求，启用时先校验配置。

- [ ] **Step 1: 创建模块骨架并写客户端失败测试**

POM 使用 `spring-boot-starter`、`spring-web`、`spring-boot-starter-json` 与测试依赖。使用 `MockRestServiceServer` 绑定 `RestClient.Builder`：

```java
mockServer.expect(requestTo("http://localhost:8080/api/tools"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-jwt"))
        .andExpect(jsonPath("$.type").value("HTTP"))
        .andExpect(jsonPath("$.httpConfig.parameterMappings[0].location").value("BODY"))
        .andRespond(withSuccess("""
                {"id":"00000000-0000-0000-0000-000000000201"}
                """, MediaType.APPLICATION_JSON));

mockServer.expect(requestTo(
                "http://localhost:8080/api/tools/00000000-0000-0000-0000-000000000201/debug"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.input.message").value("你好，CM Agent"))
        .andRespond(withSuccess("""
                {"success":true,"output":"ok","errorMessage":"","durationMillis":5}
                """, MediaType.APPLICATION_JSON));
```

- [ ] **Step 2: 运行客户端测试确认失败**

Run:

```powershell
mvn -pl cm-agent-examples/http-tool-client -am -Dtest=CmAgentToolClientTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是模块或客户端类尚不存在。

- [ ] **Step 3: 实现属性与创建/调试客户端**

`createAndDebug()` 先创建工具，再使用返回 ID 调试。创建请求固定使用 POST/BODY 主流程：

```java
ObjectNode mapping = objectMapper.createObjectNode()
        .put("sourcePointer", "/message")
        .put("location", "BODY")
        .put("targetName", "")
        .put("targetPointer", "/message")
        .put("required", true);
ObjectNode httpConfig = objectMapper.createObjectNode()
        .put("method", "POST")
        .put("urlTemplate", properties.getTargetUrl())
        .set("inputSchema", inputSchema());
httpConfig.set("parameterMappings", objectMapper.createArrayNode().add(mapping));
httpConfig.set("secretHeaders", secretHeaders());
httpConfig.put("timeoutMillis", 5000);
```

两个请求都使用相同的 Bearer JWT。捕获 `RestClientResponseException` 后，只报告状态码和经过长度限制的响应摘要，不把 JWT 写入异常消息。

`HttpToolExampleApplication` 使用 `@EnableConfigurationProperties(HttpToolExampleProperties.class)` 注册配置；`CmAgentToolClient` 的构造器接收 `RestClient.Builder`、`ObjectMapper` 和 `HttpToolExampleProperties`，不在类内读取环境变量。

- [ ] **Step 4: 运行客户端测试确认通过**

Run:

```powershell
mvn -pl cm-agent-examples/http-tool-client -am -Dtest=CmAgentToolClientTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS。

- [ ] **Step 5: 写 Runner 配置校验失败测试**

覆盖禁用时不调用客户端、启用但缺 JWT、缺目标 URL、Secret Header 与引用只提供一项：

```java
@Test
void shouldNotCallClientWhenDisabled() throws Exception {
    properties.setEnabled(false);
    runner.run(new DefaultApplicationArguments());
    verifyNoInteractions(client);
}

@Test
void shouldRejectIncompleteSecretConfiguration() {
    properties.setEnabled(true);
    properties.setJwt("test-jwt");
    properties.setTargetUrl("https://api.example.test/messages");
    properties.setSecretHeaderName("X-Api-Key");

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Secret Header 名称和引用必须同时提供");
}
```

- [ ] **Step 6: 实现 Runner、应用入口与安全默认配置**

`application.yml`：

```yaml
spring:
  main:
    web-application-type: none

example:
  http-tool:
    enabled: ${CM_AGENT_HTTP_EXAMPLE_ENABLED:false}
    base-url: ${CM_AGENT_BASE_URL:http://localhost:8080}
    jwt: ${CM_AGENT_JWT:}
    tool-name: ${CM_AGENT_HTTP_TOOL_NAME:developer-http-example}
    target-url: ${CM_AGENT_HTTP_TARGET_URL:}
    secret-header-name: ${CM_AGENT_HTTP_SECRET_HEADER_NAME:}
    secret-ref: ${CM_AGENT_HTTP_SECRET_REF:}
    message: ${CM_AGENT_HTTP_MESSAGE:你好，CM Agent}
```

Runner 只在 `enabled=true` 时校验并调用客户端；输出仅包含工具 ID、成功状态和脱敏后的调试响应。

- [ ] **Step 7: 运行 HTTP 示例全部测试**

Run:

```powershell
mvn -pl cm-agent-examples/http-tool-client -am test
```

Expected: PASS，且测试不访问公网。

- [ ] **Step 8: 提交 HTTP 示例**

```powershell
git add -- cm-agent-examples/pom.xml cm-agent-examples/http-tool-client
git commit -m "feat: 添加 HTTP 工具客户端示例"
```

---

### Task 3: 工具开发指南

**Files:**
- Create: `docs/tool-development-guide.md`

**Interfaces:**
- Consumes: Task 1 的 `echo`、`add` 定义与执行器。
- Consumes: Task 2 的配置键、创建请求与调试流程。
- Produces: 面向开发者的 LOCAL/HTTP 唯一入口文档。

- [ ] **Step 1: 编写指南主体**

文档按以下固定章节编写：

```markdown
# LOCAL 与 HTTP 工具开发指南
## 1. 如何选择工具类型
## 2. 公共领域模型与安全边界
## 3. 创建 LOCAL 工具
## 4. 在一个应用中注册 echo 与 add
## 5. 创建 HTTP 工具
## 6. PATH、QUERY、HEADER、BODY 映射
## 7. 调试、Agent 授权与可选 MCP 发布
## 8. 常见错误
## 9. 构建和运行示例
```

LOCAL 章节必须展示 `ToolDefinition`、`ToolExecutor`、`ToolRegistry.register`，并明确正式 Server 中 ID、tenant、名称和授权必须一致。HTTP 章节必须给出完整创建 JSON、调试 JSON、GET/POST 约束、JSON Pointer 规则、Secret 引用和 Server 前置配置。

- [ ] **Step 2: 核对代码与文档一致性**

逐项核对：

- 文档中的类名、包名、固定工具名称与 Task 1 一致。
- 文档中的配置键、环境变量、API 路径、请求字段与 Task 2 和 `ToolController` 一致。
- 不把 `MCP` 描述为本次第三种可创建工具。
- 不提供关闭安全策略或保存 Secret 实际值的示例。

- [ ] **Step 3: 检查文档格式**

Run:

```powershell
git diff --check -- docs/tool-development-guide.md
```

Expected: 无输出，退出码 0。

- [ ] **Step 4: 提交开发指南**

```powershell
git add -- docs/tool-development-guide.md
git commit -m "docs: 添加 LOCAL 与 HTTP 工具开发指南"
```

---

### Task 4: 文档入口、发布说明与最终验证

**Files:**
- Modify: `README.md`
- Modify: `docs/release-notes.md`

**Interfaces:**
- Consumes: `docs/tool-development-guide.md`。
- Produces: 根文档入口与本次变更记录。

- [ ] **Step 1: 更新 README 与发布说明**

在 `README.md` 的“生产文档”列表增加：

```markdown
- [工具开发指南](docs/tool-development-guide.md)
```

在 `docs/release-notes.md` 的“本次变更”中增加一项，说明新增 LOCAL `echo`/`add` 与 HTTP 客户端示例，不声明生产 API 或数据库变化。

- [ ] **Step 2: 确认 Java 与 Maven 环境**

Run:

```powershell
java -version
mvn -v
```

Expected: Java 21，Maven 3.9+ 且 Maven 使用 JDK 21。

- [ ] **Step 3: 运行两个示例模块测试**

Run:

```powershell
mvn -pl "cm-agent-examples/starter-local-tool,cm-agent-examples/http-tool-client" -am test
```

Expected: PASS。

- [ ] **Step 4: 运行示例聚合打包**

Run:

```powershell
mvn -pl "cm-agent-examples/starter-local-tool,cm-agent-examples/http-tool-client" -am "-DskipTests" package
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 运行最终差异检查**

Run:

```powershell
git diff --check
git status --short
```

Expected: 无格式错误；状态中只包含本任务文件和用户原有的无关修改。

- [ ] **Step 6: 提交文档入口与发布说明**

```powershell
git add -- README.md docs/release-notes.md
git commit -m "docs: 补充工具示例入口与发布说明"
```

- [ ] **Step 7: 完成前复核**

复核提交范围、测试输出和文档链接，确认没有真实凭据、没有数据库变化、没有将用户原有修改纳入提交。
