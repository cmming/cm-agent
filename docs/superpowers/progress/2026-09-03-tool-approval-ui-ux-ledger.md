# 工具人工确认 UI 交互优化进度账本

后续状态：用户已确认补齐历史，已在独立[审批历史账本](2026-09-03-tool-approval-history-ledger.md)记录实现和验证；以下是原卡片优化阶段的验收记录，保留当时缺口以便追溯。

## 关联文档

- [设计说明](../specs/2026-09-03-tool-approval-ui-ux-design.md)
- [实施计划](../plans/2026-09-03-tool-approval-ui-ux.md)
- [实现说明](../implementation/2026-09-03-tool-approval-ui-ux-implementation-design.md)

## 任务状态

| 任务 | 状态 | 结果 |
| --- | --- | --- |
| 原交互梳理 | 已完成 | 明确 PENDING、完整决定、提交、恢复、权威查询和发送锁的既有链路 |
| 选择和提交反馈 | 已完成 | 单项简化、多项批量选择说明、计数与结果型按钮文案 |
| 只读和失败体验 | 已完成 | 就近错误、阶段区分、只查询的刷新入口，未知状态保持关闭 |
| 内存草稿和焦点 | 已实现并自动化检查 | 快照变化失效；重绘焦点回退；真实浏览器行为待验收 |
| 响应式与资源版本 | 已完成 | 终态折叠、窄屏与长文案规则，v2 八页缓存版本统一 |
| 文档与发布说明 | 已完成 | 本主题四份文档及生产说明同步 |

## 实际验证

环境：Windows 当前进程 JDK 21.0.11、Maven 3.9.4。默认 Java 17 不用于本次构建，已显式设置 `F:\java21`。

| 命令 | 结果 |
| --- | --- |
| `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js` | 通过 |
| `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js` | 通过 |
| `node --test cm-agent-console/src/test/js/console-core.test.cjs` | 60 项通过，失败/跳过均为 0 |
| `mvn -q -pl cm-agent-console -am test` | Console 资源测试 12 项通过，失败/错误/跳过均为 0 |
| `mvn -q -pl cm-agent-server -am "-DskipTests" package` | 打包通过，退出码 0 |
| `git -c core.safecrlf=false diff --check` | 通过 |

首次 Node 回归发现原静态测试仍匹配旧 `updateApprovalSubmitAvailability` 签名，已同步为新的显式审批/决定/汇总输入后全量通过。新增隔离编排测试执行真实函数，证明双击只发一次决定 POST、未知状态查询失败保持关闭、手动刷新不提交和旧会话响应被忽略。

## 未验证与保留项

- 未启动真实浏览器进行 DOM、键盘焦点和窄屏视觉验收；未进行真实模型/工具全链路联调。
- 本次仅前端变化，未重复运行后端全量和数据库集成套件，不将历史测试结果当作本轮新增验证。
- 原审批实现的主动过期清理、跨客户端执行租约和崩溃恢复限制保持不变。
- 用户反馈只显示最后一次且无法回显，已确认是 PENDING 列表重建与只补回当前决定的逻辑所致。完整终态历史回显尚未实现，不能把本轮终态折叠/草稿优化描述为历史回放。
- 工作区既有后端实现、应用配置和 `.workbuddy/` 内容未覆盖；本次改动叠加在已有未提交审批实现之上。

## 提交与后续

本主题随本账本所在提交一并提交，提交说明为 `feat: 完善工具人工审批及会话历史回显`；未推送远端，本地配置和 `.workbuddy/` 保持未提交。具体提交号通过本文件的 Git 历史查询。下一步在重启后的服务中用无副作用 HIGH 测试工具验收单项、混合决定、断网重连和窄屏/键盘操作。
