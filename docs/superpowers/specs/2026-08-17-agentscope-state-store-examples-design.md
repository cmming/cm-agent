# AgentScope JSON 文件与 Redis 状态存储示例设计

## 背景与目标

现有 `ContextAndAgentStateExample` 仅使用 `InMemoryAgentStateStore` 展示单进程会话隔离，无法展示进程重启后的本地文件恢复和多副本共享状态。本次新增两个独立示例，分别演示 `JsonFileAgentStateStore` 与 `RedisAgentStateStore` 的使用方式。

## 范围

- 新增 `JsonFileAgentStateStoreExample`：首个 Agent 写入会话后关闭，第二个新建 Agent 从相同 JSON 状态目录恢复并继续调用。
- 新增 `RedisAgentStateStoreExample`：模拟两个独立应用副本，各自创建 Jedis、Redis 状态存储与 Agent，通过相同 `(userId, sessionId)` 续接会话。
- 在现有示例模块中增加 `agentscope-extensions-redis` 依赖。

不修改生产运行时、服务端配置、数据库或 Redis 部署配置，也不运行真实模型调用。

## 方案与约束

两类示例均使用 DashScope `qwen3.7-plus`、流式配置和 `DashScopeChatFormatter`，但 API Key 只从 `DASHSCOPE_API_KEY` 环境变量读取。JSON 文件示例的状态根目录固定在项目 `target/agentscope-state/json-file-demo`，每次生成 UUID 会话标识，因此不会覆盖之前的演示状态。Redis 示例使用 `REDIS_URL`，默认连接 Rocky 联调服务器 `redis://192.168.0.66:6379`，并配置受限的示例键前缀；不输出可能携带密码的完整 URL。

`JsonFileAgentStateStore` 只适合单机；Redis 示例仅配置 `AgentStateStore`。如将来增加远程工作区或沙箱，还需配置 `RedisDistributedStore`，以保证相关运行时资源同样在副本间共享。

## 验收标准

- JDK 21 下示例模块可以完成跳过测试的 Maven 打包。
- 未设置 `DASHSCOPE_API_KEY` 时，两个示例均输出中文提示并退出，不访问 Redis 或模型服务。
- 有效 DashScope 凭据与 Redis 环境下，JSON 示例可输出重建 Agent 后的上下文恢复；Redis 示例可输出副本 B 的上下文恢复。
