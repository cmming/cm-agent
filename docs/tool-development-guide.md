# LOCAL 与 HTTP 工具开发指南

本文面向 CM Agent 开发者，讲解当前已经具备完整执行链的两类工具：

- `LOCAL`：执行逻辑位于当前 Java 进程中，由开发者实现并注册。
- `HTTP`：执行逻辑位于外部 HTTP 服务中，由 CM Agent 根据受治理配置发起请求。

`MCP` 在当前项目中主要用于发布已经创建的 `LOCAL` 或 `HTTP` 工具，不是本文第三种工具实现方式。`A2A` 尚无完整执行器，因此不在本文范围内。

## 1. 如何选择工具类型

| 场景 | 建议类型 | 原因 |
| --- | --- | --- |
| 调用当前应用中的 Java 代码 | `LOCAL` | 无网络调用，执行器由应用直接控制 |
| 封装已有 HTTPS API | `HTTP` | 无需为每个接口编写 Java 执行器 |
| 需要复杂事务、领域服务或进程内依赖 | `LOCAL` | 可以直接注入应用服务，但仍需保持租户和权限边界 |
| 只需要配置 URL、参数映射和认证头 | `HTTP` | 平台统一处理 Schema、超时、SSRF 和输出限制 |

两类工具进入 Agent 执行时都会经过授权、租户检查、审计和输出处理。不要因为工具逻辑简单而绕过这些治理入口。

## 2. 公共领域模型

工具定义使用 `ToolDefinition`：

```java
public record ToolDefinition(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        ToolType type,
        String inputSchema,
        ToolRiskLevel riskLevel,
        boolean enabled,
        String endpoint,
        String createdBy,
        String updatedBy
) {
}
```

开发时重点关注以下字段：

- `id`：工具唯一标识。正式 Server 中，持久化定义和运行时注册必须使用同一个 ID。
- `tenantId`：工具所属租户。必须来自认证主体或受控配置，不能信任客户端任意覆盖。
- `name`：同一租户内保持唯一；LOCAL 注册快照的名称必须与持久化定义一致。
- `inputSchema`：提供给模型和调试入口的输入 JSON Schema。
- `riskLevel`：`LOW`、`MEDIUM` 或 `HIGH`；HIGH 工具调试时需要提交完全一致的工具名称。
- `enabled`：禁用后工具不可执行。
- `endpoint`：HTTP 工具使用 URL 模板；LOCAL 工具通常为空字符串。

输入 Schema 应尽量具体。以下 Schema 表示一个只接受 `left`、`right` 两个数字的对象：

```json
{
  "type": "object",
  "properties": {
    "left": { "type": "number" },
    "right": { "type": "number" }
  },
  "required": ["left", "right"],
  "additionalProperties": false
}
```

## 3. 创建 LOCAL 工具

完整示例位于：

- `cm-agent-examples/starter-local-tool`
- `cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/local`

该示例同时注册 `echo` 和 `add`，用于展示一个应用如何管理多个独立工具。

### 3.1 添加依赖

Spring Boot 应用引入 Starter。示例还使用 Jackson 解析 JSON：

```xml
<dependency>
    <groupId>com.cmagent</groupId>
    <artifactId>cm-agent-spring-boot-starter</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

Starter 在应用没有自定义实现时提供 `ToolRegistry`。

### 3.2 定义 add 工具

示例使用固定 UUID，便于重复运行和测试。生产代码应从持久化定义或受控外部配置取得 ID 与 tenant：

```java
public final class LocalToolDefinitions {
    public static final UUID EXAMPLE_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ADD_TOOL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

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
}
```

### 3.3 实现 ToolExecutor

执行器只负责业务输入和输出，不负责读取 JWT、决定 tenant 或绕过权限。`add` 使用 `BigDecimal`，确保 `0.1 + 0.2` 得到精确的 `0.3`：

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
            JsonNode left = input == null || !input.isObject() ? null : input.get("left");
            JsonNode right = input == null || !input.isObject() ? null : input.get("right");
            if (left == null || right == null || !left.isNumber() || !right.isNumber()) {
                return ToolExecutionResult.failed("left 和 right 必须是数字", null);
            }

            BigDecimal sum = left.decimalValue().add(right.decimalValue());
            ObjectNode output = objectMapper.createObjectNode().put("sum", sum);
            return ToolExecutionResult.succeeded(
                    objectMapper.writeValueAsString(output),
                    null
            );
        } catch (JsonProcessingException exception) {
            return ToolExecutionResult.failed("工具输入必须是合法 JSON 对象", null);
        }
    }
}
```

关键约束：

- 失败时返回受控错误，不返回堆栈或完整原始输入。
- 有外部副作用的执行器应使用 `runId`、`toolCallId` 或业务键保证幂等。
- 自定义数据库、网络或文件 I/O 必须有独立超时，并响应线程中断。
- 执行器不得使用请求输入覆盖当前租户。

### 3.4 注册多个 LOCAL 工具

定义与执行器通过 `ToolRegistry.register` 绑定。示例在 Spring Bean 初始化阶段注册，确保所有启动 Runner 执行前工具已经可用：

```java
@Configuration(proxyBeanMethods = false)
public class LocalToolRegistration {

    @Bean
    ObjectMapper localToolObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    InitializingBean registerLocalTools(
            ToolRegistry registry,
            ObjectMapper objectMapper
    ) {
        return () -> {
            registry.register(
                    LocalToolDefinitions.echo(),
                    new EchoToolExecutor(objectMapper)
            );
            registry.register(
                    LocalToolDefinitions.add(),
                    new AddToolExecutor(objectMapper)
            );
        };
    }
}
```

注册后可以发起最小调用：

```java
ToolExecutionResult result = registry.execute(
        new ToolExecutionRequest(
                LocalToolDefinitions.ADD_TOOL_ID,
                "{\"left\":0.1,\"right\":0.2}"
        )
);
```

成功结果：

```json
{"sum":0.3}
```

### 3.5 运行 LOCAL 示例

先确认 Maven 使用 JDK 21：

```powershell
$env:JAVA_HOME = 'F:\java21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -v
```

运行测试：

```powershell
mvn -pl cm-agent-examples/starter-local-tool -am test
```

启动示例：

```powershell
mvn -pl cm-agent-examples/starter-local-tool -am "-DskipTests" install
mvn -pl cm-agent-examples/starter-local-tool spring-boot:run
```

第一条命令把当前分支的 Starter 与 Core SNAPSHOT 安装到本地 Maven 仓库，第二条命令只在包含 main class 的叶子模块上执行 Boot goal。预期可以看到 `echo` 和 `add` 的成功结果。

### 3.6 接入正式 CM Agent Server

独立 Starter 示例中的 `registry.execute(...)` 用于说明注册机制，不等同于生产治理链。正式 Server 接入需要同时满足：

1. 通过 `POST /api/tools` 创建 `type=LOCAL` 的持久化工具定义。
2. 从创建响应取得 Server 生成的 `id` 和认证主体的 `tenantId`。
3. 将该 ID 和 tenant 通过受控配置提供给同一 Server JVM 中的扩展代码。
4. 扩展代码按 `tenantId + toolId` 读取持久化定义，并将原定义与执行器注册到 `ToolRegistry`。
5. 通过 `/api/tools/{id}/grants` 将工具授权给 Agent。

创建 LOCAL 元数据：

```http
POST /api/tools
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{
  "name": "add",
  "description": "对两个数字执行精确加法",
  "type": "LOCAL",
  "riskLevel": "LOW"
}
```

当前 API 会为非 HTTP 工具生成 ID，并使用通用的 `{"type":"object"}` Schema。运行时注册必须复用返回的持久化定义，而不是在另一个进程中注册一个同名但不同 ID 的工具：

```java
@Bean
InitializingBean registerServerLocalAddTool(
        ToolDefinitionRepository definitions,
        ToolRegistry registry,
        ObjectMapper objectMapper,
        LocalToolProperties properties
) {
    return () -> {
        ToolDefinition definition = definitions.findByTenantAndId(
                        properties.tenantId(),
                        properties.toolId()
                )
                .filter(tool -> tool.type() == ToolType.LOCAL)
                .orElseThrow(() -> new IllegalStateException(
                        "LOCAL 工具定义不存在或类型不正确"
                ));
        registry.register(definition, new AddToolExecutor(objectMapper));
    };
}
```

这意味着精确的 `add` 输入 Schema 当前只在 Starter 独立示例中生效；Server 管理 API 暂不接收自定义 LOCAL Schema。正式接入仍必须让执行器自行严格校验输入。如需让模型看到更精确的 LOCAL Schema，应先扩展受权限和审计保护的管理流程，不能由扩展代码绕过 Repository、租户和审计直接覆盖生产数据。

`GovernedToolExecutionService` 执行 LOCAL 工具前会重新检查启用状态、tenant、ID、名称和当前注册快照。外部进程中的另一个 `ToolRegistry` 不会自动注册到 Server。

### 3.7 通过 MySQL profile 安装内置示例

工具治理页在非生产 `mysql` profile 下只向固定 bootstrap 示例租户 `00000000-0000-0000-0000-000000000001` 提供固定目录中的 `echo`、`add` 内置 LOCAL 示例。这是 MySQL 调试的隔离演示边界，不是面向所有 tenant 的安装能力。此能力只在激活 `mysql` 且未激活 `prod`、`production`、`supabase` 时可用；生产和类生产 profile 不会注册这些执行器，也不能将其作为生产工具接入方式。

示例租户的认证主体使用 `tool:read` 读取目录、使用 `tool:grant` 通过 `POST /api/tools/local-examples/{key}` 显式安装、使用 `tool:debug` 调用既有调试入口。其他 tenant 即使拥有这些权限，目录也为空，安装请求返回 `404`；请求不能指定或覆盖 JWT 中的 tenant。服务启动仅在当前 JVM 注册固定 Java 执行器，不会自动向 MySQL 写入工具定义。安装成功后，页面会选择相应工具并自动填入示例输入。

`echo` 的示例输入为：

```json
{"message":"你好，CM Agent"}
```

`add` 的示例输入为：

```json
{"left":0.1,"right":0.2}
```

安装固定使用 bootstrap 示例租户，不能通过请求指定、覆盖或切换 JWT 中的 tenant。示例租户中已存在同名或同 ID 但定义不兼容的工具时，接口返回 `409`；应先核对该 tenant 下现有治理定义，而不是删除或覆盖未知工具。MySQL 保存的定义在重启后仍存在；每次 MySQL 非生产 profile 启动都会重新注册固定执行器，因此定义与注册快照一致时会再次显示“运行时已就绪”。该字段只是查询快照，调试和 Agent 调用仍会重新执行状态、租户、身份、授权和审计校验。

此页面入口不能上传、编译或解释执行代码。它只安装项目固定的两个示例定义；正式业务 LOCAL 工具仍必须在同一 Server JVM 中以 Java 实现并注册 `ToolExecutor`，并复用持久化定义的 tenant、ID 和名称。

## 4. 创建 HTTP 工具

完整客户端示例位于 `cm-agent-examples/http-tool-client`。它不调用 Server 内部 Service 或 Repository，而是使用公开 REST API：

```text
POST /api/tools
  → 取得工具 ID
  → POST /api/tools/{id}/debug
```

### 4.1 Server 前置配置

HTTP 工具出站执行默认关闭。开发或部署配置至少需要：

```yaml
cm-agent:
  http-tools:
    enabled: true
    allow-http: false
    allowed-hosts:
      - api.example.test
    min-timeout: 100ms
    max-timeout: 30s
    max-response-bytes: 262144
    max-redirects: 3
    secrets:
      secret/integration/example-api-key: ${EXAMPLE_API_KEY}
```

说明：

- `allowed-hosts` 只填写主机名，不填写协议、路径或查询串。
- 默认只允许 HTTPS。生产环境不应开启 `allow-http`。
- 回环、链路本地、私有和保留地址会被拒绝，因此不要把 `localhost` 作为动态 HTTP 工具目标。
- `secrets` 示例只说明本地默认 Provider 的映射方式。生产应提供受控 `HttpToolSecretProvider`。
- Secret 实际值不得进入 Git、工具定义、创建响应、日志或审计。

创建工具需要 `tool:grant`，调试需要 `tool:debug`。本地 `bootstrap admin` 登录响应包含这些权限；生产环境必须使用外部签发的 Bearer JWT。

### 4.2 完整创建请求

以下请求创建一个 POST/BODY 工具。调用方只定义参数，服务端自动生成并返回 `inputSchema`：

```http
POST /api/tools
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{
  "name": "developer-http-example",
  "description": "通过公开 API 创建的 HTTP 工具示例",
  "type": "HTTP",
  "riskLevel": "LOW",
  "mcpPublished": false,
  "httpConfig": {
    "method": "POST",
    "urlTemplate": "https://api.example.test/messages",
    "parameters": [
      {
        "id": "message",
        "name": "message",
        "dataType": "STRING",
        "requestLocation": "BODY",
        "description": "待发送的消息",
        "required": true,
        "minLength": 1,
        "exampleValue": "你好，CM Agent"
      }
    ],
    "secretHeaders": {
      "X-Api-Key": "secret/integration/example-api-key"
    },
    "timeoutMillis": 5000
  }
}
```

工具定义、HTTP 配置、可选 MCP 发布记录和创建审计在同一创建流程中完成。同一租户内工具名称必须唯一。

### 4.3 扁平参数定义

参数定义使用一层 JSON 数组保存树结构。每个节点有独立 `id`，嵌套节点通过 `parentId` 指向父节点；只有顶层节点填写 `requestLocation`。字段名称直接作为请求中的参数名，因此不需要维护 JSON Pointer 映射。

控制台展示为树形编辑器，用户通过“添加顶层参数”和节点内“添加子参数”建立层级；页面提交时再按树的先序遍历生成下述一层 JSON 数组。直接调用 REST API 的客户端仍按扁平数组提交。

| 字段 | 作用 | 主要约束 |
| --- | --- | --- |
| `id` | 节点唯一标识 | 字母开头，最多 64 个字母、数字、`_` 或 `-` |
| `parentId` | 父节点 ID | 顶层不填；不能循环或指向不存在节点 |
| `name` | Tool 输入字段名和请求字段名 | OBJECT 子字段必填；ARRAY 直接元素节点必须为空 |
| `dataType` | 字段类型 | `STRING`、`INTEGER`、`NUMBER`、`BOOLEAN`、`OBJECT`、`ARRAY` |
| `requestLocation` | 顶层字段的请求位置 | `PATH`、`QUERY`、`HEADER`、`BODY`、`BODY_ROOT` |
| `required` | 字段是否必填 | PATH 必须为 `true`；ARRAY 元素节点不填写 |
| `defaultValue` / `exampleValue` | JSON 默认值和示例值 | 必须与 `dataType` 一致 |

位置约束如下：

| 位置 | 支持类型 | 主要约束 |
| --- | --- | --- |
| `PATH` | 标量 | 必填，`name` 必须与 URL `{placeholder}` 完整匹配 |
| `QUERY` | 标量或标量数组 | 自动以 `name` 追加查询参数 |
| `HEADER` | 标量 | 不能覆盖 `Authorization`、`Cookie` 等受限 Header |
| `BODY` | 任意类型 | 多个顶层字段按名称组装成对象；GET 禁止 |
| `BODY_ROOT` | 任意类型 | 字段值直接作为完整请求体，只能有一个，不能与 BODY 并用 |

PATH 与 QUERY 混合请求示例：

```json
{
  "method": "GET",
  "urlTemplate": "https://api.example.test/orders/{orderId}",
  "parameters": [
    { "id": "orderId", "name": "orderId", "dataType": "STRING", "requestLocation": "PATH", "required": true },
    { "id": "details", "name": "details", "dataType": "BOOLEAN", "requestLocation": "QUERY", "required": false, "defaultValue": true }
  ]
}
```

对应输入 `{"orderId":"o-100"}` 会调用 `https://api.example.test/orders/o-100?details=true`。

嵌套 BODY 对象示例：

```json
[
  { "id": "payload", "name": "payload", "dataType": "OBJECT", "requestLocation": "BODY", "required": true },
  { "id": "customer", "parentId": "payload", "name": "customer", "dataType": "OBJECT", "required": true },
  { "id": "customerName", "parentId": "customer", "name": "name", "dataType": "STRING", "required": true }
]
```

根数组请求 `[{"p1":"v1"}]` 使用一个命名 Tool 入参承载数组，并以匿名子节点表示数组元素：

```json
[
  { "id": "payload", "name": "payload", "dataType": "ARRAY", "requestLocation": "BODY_ROOT", "required": true, "minItems": 1 },
  { "id": "payloadItem", "parentId": "payload", "name": "", "dataType": "OBJECT", "required": false },
  { "id": "p1", "parentId": "payloadItem", "name": "p1", "dataType": "STRING", "required": true, "exampleValue": "v1" }
]
```

调试输入为 `{"payload":[{"p1":"v1"}]}`，目标 HTTP 接口收到的请求体就是 `[{"p1":"v1"}]`。这样既支持根数组，也保持 Tool/MCP 输入 Schema 的根节点为 object。

HTTP 工具只接受 `parameters`。服务端根据参数树自动生成 Tool/MCP 输入 Schema，不再接收 `inputSchema`、`parameterMappings`、`sourcePointer` 或 `targetPointer`。

没有任何输入参数的接口直接提交空数组：

```json
{
  "method": "GET",
  "urlTemplate": "https://api.example.com/tools",
  "parameters": [],
  "secretHeaders": {},
  "timeoutMillis": 1000
}
```

服务端会生成 `properties` 为空且 `additionalProperties=false` 的对象 Schema。调用或调试时输入使用 `{}`，实际 HTTP 请求不包含 PATH、QUERY、动态 HEADER 或请求体。

### 4.4 Secret Header

敏感 Header 不使用普通 HEADER 映射，而是提交引用：

```json
{
  "secretHeaders": {
    "X-Service-Token": "secret/integration/order-service"
  }
}
```

引用必须符合 `secret/...` 格式。执行时 `HttpToolSecretProvider` 根据当前 tenant 解析实际值。工具配置和 API 只保存引用。

### 4.5 调试 HTTP 工具

创建成功后取得工具 ID：

```http
POST /api/tools/{toolId}/debug
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{
  "input": {
    "message": "你好，CM Agent"
  }
}
```

LOW 和 MEDIUM 工具不需要额外确认。HIGH 工具还必须提交：

```json
{
  "input": {
    "message": "你好，CM Agent"
  },
  "confirmedToolName": "developer-http-example"
}
```

调试不创建 Agent 或 Run，但仍会校验权限、tenant、工具状态、HTTP 配置和风险确认，并记录严格审计。

执行成功时响应包含输出、HTTP 状态和耗时。执行器返回失败时，接口会返回经过脱敏的具体原因、稳定错误码和本次调用的错误编号：

```json
{
  "success": false,
  "statusCode": 503,
  "output": "",
  "errorMessage": "HTTP 服务返回非成功状态",
  "durationMillis": 86,
  "errorCode": "HTTP_UPSTREAM_ERROR",
  "errorId": "5ff4ffec-bf80-4d77-b150-462256ee8082"
}
```

控制台会直接展示 `errorMessage`，并同时展示 `errorCode` 和 `errorId`。后台日志使用同一个 `errorId`，运维人员可据此定位对应的 tenant、工具、调用来源、HTTP 状态和脱敏失败原因。执行器抛出未分类异常时，前端不会显示内部堆栈，只提示根据错误编号查看后台日志；后台记录脱敏后的异常说明和堆栈。日志不得包含调试输入、Secret 原值、JWT、完整内部 URL 或其他敏感信息。

### 4.6 运行 HTTP 客户端示例

先启动已正确配置的 CM Agent Server，并取得本地开发 JWT。JWT 只保存在当前 PowerShell 进程：

```powershell
$loginBody = @{
    username = 'admin'
    password = $env:CM_AGENT_BOOTSTRAP_ADMIN_PASSWORD
} | ConvertTo-Json

$login = Invoke-RestMethod `
    -Method Post `
    -Uri 'http://localhost:8080/api/auth/login' `
    -ContentType 'application/json' `
    -Body $loginBody

$env:CM_AGENT_JWT = $login.token
```

设置 HTTP 示例参数：

```powershell
$env:CM_AGENT_HTTP_EXAMPLE_ENABLED = 'true'
$env:CM_AGENT_BASE_URL = 'http://localhost:8080'
$env:CM_AGENT_HTTP_TOOL_NAME = 'developer-http-example-01'
$env:CM_AGENT_HTTP_TARGET_URL = 'https://api.example.test/messages'
$env:CM_AGENT_HTTP_SECRET_HEADER_NAME = 'X-Api-Key'
$env:CM_AGENT_HTTP_SECRET_REF = 'secret/integration/example-api-key'
$env:CM_AGENT_HTTP_MESSAGE = '你好，CM Agent'
```

`api.example.test` 是文档示例域名，不能用于真实调用。运行前必须把目标 URL 和 Server 的 `allowed-hosts` 同时替换为开发者实际拥有、可通过公网 DNS 解析的 HTTPS 服务。

运行：

```powershell
mvn -pl cm-agent-examples/http-tool-client -am "-DskipTests" install
mvn -pl cm-agent-examples/http-tool-client spring-boot:run
```

如目标接口不需要认证，将两个 Secret 相关环境变量都留空。只设置其中一个会在发送请求前失败。

示例默认 `enabled=false`，未显式开启时只输出运行提示，不发送网络请求。重复使用同一个工具名称会因租户内名称唯一约束返回 `409`，修改 `CM_AGENT_HTTP_TOOL_NAME` 后再运行。

## 5. Agent 授权与可选 MCP 发布

工具创建后不会自动授权给 Agent。授权接口：

```http
POST /api/tools/{toolId}/grants
Authorization: Bearer <access-token>
Content-Type: application/json
```

```json
{
  "agentId": "00000000-0000-0000-0000-000000000301"
}
```

请求中的 Agent 必须与当前认证主体属于同一 tenant。

如需把已创建且可执行的 HTTP/LOCAL 工具发布到 MCP 目录：

```http
PUT /api/tools/{toolId}/mcp-publication
Authorization: Bearer <access-token>
```

取消发布：

```http
DELETE /api/tools/{toolId}/mcp-publication
Authorization: Bearer <access-token>
```

MCP Server 默认关闭，且有独立的 Origin、Host、JWT 和 `tool:mcp:invoke` 权限要求。完整说明见[配置说明](configuration.md)。

## 6. 常见错误

| 现象 | 常见原因 | 处理方式 |
| --- | --- | --- |
| LOCAL 返回“工具不可用” | 未注册，或注册定义与持久化定义的 ID、tenant、名称不一致 | 从 Repository 读取原定义后注册执行器 |
| LOCAL 独立示例可调用，Agent 中不可用 | 尚未创建持久化定义或未授权给 Agent | 创建工具元数据并调用 grants 接口 |
| HTTP 返回 `403` | JWT 缺少 `tool:grant` 或 `tool:debug` | 使用具备对应权限的认证主体 |
| HTTP 创建返回 `409` | 同一 tenant 已有同名工具 | 使用新名称，或复用已有工具 |
| HTTP 调试提示目标地址不允许 | 开关未启用、Host 不在白名单，或地址属于私有/保留范围 | 检查 `cm-agent.http-tools` 和网络出口策略 |
| HTTP 调试提示工具不可用 | 定义和 HTTP 配置漂移，或工具已禁用 | 查询工具摘要并核对 URL 模板与启用状态 |
| HTTP 调试失败但需要进一步定位 | 目标服务、Secret、超时、响应格式或网络策略失败 | 先查看前端具体错误原因和 HTTP 状态，再使用错误编号检索后台日志 |
| PATH 映射校验失败 | 占位符与必填 PATH 映射不一致 | 让 `{name}` 与 `targetName=name` 精确对应 |
| GET 配置校验失败 | 配置了 BODY 映射 | 改用 QUERY/PATH/HEADER，或改为 POST |
| Secret 解析失败 | 引用不存在，或 Provider 超时/失败 | 核对 tenant 范围内引用和 Provider 配置 |
| HIGH 工具不能调试 | `confirmedToolName` 缺失或不完全匹配 | 提交与工具名称完全一致的确认值 |

## 7. 验证命令

确认环境：

```powershell
$env:JAVA_HOME = 'F:\java21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -v
```

运行 LOCAL 示例测试：

```powershell
mvn -pl cm-agent-examples/starter-local-tool -am test
```

运行 HTTP 示例测试：

```powershell
mvn -pl cm-agent-examples/http-tool-client -am test
```

打包两个示例：

```powershell
mvn -pl "cm-agent-examples/starter-local-tool,cm-agent-examples/http-tool-client" -am "-DskipTests" package
```

HTTP 客户端测试使用受控的本地模拟 CM Agent API，不访问公网，不需要真实 JWT、Secret、数据库或正在运行的 Server。

## 8. 安全检查清单

- LOCAL 执行器没有信任输入中的 tenant、权限或主体信息。
- LOCAL 持久化定义与注册定义的 ID、tenant、名称一致。
- 有副作用的 LOCAL 工具具有幂等策略。
- HTTP 出站开关和 Host 白名单由服务端控制。
- HTTP 目标不使用回环、私有或保留地址。
- Secret 配置只包含 `secret/...` 引用。
- JWT、Secret 实际值、生产 URL 和底层异常不会进入日志或文档。
- HIGH 工具调试保留名称二次确认。
- 工具已按最小权限授权给正确 tenant 下的 Agent。
