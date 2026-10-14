# T3 审批恢复、状态与停止清单

日期：2026-10-10；主题：harness-t3；修订：R0；模式：mode=default。
Git 基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

用户明确授权推进架构 T3；来源为 [T2 提示词](../prompts/2026-10-10-harness-t2-prompt.md) 与 [架构清单](../checklists/2026-10-10-harness-architecture-checklist.md)。T2 的只读入口不限制本轮明确实施授权。历史架构、兼容实验、入口收紧、T2 共 24 份文档保持原哈希。

范围为架构 T3 的审批恢复、运行状态和停止合同；默认仍 `react`，显式 Harness 使用全部 AgentScope 2.0.2。不新增接口、配置键或数据库迁移，不改依赖、前端、MCP Server2.0.0/示例客户端0.17.0，不开展 T4–T6、提交或部署。

| 编号 | 状态 | 交付与验收 | 依赖 |
|---|---|---|---|
| A0 | 完成 | 核对当前 T2、AgentScope 2.0.2、JDK 21、Git 与脏文件，保留历史 | 无 |
| A1 | 完成 | 审批创建/通知/恢复前置失败关闭 Run 与清理状态；恢复读取及 ASK 关闭失败严格传播 | A0 |
| A2 | 完成 | 实际 Harness、加密 Store、编排对象重建、原 Run、单次/多轮/重复/部分审批、撤权与历史 | A1 |
| A3 | 完成 | 等待停止、恢复停止、停止先于模型准入、实际工具退出、严格审计、过期竞态、Web 错误/权限 | A1 |
| A4 | 完成 | Rocky PostgreSQL16/MySQL8.4 的实际 Harness 合同、回滚、到期及竞态，当前源码哈希一致 | A2、A3 |
| A5 | 完成 | 六文档、生产说明、差异和敏感信息检查，报告真实验收边界 | A0–A4 |

## 当前证据与验收

- Adapter：`AgentScopeReActExecutor` 在恢复前直接校验原 `agent_state`，在 ASK 后关闭失败时删除槽；`AgentScopeRuntimeAdapter` 更正状态槽 JavaDoc。
- Server：`ConversationService`、`ToolApprovalService` 的异常补偿共享 `closeFailedRun`；`RepositoryAgentStateStore` 支持默认槽旧格式只读探测并继续拒绝匿名写入/删除。
- 新测试：`cm-agent-server/src/test/java/com/cmagent/server/runtime/HarnessApprovalFlowTest.java` 19 项；`HarnessApprovalJdbcFlowTest.java` 继承相同合同并追加 2 项 JDBC 回滚。
- 增补测试：`AgentScopeRuntimeContractTest` 的 ASK 关闭失败，由实际 Harness 子类共享重跑；`ToolApprovalServiceTest` 的独立运行前置失败与清理异常优先级；`RepositoryAgentStateStoreTest` 的旧格式匿名探测。

| 情况 | 可观察合同 |
|---|---|
| 会话 HIGH + 开启权限机制 | 同一 Run 暂停，批准前零工具执行；恢复不新增 USER 或重复历史 |
| 全部拒绝 | DENIED、零工具、状态删除 |
| 部分批准 | PARTIALLY_APPROVED 保留逐项决定；只执行允许项，模型正常完成时沿用 SUCCEEDED |
| 当前授权撤销/密文损坏 | FAILED、零新模型/工具执行、状态清理；不回退新运行 |
| 检查点读取数据库故障 | 原 DataAccessException 严格传播，Run 失败、零工具；清理不可用不假报成功 |
| 重复决定/并发重复 | 409 冲突，一次实际工具；历史只读 |
| 审批到期/到期与批准竞争 | EXPIRED + DENIED 或批准方正常完成，条件更新只允许一方生效 |
| 等待审批停止 | CANCELLED，PENDING 取消且版本递增，旧批准不能恢复，状态删除 |
| 恢复中停止 | 先 CANCELLING；实际工具退出才 CANCELLED，不接纳迟到模型输出 |
| 停止后严格工具审计失败 | FAILED 并传播严格异常，不能误报正常取消 |
| 无会话 Run/TEST | 非交互 HIGH 仍直接 DENIED，不隐式创建审批或会话消息 |

A0–A5 已完成；以实际命令和结果为准，详见账本。文档生成不代替业务验收。

## 关联产物

- [提示词](../prompts/2026-10-10-harness-t3-prompt.md)
- [设计](../specs/2026-10-10-harness-t3-design.md)
- [计划](../plans/2026-10-10-harness-t3.md)
- [实现说明](../implementation/2026-10-10-harness-t3-implementation-design.md)
- [账本](../progress/2026-10-10-harness-t3-ledger.md)

