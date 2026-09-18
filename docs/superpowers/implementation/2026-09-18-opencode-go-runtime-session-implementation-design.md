# OpenCode Go 运行会话路由与状态槽告警修复实现说明

关联[设计](../specs/2026-09-18-opencode-go-runtime-session-design.md)与[计划](../plans/2026-09-18-opencode-go-runtime-session.md)。

`AgentScopeReActExecutor` 现在把可信 Run ID 传入 `AgentScopeModelFactory`。模型工厂仍保留原有通用创建入口；仅在 `OPENAI_COMPATIBLE` 的基础地址主机为 `opencode.ai` 且运行 ID 存在时，使用 `ProviderSessionHeaderHttpTransport` 包装 AgentScope 的共享传输。包装器复制原请求字段并覆盖 `x-opencode-session`，不会暴露通用自定义 Header 能力，也不会关闭进程级共享传输。

`RepositoryAgentStateStore#get` 对 `userId=null` 返回空结果。这只覆盖 AgentScope 2.0.0 在 `RuntimeContext` 绑定前的默认槽探测，不能读取或持久化任何状态；`save`、删除、存在性查询及非空非法身份仍经原有租户前缀校验拒绝。

实现未修改数据库、API、模型配置结构或用户已有 YAML。README、配置说明和发布说明已明确 OpenCode 的精确主机匹配、服务端 Run ID 来源与重启生效方式。
