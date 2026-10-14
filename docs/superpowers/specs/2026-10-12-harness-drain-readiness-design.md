# MySQL 排空与迁移接续设计

日期：2026-10-12；主题：harness-drain-readiness；修订：R1；实际修订日期：2026-10-13；模式：mode=default。
当前状态：G11、G12、G13、G14完成；G5/G9/G10移出当前范围，保留历史阻塞，未完成Rocky生产部署。

## R1 差异与决定

| 差异 | 类别 | 调整前 → 当前 | 来源/影响 | 采用状态 |
|---|---|---|---|---|
| R1-1 | 调整 | Rocky生产 → Windows本机开发运行，数据库仍Rocky MySQL | 用户最新要求；D13替代D9的当前部署目标，G5/G9/G10移出当前范围 | 已采用 |
| R1-2 | 新增 | 暂存候选包 → 显式JDK21/原密钥/回环入口/SSH转发启动 | G11/G12；不修改业务源码和默认react，不迁入生产弱配置 | 已采用 |
| R1-3 | 新增 | 未运行模型 → 登录及无工具真实Run | G13；失败保留、临时Agent停用；成功只证明已选现有DeepSeek配置 | 已采用 |
| R1-4 | 澄清 | 缺生产材料 → 本机服务可运行，但Rocky生产准入仍未完成 | G14及文档；保留生产包、旧密文和七Secret历史缺口 | 已采用 |

- D13：按用户最新表述改在Windows运行；使用非生产`mysql`profile及既有bootstrap管理员，仅监听127.0.0.1。数据库位置不等于应用部署位置；不将本机成功算作production准入。
- D14：使用与577份当前覆盖源码一致的T6清洁可执行包，SHA256为`b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12`。JDK21显式指定；模型主密钥从当前进程或已有`.env`读取，其他旧密钥/目录沿用外部mysql配置，不复制或随机替换。
- D15：直接数据库连接及普通SSH曾异常，使用绑定本机物理网卡源地址的SSH恢复连接，再把127.0.0.1:13306转发到Rocky的MySQL。未更改网卡、SSH安全策略、代理、系统时钟或重启VM；不将问题归因于未经证实的代理故障。源地址变化须复核，转发与应用同时保持运行。
- D16：禁用当前不可达的可选Studio集成；真实AgentScope及Harness开启，fake关闭。Harness内部复用治理pipeline，因此日志类名AgentScopeReActExecutor不代表启用ReAct策略；真实事件入口为HarnessAgent。
- D17：验收只使用现有模型、无工具/无Skill绑定的临时Agent。保留2条FAILED及1条SUCCEEDED和审计，3个临时Agent停用，不改变现有Agent/模型。模式经既有V21启动门禁变为harness；后续ReAct实例必须统一排空切换，不能手写SQL改模式。
- D18：新增前台启动脚本，原密钥仅内存读取，日志目录限制当前用户；Ctrl+C正常关闭服务，再关闭转发。此次不验收独立密钥恢复、技能容器、生产TLS/外部身份或全部Provider。

验收：G11包/源码/环境身份；G12配置/健康/登录页；G13登录、真实随机字符串及无残留非终态；G14脚本与文档一致、原修改保护、未提交，服务持续运行。以下R0决定和验证仍保留，但不能代替上述本轮验收。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。

## R0 历史快照（2026-10-12）

以下为保留的首轮证据；其中当时的当前状态、react/170Run及未启用描述只适用于首轮，最新范围与状态以上方R1为准。

历史基线：R0，2026-10-12，mode=default。

目标：解除已明确数据集的迁移与数据库备份还原缺口，定位8条遗留Run，只有安全排空后才评估实际启用。非目标：增加API、自动接管/重放、删除业务记录、批量补终态、变更密钥或默认react。

## 决定与边界

- D1：用户明确MySQL，按现有 `cm-agent-mysql-1 / cm_agent` 推进；不再猜测PostgreSQL为目标。应用入口与其他主机实例仍需外部事实确认。
- D2：先以既有mysqldump做一致性备份，再在新建内部网络的临时MySQL8.4还原。备份仅保留Rocky私有目录700/文件600；凭据不下载、不写命令参数或日志。还原副本使用随机临时密码。
- D3：使用T6最终JAR内的实际依赖和迁移，由 `CmAgentFlyway` 正常迁移副本，核对历史保留及模式门禁。原库补齐V21前核对版本/业务快照/当前其他连接为零，使用相同实际Flyway；不启动模型或Runtime、不切换Harness。瞬时无连接不是全实例关闭证明。
- D4：8条Run没有已存工具调用、审批和完成审计。工具结果可能在外部调用之后才持久化，因此“无记录”不等于“无副作用”。旧执行句柄不可达时现有会话停止合同拒绝409；不能伪造成功/取消，也不能重启后自动重放。
- D5（首轮约束）：核实旧Run来源及执行事实后才能制定逐项受控收口方案；必须保留原始输入/消息/审计，补准确的管理审计，在同一事务内检查旧状态和归属。首轮尚未确定修复接口/主体，不为排空直接写UPDATE批处理。
- D6（G4接续）：用户明确8条旧Run是已中断测试并要求继续，采用现有JdbcRunRepository的条件complete与JdbcAuditEventRepository.append，限定八个历史ID/归属，使用模式行及Run行锁和同一TransactionTemplate；检查无工具/审批/产物关联、零检查点/非终态TEST和其他当前数据库连接后逐项FAILED，不标成功或假称主动取消。维护主体为maintenance:ssh-rocky-root，真实认证来源为已有SSH会话，不冒充原Run发起人；原始主体和Run身份保留。
- D7：副本先证明第5条审计阶段故障会撤销全部状态/审计写入，提交后重复执行NOOP；原库只在相同证明通过后执行。限定ID、原因和操作编号的既有审计共同保证重复调用不新增。正常终态审计与修复审计各8条，失败与修复成功分别表达，不混淆。
- D8：修复前出现本机IDE的10个新数据库连接，前置门禁拒绝写入。通过Spring Admin正常关闭项目上下文，确认入口/连接释放后完成收口；JVM残留不强杀，不当作正式启用时要求的全部进程退出证明。实际mysql profile开启bootstrap admin，只适合开发；G5必须明确Windows开发验证还是Rocky生产及对应配置，不能据数据库位置推断应用部署位置。
- D9：用户现已明确选择Rocky生产，授权推进生产装配。缺受控Secret来源时先准备可审查的部署文件、私有目录与旧密文副本，不随机生成主密钥或迁入开发JWT fallback。配置材料供给后依既有授权继续，不因本工作包重复索取普通许可。
- D10：根分区约3GiB可用/96%，/home约117GiB可用，因此新装配和持久卷放/home；不清理无关Docker资源。沿用已缓存Maven3.9.9/JDK21和Python3.12-alpine，初次1CPU/1GiB、日志限额，不称为已完成容量验收。
- D11：服务以容器UID/GID10001、额外Docker socket组977、只读根/cap-drop/no-new-privileges运行；包/配置root管理，产物/Secret700目录及600或400文件。Docker编排服务是可信宿主组件，socket权限等价于宿主管理能力，不能授予模型或技能子容器。实际应用未启动，生产入口未知时仅准备127.0.0.1:18086，不打开公网端口。
- D12：旧产物从现有本机配置的CM_AGENT_SKILL_ARTIFACTS_ROOT_DIRECTORY定位，只读取5个数据库明确ID的CMA1密文并备份；未读取或迁入主密钥。恢复证明必须实际解密5模型、1端点及认证5文件，只有密文头和文件摘要不够。preflight检查管理员证据登记，不能把自填true算作验收。

数据路径（迁移阶段）：原库只读快照 → 私有逻辑备份 → 临时副本还原/全部表数据匹配 → 副本V21/模式拒绝验证 → 原库V21/全部业务数据匹配；当时8条RUNNING保留。G4接续：新V21备份 → 副本原库修复逻辑/回滚/重复/排空模式往返 → 项目入口与连接关闭 → 原库八条FAILED及16审计 → 历史内容/其他数据一致性复核。原库模式仍react，不运行模型/工具。

验收：G2必须实际还原而非仅生成摘要；G3必须使用同一版本候选包与JDK21，并核查所有业务表数据摘要，而不只总数。G4已按用户确认完成；G5需正式入口与全实例排空、JDBC/身份/独立密钥/密文产物卷/固定沙箱及完整恢复预案。G8已备份/暂存5个密文，但主密钥及认证恢复仍未验证；不能宣称整个系统可恢复。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。
