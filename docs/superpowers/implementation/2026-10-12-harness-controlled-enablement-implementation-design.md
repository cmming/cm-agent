# 时钟校准与 Harness 受控启用评估实际交付

日期：2026-10-12；主题：harness-controlled-enablement；修订：R0；模式：mode=default。

实际操作：在 ssh rocky 中使用原 chronyd，执行 refresh/online/burst 恢复离线源，确认 2026-10-12 新样本后 makestep；系统时间从 2026-10-05T16:18:52Z 跳至 2026-10-12T07:03:05Z，并两次将 RTC 同步为 UTC。后续系统已同步、NTP active、chronyd active/enabled，waitsync correction=0.001030669秒。未更换 NTP pool、修改配置或重启服务；原 offline 原因未确定，不能归因为已证实的网络组件故障。

候选库只读一致性快照：MySQL V20，Run170条、8 RUNNING，其 started_at 范围 2026-09-01 01:02:41 至2026-09-28 02:56:15；TEST22条全部终态，检查点0，待审批0。审批历史8 APPROVED、2 DENIED、1 EXPIRED。旧 RUNNING 时戳不能证明工具未执行或可安全取消。PostgreSQL V1、Run0，TEST/检查点/模式表缺失。

本机只见 cm-agent 的三个 Compose 数据服务；Java 进程属于 Neo4j/Elasticsearch，未见 CM Agent systemd 单元或应用容器。没有停止、迁移或重启本项目应用，不对其他主机实例作推断。

远端候选包仍为 T6 最终 SHA256 b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12，Git 基线6752bf88c173dfd8fd31c58339328da9ac2fe825；这不是新的干净提交，577源码覆盖及未提交差异沿用 T6 身份证据，本轮未重建包。

实际仓库交付：本六份文档；运维说明同步 T6 完成事实，补充时间校准、只读排空 SQL、当前准入边界；发布说明记录运维更新。无 Java、POM、YAML、数据库或默认模式修改。证据和仅执行辅助脚本在忽略目录 `cm-agent-server/target/harness-controlled-enablement/`，不加入版本控制。

与期望启用的差异：校准完成，评估完成，但正式启用暂缓；没有以 T6 临时库验收替代当前数据集门禁，没有将8条旧 RUNNING 批量改为终态。解除条件见设计。

关联：[清单](../checklists/2026-10-12-harness-controlled-enablement-checklist.md)、[提示词](../prompts/2026-10-12-harness-controlled-enablement-prompt.md)、[设计](../specs/2026-10-12-harness-controlled-enablement-design.md)、[计划](../plans/2026-10-12-harness-controlled-enablement.md)、[账本](../progress/2026-10-12-harness-controlled-enablement-ledger.md)。
