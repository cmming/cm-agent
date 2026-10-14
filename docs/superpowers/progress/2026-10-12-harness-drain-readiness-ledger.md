# MySQL 排空与迁移接续账本

日期：2026-10-12；主题：harness-drain-readiness；修订：R1；实际修订日期：2026-10-13；模式：mode=default。
当前状态：G11、G12、G13、G14完成；G5/G9/G10移出当前范围，保留历史阻塞，未完成Rocky生产部署。

## R1 接续：Windows 本机 Harness（2026-10-13）

Git仍为`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交、未推送。G5/G9/G10的Rocky生产路径移出本次有效范围，历史阻塞保留；采用R1-1至R1-4/D13–D18，详见设计。G0–G4/G6–G8保留原历史完成证据，不重复修复旧8Run或用T6通过冒充当前运行。

| 编号 | 状态 | 本轮实际结果及证据 |
|---|---|---|
| G11 | 完成 | 577份覆盖文件与T6匹配；JAR SHA b456c963；JDK21.0.11（默认shell/Maven仍JDK17，不以默认mvn运行新测试）；preflight原库react/V21/排空及waitsync通过 |
| G12 | 完成 | runtime.json及私有日志：PID5336/会话86243/转发17091；JMX mysql/JDBC/harness/fake=false/Studio=false；HTTP健康UP、登录页200 |
| G13 | 完成 | api-acceptance-third.log：登录成功，真实DeepSeek Run成功/nonce=true/0工具；final-db.log：harness/V21/173Run全终态/22TEST全终态/检查点与待审批0/3Agent全停用 |
| G14 | 完成 | 六文件R1、前台脚本、运维/发布说明；脚本语法和CheckOnly、六文件链接/元数据/差异及敏感信息/保护检查全部通过；保持应用及转发运行 |

证据目录：`cm-agent-server/target/harness-local-runtime/`，不入Git。实际验证命令与范围：

- `F:/java21/bin/java.exe -version`：Java21.0.11；候选JAR与T6清洁包同SHA，源码覆盖577份全部匹配。
- 本机Java前台运行，`--spring.profiles.active=mysql --cm-agent.agentscope.execution-mode=harness --cm-agent.fake-runtime-enabled=false --cm-agent.agentscope.studio.enabled=false --server.address=127.0.0.1`；原配置与密钥沿用，密码/JWT不进入参数或输出。
- `ssh -b 192.168.0.159 -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L127.0.0.1:13306:127.0.0.1:3306 rocky`：独立转发会话持续运行。先前SSH在KEX前关闭且直接MySQL握手失败；只绑定本机物理网卡源地址后恢复，未改代理或VM网卡配置。
- `GET /actuator/health`和`GET /console/v2/login.html`：UP/200；本地JMX允许列表证明实际harness、真实AgentScope、Studio关闭，原mysql bootstrap管理员保留，仅开发回环入口。
- `LocalHarnessAcceptance.java`：管理员密码从本机JMX内存获取、JWT仅内存；只用现有模型、新建临时无工具Agent、finally停用。首轮Qwen Run `cb4abf4a-1504-4593-b0e2-7f113990fd40` FAILED/Provider OpenAIException，细分原因未核实；第二轮GLM Run `2a5fb046-b362-4936-b820-c7eb5024787e` FAILED，服务端上游HTTP429/code1113/余额不足；第三轮DeepSeek Run `ec98263b-3bcd-4f26-8c5a-5e399642f0db` SUCCEEDED，随机验收字符串匹配、0工具。保留失败及审计，不改原Agent/模型、不随机换密钥。
- 远端只读最终聚合：harness、V21、Run173/非终态0、TEST22/非终态0、检查点0、待审批0、3临时Agent/enabled0；waitsync当前校正约0.55毫秒。第一次末尾Agent聚合误写agents表1146，其前面聚合已读取成功；复核当前JdbcAgentDefinitionRepository后使用agent_definitions，完整重跑通过。没有直接SQL改模式。
- `scripts/start-local-harness.ps1 -CheckOnly`：JDK21、包摘要、旧模型主密钥格式及SSH转发检查通过；PowerShell语法0错误，无新增实例或环境持久化。

启动过程中保留失败事实：第一次缺`.env`导致模型主密钥占位符未解析；仅载入既有主密钥解决。随后直接数据库握手失败，以SSH转发解决。可选Studio WebSocket不可达，显式关闭后应用启动成功。自动审批首次拒绝后台Start-Process，改用工具管理的前台进程完成，未重复执行被拒绝的后台动作。未重跑Maven/JDBC容器/技能/浏览器完整合同：业务源码与T6相同，本轮为本机启动与指定真实模型验证，不声称重新完成生产/T6所有验收。

后续仅处理实际使用到的模型余额/配置问题；保持转发与服务，重启按运维说明。Rocky生产七Secret、认证恢复、正式入口仍是R0未完成项，只有用户重新选择生产时才恢复该范围。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。

G14最终检查：document-check.log显示六文件/当前任务编号/相对链接/围栏0错误；既有文件摘要仅本六文件与operations/release-notes共8处变化，无范围外修改，新增启动脚本1份；git diff --check与PowerShell语法通过。secret-check.log显示9份公开文件匹配既有实际配置敏感值0；私有日志目录仅当前用户、受保护ACL/1条规则。保留服务与SSH转发运行，未提交。

## R0 历史快照（2026-10-12）

以下为保留的首轮证据；其中当时的当前状态、react/170Run及未启用描述只适用于首轮，最新范围与状态以上方R1为准。

历史基线：R0，2026-10-12，mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送，未启动生产应用/启用Harness；原库已经正常迁移至V21。

| 编号 | 状态 | 实际结果与证据 |
|---|---|---|
| G0 | 完成 | 用户明确Rocky MySQL；waitsync成功；Docker23.0.6；候选包b456c963摘要与基线匹配 |
| G1 | 完成 | audit.log及related-metadata.log：8Run逐项工具/审批/审计调查、8关联消息；当前停止合同无句柄会拒绝 |
| G2 | 完成 | backup.log、restore-probe-final.log：私有备份3498178字节，实际还原，全表数据匹配 |
| G3 | 完成 | restore-probe-final.log、source-migration.log：副本迁移并拒绝Harness；原库V21/react，全部业务数据不变 |
| G4 | 完成 | 用户明确8条中断测试；实际副本回滚/重复/排空往返通过，原库8 FAILED/16新审计，其他数据及原始内容保留 |
| G5 | 阻塞 | 移出当前范围； 已选择Rocky生产，G7/G8完成；7个生产Secret缺失及真实认证恢复/全实例停机证明不足，预检查拒绝，未启动 |
| G6 | 完成 | 六文件存在、链接/元数据0错误、围栏成对；摘要仅运维/发布说明变化，diff --check通过、实际环境凭据匹配0；专属副本/网络关闭，document-check.log |
| G7 | 完成 | scripts/rocky-harness四文件及Rocky/home私有装配；同包摘要、bash -n、缺Secret拒绝与实际排空SQL通过 |
| G8 | 完成 | 本机定位5个密文并备份，23096字节/私有ACL；远端同摘要/5文件600、目录700；未验证解密认证 |
| G9 | 阻塞 | 移出当前范围； secrets空，七个同名文件缺失；5模型/1端点/5产物的旧主密钥匹配和成套恢复未验证 |
| G10 | 阻塞 | 移出当前范围； 依赖G9/所有旧实例正常退出/生产认证与入口；未启动production/react或harness |

## 迁移阶段实际验证（G0–G3历史）

证据目录：`cm-agent-server/target/harness-drain-readiness/`（忽略目录）。

- `chronyc waitsync 6 0.1 0 5`：首轮成功，校正量约0.6–0.7毫秒；没有再次makestep。
- 原库只读SQL：8条RUNNING均无已存工具/审批及完成审计；仅开始审计。当前本机Java进程与Rocky应用清点无CM Agent，查询时无其他cm_agent连接，但未据此证明所有实例停用。
- `mysqldump --single-transaction --routines --triggers --hex-blob --set-gtid-purged=OFF --no-tablespaces cm_agent`：私有完整逻辑备份成功；目录700、文件600；SHA见实现说明。
- 临时内部网络及MySQL8.4实际导入，规范化全表数据匹配。指定 `maven:3.9.9-eclipse-temurin-21`：Maven3.9.9、Java21.0.7。使用真实包内依赖编译运行ReadinessProbe，`PROBE_OK migrations=1 version=21 runs=170 trials=22 nonterminal=8 mode=react harnessRejected=true`。
- 原库迁移前V20/170Run/8非终态和当前其他连接零均符合预检查，使用同候选包Flyway执行：`SOURCE_MIGRATION_OK migrations=1 version=21 mode=react runs=170 nonterminal=8 trials=22 checkpoints=0`；迁移前后全业务表数据字节一致。
- 迁移阶段当时待审批0/非终态TEST0/检查点0，仍有8非终态Run、5产物元数据；该阶段没有收口Run或删检查点。随后G4的变化单独记录如下。

首次备份后Python中文脚本受PowerShell默认stdin编码影响而失败，备份已成功；改为明确UTF-8的任务专属helper后正常。首次副本mysqladmin ping早于认证初始化完成，导入得到1045；改为真实SELECT就绪检查。中间一次SQL引号错误导致就绪等待失败；最终正确SELECT检查、实际还原和迁移通过。一次查询误用conversation_messages表名1146，复核实际表messages后关联消息计数8。失败轮保留，不计通过；只涉及临时副本/只读查询，未降低原库权限。

## 8条待核实Run

| Run ID | 开始时间（数据库原值） | 工具/审批/完成审计 |
|---|---|---|
| 0cd9cdc4-3541-4697-8522-f1f022777fb3 | 2026-09-01 01:02:41 | 均0 |
| 6f27b177-2950-43bb-b9ba-f695920867a7 | 2026-09-01 01:03:32 | 均0 |
| ab464a04-f461-486d-8433-2e875228f3e3 | 2026-09-01 01:05:46 | 均0 |
| 902e6824-f7a5-4fab-84ba-10cef30c2327 | 2026-09-01 01:25:31 | 均0 |
| b93ad36c-6c45-43af-ab4f-b17b841753ba | 2026-09-01 07:32:34 | 均0 |
| 9b541c80-d4be-465e-98fd-f3b7e9a60ed8 | 2026-09-01 07:33:07 | 均0 |
| bf57a29a-a13f-4f23-a738-6fb62d17a187 | 2026-09-01 07:34:59 | 均0 |
| b5d64357-2a9a-4e84-a731-8e7653ccffe5 | 2026-09-28 02:56:15 | 均0 |

上表是修复前历史快照，不能根据零记录推断外部调用一定未发生。首轮询问时尚未得到答复，G4/G5保持阻塞。随后用户明确“8条旧Run属于已中断的测试，继续下一步”，按授权完成G4；当前这8条已FAILED，原消息和开始审计均保留。没有将该回复扩展成所有实例已停止或生产配置已完成。

## G4 接续实际证据

证据目录 `cm-agent-server/target/harness-test-run-reconciliation/`。

- backup.log：新V21私有备份3499542字节、600/700，SHA a6577d98c3a585b6e023c80644bbeab1a62a3736d8219adbdb0705a91d5783ab；原备份不覆盖。
- probe.log：Rocky内部临时MySQL8.4实际还原；maven:3.9.9-eclipse-temurin-21/Maven3.9.9/JDK21.0.7，AUDIT_FAILURE_ROLLBACK_OK、DRAINED_MODE_ROUNDTRIP_OK、RECONCILE_OK result=APPLIED repeat=NOOP failedRuns=8 newAudits=16。
- 首次apply.log：当前连接10而前置拒绝，原库0条修复审计/8 RUNNING，未写入。连接源192.168.0.159，本机TCP归属PID62532，其他Java进程不操作。
- live-configuration-corrected.log：只输出允许列表和密钥配置布尔值；profile=mysql、JDBC、真实AgentScope、bootstrap admin=true、沙箱及产物启用，模型与产物密钥/目录配置存在。第一次使用错误产物前缀未找到配置，查当前绑定后改为cm-agent.skills.sandbox.artifacts，不把第一次false当作真实缺失。
- graceful-shutdown.log、context-after-shutdown.log：JDK21本地JMX调用Spring Admin shutdown正常关闭上下文；随后SPRING_ADMIN_NOT_AVAILABLE、HTTP8080及数据库连接0。10秒及追加等待中JVM未退出，保留原状态，不强杀；不能据上下文关闭宣称全部JVM已退出。
- apply-final.log：现有Run/Audit仓储及同一事务，精确8个ID、归属/原状态/无治理关联、模式锁/行锁核查后FAILED，新增AGENT_RUN/FAILED8和AGENT_RUN_RECONCILE/SUCCESS8。操作编号362ff9bb-a099-4dce-9d07-a2e8b27c31e8，主体maintenance:ssh-rocky-root，重复执行NOOP。
- 保留性验证：其他表、其他162条Run、原审计、目标Run输入/输出/身份/开始时间一致；其他表摘要cd1c46b8150622a3d11c7717524823b68118b8276c0bcc12c5f54db37ba9bc85。170Run终态分布FAILED67/SUCCEEDED99/DENIED3/CANCELLED1，非终态Run/TEST/全部检查点/待审批均0，原库react。

未运行真实模型/工具或重放历史调用；修复成功不表示曾主动取消外部操作。原库密码仅stdin管道，正文与实际密钥不输出、不回传。

G4结束时G5阻塞于目标不明；随后用户明确“Rocky生产部署”。继续询问受控配置及Secret来源时仅回复“直接ssh rocky就可以连接”，这确认SSH访问方式，不提供密钥来源。未将该回复当作允许生成替代主密钥或供给开发JWT。

## G7/G8 Rocky生产装配接续

证据目录 `cm-agent-server/target/harness-rocky-production/`。

- discover.log：SSH/time/Docker可用，原库react、Run/TEST/检查点/当前其他连接均0，5 READY；常规部署目录及CM Agent任务目录候选生产配置数0，已查四个SSH环境主密钥别名未配置。范围有限，不否认其他未明确的Secret平台。
- 元数据只读：5条模型配置、1条沙箱端点、5个产物ID。只用于恢复验证目标，不读取密钥或正文。
- host-preflight-final.log：Maven21镜像已缓存，Python3.12-alpine已缓存，socket组977/660，18086空闲；/home暂存路径之前不存在。一次猜测Python3.12-slim不存在，按当前固定默认/既有验收修正为alpine，没有拉取镜像。
- local-artifact-discovery-resolved.log：SafeConstructor只读取目录；最初只检查嵌套字段未匹配，后来按实际application-mysql.yml中CM_AGENT_SKILL_ARTIFACTS_ROOT_DIRECTORY环境别名定位1个目录、完整5个密文集合。ZIP23096字节，当前用户独占ACL，SHA464cf0bf005028103e5871b1b91ad14363ebbbdb050bea889c369e612ea1fd1e。未读取主密钥、认证tag或明文。
- stage-production.log：/home/cm-agent-production私有装配完成，同JAR摘要b456c963，旧密文5个CMA1文件暂存；目录700/文件600、UID10001。仅包/模板/密文副本，不创建生产容器、账号、JWT或主密钥。根分区仅约3GiB/96%，/home117GiB，未清理其他资源。
- verify-scripts.log：bash -n通过，缺七个Secret的负例拒绝（MISSING_SECRET_FAIL_CLOSED=7）；实际预检查SQL结果五个0，artifact owner10001/700、五文件600。缺材料时没有启动应用或改模式。恢复证明文件尚未创建，不填虚假的true。
- verify-runtime.log：指定镜像真实运行UID10001/组977、只读/cap-drop/no-new-privileges，JDK21.0.7、候选包可读、Docker23.0.6访问通过；只执行java -version和docker version，不启动CM Agent或模型。启动脚本显式entrypoint=java并用/tmp作为HOME，避免Maven默认入口试图写root缓存；临时验证容器--rm关闭。
- scripts/rocky-harness/.gitattributes限定部署shell脚本LF检出，避免Windows autocrlf造成Linux末行参数含CR；不改变其他文件的换行策略。结束时完整链接/空白/实际环境凭据检查通过，既有8文档摘要之外无生产源码/POM/YAML变化，新增四运行文件及该局部规则。
- final-refusal.log：实际调用远端run.sh react因七个Secret缺失而在创建容器前拒绝，生产容器不存在；原库仍react/非终态Run0。不是生产启动失败后的回滚或真实应用验收。

七个缺失Secret：外部JWT验证密钥、JDBC连接/应用账号/密码、模型主密钥、沙箱凭据主密钥、产物主密钥。完整同名清单见scripts/rocky-harness/README.md。要求最小权限DB账号及既有AES密钥真实匹配；SSH可用不能替代供给。

G5/G9/G10未执行：生产启动、外部身份令牌验证、真实Provider/审批/停止/文件恢复、入口开放。确切阻塞为上述七个Secret及认证恢复/全实例正常退出/正式入口材料缺失，不用测试实例或随机主密钥冒充通过。既有数据库还原证明与G4全部排空保持。

## 证明范围与剩余条件

数据库备份已真实恢复，V21已原库落地，G4已按用户确认完成收口。没有完整生产备份恢复证明：5条文件元数据对应密文卷与独立密钥未备份/还原。既有开发实例配置已核实，但正式应用目标、生产身份及沙箱/产物完整恢复尚未满足。未启动生产应用、未恢复入口流量、未运行模型/工具、未强杀原服务，未进入T7/T8；全量Maven及旧T6合同未重跑，因为生产代码未变。

仅移除专属临时MySQL容器与内部网络，以及本轮随机环境文件和重复数据快照；无项目源库/卷或无关资源清理。私有备份保留Rocky `/root/cm-agent-harness-drain-6752bf8-20261012/mysql-v20.sql`。备份正文、原库凭据、密钥和业务消息未回传、下载或提交。

G4结束保护检查：仅本六文件及docs/operations.md、docs/release-notes.md变化；生产代码、POM/YAML及其他工作包摘要保持。六文件/链接0错误，提示词围栏成对，实际环境凭据匹配0；副本/网络及重复数据快照关闭/移除，新V21私有备份保留，证据见harness-test-run-reconciliation/document-check.log。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)。
