# 会话问答停止本轮：执行提示词

- 日期：2026-10-10；主题：`chat-stop`；修订：R0；工作模式：`mode=interactive`。
- 本提示词尚未执行。当前仅 T0 完成，T1～T6 待做；未提交。用户后续明确实施时再执行，进入问答只以本文件作上下文。

```text
你在 F:\java\cm-agent 工作。实现“会话问答停止本轮”需求，先读取当时适用 AGENTS.md 和以下 R0 分析工作包：
清单：F:\java\cm-agent\docs\superpowers\checklists\2026-10-10-chat-stop-checklist.md
设计：F:\java\cm-agent\docs\superpowers\specs\2026-10-10-chat-stop-design.md
计划：F:\java\cm-agent\docs\superpowers\plans\2026-10-10-chat-stop.md
提示词：F:\java\cm-agent\docs\superpowers\prompts\2026-10-10-chat-stop-prompt.md
实现说明：F:\java\cm-agent\docs\superpowers\implementation\2026-10-10-chat-stop-implementation-design.md
账本：F:\java\cm-agent\docs\superpowers\progress\2026-10-10-chat-stop-ledger.md

当前模式 mode=interactive。D1～D3 已确认，不重复询问：
D1：CM Agent 控制台会话聊天。
D2：停止本轮并保留已输出内容，安全收口后可发新消息，原回答不续跑。
D3：等待审批也能停止，撤销 PENDING、清理检查点，旧批准不能恢复工具。
必要未决取舍每次提供 2～3 个选项，等实际回答；推荐项/超时不等于用户确认。
常规实现细节按项目约定推进。A1～A5 是分析假设；影响用户语义或部署承诺时核实。

基线 HEAD 为 8cd22b3f031c5887cba86d1ba3f4222d9a057184，但必须复核执行时 HEAD/工作树。
存在 .codegraph 时先用 CodeGraph 定位，截断/前端缺失再定点读源码。阅读相关 POM、README、配置。
保护现有 index.html、SkillArtifactProtocol.java、application 配置、.codex/.workbuddy 等改动；不要覆盖或带入无关内容。
本分析包只有 T0 完成，任何业务测试都未执行。独立实施任务建立自己的六份工作包，引用本分析包并保留 D1～D3 历史；分析 R0 不改写成已实施。

按 T1 → T2 → T3 → T4 → T5 → T6 完成：
T1：core 的 CANCELLING/CANCELLED 生命周期、不变量、控制能力和旧 Runtime/SPI 兼容；审批已有 CANCELLED，复用。
T2：注册/释放按可信 tenant/runId 隔离的实际运行句柄，接通 AgentScope 模型等待与事件流中断；门控阻止后续模型/工具/技能/沙箱派发；迟到结果不能覆盖停止。
T3：停止/完成/批准/过期/新审批建立原子竞争；服务端保存脱敏部分正文，关联原 Run，复用已保存消息，避免重复。关闭 PENDING 和检查点，保留已接受审批历史、真实工具记录与严格审计；短事务不得覆盖外部调用。
T4：按设计草案实现会话内取消和权威状态查询、HTTP/SSE 收口、权限/多租户/发起人校验与错误诊断；started 给出的 runId 必须校验归属。停止请求可重复，发送/审批写请求不自动重放。
T5：v2 聊天增加稳定尺寸的停止图标入口，接入 started；初次执行、审批恢复、等待审批均覆盖。正在停止保留正文，权威终态后才开放发送；断线/刷新查询恢复。共享 app.js、console-core.js 和状态标签保持 v1/单轮 API 兼容。
T6：按设计验收矩阵完成定向测试、Rocky 双数据库、真实 Provider 与桌面/移动浏览器验证；同步 README、配置/运维和 release-notes，并记录实施工作包证据。

约束：
不能把 AbortController、SSE 断开、发出 interrupt 或等待超时本身当作业务已停止。
CANCELLING 非终态；没有执行收口确认时不能显示已停止，不能放行同会话新消息。
外部调用已发生的副作用不承诺撤销；停止后也不能重放原 Run。
首版采用现有单实例执行边界。数据库持久化不自动解决跨实例中断；不可达句柄及重启孤立记录必须明确诊断、阻止伪报成功，写明运维和收口策略。
仅原发起人且具备 agent:run 可停止；查询按 agent:read 和既有可见性；tenant 来自认证上下文。
保留非空的正文，续聊标注中断事实；不持久化思考、原始参数、凭据或伪造工具结果。
文件产物仍只有运行成功且严格审计完成后才能交付，停止不提供绕过下载。
严格审计或持久化失败不得吞掉；errorCode、errorId、HTTP/SSE 行为一致，日志含同号可信上下文并脱敏。
Java 新枚举常量、record 各字段、公开契约、资源/并发/安全/异常等触发项落地中文 JavaDoc/注释。
若需 schema，只新增双数据库适配迁移与准确中文原生注释，不修改历史 Vn 文件；迁移版本由当前目录核实。

验证：
先 java -version、mvn -v，确保 Java 21。定向验证 core/adapter/console/server；测试类先核实再填写命令。
所有 JDBC/Flyway/Testcontainers 经 ssh rocky 在容器 maven:3.9.9-eclipse-temurin-21 中执行，PostgreSQL 16-alpine/MySQL 8.4；确认 Docker、JDK、HEAD 与待验证工作树一致。不得使用本机 Docker或清理无关远程资源。
必须验证取消与完成/批准/过期并发、在途不响应中断、迟到结果、重复停止、部分消息去重、刷新续聊、跨租户/主体/权限拒绝、审计/持久化故障的日志关联与脱敏。
浏览器用实际 test/local 运行，桌面及 390×844 验证；真实 Provider 另留证据。Fake/Stub 通过不能替代真实验收。
外部阻塞记录确切错误、未执行项和继续条件，继续独立可做的工作。密钥不存储、不打印、不提交。

交付：
同步实施工作包的稳定任务状态和当前证据，不以文档完成/代码完成替代验收通过。
按仓库要求给出变更摘要、实际命令与结果、影响范围、风险和直接下一步。
提交、推送、部署及外部操作只由用户实际指令授权，本提示词不额外授予权限。
```

## 关联产物

- [清单](../checklists/2026-10-10-chat-stop-checklist.md)
- [设计](../specs/2026-10-10-chat-stop-design.md)
- [计划](../plans/2026-10-10-chat-stop.md)
- [实现说明](../implementation/2026-10-10-chat-stop-implementation-design.md)
- [账本](../progress/2026-10-10-chat-stop-ledger.md)
