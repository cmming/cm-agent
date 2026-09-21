# OpenCode Go 消息名称兼容实际实现

## 调用链与代码位置

`AgentScopeModelFactory#createOpenAiCompatibleModel` 根据基础地址主机及 Go v1 路径装配
`OpenCodeGoChatFormatter`。`OpenCodeGoChatFormatter#doFormat` 调用框架原实现后，
对新建 `OpenAIMessage` 执行 `setName(null)`。实际模型编码器省略这些空属性，
之后既有 `ProviderSessionHeaderHttpTransport` 继续注入运行会话头。

已核对实际 AgentScope 2.0.2 依赖字节码，并用真实 `Model.stream` 到 HTTP 传输出口的测试
验证最终 JSON，而非仅断言 DTO 为 null。原始 Msg 不变，嵌套工具名称、schema 和参数不受影响。
不引入异常捕获、重试、日志或错误转换分支。

## 验证覆盖

`OpenCodeGoRequestTest` 参数化覆盖：标准地址、尾斜杠、大写主机、无 runId 公开入口、普通网关、
伪装域名、Zen 路径、相邻 v10 路径；包含 system/user/assistant/tool 四类消息和工具定义。
测试仅替换最终传输出口，不进行公网调用、不持有真实凭据。

## 与设计差异

无。未重启用户当前服务，真实 Provider 验收状态见账本。

关联：[设计](../specs/2026-09-21-opencode-message-name-design.md)、
[计划](../plans/2026-09-21-opencode-message-name.md)、
[账本](../progress/2026-09-21-opencode-message-name-ledger.md)。
