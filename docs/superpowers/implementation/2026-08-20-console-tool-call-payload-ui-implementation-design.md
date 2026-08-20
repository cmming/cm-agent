# 控制台工具调用载荷展示优化实现说明

## 实际实现

`renderToolCall` 改为构造独立执行卡片：工具名称与运行状态位于卡片头部，授权状态、执行耗时和内容范围使用三个紧凑元数据项展示。请求输入和执行输出使用并列载荷区；运行有错误时追加横跨整行的错误卡片。

`formatToolCallPayload` 仅在浏览器中尝试解析现有摘要文本。JSON 解析成功时以两空格缩进显示并标记为 JSON；失败时保留原文本并标记为文本；空摘要显示明确占位信息。所有内容通过 `textContent` 写入，且来源仍是服务端 `SensitiveDataRedactor` 已处理的字段。

`styles.css` 增加载荷卡片、元数据块、错误态和小屏单列规则。大段输入输出限制在可滚动区域内，避免单条工具调用压缩其他运行详情。

## 与原方案的差异

无范围差异。没有新增 API 字段或改变摘要长度，界面仅重新组织既有脱敏数据。

相关设计见 [2026-08-20-console-tool-call-payload-ui-design.md](../specs/2026-08-20-console-tool-call-payload-ui-design.md)，任务清单见 [2026-08-20-console-tool-call-payload-ui.md](../plans/2026-08-20-console-tool-call-payload-ui.md)。
