# 时钟校准与 Harness 受控启用评估设计

日期：2026-10-12；主题：harness-controlled-enablement；修订：R0；模式：mode=default。

T6 的临时双库排空回退与真实 Provider/浏览器验收已经结束，但 Rocky 时钟偏差和正式部署前提未关闭。用户本轮要求校准时钟并评估受控启用，目标是形成当前环境的可执行准入结论。

范围：既有 chronyd 时间源恢复、系统/RTC 校准、只读实例与项目数据库核查、启用条件和运维记录。非目标：改 Java、依赖、迁移、业务记录、默认 react、生产配置或启动生产实例；不把候选库当作已确认生产数据集。

## 决定与依据

- D1：使用原有 Rocky pool 和 chronyd，不新增时间服务。原源处于 offline，refresh/online/burst 后已有多个新鲜可用源；在新样本选源后 makestep，不按旧 tracking 偏差盲调。
- D2：时钟跳变前检查本机应用；未发现 CM Agent 进程。仍存在共享主机业务，校准属于用户本轮明确授权，只改变主机时间和 RTC，不停止其他服务。校准后可能使既有 JWT、审批和检查点按真实时间过期，不能延长历史 TTL 掩盖。
- D3：读取两个 Compose 项目库作为候选数据集，不迁移或改业务状态。管理员内部跨租户聚合只输出状态计数，用于系统级部署门禁；不是租户 API，不读取正文、凭据或明文产物。
- D4：MySQL 尚有8条 RUNNING 且缺 V21，PostgreSQL 仅 V1；当前不能启用。默认 react 保持。正式入口、数据集、所有实例和可恢复备份未核实，因此不因本机无进程就认定生产停止。

门禁来自当前 `JdbcRuntimeExecutionModeRepository`：Run 只允许 SUCCEEDED/FAILED/DENIED/CANCELLED；TEST 只允许 PASSED/NOT_TRIGGERED/FAILED；全部 runtime_checkpoints 必须为零，过期或孤立状态也阻塞。V21 模式行锁在应用启动时核对；不可直接 UPDATE 模式表绕过。

## 解除阻塞顺序

1. 明确正式数据集、网关/自动任务入口和所有实例。先暂停新增 Run/TEST/自动任务，保留经认证的运维治理通道；本项目未提供通用维护开关，不编造配置键。
2. 在备份与恢复预案落实的窗口部署同一候选版本并保持 react，由 Flyway 正常补齐迁移；V21 初始 react 不意味着历史 Run 已排空。禁止修改发布过的迁移。
3. 通过既有审批/停止生命周期收口；失去实际执行句柄的 RUNNING 不能靠重启自动恢复。逐条核查工具外部副作用、状态来源与审计，另行制定受控修复，禁止自动重发/批量 SQL 改终态。
4. 在入口关闭期间重新核对 Run/TEST/全部检查点为零，并核查待审批与在途工具；停止全部应用实例，保留正常退出证据。
5. 满足身份、JDBC、独立密钥/私有产物卷、固定沙箱及备份还原条件后，经明确部署指令在外部配置统一设 harness，启动受控单实例；启动拒绝时保持隔离并核查权威数据，不能强改模式。
6. 验证中文续聊/SSE、审批恢复/拒绝/停止、真实 Provider、Skill TEST/下载和严格审计，再逐步恢复入口。同版本回切 react 使用同样排空；旧无门禁二进制不得混用，只适用已验收版本与空检查点前提。

验收：新鲜 NTP、误差小于100毫秒、chronyd active/enabled、系统已同步、RTC 为 UTC；只读排空快照和迁移状态可追溯；准入结论不越过上述缺口。重启/恢复虚拟机后的持续同步、生产备份还原和启用窗口尚未验证。

关联：[清单](../checklists/2026-10-12-harness-controlled-enablement-checklist.md)、[提示词](../prompts/2026-10-12-harness-controlled-enablement-prompt.md)、[计划](../plans/2026-10-12-harness-controlled-enablement.md)、[实现](../implementation/2026-10-12-harness-controlled-enablement-implementation-design.md)、[账本](../progress/2026-10-12-harness-controlled-enablement-ledger.md)。
