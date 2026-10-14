# Harness 2.0.2 准入问题修复计划

日期：2026-10-10；主题：harness-entry-hardening；修订：R0；模式：mode=default。

基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；本次未提交、未推送、未部署。

## 实施顺序与文件

| 编号 | 步骤 | 文件与验证 |
| --- | --- | --- |
| E0 | 核对来源/HEAD/脏文件与当前代码，再记录哈希 | 原十二份分析/实验文档只读；正式 POM/配置；CodeGraph 查 executor/adapter/取消/权限链路 |
| E1 | 无会话 HIGH 规则改为 DENY；按原生事件补拒绝记录和终态，保留 ASK/兼容开关 | AgentScopeReActExecutor.java；AgentScopePermissionPolicyTest.java；AgentScopeRuntimeContractTest.java，断言检查点、网关零调用、日志编号/原因码与脱敏；RunControllerTest.java 验证既有 HTTP/持久化/审计合同 |
| E2 | 实验受控构造添加最后白名单、桥接器身份限定、私有目录、拒绝文件系统/空消息查询；追加负向合同 | experiments/harness-compatibility-2.0.2/src/test/java/com/cmagent/agentscope/Harness202ContractTest.java；真实事件流与调用方目录毒化实验 |
| E3 | JDK21 运行隔离 clean test 与选定服务端回归；不使用旧 SNAPSHOT 代替当前源码 | 本包账本的实际命令与 surefire 报告；不运行无关容器测试 |
| E4 | 同步实际设计、结果和限制；保护历史，检查 diff/文件哈希/链接 | 本组六份文档、docs/configuration.md、docs/release-notes.md、adapter README、实验 README |

E0 → E1 → E2 → E3 → E4，当前全部完成。实际结果以账本为准；这是本轮问题修复计划，不能代替原 T2–T6。

## 关键验证与阻塞策略

先验证无会话拒绝，再确认返回状态与检查点；原生 DENY 可能跳过桥接器导致记录遗漏，必须用实际 Stub 事件流发现，而不是只测试权限对象。ASK 审批/恢复、严格失败、停止、tenant/Run 隔离复用当前适配器合同。

构造后必须在第一次模型请求前校验白名单；宿主路径注入通过只证明测试构造的隔离路径。使用明确拒绝的替身并在结束时核对所有交互，未知操作即失败；生产接入需要具体实现另行验收。

测试失败按实际输出定位后修复，不能把未执行或跳过写成通过。不把数据库/容器迁移到本机 Docker；若后续涉及，按 AGENTS.md 的 Rocky 容器要求执行并记录远程提交。没有自动回退重跑外部副作用。

## 关联产物

- [清单](../checklists/2026-10-10-harness-entry-hardening-checklist.md)
- [提示词](../prompts/2026-10-10-harness-entry-hardening-prompt.md)
- [设计](../specs/2026-10-10-harness-entry-hardening-design.md)
- [计划](../plans/2026-10-10-harness-entry-hardening.md)
- [实现说明](../implementation/2026-10-10-harness-entry-hardening-implementation-design.md)
- [账本](../progress/2026-10-10-harness-entry-hardening-ledger.md)

