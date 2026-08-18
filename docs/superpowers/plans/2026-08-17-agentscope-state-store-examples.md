# AgentScope JSON 文件与 Redis 状态存储示例实施计划

对应设计：[JSON 文件与 Redis 状态存储示例设计](../specs/2026-08-17-agentscope-state-store-examples-design.md)。

1. 在 `dashscope-mcp-agent` 模块增加 Redis 扩展依赖，供 `RedisAgentStateStore` 和 Jedis 使用。
2. 新增 JSON 文件示例：使用相同目录、重建 Agent 和相同 `RuntimeContext` 展示自动持久化与恢复。
3. 新增 Redis 示例：使用两个独立的 Jedis/状态存储/Agent 组合模拟副本 A 与 B，展示跨实例恢复。
4. 完成 JDK 21 编译打包和未配置 API Key 的安全退出验证；不使用真实凭据或真实 Redis 调用。

涉及文件：模块 `pom.xml`、两个 Java 示例类，以及本组设计、计划、实现说明和进度账本。
