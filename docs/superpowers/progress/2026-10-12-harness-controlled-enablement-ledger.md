# 时钟校准与 Harness 受控启用评估账本

日期：2026-10-12；主题：harness-controlled-enablement；修订：R0；模式：mode=default。
Git 基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署生产，默认 react 保持。

| 编号 | 状态 | 实际证据 |
|---|---|---|
| E0 | 完成 | 本机 Git/摘要快照，SSH 实例清点和远端包SHA匹配 T6；environment-inventory.log、readiness.log |
| E1 | 完成 | clock-calibration.log、readiness.log、drain.log、final-check.log：新鲜 NTP、多轮同步、RTC UTC、chronyd enabled/active |
| E2 | 完成 | drain.log：MySQL170/8非终态Run、22/0非终态TEST、0检查点；final-check.log：0 PENDING；PG V1/0Run且缺结构 |
| E3 | 完成 | 暂不启用：候选MySQL8 RUNNING/V20，PG V1；正式入口/数据集/全实例和可恢复备份未核实；设计列解除步骤 |
| E4 | 完成 | 六文档存在，链接/元数据0错误，提示词围栏成对；既有摘要仅运维/发布说明变化；diff --check通过、实际环境凭据匹配0；document-check.log；没有业务代码修改 |

## 本轮执行与结果

证据目录：`cm-agent-server/target/harness-controlled-enablement/`（忽略生成物，非提交内容）。

- `ssh rocky`；`chronyc activity/sources -v/tracking`：开始4源offline、Reach=0、LastRx约20天，参考时间仍为09-20，不能认为已同步。
- `chronyc refresh; chronyc online; chronyc burst 4/4`：均200 OK，原pool取得10-12新样本和选中源。这里的顺序为实际远端逐行执行，未新增服务器。
- `chronyc makestep`：200 OK；校准前UTC10-05 16:18:52，后UTC10-12 07:03:05，跳变约571452秒。`hwclock --systohc --utc`成功。
- 07:03:44/07:04:20/07:05:04 UTC连续观察 System clock synchronized=yes。`chronyc waitsync 6 0.1 0 5`首轮成功，校正量约1.03毫秒；chronyd active/enabled、四源online。RMS offset仍含历史大偏差，不作为当前误差；使用新鲜参考时间、当前correction和sources共同判断。
- Rocky现有项目MySQL8.4/PostgreSQL16-alpine容器内执行只读一致性事务，只输出结构/迁移/聚合状态。MySQL V20、8 RUNNING；PG V1，无必要门禁结构；没有业务UPDATE/DELETE或迁移。
- MySQL8 RUNNING 的 started_at 介于09-01至09-28，待审批0、TEST和检查点不阻塞；没有关联工具副作用核查，不把无当前进程等同可安全强制终结。
- SHA256当前JAR匹配b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12；未重编译或重跑历史131项 T6合同，见[T6账本](2026-10-12-harness-t6-ledger.md)。

初次 PowerShell 管道传入脚本末行带 CR，导致 chronyd 单元名和 JAR 最后参数含CR而查询失败；前面的 makestep/RTC 已成功。随后改用忽略目录 Invoke-Rocky.ps1，以 Process.StandardInput 明确转换 LF，全项复核成功。没有将失败查询计为通过。CodeGraph旧索引缺新仓储，回读当前源码确认门禁；一次rg的Windows通配路径失败，不影响已读取源码。

## 准入结论和未执行

本轮工作是时间校准与评估，均完成；**实际生产启用未执行，当前准入不通过**。正式目标尚未确定，只评估已核实的项目候选库。缺口：权威入口关闭/所有实例清点、8条RUNNING的安全收口、V21正常迁移、正式配置/密钥/私有卷/固定沙箱及生产备份恢复证据。不能通过删除检查点、改模式行或批量补终态解除门禁。

未执行：真实部署、正式备份还原、VM重启后的时间持续同步、全量Maven/新Java测试、T7/T8。原因分别为超出本轮评估范围/正式目标缺失及没有业务代码变化；历史 T6 仍保留，不写成新验收。

影响：Rocky共享主机系统时间及RTC已经校准，时间窗口内各容器共同使用主机时间；既有令牌/审批/检查点可能按真实时间过期，不延长TTL或修改记录。没有停止其他容器、改密钥、打印环境/进程参数或配置凭据。保留原pool与配置，offline起因和未来恢复网络/VM行为尚未验证。

关联：[清单](../checklists/2026-10-12-harness-controlled-enablement-checklist.md)、[提示词](../prompts/2026-10-12-harness-controlled-enablement-prompt.md)、[设计](../specs/2026-10-12-harness-controlled-enablement-design.md)、[计划](../plans/2026-10-12-harness-controlled-enablement.md)、[实现](../implementation/2026-10-12-harness-controlled-enablement-implementation-design.md)。
