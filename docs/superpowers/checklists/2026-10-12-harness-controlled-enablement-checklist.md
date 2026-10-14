# 时钟校准与 Harness 受控启用评估清单

日期：2026-10-12；主题：harness-controlled-enablement；修订：R0；模式：mode=default。
Git 基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；工作树包含前序未提交修改。

用户要求：校准目标环境时钟，再按排空流程评估受控启用 Harness。本轮授权覆盖 Rocky 时间校准与评估，不自动部署生产、切换数据集、提交或推进 T7/T8。

| 编号 | 状态 | 目标与文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| E0 | 完成 | 核实 Rocky、当前包、既有修改；本账本 | 无 | SSH 可用、实例清点、基线与 JAR 摘要明确，保护原修改 |
| E1 | 完成 | 恢复时间源、校准系统和 RTC；运维说明 | E0 | 新鲜 NTP 样本、waitsync 成功、系统已同步、chronyd active/enabled |
| E2 | 完成 | 只读检查项目库的 Run、TEST、审批与检查点 | E1 | 固定终态白名单，全部检查点计数，缺失表记为缺失而非零 |
| E3 | 完成 | 给出受控启用判断和解除条件；设计/账本 | E2 | 区分历史 T6 验收、当前候选库与尚未确定的正式目标；不强行清理 |
| E4 | 完成 | 六份工作包、运维/发布说明与保护检查 | E3 | 链接与编号一致，差异和敏感信息检查通过，明确未提交 |

当前事实：Rocky 系统原为 2026-10-05，四个 chrony 源 offline、Reach=0，旧样本约 20 天；重新 refresh/online/burst 后取得 2026-10-12 新样本，再 makestep、同步 RTC。2026-10-12 07:05:04 UTC 复核已同步，偏差约 1 毫秒。

| 项目候选数据集 | 迁移 | 非终态 Run | 非终态 TEST | 全部检查点 | 待审批 | 判断 |
|---|---|---|---|---|---|---|
| Rocky cm-agent-mysql-1 / cm_agent | V20，无模式表 | 8 / 总计170 | 0 / 总计22 | 0 | 0 | 未排空，禁止切换 |
| Rocky cm-agent-postgres-1 / cm_agent | V1 | 0 / 总计0 | 表缺失 | 表缺失 | 未查询 | 结构不足，不能认定满足门禁 |

没有观察到 Rocky 本机运行中的 CM Agent 应用容器、Java 服务或 systemd 单元；不能据此排除其他主机实例或证明入口已排空。名为 harness 的第三方容器不属于本项目。

结论：**本轮评估完成，正式启用暂不具备条件**。正式数据集/入口/全部实例、备份还原证据尚未确定；MySQL 的8条 RUNNING 需通过受控治理和副作用核查收口，不能直接改终态或删记录。候选包摘要与 T6 最终包一致，仅代表包身份。

证据：忽略目录 `cm-agent-server/target/harness-controlled-enablement/` 的 clock-calibration.log、readiness.log、drain.log、final-check.log；历史验收见 [T6账本](../progress/2026-10-12-harness-t6-ledger.md)，不冒充本轮重跑。

关联：[提示词](../prompts/2026-10-12-harness-controlled-enablement-prompt.md)、[设计](../specs/2026-10-12-harness-controlled-enablement-design.md)、[计划](../plans/2026-10-12-harness-controlled-enablement.md)、[实现](../implementation/2026-10-12-harness-controlled-enablement-implementation-design.md)、[账本](../progress/2026-10-12-harness-controlled-enablement-ledger.md)。
