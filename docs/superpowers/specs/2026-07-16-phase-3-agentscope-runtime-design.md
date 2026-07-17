# 阶段 3：接入真实 AgentScope Runtime 设计

## 背景与目标

当前 `cm-agent-core` 已定义 `AgentRuntime`，`cm-agent-server` 已具备认证、权限入口、租户范围 Repository、工具授权、两段式 Run/ToolCall 持久化、严格审计和敏感数据脱敏，但 `cm-agent-agentscope-adapter` 仍只返回“真实运行桥接尚未启用”的受控失败结果。

本阶段把运行边界接到 AgentScope Java `2.0.0` GA，在不改变现有企业治理职责的前提下交付真实模型调用、AgentScope 消息与运行上下文、受治理工具执行、超时中断、事件结果映射和生产装配。AgentScope 负责 ReAct 推理与工具循环；CM Agent 继续负责租户隔离、模型元数据、外部密钥解析、权限判断、审计、Run/ToolCall 持久化和 HTTP 契约。

AgentScope Java `2.0.0` 于 2026-07-10 发布 GA。正式版本将 Provider 实现拆分到独立模型扩展模块，`ReActAgent` 通过 `RuntimeContext` 承载每次调用的用户和会话状态，并提供 `streamEvents` 事件流。本设计以正式文档和 Maven Central 工件中的 API 为准：

- `agentscope-core:2.0.0`
- `agentscope-extensions-model-openai:2.0.0`
- `agentscope-extensions-model-dashscope:2.0.0`

## 已确认的设计决策

1. 使用服务端编排与轻量 AgentScope 适配器，不让适配器直接访问 JDBC、Spring Security 或 Controller。
2. 新增可插拔 `ModelCredentialProvider`，按 `tenantId + modelConfigId` 从外部 Secret 映射解析模型密钥。本阶段不实现数据库密钥加解密。
3. 每次同步运行创建并关闭一个 `ReActAgent`，优先保证租户隔离、配置一致性和密钥轮换即时生效；待 Agent 配置版本和缓存失效协议稳定后再评估复用。
4. 当前 REST API 保持同步单轮语义，不新增多轮会话持久化、流式 REST、人工确认或手动取消接口。
5. AgentScope Toolkit 只暴露 CM Agent 本次授权的动态桥接工具，不启用 AgentScope 内置文件、Shell、MCP、Harness 或元工具。
6. 每次真实工具调用前由 CM Agent 再次执行租户、Agent 和工具授权检查；AgentScope 权限层不替代企业授权边界。
7. 复用现有 `model_configs`、`agent_definitions`、`runs`、`tool_calls` 和 `audit_events` 表，不新增或修改 Flyway 迁移。

## 范围与非目标

### 本阶段范围

- 将父 POM 的 `agentscope.version` 从 `2.0.0-RC3` 升级到 `2.0.0`。
- 支持 `DASHSCOPE_NATIVE` 和 `OPENAI_COMPATIBLE` 两种既有 Provider 类型。
- 从租户级 `model_configs` 读取 Provider、`baseUrl` 和默认模型名。
- 从外部 `ModelCredentialProvider` 获取 API Key。
- 映射 Agent 的系统提示词、模型名、温度和最大迭代次数。
- 使用 `RuntimeContext` 传递租户、调用主体、Agent 和 Run 标识。
- 使用 `streamEvents` 获取最终结果和运行事件。
- 将 CM Agent 工具桥接为 AgentScope `AgentTool`，记录授权、耗时、输入输出摘要和错误。
- 支持模型超时、工具超时和 AgentScope interrupt。
- 完成单元测试、Spring 集成测试、真实本地 HTTP Stub 合同测试和远程 Testcontainers 验证。

### 非目标

- 不实现 AgentScope Harness、Workspace、Sandbox、Skill 或子 Agent。
- 不引入 AgentScope 自有数据库状态存储，不形成第二套租户或会话数据库。
- 不新增模型配置管理 REST API；模型元数据继续通过现有数据库初始化和受控部署流程提供。
- 不实现数据库 `encrypted_api_key` 的加解密；该列保留兼容值，运行时密钥仅由外部 Provider 提供。
- 不自动根据 `ToolDefinition.endpoint` 发起 MCP/A2A 请求，避免绕过现有工具注册与治理边界。
- 不使用真实生产 API Key 执行自动测试。

## 模块边界与依赖

### 父 POM

将 `agentscope.version` 设为 `2.0.0`，并在 `dependencyManagement` 中统一管理 AgentScope Core、OpenAI 模型扩展和 DashScope 模型扩展。升级后分别执行 adapter 和 server 的 `dependency:tree`，核对 Spring Boot 依赖管理下的 Jackson、Reactor、SLF4J 和 OkHttp 实际解析版本。未经运行期合同测试证明，不主动覆盖整套 Spring Boot BOM。

### `cm-agent-core`

Core 继续只保存稳定领域和运行契约，不依赖 AgentScope 类型。

新增：

- `ModelConfigRepository`：按租户读取模型配置。
- `ModelCredentialProvider`：解析外部模型密钥。
- `ModelCredential`：封装敏感密钥，`toString()` 固定返回脱敏文本。
- `ToolInvocationGateway`：真实工具调用的企业治理入口。
- `ToolInvocationRequest`：携带 tenant、agent、principal、run、tool-call 和输入 JSON。
- `ToolInvocationResult`：携带输出、成功状态、授权状态和受控错误。

调整 `AgentRunRequest`，使其包含：

- `runId`
- `tenantId`
- `AgentDefinition agent`
- `ModelConfig modelConfig`
- `PrincipalRef principal`
- `input`
- 本次可见的 `List<ToolDefinition> tools`

构造器必须复制工具列表，并校验 Run、Agent、模型配置和主体的 tenant 一致。`FakeAgentRuntime` 继续接受同一请求，保持 local/test 回归能力。

`ModelConfigRepository` 的最小契约为：

```java
Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId);
```

`ModelCredentialProvider` 的最小契约为：

```java
ModelCredential resolve(UUID tenantId, UUID modelConfigId);
```

`ToolInvocationGateway` 的最小契约为：

```java
ToolInvocationResult invoke(ToolInvocationRequest request);
```

### `cm-agent-persistence`

新增 `JdbcModelConfigRepository`，使用 `JdbcClient` 和显式 mapper，从 `model_configs` 按 `tenant_id = :tenantId AND id = :id` 查询。Repository 不读取或返回 `encrypted_api_key`，避免把数据库兼容字段误当成可用明文密钥。

PostgreSQL 与 MySQL Testcontainers 测试必须覆盖：

- 当前租户可以读取自己的配置。
- 相同 ID 或其他租户条件不能跨租户读取。
- Provider 枚举、`baseUrl`、模型名和启用状态映射正确。

现有表和索引足以支持主键加 tenant 条件查询，因此不新增迁移。

### `cm-agent-agentscope-adapter`

Adapter 保持无 Spring、无 JDBC、无 Web 依赖。它包含以下职责清晰的组件：

- `AgentScopeRuntimeAdapter`：实现 `AgentRuntime`，负责 Core 请求与结果映射。
- `AgentScopeRunSpec`：Adapter 内部不可变运行规格，包含运行、模型和隔离上下文。
- `AgentScopeModelFactory`：根据 Provider 创建正式 AgentScope `Model`。
- `AgentScopeToolBridge`：把 `ToolDefinition` 和 `ToolInvocationGateway` 适配为 `AgentTool`。
- `AgentScopeReActExecutor`：构造、执行、中断并关闭真实 `ReActAgent`。
- `AgentScopeRuntimeOptions`：承载模型超时、工具超时和模型最大尝试次数。
- `AgentScopeEventCollector`：从事件流提取最终消息，并关联工具事件。

为了测试不依赖公网模型，`AgentScopeRuntimeAdapter` 与真实 `ReActAgent` 执行之间保留包内执行接口。单元测试使用受控 fake executor，合同测试使用真实 `AgentScopeReActExecutor` 和本地 HTTP Stub。

Adapter POM 继续把 AgentScope 依赖声明为 optional；除 `agentscope-core` 外增加两个正式 Provider 扩展。这样 SDK/Starter 使用者不会被动获得 AgentScope 运行依赖。

### `cm-agent-server`

Server 显式依赖 `cm-agent-agentscope-adapter` 和运行所需 AgentScope 工件，新增：

- `AgentScopeRuntimeProperties`
- `AgentScopeRuntimeConfiguration`
- `ExternalModelCredentialProvider`
- `GovernedToolInvocationService`

`RunExecutionService` 增加 `ModelConfigRepository`，在开始 Run 前按认证 tenant 加载并验证 Agent 对应的模型配置；在创建持久化 Run 后，用最终 `runId` 组装运行请求。

memory 模式由 `ServerRepositoryConfiguration` 提供按 tenant 过滤的 `ModelConfigRepository`，并为既有默认 `modelProviderId` 初始化对应的 `ModelConfig`；JDBC 模式由 `JdbcPersistenceConfiguration` 装配 `JdbcModelConfigRepository`。两种模式必须使用同一 Repository 契约，避免 local/test 因真实运行请求扩展而缺少模型配置 Bean。

`GovernedToolInvocationService` 依赖现有 Tool Repository、Grant Repository、`ToolAuthorizationPolicy`、`ToolRegistry`、`AuditAppender` 和 `SensitiveDataRedactor`，不在 Controller 中增加业务或数据访问逻辑。

`ProfileSafetyValidator` 保持严格 profile 禁止 fake runtime 的规则。AgentScope 默认 Bean 只在 `cm-agent.agentscope.enabled=true` 且不存在用户自定义 `AgentRuntime` 时创建。启用 AgentScope 与启用 fake runtime 同时出现时启动失败。

### `cm-agent-spring-boot-starter`

Starter 不新增 AgentScope 依赖或 Provider 配置，只维持通用 Core Bean、权限策略、工具注册表和条件 fake runtime。嵌入式用户仍可提供自己的 `AgentRuntime`。

## 配置设计

公共配置键：

```yaml
cm-agent:
  agentscope:
    enabled: false
    model-timeout: 60s
    tool-timeout: 30s
    model-max-attempts: 2
    credentials:
      - tenant-id: <tenant-uuid>
        model-config-id: <model-config-uuid>
        api-key: ${MODEL_API_KEY}
```

规则：

1. `enabled` 默认 `false`，避免 local/test 意外联网。
2. timeout 必须为正数，`model-max-attempts` 必须为 1 到 5。
3. 默认外部 Provider 使用 `tenant-id + model-config-id` 复合键，重复项、空 ID 和空密钥启动失败。
4. 当用户提供自定义 `ModelCredentialProvider` Bean 时，可以不配置 `credentials` 列表。
5. 当 AgentScope 已启用、没有自定义 Provider 且 credentials 为空时启动失败。
6. `baseUrl`、Provider 和默认模型名来自数据库；AgentDefinition 的非空 `modelName` 覆盖模型配置默认模型名。
7. 生产文档只使用环境变量或 Secret Manager 占位符，不写入真实密钥、可用凭据或完整生产 URL。

`production` 和 `supabase` profile 关闭 fake runtime 并启用 AgentScope 默认装配；`prod` 继续通过 profile group 继承 production。`postgres`、`mysql` 是非生产验证 profile，不默认启用真实模型调用，使用者可显式关闭 fake 并打开 AgentScope。

## 运行数据流

1. `RunController` 从 JWT 会话构造 `PrincipalRef`，检查 `agent:run`；拒绝时沿用现有访问拒绝审计。
2. `RunExecutionService` 使用 `principal.tenantId()` 加载 Agent。Agent 不存在或禁用时在外部调用前返回受控错误。
3. 服务按 `agent.modelProviderId()` 和同一 tenant 加载启用的 `ModelConfig`。不存在、跨租户或禁用时拒绝运行。
4. 服务加载 Agent 的工具授权，并形成第一次授权后的可见工具列表。
5. `RunPersistenceService.start` 在短事务中保存 `RUNNING` Run 和启动审计，得到最终持久化 `runId`。
6. 服务组装完整 `AgentRunRequest` 并调用 `AgentScopeRuntimeAdapter`。
7. Adapter 将请求映射为 `AgentScopeRunSpec`，调用 `ModelCredentialProvider.resolve(tenantId, modelConfigId)`。
8. `AgentScopeModelFactory` 创建模型：
   - `DASHSCOPE_NATIVE` 使用 `DashScopeChatModel.builder()`。
   - `OPENAI_COMPATIBLE` 使用 `OpenAIChatModel.builder()`。
9. 模型使用外部 API Key、数据库 `baseUrl`、Agent 模型名和 `GenerateOptions.temperature`；模型执行使用配置的 timeout 和最大尝试次数。
10. Adapter 为每个可见工具创建独立 `AgentScopeToolBridge`，解析并校验工具的 JSON Schema，然后注册到本次 Toolkit。
11. Adapter 构造 `ReActAgent`，映射 Agent 名称、系统提示词、模型、Toolkit、`maxIters`、模型执行配置和工具执行配置。不开启 meta tool、task list、skill 或 state store。
12. Adapter 构造 `RuntimeContext`：
    - `userId = tenantId + ":" + principalId`
    - `sessionId = runId`
    - extra 中写入 tenantId、agentId、principalId 和 runId
13. Adapter 用 `UserMessage` 包装输入，通过 `streamEvents` 执行。`AgentScopeEventCollector` 提取最终 `AgentResultEvent`，工具桥接独立记录精确调用参数、输出、授权状态和耗时。
14. 成功时返回 `SUCCEEDED`、最终文本和 ToolCall 列表；权限拒绝时强制 Run 状态为 `DENIED`。
15. timeout 时调用 AgentScope interrupt，保留已收集 ToolCall，返回 `FAILED` 和固定错误“Agent 运行超时”。
16. Adapter 在 finally 中关闭本次 `ReActAgent`。
17. `RunExecutionService` 调用现有 `RunPersistenceService.complete`，在短事务中完成 Run、保存 ToolCall 并追加完成审计；响应中的 runId 仍使用持久化 ID。

## 工具执行治理

`AgentScopeToolBridge` 不直接调用 `ToolRegistry`，而是创建 `ToolInvocationRequest` 交给 `GovernedToolInvocationService`。请求包含：

- tenantId
- agentId
- principal
- runId
- toolCallId
- toolId
- toolName
- inputJson

每次调用按以下顺序执行：

1. 只使用请求主体中的 tenantId 查询工具和授权记录。
2. 核对工具仍然启用、属于当前 tenant，并且 ID 与名称均匹配本次可见定义。
3. 重新调用 `ToolAuthorizationPolicy.check`，防止运行期间授权撤销后继续执行。
4. 核对 `ToolRegistry.find(toolId)` 的注册定义与数据库定义在 tenant、ID、名称上匹配。
5. 权限拒绝时调用 `AuditAppender.accessDenied`，不调用执行器，返回 `authorized=false` 的拒绝结果。
6. 允许执行时先写工具调用开始审计；审计失败则不执行工具。
7. 使用带上下文的 `ToolExecutionRequest` 调用注册执行器。
8. 写入成功或失败审计，返回受控结果。所有审计消息经过 `SensitiveDataRedactor`。

`ToolExecutionRequest` 扩充 tenant、agent、principal、run 和 tool-call 字段，同时保留现有 `(toolId, inputJson)` 便利构造器，避免破坏示例和第三方本地工具的源码兼容。

LOCAL、MCP、A2A 定义只有在 `ToolRegistry` 中存在受控执行器时才能执行。Adapter 不根据 endpoint 自动联网，避免形成 SSRF 或绕过授权的新路径。

## 状态与结果映射

- AgentScope 返回最终消息且没有工具拒绝：Run 为 `SUCCEEDED`。
- 任一工具被拒绝：对应 ToolCall 为 `DENIED`、`authorized=false`，Run 最终强制为 `DENIED`。
- 工具执行失败但 AgentScope 生成有效最终答复：ToolCall 为 `FAILED`，Run 可以为 `SUCCEEDED`。
- 模型或工具 timeout：Run 为 `FAILED`，错误为“Agent 运行超时”。
- Provider 网络错误、限流或响应解析失败：Run 为 `FAILED`，对外只返回受控运行错误，不暴露响应体、密钥或底层堆栈。
- 未知编程错误：Adapter 抛出运行异常，沿用 `RunExecutionService` 的失败收口和统一 API 内部错误。

ToolCall 的输入和输出仅保存摘要，并继续由 `RunPersistenceService` 脱敏。即时 Run 响应继续通过 `RunExecutionService.responseWithPersistentId` 脱敏，确保直接响应与查询响应一致。

## 多租户与敏感数据边界

1. Controller 不接受 tenant 覆盖字段，tenant 只来自认证主体。
2. Agent、ModelConfig、Tool、Grant 和 Run 的每次 Repository 查询都携带认证 tenant。
3. `AgentRunRequest` 构造器再次校验主体、Agent、ModelConfig 和工具 tenant 一致。
4. `RuntimeContext` 的 userId 带 tenant 前缀，sessionId 使用不可复用的持久化 runId。
5. 每次运行创建独立 Agent、Toolkit、工具桥接和事件收集器，不共享可变 ToolCall 列表。
6. `ModelCredential` 不使用 record，避免自动 `toString()` 输出密钥；日志中只允许记录 tenantId、modelConfigId 和受控错误分类。
7. API Key 不进入 AgentRunResult、ToolCallRecord、审计消息、DTO、OpenAPI 示例或测试输出。
8. Actuator 继续只公开 health 和 info，不新增 env/configprops 端点。

## 审计与失败语义

沿用阶段 2 的严格审计原则：

- Run 启动审计失败时不调用 Runtime。
- 工具开始审计失败时不调用工具执行器。
- 权限拒绝必须写访问拒绝审计。
- 工具成功、失败和拒绝分别有可查询审计事件。
- Run 完成审计与 Run/ToolCall 继续使用现有事务边界。

外部工具的副作用无法参与本地数据库事务。如果工具已完成但完成审计失败，Run 会失败，但外部副作用不能自动回滚。设计通过 `runId + toolCallId` 提供幂等键，生产文档要求 MEDIUM/HIGH 风险和有副作用的执行器实现幂等或补偿。

## 异常处理

- 配置绑定错误、重复 credential、非法 timeout：启动失败，错误消息不得包含密钥。
- 模型配置缺失、禁用或跨租户：在 Runtime 调用前拒绝，并由现有 Run/API 流程返回受控中文消息。
- 外部密钥缺失：已创建的 Run 收口为 `FAILED`，错误固定为模型凭据不可用，不输出配置内容。
- JSON Schema 非法：在模型调用前失败，避免向模型暴露不可执行工具。
- ToolRegistry 未注册或定义不一致：按工具失败处理并审计，不自动调用 endpoint。
- timeout：interrupt 后返回带已收集 ToolCall 的失败结果。
- AgentScope/Provider 的原始异常仅作为内部 cause，不写入持久化错误、审计或 HTTP 响应。

## 测试策略

所有实现任务遵循先红后绿再重构。

### Core 单元测试

- `AgentRunRequestTest`：tenant 一致性、不可变工具列表和 runId 必填。
- `ModelCredentialTest`：空密钥拒绝、`toString()` 脱敏。
- `ToolInvocationRequestTest`：上下文完整性和 tenant 一致性。
- `FakeAgentRuntimeTest`：扩展请求后保持原有成功语义。
- `InMemoryToolRegistryTest`：带上下文请求兼容旧执行器。

### Adapter 单元与合同测试

- `AgentScopeRuntimeAdapterTest`：Core 请求映射、状态和错误映射。
- `AgentScopeModelFactoryTest`：两种 Provider、baseUrl、模型名和温度映射。
- `AgentScopeToolBridgeTest`：Schema、成功、失败、拒绝、异常和耗时记录。
- `AgentScopeEventCollectorTest`：最终事件、缺失最终事件和工具事件关联。
- `AgentScopeReActExecutorTest`：RuntimeContext、maxIters、timeout、interrupt 和 close。
- `AgentScopeRuntimeContractTest`：启动本地 HTTP Stub，使用假的 OpenAI-compatible 凭据和响应，真实执行 `ReActAgent.streamEvents()`，验证最终文本；工具场景让 Stub 先返回工具调用、再返回最终答复，验证真实 Toolkit 回调。测试不访问公网。

### Server 测试

- `AgentScopeRuntimeConfigurationTest`：fake/real 条件、用户 Bean back-off、非法配置和空 credential fail-fast。
- `ApplicationProfileConfigurationTest`：production/supabase 使用真实 runtime，禁止 fake 与 real 同时启用。
- `RunControllerTest`：模型配置不可用、运行成功、timeout、Provider 失败和响应脱敏。
- `GovernedToolInvocationServiceTest`：每次调用重新加载授权、跨租户拒绝、注册定义不一致、审计失败不执行、成功/失败审计。
- `RunControllerJdbcPersistenceTest`：真实适配器的受控 fake executor 与 JDBC Run/ToolCall/Audit 两段式链路。

### Persistence 与远程验证

- 新增 PostgreSQL `JdbcModelConfigRepositoryTest`。
- 扩展 MySQL runtime repository 测试或新增对应测试，覆盖同一查询契约。
- 所有 Docker、JDBC、Flyway 和 Testcontainers 验证通过 `ssh rocky`，使用 `maven:3.9.9-eclipse-temurin-21`。
- 远程验证前确认 Docker 可用、容器 Maven 使用 JDK 21，并确认远程工作区 HEAD 与本地待验证提交一致。

## 验证命令

环境：

```powershell
java -version
mvn -v
```

依赖与受影响模块：

```powershell
mvn -pl cm-agent-agentscope-adapter dependency:tree
mvn -pl cm-agent-server dependency:tree
mvn -q -pl cm-agent-core -am test
mvn -q -pl cm-agent-agentscope-adapter -am test
mvn -q -pl cm-agent-server -am test
```

远程数据库与整体回归：

```powershell
ssh rocky 'set -euo pipefail; cd /tmp/cm-agent-phase3; docker info >/dev/null; git rev-parse HEAD; docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-phase3:/workspace -w /workspace maven:3.9.9-eclipse-temurin-21 mvn -q test'
mvn -q test
mvn -q "-DskipTests" package
git diff --check
```

执行远程命令前，先把本地待验证提交同步到 `/tmp/cm-agent-phase3`，并将远程 `git rev-parse HEAD` 与本地 `git rev-parse HEAD` 的实际输出逐字比较；不一致时不得执行或采信远程测试。

本地默认 JDK 若不是 21，不得将本地 Maven 结果作为最终证据；必须切换 JDK 21 或使用规定的 Rocky 容器环境。

## 文档更新

同步更新：

- `README.md`：阶段 3 已交付范围和快速启用入口。
- `docs/configuration.md`：AgentScope、Provider、credential 映射和 profile 规则。
- `docs/deployment.md`：外部 Secret、模型元数据准备和生产启动检查。
- `docs/operations.md`：timeout、Provider 故障、审计失败和幂等补偿。
- `docs/roadmap.md`：阶段 3 状态及明确未交付的多轮/流式范围。
- `docs/release-notes.md`：AgentScope 2.0.0、真实模型与工具桥接、兼容性和限制。

所有文档使用中文，示例只包含占位符。

## 兼容性、风险与回滚

### 兼容性

- REST 路径和响应结构不变。
- 数据库 schema 和既有 Flyway 历史不变。
- local/test 继续默认 fake runtime。
- Starter 不传递 AgentScope 依赖。
- 旧式 `(toolId, inputJson)` 工具执行请求保留便利构造器。
- 自定义 `AgentRuntime` 和自定义 `ModelCredentialProvider` 可以覆盖默认装配。

### 主要风险

- AgentScope 2.0.0 的 Jackson/Reactor 编译版本可能与 Spring Boot 3.5.0 管理版本不同，必须通过 dependency tree 和真实本地 Stub 合同测试验证二进制兼容性。
- 每次运行创建 Agent 有额外开销，但避免跨租户状态、缓存失效和密钥轮换问题。
- 工具外部副作用无法随本地审计事务回滚，需要幂等或补偿。
- 当前同步单轮 API 不提供持久会话恢复或客户端流式事件。
- 既有数据库中的默认模型配置可能仍指向占位 baseUrl；部署必须通过受控流程写入有效元数据，并配置匹配的外部 credential。

### 回滚

- 非严格 profile 可关闭 `cm-agent.agentscope.enabled`，继续使用 fake runtime 或用户自定义 `AgentRuntime`。
- 严格生产 profile 只能回滚到另一个真实 `AgentRuntime`，不能启用 fake runtime。
- 本阶段没有 schema 迁移，代码回滚不需要数据库降级。
- 外部 credential 映射可以独立撤销或轮换，不修改数据库密钥字段。
