# MySQL 排空与迁移接续实际交付

日期：2026-10-12；主题：harness-drain-readiness；修订：R1；实际修订日期：2026-10-13；模式：mode=default。
当前状态：G11、G12、G13、G14完成；G5/G9/G10移出当前范围，保留历史阻塞，未完成Rocky生产部署。

## R1 实际本机运行

2026-10-13切换当前应用目标至本机Windows。577份Java/SQL/POM/固定资源与T6覆盖摘要全部相同；从Rocky取得清洁可执行JAR，SHA256仍`b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12`，放在忽略的target/harness-local-runtime，未把本机私有配置重新打入包。

应用使用`F:/java21/bin/java.exe`（21.0.11），显式`mysql`profile、外部resources配置、harness、真实AgentScope、fake=false、Studio=false、127.0.0.1:8080。原`.env`模型主密钥仅进入进程内存；沿用外部mysql原沙箱/产物主密钥和目录、管理员账号。数据库经独立SSH转发13306连接Rocky MySQL `cm_agent`，未安装或运行本机Docker。

任务管理会话中实际应用PID5336，服务会话86243，转发会话17091；这些是本轮快照，重启后须重新查询。健康UP、登录页200、JMX有效harness/fake=false/Studio=false，真实登录成功。可选Studio先因WebSocket不可达阻塞启动，关闭该集成后正常。

无工具/Skill绑定的临时Agent共3个，现有DeepSeek模型真实Run `ec98263b-3bcd-4f26-8c5a-5e399642f0db` SUCCEEDED、随机字符串匹配、0工具；前2次失败保留且3个Agent均已停用。原库正常启动门禁切为harness，V21保持；173Run全终态（FAILED69/SUCCEEDED100/DENIED3/CANCELLED1），22TEST全终态、全部检查点/待审批0。该结果不代表所有现有模型或技能沙箱验收通过。

新增`scripts/start-local-harness.ps1`支持显式包摘要、JDK21、现有主密钥、转发检查、仅回环及私有日志的前台运行；`-CheckOnly`仅检查、不启动第二实例。同步本六文件R1和运维/发布说明。Java、POM、YAML、迁移、旧密钥及既有业务配置未修改；无提交/推送，也没有启动Rocky生产容器或完成生产Secret恢复。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。

## R0 历史快照（2026-10-12）

以下为保留的首轮证据；其中当时的当前状态、react/170Run及未启用描述只适用于首轮，最新范围与状态以上方R1为准。

历史基线：R0，2026-10-12，mode=default。

正式目标由用户确定为Rocky MySQL `cm_agent`。时间复核保持同步，无需再次跳时。实际备份、还原和迁移均在Rocky完成，本机没有运行Docker。

8条旧Run均为同一tenant/Agent，7条09-01启动、1条09-28启动，无结束时间；每条只有1个AGENT_RUN/RUNNING审计、0已存工具调用/0审批；关联消息共8条。没有读取输入输出或直接根据测试租户编号推断可安全删除。本机及Rocky未见CM Agent应用，原库连接快照为零，其他主机实例未确认。

原库V20逻辑备份：`/root/cm-agent-harness-drain-6752bf8-20261012/mysql-v20.sql`，3498178字节，目录700/文件600，SHA256 `ba53ef75e45dd9ce66947f2b6e26b41229eae7052188f5f04d8bf21e4c44c8e3`。只存远端私有目录，不加入Git或下载。

临时MySQL8.4在独立内部网络、tmpfs数据目录中实际还原。全表规范化数据匹配，摘要 `9124b457ee1b535042aed691da9c59a6d700a6d47ddf1c44be0fe38c875b08cb`。使用候选JAR内依赖的 `CmAgentFlyway` 和 `JdbcRuntimeExecutionModeRepository`，副本V20→V21成功，170Run/22TEST保留，8非终态使activate(harness)抛出预期拒绝，react保留。

随后原库执行相同V21迁移，模式初始化react，业务数据未改。全业务表数据（仅排除Flyway历史和新模式元数据）迁移前后逐字节一致，摘要 `66dd6bdb4382dffd4ed3e992f4e0aebc37d381bef966ce05a2e6cf3e0b33c162`。原库密码仅由Docker内存经stdin传至临时Java进程，没有写入文件、CLI参数、容器环境或输出。

迁移完成时原库V21/react、170Run/8非终态、22TEST/0非终态、0全部检查点/0待审批，5条产物元数据保持；当时未修改历史Run。G4接续后170条Run全部终态，8条已确认中断测试为FAILED，其余Run不变；5条产物及TEST/消息等仍保持。未改原迁移脚本、Java生产代码、POM、YAML或默认react，未启动生产应用。备份恢复证明仅覆盖数据库，不含独立状态/文件密钥与密文卷。

## G4 实际收口

用户明确8条旧Run为已中断测试。新增V21私有备份 `/root/cm-agent-harness-test-reconcile-6752bf8-20261012/mysql-v21-before.sql`，3499542字节、文件600/目录700，SHA256 a6577d98c3a585b6e023c80644bbeab1a62a3736d8219adbdb0705a91d5783ab。实际隔离副本/JDK21验证：第5条审计阶段模拟故障撤销全部写入；正式提交8条FAILED/16条审计；重复NOOP；排空模式harness→react通过。

原库使用现有JdbcRunRepository/JdbcAuditEventRepository，同一短事务逐项条件更新。模式和8个Run行锁覆盖读写，固定归属及无工具/审批/产物引用检查；错误原因“测试运行已中断；经用户确认关闭遗留运行，未重新执行模型或工具。”。新增终态与修复审计各8，维护主体maintenance:ssh-rocky-root，操作编号362ff9bb-a099-4dce-9d07-a2e8b27c31e8。没有修改原Run的主体、输入、输出、开始时间或历史消息。

其他业务表、其他162条Run、全部旧审计以及目标Run不可变字段迁移前后逐字节/摘要一致；排空结果Run/TEST/全部检查点/待审批均0，原库模式react。

第一次原库执行发现10条新连接而拒绝，零写入。来源为本机IDE项目PID62532，JDK21/Spring Admin确认profile=mysql、JDBC、真实AgentScope、bootstrap admin=true；产物/模型密钥配置存在仅核对布尔值，不读取密钥值。通过Spring Admin正常关闭上下文，8080入口和MySQL连接释放、Admin注销；IDE JVM仍存活，未强杀，也未自动重启应用。

G5随后由用户明确选择Rocky生产；现有mysql/bootstrap配置不能当作生产准入。G7/G8装配和密文副本已完成，Secret和认证恢复仍缺失。

## G7/G8 实际生产装配与密文暂存

新增 `scripts/rocky-harness/` 的生产properties示例、preflight.sh、run.sh、README.md。仅production、显式MySQL驱动、真实AgentScope/react、禁用bootstrap/dev fallback/fake/Studio/明文HTTP；外部ConfigTree供给七个Secret，缺失/格式/证据异常拒绝。未创建新JWT或主密钥，也未创建数据库账号。文件参考同一候选包摘要，非正式发布版本。

Rocky暂存 `/home/cm-agent-production`：app包摘要仍b456c963；config为非敏感模板，secrets目录空，artifacts为5个旧密文私有副本，UID/GID10001、目录700/文件600。脚本root管理，应用拟用非root容器并加入现有Docker组977；回环18086可用，未启动容器。根分区约3GiB、/home约117GiB，未做无关清理。

本机从application-mysql.yml的目录环境别名定位完整5个文件集合，只打包CMA1密文，未读取密钥。ZIP23096字节，当前用户独占ACL，SHA256 `464cf0bf005028103e5871b1b91ad14363ebbbdb050bea889c369e612ea1fd1e`；远端私有备份/解包同摘要。这个结果只证明文件副本，不证明AAD/tag/明文摘要及旧主密钥匹配。

常规Rocky配置目录及CM Agent私有任务目录未发现现成生产配置，已查SSH环境主密钥别名缺失；非全主机/外部Secret平台扫描，不能声称所有来源不存在。production前置实际拒绝七个缺失Secret，未启动应用，原库react和排空状态保持。bash -n及预检查实际排空SQL五个零通过。详见G5账本接续。

实际文档交付：本六文件及运维/发布说明；前轮临时验证代码在 `cm-agent-server/target/harness-drain-readiness/`，G4探针/JMX辅助代码在 `cm-agent-server/target/harness-test-run-reconciliation/`，不入版本控制。副本容器/网络已关闭，私有逻辑备份保留。旧Run收口完成，实际Harness启用等待目标应用和配置，不以排空完成代替生产就绪。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。
