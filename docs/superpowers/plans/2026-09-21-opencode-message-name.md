# OpenCode Go 消息名称兼容实施计划

1. 核对工作区、模块 POM/README、实际 AgentScope 2.0.2 字节码和既有会话头实现。
2. 新增 `OpenCodeGoChatFormatter`，在 `AgentScopeModelFactory` 精确匹配 Go 地址后装配。
3. 新增 `OpenCodeGoRequestTest`，通过真实模型序列化并捕获最终请求，验证名称省略与工具字段保留。
4. 使用 Java 21 执行定向测试与 `mvn -pl cm-agent-agentscope-adapter -am test`。
5. 同步 adapter README、发布说明和本任务四份文档；检查 diff，不提交无关配置。
6. 浏览器检查用户提供的控制台；需服务重启后才能验证真实 Provider 使用新代码。

涉及文件位于 adapter 主代码/测试目录、adapter README、`docs/release-notes.md` 和本组过程文档。

关联：[设计](../specs/2026-09-21-opencode-message-name-design.md)、
[实现](../implementation/2026-09-21-opencode-message-name-implementation-design.md)、
[账本](../progress/2026-09-21-opencode-message-name-ledger.md)。
