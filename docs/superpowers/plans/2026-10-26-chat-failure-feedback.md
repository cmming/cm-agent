# 会话失败反馈实施计划

## 当前修订 R1（自主模式）

| 编号 | 实施位置与顺序 | 验证 |
| --- | --- | --- |
| T5 | adapter 新增 ModelFailureSummary，ReAct/Harness 公用循环保留安全上游摘要；core AgentRunResult/RunRecord 添加派生诊断 getter；Server 默认抑制第三方原始响应日志 | 真正本地 HTTP 500、异常类型兼容、敏感信息过滤、日志编号对应；无数据库列变化 |
| T6 | 新增摘要单测，扩展 RuntimeContract、RunRecord、RunController 测试；维护 adapter README、配置说明、发布记录及工作包 | JDK 21 定向 Maven 测试及全部 console Node 测试；同步/SSE/历史结果一致 |

T5 依赖已完成的 T1～T2；T6 依赖 T5。所有新结果写入账本 R1，不能用 R0 的 149/15 通过作为后端验收。后端不自动重启；静态资源通过不代表 JVM 已加载新类。只运行 memory 与本地 HTTP Stub 测试，不启动本机 Docker。

## R0 原计划（历史）

修订 R0；模式：自主。状态与实际证据以[账本](../progress/2026-10-26-chat-failure-feedback-ledger.md)为准。

| 编号 | 实施顺序与位置 | 验证 |
| --- | --- | --- |
| T1 | CodeGraph 核对 ConversationService、ConversationController；阅读 console POM、README 与现有脏改动，再检查 app.js | 证实失败无 assistant 是已有合同，已有 GET 原 Run 接口可用 |
| T2 | console-core.js 统一文案；app.js 发送、审批、轮询及消息渲染；chat-stop.css 增加错误反馈样式；v2 HTML 同步共享脚本版本 | 仅 SUCCEEDED 显示成功；无回答仍有失败反馈；历史详情 GET 与迟到隔离 |
| T3 | 新增 chat-failure.test.cjs，维护 chat-stop、chat-approval-terminal、console-core 测试和 chat-ux-preview 模拟服务 | Node 全部回归、Java 21 console 资源测试，桌面/手机模拟浏览器截图与可用性 |
| T4 | 发布说明、六份工作包，核对差异与未提交状态 | 不覆盖配置等无关改动，记录真实登录缺口 |

无需数据库或容器验证，因为不涉及后端和数据库实现。真实登录失效时使用模拟服务复现协议，不能把模拟结果写成真实模型通过。测试日志、截图放入 target 或 output/playwright，不提交生成物。

关联：[清单](../checklists/2026-10-26-chat-failure-feedback-checklist.md)、[提示词](../prompts/2026-10-26-chat-failure-feedback-prompt.md)、[设计](../specs/2026-10-26-chat-failure-feedback-design.md)、[实现](../implementation/2026-10-26-chat-failure-feedback-implementation-design.md)。
