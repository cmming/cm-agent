# OpenCode Go 消息名称兼容设计

## 背景与目标

运行日志显示 Go 聊天端点返回 HTTP 400：`messages[0]: "name" is not supported by this endpoint`。
本机实际依赖为 AgentScope 2.0.2，经字节码核对，默认 `OpenAIMessageConverter` 会把
system、user、assistant 的 `Msg.name` 写入消息 DTO。目标是消除这个协议不兼容字段。

## 范围与非目标

仅修改 adapter 的模型工厂与新增专用格式化器；不改变 Agent 名称、历史消息、工具治理、凭据、
数据库、错误处理或控制台。不全局删 JSON 中的 `name`，不升级依赖，不处理其他供应商潜在兼容问题。

## 方案与约束

精确匹配 `OPENAI_COMPATIBLE`、主机 `opencode.ai`、路径 `/zen/go/v1` 或 `/zen/go/v1/`。
继承默认聊天格式化器，委托原格式转换后仅将新 DTO 的消息级名称置空，由框架编码器省略空值。
工具 `function.name`、schema 属性名、参数中的 `name` 和结果关联不变。格式化不依赖 runId，
现有会话头逻辑继续使用可信服务端运行标识。测试替换最终网络出口，不使用真实模型凭据。

## 验收

- 实际序列化报文无消息级 name，工具定义、调用、参数、正文、角色和关联字段完整。
- 原始 Msg 不变；公开工厂入口、带 runId 入口、尾斜杠及大小写主机行为一致。
- 其他网关、相似恶意域名、Zen 与相邻路径保持默认格式。
- 定向测试、适配器及上游测试通过；真实服务重启后的验证单独记录。

## 关联记录

- [计划](../plans/2026-09-21-opencode-message-name.md)
- [实现](../implementation/2026-09-21-opencode-message-name-implementation-design.md)
- [账本](../progress/2026-09-21-opencode-message-name-ledger.md)
