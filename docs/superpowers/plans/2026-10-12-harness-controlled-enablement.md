# 时钟校准与 Harness 受控启用评估计划

日期：2026-10-12；主题：harness-controlled-enablement；修订：R0；模式：mode=default。

| 编号 | 实施方法 | 完成标准 |
|---|---|---|
| E0 | 读取 T6、运维说明与工作树；保存已有文件摘要；SSH 清点应用，核对远端基线和可执行包 | 区分第三方 harness、数据服务与本项目应用；不输出进程参数/环境/配置密钥 |
| E1 | chronyc activity/sources/tracking 检查；refresh、online、burst 恢复源；得到新鲜选源后 makestep、hwclock --systohc --utc；间隔复核 waitsync/timedatectl | 小于100毫秒、NTP新样本、已同步、active/enabled；不信任旧样本，不变更无关服务 |
| E2 | 只读一致性事务输出候选库版本、状态分组和门禁聚合；缺表显式记录 | Run/TEST/全部检查点及待审批有明确计数或缺失原因；无业务写入 |
| E3 | 对照 V21 当前源码和 T6 历史证明，落实正式目标/入口/全实例/备份恢复等缺口 | 输出允许或暂缓判断、解除步骤；不自动执行生产启用 |
| E4 | 更新本六文档、docs/operations.md、docs/release-notes.md；检查链接、差异和既有摘要 | 仅本轮文件变化、无凭据、历史 T6保留、未提交明确 |

代码定位先用 CodeGraph，旧索引未覆盖新模式仓储时回读当前 `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRuntimeExecutionModeRepository.java`。数据库命令仅 SSH Rocky，使用现有项目 PostgreSQL16/MySQL8.4 容器；本轮无需 Maven 构建或重新启动验收应用。

排空解除和实际部署属于后续实施范围，详见设计。无应用实例时只能检查候选库，不能模拟一个空实例就算历史数据已排空。

关联：[清单](../checklists/2026-10-12-harness-controlled-enablement-checklist.md)、[提示词](../prompts/2026-10-12-harness-controlled-enablement-prompt.md)、[设计](../specs/2026-10-12-harness-controlled-enablement-design.md)、[实现](../implementation/2026-10-12-harness-controlled-enablement-implementation-design.md)、[账本](../progress/2026-10-12-harness-controlled-enablement-ledger.md)。
