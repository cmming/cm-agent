# OpenCode Go 运行会话路由与状态槽告警修复计划

关联[设计](../specs/2026-09-18-opencode-go-runtime-session-design.md)。

1. 解析 AgentScope OpenAI Builder 与 HTTP 传输接口，确认无法直接传入自定义 Header，并定位默认状态槽读取调用。
2. 在模型工厂以可信 `runId` 构造 OpenCode 专用传输包装器，仅匹配精确主机。
3. 在状态仓储把无身份的只读默认槽探测作为未命中，保留所有可持久化操作的身份校验。
4. 覆盖请求头、非 OpenCode 隔离、默认槽读取和无身份写入拒绝测试。
5. 更新 README、配置、发布说明及本组实现和进度文档；不变更用户已有本地 YAML。
