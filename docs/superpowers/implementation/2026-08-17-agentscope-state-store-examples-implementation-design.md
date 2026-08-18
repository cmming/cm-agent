# AgentScope JSON 文件与 Redis 状态存储示例实现说明

对应[设计](../specs/2026-08-17-agentscope-state-store-examples-design.md)与[实施计划](../plans/2026-08-17-agentscope-state-store-examples.md)。

## 实现内容

- `JsonFileAgentStateStoreExample` 使用 `new JsonFileAgentStateStore(Path)` 指定本机状态目录。首次 Agent 调用结束时框架自动保存 `AgentState`；第二个新建 Agent 使用相同目录和 `(userId, sessionId)` 调用时自动加载状态。
- `RedisAgentStateStoreExample` 使用 `RedisAgentStateStore.builder().jedisClient(...).keyPrefix(...).build()`。副本 A 和 B 分别创建连接、状态存储和 Agent，证明恢复来自 Redis 而不是内存对象共享。
- 模块新增 `io.agentscope:agentscope-extensions-redis:${agentscope.version}`；其编译依赖已提供 Jedis 客户端。

两类示例均将每个用户问题包装成 `RuntimeContext`，由 `(userId, sessionId)` 定位状态槽位，并在调用后读取 `AgentState#getContext()` 的数量。API Key 与 Redis URL 都通过环境变量提供，输出中不会包含 API Key 或完整 Redis URL。未设置 `REDIS_URL` 时，Redis 示例连接 Rocky 联调服务器 `192.168.0.66:6379`；环境变量仍可覆盖该默认值。

`RedisAgentStateStore` 自身不实现 `AutoCloseable`，但其 `close()` 会关闭注入的 Jedis 客户端。因此 Redis 示例在 Agent 关闭后于 `finally` 显式关闭状态存储，避免连接池遗留。

与设计无差异。
