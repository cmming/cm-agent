# T3 设计

日期：2026-10-10；主题：harness-t3；修订：R0；模式：mode=default。
Git 基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

用户明确授权推进架构 T3；来源为 [T2 提示词](../prompts/2026-10-10-harness-t2-prompt.md) 与 [架构清单](../checklists/2026-10-10-harness-architecture-checklist.md)。T2 的只读入口不限制本轮明确实施授权。历史架构、兼容实验、入口收紧、T2 共 24 份文档保持原哈希。

范围为架构 T3 的审批恢复、运行状态和停止合同；默认仍 `react`，显式 Harness 使用全部 AgentScope 2.0.2。不新增接口、配置键或数据库迁移，不改依赖、前端、MCP Server2.0.0/示例客户端0.17.0，不开展 T4–T6、提交或部署。

## 当前问题与选择

首次 ASK 后审批创建失败可能留下检查点；决定通知失败原位于恢复补偿之外，可能留下 RUNNING。独立运行前置失败也未统一删除状态。框架默认加载回退会掩盖读取失败，ASK 后关闭失败也不应继续保留可恢复槽。

采用共享短锁/短事务补偿，使用仓储的可信 tenant、agent、原发起主体及 runId；先关闭活动 Run，再删除同一状态槽，即使 Run 已进入终态也不能跳过删除。清理失败优先传播、附加原异常，不重试模型或工具。

恢复前显式读取原 agent_state，保留数据库/解密故障，并继续校验原工具ID、哈希与最新授权。Harness 资源每片段重新建立，状态由宿主 Store 持有，长期历史由 ConversationPromptComposer 编排。匿名默认槽读探测返回空，写入/删除仍拒绝。

## 状态合同

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

## 假设与边界

默认自主决定：保持现有部分批准的总体成功语义，区分用户逐项拒绝和工具执行授权拒绝；保持单实例句柄与非交互 HIGH 拒绝，不实现新接管机制。固定 Skill 快照现有单元回归仍执行；本轮实际 Harness 会话未读取脚本或产物，交给 T4。

真实外部 Provider 的断连、真实浏览器/SSE网络、非空 Skill 经 Harness 的脚本/产物链、Studio/MCP 联调和完整生产准入未执行，属于后续 T4–T6；模型只用回环协议桩。对象重建验证重新创建 Store、Runtime、RunExecutionService、ToolApprovalService，但不等于强杀进程恢复。提交决定后的崩溃不自动重放，无跨实例执行租约/停止接管。数据库不可用时补偿可能失败，仍应核对 Run/审批/检查点，不能承诺强制清理。

Rocky UTC 当前为2026-10-03，本机为2026-10-10，存在约七天偏差；本轮使用同一验证主机的时间或合成截止时间，不调整虚拟机时钟，不宣称跨主机 TTL 验收。部署需同步时钟。

## 关联产物

- [清单](../checklists/2026-10-10-harness-t3-checklist.md)
- [提示词](../prompts/2026-10-10-harness-t3-prompt.md)
- [计划](../plans/2026-10-10-harness-t3.md)
- [实现说明](../implementation/2026-10-10-harness-t3-implementation-design.md)
- [账本](../progress/2026-10-10-harness-t3-ledger.md)

