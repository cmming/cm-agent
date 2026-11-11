# 本机客户端项目工作区实施账本

## R5 T9/T17 续验账本（2026-11-11）

- **修订基线：** HEAD `8e1190f`；mode=default。R5 复用原六份工作包，不新增任务编号；T9/T17 保持进行中，移出项无。
- **客户端进程重启：** Run `d6244d86-4e3f-43ca-a173-22074189d225`，命令 `525ff639-c6bc-4e12-9459-c839499580de`。客户端 PID `45772` 与受管 node PID `19684` 在强制终止后均不存在；重启 PID `40480` 后命令为 `UNKNOWN`，未重放。
- **旧客户端无见证：** Run `e8662979-6744-46e8-8152-37f11395e2b2`；代次确认数 `0`，任务 `228a519e-1719-4e61-a5c7-7ddcc9354ac0` 为 `PENDING` 且 `claimed_at=NULL`。修补客户端退出码 1，显示显式 `init-fence-store` 指引。
- **真实双实例竞争：** 在 `8443` PID `9964`、`8444` PID `4732` 同时监听时，MySQL 通用日志记录线程 `24619/24620/24623` 对同一归属的竞争。原实例 `3e4f10d7-...` 变为 `8444` 实例 `1191db8a-...` 的代次 2；`8444` 日志记录 Run `cef6c8b3-...` 在 epoch 2 安全收口，命令保持 `UNKNOWN`。
- **强制终止与断电边界：** Windows Job Object 在客户端进程句柄关闭时结束受管 node，覆盖非优雅崩溃和会话终止后的资源回收。真实交互式 Windows 注销、物理关机/拔电仍未执行，不能以本账本替代，T9/T17 综合状态仍为进行中。
- **本机测试：** JDK 21/Maven 3.9.4 执行 `ClientProjectFenceJournalTest,LocalClientReconnectTest,ClientProjectFenceServiceTest,ClientProjectRunRecoveryServiceTest,LocalClientFenceControllerTest` 退出码 0；`cm-agent-local-client` 完整 `package` 退出码 0。
- **Rocky 双库与客户端：** 提交 `ce84c3c` 在 `maven:3.9.9-eclipse-temurin-21` 中通过：PostgreSQL 16.14/MySQL 8.4 的 `MigrationTest` 3/3、`JdbcLocalClientDeviceRepositoryTest` 2/2；`ClientProjectFenceJournalTest` 5/5、`LocalClientReconnectTest` 2/2；均 0 errors/failures/skipped。
- **Git：** 业务、发布说明与首轮 R5 文档提交 `ce84c3c` 已推送；双库结果回填文档提交 `a0266ac` 也已推送至 `origin/master`。

## R4 历史 T19 收口账本（2026-11-11）

- **业务提交：** `9fff872`（`修复 T19 代次轮询与恢复归属收口`）。范围包括 `LocalClientServerClient`、`LocalClientProofService`、`ClientProjectRunRecoveryService`、Fence/Recovery JDBC 查询、`LocalClientApplication` 受控口令通道及对应测试；未纳入用户 `application*.yml`、`.impeccable/`、`.gitignore`、`.workbuddy/` 等无关改动。
- **本机验证：** `mvn -q -pl cm-agent-local-client -am test` 通过；`ClientProjectRunRecoveryServiceTest,LocalClientProofServiceTest,LocalClientFenceControllerTest` 定向通过；`git diff --cached --check` 通过。
- **Rocky 双库：** 容器 `maven:3.9.9-eclipse-temurin-21`，工作区 HEAD 精确为 `9fff87254ce10ac69465e3f77d386995431655af`。`MigrationTest` 3 tests、0 errors、0 failures、0 skipped；`JdbcLocalClientDeviceRepositoryTest` 2 tests、0 errors、0 failures、0 skipped。数据库日志显示 PostgreSQL 16.14 与 MySQL 8.4，V31 迁移和恢复终态归属断言均通过。
- **Rocky 客户端：** `ClientProjectFenceJournalTest` 4/4、`LocalClientServerClientTest` 9/9，均 0 errors、0 failures、0 skipped，覆盖跨进程文件锁、丢失/损坏见证、零代次非运行任务和可信 TLS 握手。
- **Windows 真实链路：** 设备项目页面与签名客户端完成代次 1 握手；`测试Qwen` 调用 `client_project_list_toolchains`、`client_project_propose_command`，产生精确 HIGH 审批，再执行 `client_project_run_command`。命令 `c406d958-0585-4eb7-8e76-f3e0dc45043d` 状态为 `RUNNING`、版本 13、输出序号 27；客户端 PID 19436、node PID 15156 均在。
- **Server 重启：** Server PID 47768 停机 36 秒，客户端 PID 19436 保持在线，未重放已领取任务。新 Server PID 44048 启动后恢复扫描把 Run `e8eef87f-962d-49a9-8f1b-9be10ae7e0d5` 从代次 1 `EXECUTING` 推进到代次 2 `RECOVERING`；本机见证文件记录同代次，数据库确认行存在。
- **安全结果：** Run 最终 `FAILED`，错误码 `RUNTIME_EXECUTION_FAILED`；命令保持 `UNKNOWN`、版本 23、序号 39、`stopRequested=false`；node 进程不存在，未产生新启动任务或重放。查询确认恢复任务仅包含 `COMMAND_STATUS`/停止类，不包含 `RUN_COMMAND` 或文件应用。
- **修复：** 首个真实目录任务暴露客户端把 `epoch=0` 误判为运行任务，已修复并回归；多客户端轮询暴露同源限流失衡，已改为设备分桶；恢复完成后归属滞留问题已通过终态过滤和同事务关闭修复。
- **边界：** 本轮通过 Server 重启恢复和不重放；Windows 注销/断电、旧版无见证迁移、完整原生目录窗口矩阵、跨主机多实例、外部进程脱离树仍由 T9/T17 继续，不宣称为全平台完成。

以下 T19 小节保留形成 R4 前的过程证据；其中“未接入、未完成”均不覆盖本节。

## T19 任务代次入队续验（2026-11-11）

- T19 **进行中，未完成、未提交、未推送**。新增任务代次持久绑定及入队事务组件，旧领取和旧过期扫描跳过带代次任务；组件尚未与 Server 的真实任务生成、设备领取、客户端握手或 Run 恢复接线。
- V31 与 V30 均为未提交工作树中的迁移；使用本工作树构建的 JDBC Server 重启可能执行迁移，不能当成已发布或已验收功能。
- 接入前仍需可信服务端响应认证、设备见证防回滚、握手确认与旧进程/UNKNOWN 对账；本轮未进行真实 Server/Windows 端到端验收。
- 本机 JDK 21 已通过跳过测试打包；Rocky 临时快照 `1e2f3efd5c9c4219b3930d7aa6d82a44d79dc735` 的首轮双库测试通过。增强租户、主体及恢复隔离的临时快照 `a26c21352dba18eb565fa7aa0d408f6466d202b4` 正在另行复验。

## T19 接管屏障与恢复确认续验（2026-11-11）

- T19 **进行中，未完成、未提交、未推送**。公开接管接口不再接受调用方提供的恢复屏障，避免空回调绕过隔离和审计；客户端持久恢复确认支持同代重试。两项仍为未接入运行路径的组件，不能声称真实 Run 已恢复。
- Rocky 临时快照 `7576574366a803514c9e7127236bb32d54089540` 与待验代码对齐，Docker 使用 `maven:3.9.9-eclipse-temurin-21`，`MigrationTest`、`JdbcLocalClientDeviceRepositoryTest` 和 `ClientProjectFenceJournalTest` 分别 3、2、4 项通过，覆盖 PostgreSQL 16/MySQL 8.4 和客户端跨进程锁。该快照不是正式提交。
- 审计主体修订：自动恢复事件使用 `system:client-project-recovery`，避免冒用 Run 原发起人；原主体通过可信 Run 来源追溯。对应临时快照 `cdd0e64fdc2b42364195ed5e4117cf1256f6217a` 已在 Rocky 重跑双库和客户端定向测试，分别 3、2、4 项通过，无失败或跳过；它不是正式提交。
- 接入前必须建立可信的服务端响应认证、客户端持久见证防回滚和代次握手；真实 Server 重启、Windows 端到端和旧 UNKNOWN 对账尚未验收。不要将本机日志、内存句柄或旧任务状态当作副作用已停止的证明。
- 本机客户端测试验证确认丢失后的同代重试不会增加见证记录，后续代次出现后旧确认仍被拒绝。双库验证固定屏障、真实审计插入、隔离和审计失败事务回滚；生产服务尚未装配。

## T19 客户端见证与审计夹具续验（2026-11-11）

- T19 **未完成、未提交、未推送**。新增客户端本机代次见证组件及测试，但尚未接入现有任务领取、设备协议或生产恢复路径；测试不能证明真实端到端接管。
- 服务端隔离测试新增真实 `JdbcAuditEventRepository` 写入，使用唯一键冲突检查归属和隔离回滚；现新增 `JdbcClientProjectRecoveryBarrier`，以同数据源同事务执行隔离和真实 JDBC 审计；它尚未装配到生产自动接管流程。
- Rocky 临时验证快照 `f2a2aa7646caa1925c8b74e1a57f93f57cb6f17a`：在 `maven:3.9.9-eclipse-temurin-21` 中运行 `MigrationTest,JdbcLocalClientDeviceRepositoryTest,ClientProjectFenceJournalTest`，分别 3、2、4 项通过、无失败或跳过；覆盖 PostgreSQL 16/MySQL 8.4、实际 JDBC 审计冲突回滚及客户端进程间锁。快照仅用于对齐验证，不是正式提交。
- 已发出任务、已启动进程以及 UNKNOWN 结果不能通过改数据库状态当作无副作用；必须等待可信新代次确认并保留隔离、停止和对账。V30 处于未提交工作树，使用该工作树构建的 JDBC Server 重启可能自动迁移，禁止当作已完成发布。
- Windows 本机执行 `mvn -q -pl cm-agent-local-client -am -Dtest=ClientProjectFenceJournalTest -Dsurefire.failIfNoSpecifiedTests=false test`，4 项通过，含独立 Java 进程的锁竞争、重启、旧代次及损坏拒绝。隔离夹具新增真实 JDBC 审计唯一键冲突与成功写入验证，Rocky 双库与客户端定向结果见本节。

## T19 隔离和事务边界续验（2026-11-11）

- T19 **进行中，尚未完成或发布**。隔离组件仅在测试中通过恢复回调调用；服务器任务领取、旧实例执行、客户端持久代次和真实严格审计尚未联通，不能据此声称支持安全接管。
- PostgreSQL 16/MySQL 8.4 的 `MigrationTest,JdbcLocalClientDeviceRepositoryTest` 已在 Rocky 对齐的临时快照 `16bd63172fb5f378b41c022e86fb6d1f7f400cb2` 上通过，覆盖隔离及回调失败回滚；其后的跨数据源活动事务检查在快照 `4a40b0b2ece7714eea4cd2935d418f4fbea383dc` 上重跑通过：迁移测试 3 项、设备仓储测试 2 项，均无失败或跳过；测试在 Maven 3.9.9/JDK 21.0.7 容器内执行。临时快照不等于最终提交。
- 已领取任务可能在数据库隔离后迟到执行；客户端必须持久化并确认新代次，Server 还须对入队、领取、回报、审批和终态写使用可信归属栅栏。未知副作用不得重放，旧版无见证 Run 不能猜测已停止。当前真实端到端验收未完成。
- V30 仍在未提交工作树，重启使用该工作树构建的 JDBC Server 可能执行迁移；功能尚未完成，不应作为生产发布。未提交、未推送。
- 账本：本轮修正数据源错配和仅绑定非事务连接时误判为同一事务的风险；隔离夹具验证回调异常整体回滚。独立数据库测试不能替代实际签名设备、审计系统和 Server 恢复链路的验收。

## T19 数据基线续验（历史，2026-11-10）

- T19 **进行中，尚未交付**。新增双方言 V30 `client_project_run_ownership` 表与逐字段数据库注释，JDBC 仓储在数据库事务中锁定来源和归属、使用数据库实际时间、条件续期并以单调代次进入受限恢复。PostgreSQL 使用 `clock_timestamp()`，避免长时间等锁后误用事务开始时间；MySQL 使用新语句的 `NOW(6)`。
- 首代归属要求 Run 在同一事务刚以协议 5 插入，事务创建见证随事务完成清除；历史已提交 Run 不能在后续事务补造首代执行许可。接管通过事务模板调用隔离与严格审计回调，回调抛错回滚归属推进；当前测试回调使用没有在途任务的夹具，**不是生产任务隔离实现**。
- Rocky 以临时 Git 验证快照 `3ba43b16dbca060efba524d9b0a921e9c660948a` 对齐，Docker 23.0.6、`maven:3.9.9-eclipse-temurin-21` 中定向运行 `MigrationTest,JdbcLocalClientDeviceRepositoryTest`：3+2 项通过，覆盖 PostgreSQL 16/MySQL 8.4 迁移注释、等锁跨过期限、旧 Run 拒绝补造、归属身份隔离、接管竞争和回调异常回滚。前一快照迁移测试因旧固定数量断言失败，修正后重跑通过。该快照不是最终提交，也不能代表后续未验证改动。
- **尚未完成**：仓储尚未装配到 Server；首发、审批恢复、任务入队/领取/回报、命令和文件执行及 Run 终态尚未共同接入持久栅栏；旧任务隔离、可信审计回调、客户端持久最高代次及迟到执行拒绝、失效后的 Run 安全收敛、崩溃恢复与真实端到端验收均未实现。仅有租约表和竞争测试不能防止已派发旧任务产生副作用。
- 本次 V30 和代码仍为未提交工作树，不应当作已发布迁移或生产可用功能。真实旧 Run `ccbb3f7f-fac6-4472-97e0-02e2ff47894d` 未被改写；完整验收前不得清除 UNKNOWN 或直接改库。未提交、未推送。

## T19 实施进展（历史，2026-11-10）

- T19 **进行中，尚未完成**。新增 `ClientProjectRunOwnership` 领域快照和 `ClientProjectRunOwnershipRepository` 契约，定义租户/原主体/Run/实例/代次/阶段/期限、旧代次拒绝、到期后仅进入受限恢复、禁止代次回绕和一秒至五分钟租期。时间必须来自同一数据库事务，领域判断本身不是跨进程授权。新增四项边界测试，`mvn -q -pl cm-agent-core -am test` 通过。
- 当前只有领域规则与仓储接口，**尚无数据库迁移或仓储实现，尚未接入 Server、设备客户端、领取/回报/审批/副作用与 Run 恢复链路**。它们不会改变现有生产行为，不能据此声称持久租约、栅栏、自动恢复或端到端通过。
- 源码证据：首发 `ClientProjectRunService.save` 与 Run 来源同事务；审批恢复在 `ToolApprovalService` 的状态 CAS 后才登记进程内句柄；设备任务在 `JdbcClientProjectTaskRepository.claimNext` 中独立领取，命令派发由 `ClientProjectCommandService.startRun` 等入口登记。下一步必须将归属及可信数据库时间接入上述入口，确保旧代次的授予、入队、领取和状态写入不能在接管后提交；已领取任务可能已产生副作用，转 UNKNOWN 并保留停止/状态/对账，不自动重放。
- 迁移必须新增方言版本及逐字段中文数据库注释，审计失败与租约推进/任务隔离必须同事务回滚；覆盖旧实例暂停、租约过期、双方时钟偏差、接管竞争、迟到回报、审批准入、根隔离及原始 Run 无归属见证的拒绝。Run `ccbb3f7f-fac6-4472-97e0-02e2ff47894d` 本轮只读查询仍为 RUNNING，未改库或伪造终态。
- Rocky Docker 23.0.6 和 `maven:3.9.9-eclipse-temurin-21` 镜像可用，根分区剩余约 1.6 GiB；本轮尚未产生并对齐待验提交，**未运行双库或真实重启复验**。本轮变更未提交、未推送；当前阶段未完成用户要求的完整交付。

## 2026-11-10 当前续验：目录确认与 Server 重启

本节是 R3、mode=default 的最新实施状态；下方早期“R3 续验结果”和“目录窗口与运行中重启”均为历史，旧的未登录、未提交及窗口不可见结论不覆盖本节。保留 T0–T18，追加 T19；本段形成时 T1/T2/T9/T17 进行中、T19 待做，当前状态以顶部实施进展为准。

- T1/T2：设备领取请求正文携带签名覆盖的 `selectionProtocol:1`；服务端拒绝不声明能力的旧客户端领取项目任务，并向新客户端下发持久化选择期限。项目页显示完整请求编号；Windows 客户端先显示配对来源、设备名和编号供本机确认，再调用系统 IFileOpenDialog。窗口到期尝试按本进程及任务编号关闭，受控线程保证等待有界；旧窗口未退出前拒绝新选择，登记前复核期限。仅 Windows x64 目录窗口得到实现。
- 真机证据：请求 `75108e21-6796-4f8e-b363-aeb020cb8fb7` 的页面与 Windows 确认框编号一致，用户完成系统窗口选择，页面新增设备项目 `cm-agent-e2e-project-20261103` 并读取目录；实际选择的是既有验收目录，和原计划的新目录不同。请求 `edb7e9f3-14e1-4c27-912f-419bb9910476` 返回“设备项目任务失败”，未登记新根；指定目录的只读路径与对象身份检查通过，失败原因尚未确定，不能称所有选择路径通过。自动化无法向确认框发送输入，真实选择由用户操作。
- 超时证据：真实项目请求 `918c91f3-3813-4ec2-8b5f-b05cc12f16bc` 到期后客户端仍运行且窗口线程退出；独立 Windows 原生窗口探针在约 8.2 秒返回未选择，确认框独立超时探针返回未选择。这些探针不代替完整项目登记验收。
- T17/C04：命令 `229de3ba-51b4-4d45-89d2-9346c799f6d2` 真实启动后重启 Server；旧客户端遇连接异常退出并停止该进程，启动标记为 1。修复客户端短时断线退避、拒收回执后继续轮询且不重发；修复 Server 接收重启后的终态无输出回执时保留既有输出/序号。真实重查该命令得到 `CANCELLED`、退出码 1，保留旧输出；另一次空闲 Server 重启期间新客户端 PID 保持运行。上述失败及对账不是修复后运行中自动接管通过证据。
- 用户恢复“测试Qwen”后完成新的真实命令重启复验：命令 `8bcca448-827a-49bc-99e3-01268ddd053b` 精确批准后，Server 停机及恢复期间客户端 PID 26020、node PID 39956 保持运行；重启后状态查询为 RUNNING，输出持续，marker 累计 2 次仅对应两次单独批准。停止后命令 CANCELLED/退出码 1、node 已退出且 marker 未增加。首次终态重复停止暴露 500 与领取状态矛盾；修复后真实 HTTP 重复停止转为 COMMAND_STATUS，任务 SUCCEEDED，命令仍 CANCELLED。
- **Run 接管仍未通过：**原 Run `ccbb3f7f-fac6-4472-97e0-02e2ff47894d` 在命令终态后仍为 RUNNING，停止返回 409 `RUN_CONTROL_UNAVAILABLE`（错误编号 `5972f207-397f-43ad-8085-141542a20d0b`）。现有设备项目运行句柄只存在于进程内，缺少可跨实例确认原执行归属和失效的持久租约/屏障见证；不得仅因本实例查不到句柄而修改 Run 或释放隔离。T17/T9 仍进行中，需实现有竞争控制的持久归属、可证明失效后的恢复与双库/真实重启回归；其他 TLS、多实例、注销/断电、竞争和窄屏矩阵仍待验。
- T19（当时待做，依赖 T3/T17）：为设备项目 Run 建立可信、可过期的持久实例归属与单调防护代次；领取、状态推进、外部副作用许可和恢复事务须共享可验证栅栏。仅在证明旧执行已失效且复核任务/命令时收敛，已有未知副作用继续隔离，不凭空内存缺失或客户端声明判定；跨实例竞争、迟到许可、审批恢复、审计失败回滚及 MySQL/PostgreSQL 双库测试必须覆盖。旧版没有归属见证的 Run 不得被新逻辑猜测为已停。
- 本轮代码、运维与发布说明已修改；提交前需审查暂存区，仅提交本任务范围。旧客户端需先停用并排空已领取选择任务，再同步升级；Server 回退不能作为绕过确认的方式。

日期：2026-11-09；当前修订 R5（2026-11-11）；mode=default；R4 业务代码提交 `9fff872`，R5 提交与推送状态以最终 Git 记录为准。

## R3 续验结果（历史，2026-11-10）

当前仍为 R3、mode=default；这是原任务的实现与验收收口，不新增需求修订或重复工作包。业务代码已提交并推送至 `d85f785`，客户端输出修复为 `c8d6ad3`。T6/T17 的真实 Windows 命令子集通过；T9 改为进行中，整体验收仍未闭合。

- Rocky：`17bf309` 上持久化全量 94/94 通过；`9e1745e` 上迁移/检查点/设备仓储及 core 命令定向通过；最终 `c8d6ad3` 对齐双库命令仓储增量 2/2 和 core 命令 3/3 通过。当前持久化/core 与该次验证无差异，不把全量 94 项冒充在最终提交重跑。
- Windows：真实已配对客户端、认证浏览器和 `OPENAI_COMPATIBLE / glm-5.3` 完成工具链查询、提案、HIGH 精确预览/批准、命令成功、Run 屏障、进程树停止、重复停止查询、客户端重启 UNKNOWN 回报与人工对账。所用 Agent 显示名为“测试Qwen”，实际模型以运行配置的 `glm-5.3` 为准。
- UNKNOWN：本机孤立回执为 UNKNOWN；服务端恢复后仍为 UNKNOWN/version=17，输出序号 69 与已有 1579 字节输出保留。人工对账 HTTP 200 不改变状态、版本、序号；真实审计存在；归档 HTTP 409 `CLIENT_PROJECT_TASK_PENDING`，测试进程数量为 0，未自动重放。
- 边界：使用既有登记的验收项目；原生目录窗口在当前自动化桌面不可操作，不能据此宣称完整“选择目录到开发”验收通过。远端 Server/TLS、多实例、设备撤销、Windows 注销/断电、文件写入竞争和完整窄屏/迟到响应矩阵仍未验。

具体命令、Run/命令编号、失败修复与证据适用范围以[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)为准。

## R0 历史基线

- R3 续验起点（2026-11-10）：当前 HEAD `1b19863`，远端已有主体 `21344f4`；本轮六个代码/测试文件和六份工作包、发布说明尚未提交。无关配置、设计、忽略规则和工作目录保留不动。
- 本轮 `mvn -q -pl cm-agent-local-client,cm-agent-server -am "-Dtest=ClientProject*Test,LocalClientServerClientTest,LocalClientTaskExecutorTest,LocalProjectRunCompletionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：JDK 21.0.11/Maven 3.9.4，退出码 0；客户端匹配 19 项、Server 匹配 27 项，均无失败/错误/跳过。未运行数据库测试（该命令没有匹配持久化测试）。
- Rocky 当前根分区余量约 1.7 GiB、可用内存约 3.7 GiB；既有 `/tmp/cm-agent-verify-v29fix-20261110` 对应 `1b19863` 的 MigrationTest 报告 3/3 通过。这是恢复发现的历史报告，不作为本轮完整双库复跑或端到端通过。接下来用对齐新提交的隔离目录和已有 Maven 缓存串行验证，禁止清理无关容器/卷。
- HEAD `0a96c6a0a70c77c20d29e325e276a7eba7ce5010`，工作树已有用户配置、设计文件及原 R2 工作包改动，保持不动。
- Maven 3.9.4；`F:\java21` 提供 JDK 21.0.11。默认 shell 为 JDK 17，后续命令显式切换。
- R0 形成时最高迁移为 V28；候选 V29 已在本 R2 实施并通过静态双方言检查。

## R3 当前任务状态（含续验）

| 编号 | 状态 | 证据或未执行项 |
|---|---|---|
| T0 | 完成 | 已核对 Git/JDK/Maven、模块边界、V29 与主要入口；环境/代码调查证据见本账本 |
| T1 | 进行中 | 真实 Windows 确认与系统窗口已完成一次用户选择登记；超时独立探针通过，另一次选择失败待定位，取消与完整矩阵待验 |
| T2 | 进行中 | 既有登记项目在真实客户端多次重启后仍可读取；双库协议 5 会话/Run/任务合同通过；真实原生登记和超时恢复仍待验 |
| T3 | 进行中 | 已补齐协议 5 首次 Run、消息/审批/历史闸门与行动头；真实模型命令会话通过；跨入口/权限撤销完整矩阵待补 |
| T4 | 进行中 | 真实目录与 README 文本预览通过；来源时间统一微秒并有加密快照回归；真实文件竞争与搜索预算矩阵待补 |
| T5 | 进行中 | 六类文件/空目录动作、精确预览、HIGH 审批、条件应用/撤销和私有回执已实现；新增计划冲突与跨 Journal 实例回执测试，真实多进程竞争、断线和断电仍待验 |
| T6 | 进行中 | 真实 quick 命令成功；tree 父子进程停止、无重复输出、重启 UNKNOWN 恢复与对账通过；注销/断电、脱离进程树与跨主机故障仍待验 |
| T7 | 进行中 | 客户端重启 UNKNOWN、完成屏障、停止与对账保留隔离已验；设备撤销、任务全 TTL 和完整故障矩阵待补 |
| T8 | 进行中 | 真实认证桌面页面、命令预览、SSE 审批、轮询保留草稿、停止和 UNKNOWN 对账通过；窄屏/键盘/迟到响应完整矩阵待验 |
| T9 | 进行中 | Rocky 双库、真实 Windows 命令子集和一次原生目录登记已验；R5 通过客户端进程重启、旧客户端无见证、双实例竞争和强制进程树回收。实际 Windows 注销/物理掉电、完整原生目录、远端 TLS、设备撤销及外部进程脱离 Job 树仍待验 |
| T10 | 进行中 | 独立属性、默认关闭和 local/test+JDBC+设备通道校验已实现；停机升级/回滚/旧程序栅栏未验 |
| T11 | 进行中 | 选择、只读、变更及命令任务使用独立签名队列并交错轮询；命令异步启动后由状态/停止任务控制；真实断线、TTL、客户端重启和故障注入未完成 |
| T12 | 进行中 | 文件与命令均具备一次派发许可；命令 QUEUED 前复核状态/摘要，设备领取再复核 claimable；重复停止只查询；旧进程、迟到许可和真实崩溃竞态待 T9 |
| T13 | 完成 | 原始/展示摘要分离、HIGH 决策后端复核、`displayExact` 拒绝和页面门禁均有合同 |
| T14 | 进行中 | C04/C07 已取得真实 Windows 命令子集与双库证据；C02/C08/C10 补充真实模型/认证页面证据；各条目的完整撤销、旧版、跨入口或物理故障矩阵仍待补 |
| T15 | 进行中 | 12 个专用工具 ID、风险分类、命令审批定位摘要和 ReAct/Harness 载荷保护已有定向测试；全入口伪造、旧版、MCP、断线和恢复矩阵未完成 |
| T16 | 进行中 | Windows 目录/文件身份、跨进程冲突域、写前预算和写后快照已实现；项目代次、旧许可和真实竞争待验 |
| T17 | 进行中 | 仅提案 Run 成功、命令成功 Run 成功、停止后 Run 不成功、客户端强制重启后的 UNKNOWN、双实例代次 2 安全接管与不重放均有真实证据；实际 Windows 注销/物理掉电仍待独立会话验收 |
| T18 | 进行中 | 长期来源历史与运行快照分离、微秒往返和轮询交互回归通过；真实重启后原会话/回执读取通过；完整离线/撤销/归档浏览器矩阵待验 |
| T19 | 本轮完成 | 提交 `9fff872`：可信代次握手、任务派发门禁、恢复隔离和终态归属关闭已接线；Rocky PostgreSQL/MySQL 与真实 Windows Server 重启通过，Run `e8eef87f-962d-49a9-8f1b-9be10ae7e0d5` 安全收敛且无重放。历史 Run `ccbb3f7f-fac6-4472-97e0-02e2ff47894d` 仍保持原状态，不补造见证 |

## R1 历史验证

- 历史 R0：`mvn -q -pl cm-agent-agentscope-adapter,cm-agent-local-client,cm-agent-server -am test` 使用 JDK 21 启动，在 `cm-agent-persistence` 因本机无 Docker 环境失败，相关 Testcontainers 未形成有效断言。该失败保留为“本机不能替代 Rocky”的历史证据。
- `mvn -q -pl cm-agent-local-client -am clean test`：退出码 0。
- `mvn -q -pl cm-agent-server -am "-Dtest=ClientProjectServiceTest,ClientProjectRequestBoundaryTest,ClientProjectToolCatalogTest,ToolOperationCategoriesTest,ProjectRuntimeContextTest,ClientProjectRootStoreTest,LocalClientServerClientTest,AgentScopeProtectedToolPayloadTest,ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" clean test`：退出码 0。
- `mvn -q -pl cm-agent-agentscope-adapter -am test`：退出码 0；日志中的故意异常、超时与 `onErrorDropped` 场景均被对应合同断言观察，未使构建失败。
- `node --test src/test/js/*.test.cjs`（工作目录 `F:\java\cm-agent\cm-agent-console`）：232 项通过，0 失败、0 跳过。
- `mvn -q -DskipTests package`：退出码 0。
- V29 静态双方言检查：`MIGRATION_STATIC_OK tables=5 bothDialects=V29 comments=complete`。
- 六份实施文档检查：`DOC_CHECK_OK files=6 revisions=R1 tasks=T0-T18`，相对链接有效、代码围栏成对、清单与账本状态一致。
- 曾并行运行多个 Maven reactor，因共享模块 `target/` 出现 `NoClassDefFound`/缺失 class；后续全部改为串行并重新执行上述命令。该并发现象不是业务失败证据，也不应作为最终结果引用。
- `git diff --check`：通过，仅有 Windows 下既有文件 LF→CRLF 转换提示；未发现空白错误。工作树中的用户配置、`.gitignore`、`.impeccable/design.json`、`.workbuddy/` 和 `application-ok.yml` 保留不动。

## R2 验证

- R2 定向 Java：`ClientProjectServiceTest,ClientProjectRunServiceTest,ClientProjectToolCatalogTest,ClientProjectApprovalGuardTest,ClientProjectOperationTest,ToolOperationCategoriesTest,ProjectRuntimeContextTest,ClientProjectRootStoreTest,ClientProjectChangeOperationsTest,ClientProjectOperationJournalTest,LocalClientServerClientTest,AgentScopeProtectedToolPayloadTest,ConsoleResourceTest`：通过。新增合同覆盖审批版本/摘要分别不匹配、重复目标/大小写别名、普通文件父子冲突以及跨 Journal 实例回执复用拒绝。
- `mvn -q -pl cm-agent-local-client -am clean test`：通过，32 项，0 失败、0 错误、0 跳过。
- `mvn -q -pl cm-agent-agentscope-adapter -am test`：通过，181 项，0 失败、0 错误、5 项跳过；跳过项为 1 个外部停止条件项和 4 个 live Provider 项。
- `node --check assets/client-projects.js` 与 `node --test src/test/js/*.test.cjs`：语法检查通过；全量 232 项通过，0 失败、0 跳过。
- `mvn -q -pl cm-agent-persistence -am "-DskipTests" package`：通过，更新后的 MigrationTest 可编译。
- `mvn -q -DskipTests package`：退出码 0，全模块跳过测试打包通过。
- V29 静态双方言检查：`MIGRATION_STATIC_OK tables=5 columns=67 tableComments=5 preexistingColumnComments=2 bothDialects=V29 comments=complete`。MigrationTest 已加入五张表、关键外键/索引和协议 5 注释断言，但未在数据库执行。
- 浏览器探测：`http://127.0.0.1:8080/console/v2/projects.html` 返回 200，但 Playwright 被重定向至 `/console/v2/login.html`，当前无已登录会话，不能形成项目页桌面、390px、320px、键盘或迟到响应证据。
- Rocky 只读环境探测：Docker 23.0.6 可用，三套指定镜像已存在；根分区 70 GiB 已用 69 GiB、仅余约 1.8 GiB，并有大量无关运行容器。本轮未启动新 Testcontainers 或执行全局清理。
- `git diff --check`：通过；仅保留 Windows 换行转换提示。

## R3 初始验证（历史）

- `mvn -q -pl cm-agent-local-client -am test`：通过，37 项，0 失败、0 错误、0 跳过；新增固定工具链配置变化、敏感参数和真实子进程停止合同。
- 客户端命令合同 `ClientProjectCommandOperationsTest`：包含异步启动/停止和子进程树清理两项；`ClientProjectCommandJournalTest` 证明新进程实例把孤立 RUNNING 回执改为 UNKNOWN 且禁止复用命令编号。
- `mvn -q -pl cm-agent-server -am "-Dtest=ClientProjectCommandServiceTest,...,LocalProjectRunCompletionTest,ToolApprovalServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过；命令服务 9/9、Run 屏障 5/5。
- R3 Server 定向合同合计 7 个测试类、36/36，0 失败、0 错误、0 跳过。
- Server 合同覆盖：仅提议命令不阻断成功、已执行 FAILED/超时不通过、超时 STOP_REQUESTED→UNKNOWN、重复停止转 COMMAND_STATUS、异步 RUNNING 保留执行句柄并在后台终态后释放。
- `node --check assets/client-projects.js` 与 `node --test src/test/js/*.test.cjs`：通过，233/233，0 失败、0 跳过。
- `mvn -q -pl cm-agent-agentscope-adapter -am test`：通过，182 项，0 失败、0 错误、5 项 external/live 条件跳过。
- `mvn -q -pl cm-agent-core -am test` 与 `mvn -q -pl cm-agent-console -am test`：均退出码 0。
- V29 静态双方言检查：`MIGRATION_STATIC_OK tables=6 pgColumnComments=91 myColumnComments=89 tableComments=6/6`；表集合一致，89 个新增列注释完整，PostgreSQL 另更新 2 个既有列注释。
- `mvn -q -pl cm-agent-persistence -am "-DskipTests" package` 与 `mvn -q "-DskipTests" package`：均退出码 0。
- 配置示例与文档门禁：`TOOLCHAIN_JSON_EXAMPLE_OK`、`DOC_CHECK_OK files=6 revisions=R3 links=32`；代码围栏和相对链接有效。
- 真实环境复核：项目页静态 URL 返回 HTTP 200，但仅证明服务入口可响应，不证明已登录项目会话或命令交互；Rocky Docker 23.0.6 可用，`maven:3.9.9-eclipse-temurin-21`、`postgres:16-alpine`、`mysql:8.4` 镜像均存在；根分区 70 GiB 已用 69 GiB、仅余 1.8 GiB（98%）。
- 远端 Testcontainers 未启动：本地仍是未提交工作树，无法按仓库门禁与远端提交对齐；远端默认 Java 17/Maven 3.6.3 也不能替代指定 JDK 21 容器，且当前磁盘余量不支持新增高影响验证。该项继续阻塞 T9。
- `git diff --check`：通过；仅有工作树既有 Windows 换行转换提示。

## R3 续验命令与证据

- 本地构建：JDK 21.0.11，Maven 3.9.4；远端 `maven:3.9.9-eclipse-temurin-21` 实测 Maven 3.9.9/JDK 21.0.7，Docker 23.0.6。远端根分区约 1.5–1.7 GiB 可用，复用已有依赖缓存，串行 Testcontainers，未清理无关资源。
- `mvn -q -pl cm-agent-persistence -am test`：`0903bb5` 首轮 94 项中 2 项失败，均为检查点升级测试固定 7 次迁移与实际 10 次不符；不是运行成功。修正为核对解析出的全部待迁移版本。
- 相同全量命令在 `17bf309` 上 94/94、0 失败/错误/跳过。迁移 V29、全部表字段注释、既有仓储与新增设备项目命令/任务断言通过。
- `9e1745e` 远端定向 `MigrationTest,JdbcLocalClientDeviceRepositoryTest,JdbcRuntimeCheckpointRepositoryTest,ClientProjectCommandTest`：退出码 0。
- 最终数据库/core 代码在 `c8d6ad3` 对齐远端后运行 `mvn -q -pl cm-agent-persistence -am -Dtest=JdbcLocalClientDeviceRepositoryTest,ClientProjectCommandTest -Dsurefire.failIfNoSpecifiedTests=false test`：退出码 0，双库 2/2、core 3/3；本地后续 d85f785 仅改 Server 恢复回执与测试，`git diff c8d6ad3..d85f785 -- cm-agent-core cm-agent-persistence` 为空。
- 客户端 `mvn -q -pl cm-agent-local-client -am test package`：39 项、0 失败/错误/跳过，包含累计输出不重复合同。
- 适配器 `mvn -q -pl cm-agent-agentscope-adapter -am test`：182 项、0 失败/错误，5 项 external/live 条件跳过；真实 Provider 证据由下表单独证明。
- Server：协议/来源/快照/命令/审批/Run 相关 55 项在 6bbe6b7 通过；d85f785 的 `ClientProjectCommandServiceTest,LocalProjectRunCompletionTest` 增量 16 项通过；最终 d85f785 定向全套 12 类 56/56、0 失败/错误/跳过；`mvn -q -DskipTests package` 全模块退出码 0，不把打包当成全量 Java 测试。
- Node `node --test src/test/js/*.test.cjs`：235/235 通过；设备项目 8 项覆盖通用路由行动头、真实 SSE 决定接口、轮询草稿保留、断流错误编号与禁止重提。
- 最终文档门禁：`DOC_CHECK_OK files=6 revision=R3 links=38 tasks=T0-T18`，清单/账本状态一致、提示词范围一致、代码围栏成对；`git diff --check -- docs` 通过。本轮文档提交范围为原六份工作包与配置/运维/发布说明共九份文档，没有新增业务代码、迁移或配置值。
- 本轮曾误用默认 JDK 17，Surefire 拒绝 class 65/运行时 61；切换 JDK 21 后串行重跑通过。运行中 Console JAR 锁导致一次 install 失败，按受控停机→install→启动恢复，没有修改配置来规避。

| 实例 | 真实验收结果 | 可复核标识 |
|---|---|---|
| W01 浏览/读取 | 既有项目 LIST/README 预览成功，客户端多次重启后根绑定可用 | project `8fb8c76c-0609-4096-a0e4-a65324181804`，device `91cbb6cc-c9e2-479f-971b-f749a69af2dc` |
| W02 仅提案 | 真模型只调用查询/提案后 Run SUCCEEDED，未启动进程 | Run `f645d25f-31f1-4fdf-8a28-56ea751d85dd` |
| W03 首次 quick 失败与核对 | 本机命令 SUCCEEDED，但 JDBC NULL 映射导致 Run FAILED；修复后由真实回执核对命令 SUCCEEDED，不把旧 Run 改成成功 | command `6997a62c-0215-47bb-836e-e3cc891adbe4`，Run `e8812644-2c52-40c3-bd50-497b19803944` |
| W04 精确批准成功 | HIGH 预览/勾选/批准→签名设备执行→command/Run 均 SUCCEEDED，退出码 0、输出可用 | command `ead91102-11d2-4324-8304-0b36faf868f5`，Run `811fa0f2-8f94-424a-abbf-909bdad4343e` |
| W05 屏障与停止 | 模型流已结束而 command/Run 仍 RUNNING；停止后 command CANCELLED/Run FAILED；父子 PID 均退出；重复停止返回 COMMAND_STATUS，查询任务 SUCCEEDED；启动标记各一次 | command `9c52da99-eada-413c-b4da-d8296fc274ca`，Run `8d63843d-00e6-4e8e-8d86-0b31d0f7e170`，PID 38412/40992，重复查询 `d4d4b30f-6a8c-4beb-9375-93c1e95797ed` |
| W06 重启/UNKNOWN/对账 | 强制重启客户端后本机回执 UNKNOWN；旧服务拒收 seq=0，完成屏障按预算收口 UNKNOWN，原主体停止使 Run CANCELLED；d85f785 部署后真实客户端成功回报，保留 seq=69/1579 字节输出，version=17；人工确认 HTTP 200 后仍 UNKNOWN/version=17，归档 409，测试进程 0，没有自动重放 | command `5a2e984f-64ab-4e13-bb7c-d0736a353038`，Run `fad25c6d-42dd-4069-a854-2b53ac4a8289`，对账审计 `ddbcbf4e-50e5-4eaf-a30a-00c22d6f64a2`，拒绝编号 `8b188e5f-af3b-40df-86a9-7afeff9de090` |

W06 的查询/回报任务成功只表示未知状态已可靠接收，命令本身仍 UNKNOWN，不能称命令执行成功。实际模型配置为 `OPENAI_COMPATIBLE/glm-5.3`，未采用 fake runtime、直接插队列或改库制造上述结果。原生目录窗口未通过，以上从既有登记项目开始。

| 提交 | 范围 | 状态 |
|---|---|---|
| `21344f4` / `1b19863` | T6/T17 主体、V29 外键修复（续验前形成） | 已在远端 |
| `0903bb5` | taskId 与列表查询边界、六份初始续验记录 | 已提交并推送 |
| `17bf309` | 协议 5 Run/派生闸门、请求头、双库验收 | 已提交并推送 |
| `9e1745e` | 微秒来源、长期历史、SSE/草稿/摘要、快命令退出 | 已提交并推送 |
| `6bbe6b7` | JDBC 空退出码与调度清理归属 | 已提交并推送 |
| `c8d6ad3` | 累计输出只接收新尾部 | 已提交并推送 |
| `d85f785` | UNKNOWN 恢复回执保留权威输出/序号 | 已提交并推送，已部署验收 |

本轮文档与发布说明在上述代码之后核对、提交并推送；不包含 `.gitignore`、设计文件、用户 application*.yml、`.workbuddy/` 等无关变更。前述“未提交/未登录/双库未运行”属于早期历史，不能继续作为当前阻塞。

## C01–C10 当前证据映射

| 编号 | 自动化证据 | 真实环境证据 | 结论 |
|---|---|---|---|
| C01 | `ClientProjectToolCatalogTest`、`AgentScopeProtectedToolPayloadTest` 覆盖独立命名空间、伪造名称和载荷保护 | 普通会话/MCP/调试/旧版全入口矩阵未运行 | 部分，T15/T14 进行中 |
| C02 | `AgentScopeProtectedToolPayloadTest`、`HarnessProtectedToolPayloadTest` 覆盖成功/失败/审批恢复和事件泄漏 | 合成 Provider 合同及真实 glm-5.3 命令链通过；Harness/全入口矩阵仍待验 | 部分，T15/T14 进行中 |
| C03 | `ClientProjectRootStoreTest` 覆盖同根别名、父子根和选择任务重放 | 不同 `--state-dir`、重新配对、真实多进程未运行 | 部分，T16/T14 进行中 |
| C04 | `ClientProjectCommandServiceTest` 覆盖提议/失败/超时；`LocalProjectRunCompletionTest` 覆盖异步 RUNNING、停止句柄保留和后台终态 | 真实客户端重启、屏障/停止/UNKNOWN 对账已验；多实例/运行中 Server 接管和注销/断电未验 | 真实命令子集通过，完整故障待补；T17/T14 进行中 |
| C05 | `ClientProjectOperationTest`、审批和 T12 服务合同覆盖单次许可及冲突计划 | 旧进程/迟到许可/真实崩溃未运行 | 部分，T12/T14 进行中 |
| C06 | 计划冲突、条件应用、审批摘要和读快照合同通过 | 回执丢失、快照保存失败、真实外部编辑器竞争未运行 | 部分，T5/T16/T14 进行中 |
| C07 | `ClientProjectCommandOperationsTest` 覆盖敏感参数、构建摘要变化、异步启动和子进程停止；`ClientProjectToolchains` 拒绝 Shell/敏感覆盖 | 真实用户配置、进程树停止、累计输出与客户端重启已验；ACL、注销/断电和脱离进程树未验 | 真实命令子集通过，完整故障待补；T6/T14 进行中 |
| C08 | Node 能力关闭与来源切换相关合同通过 | 真实登录与桌面命令交互通过；跨来源迟到 202、浏览器代际/窄屏矩阵未完成 | 部分，T18/T14 进行中 |
| C09 | 归档拒绝未决任务及后端来源限制有合同 | 设备离线/撤销后的完整历史读取未运行 | 部分，T7/T18 待补 |
| C10 | `ProjectRuntimeContextTest` 与客户端审批合同约束 ASK_ON_EFFECT、HIGH 决策和跨主体入口 | 真实浏览器精确批准与 UNKNOWN 对账通过；跨租户/退出/全权限矩阵未完成 | 部分，T13 完成但 C10 整体未闭合 |

## R1→R2→R3 差异与未验证门禁

- R1/R2 形成目录/读取、文件变更与审批合同；R3 完成命令/屏障/停止/对账模块。本轮发现并修复 taskId、空搜索词、协议 5 首发/派生路由、精度、SSE、审批草稿和摘要、JDBC NULL、调度 owner、累计输出及重启 UNKNOWN 序号兼容问题；对应证据见续验结果，不覆盖原失败记录。
- T6/T17 的真实 Windows 命令子集已通过；T9 进行中，不再受未提交、未登录或双库未运行阻塞。最终数据库代码的双库门禁通过，完整故障矩阵仍未完成。
- 未通过/未执行：原生目录窗口（已尝试但不可操作）、真实文件写入/ACL/多进程竞争、权限/设备撤销全矩阵、脱离进程树、Windows 注销/断电、远端 Server/TLS、多实例/旧版/回滚栅栏和完整窄屏/键盘/迟到响应矩阵。
- 运行中 Server 重启后的自动接管未完成；本轮 Server 重启是在 Run 已收口之后，不能作为运行接管通过证据。当前页面长轮询有预算，超过后需重新选择原会话核对，不能误报成永久自动跟踪。
- 当前验收项目有意保持 UNKNOWN 隔离；人工确认已有审计、物理测试进程数为 0，但不能据此改成 SUCCEEDED 或解除隔离。不要删除回执或改库。
- 本工作包继续承接原 R2 需求包，保留其历史；无新增迁移，V29 和协议 5 未再修改。本轮六份产物保持 R3、mode=default、T0–T18 稳定编号。

关联：[清单](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md)｜[设计](../specs/2026-11-09-client-project-workspace-delivery-design.md)｜[计划](../plans/2026-11-09-client-project-workspace-delivery.md)｜[提示词](../prompts/2026-11-09-client-project-workspace-delivery-prompt.md)｜[实现说明](../implementation/2026-11-09-client-project-workspace-delivery-implementation-design.md)。

## 2026-11-10 续验补充：目录窗口与运行中重启（历史）

- T1/T9 仍进行中。已登录项目页选择设备 `91cbb6cc-c9e2-479f-971b-f749a69af2dc` 并发起真实选择请求；页面显示等待目标电脑打开选择器。客户端 PID 5116 的 JDK 21 线程快照显示 EDT 在 `JFileChooser.showOpenDialog` 模态等待，但当前桌面工具未发现可操作的客户端窗口；未取得选择、取消或登记结果，不能判定验收通过。该观察仅限当时进程及桌面。
- 源码核对：`LocalClientTaskExecutor.selectRoot` 直接调用 `SwingClientDirectoryPicker.choose(deviceName)`；当前选择器仅将设备名用于窗口标题，未实现需求要求的先展示请求来源、关联码及用户确认；Swing `JFileChooser` 本身也不能作为操作系统原生目录窗口的通过证据。应先补齐来源绑定、确认、有效期和窗口关闭/超时处理及回归，再在交互桌面验收；不得伪造选择结果或自动登记。
- T17/C04 的运行中 Server 重启接管本轮未执行：当前客户端停在选择器模态等待，既有验收项目仍有 UNKNOWN 隔离；不能以历史 Run 收口后重启或线程快照替代该场景。需恢复可用客户端并使用独立的受控验收项目，记录重启前后命令、Run、停止/查询与隔离证据。历史已通过项仍以账本 W01–W06 及双库记录为准，其他未完成矩阵不变。
- 随后页面显示“设备项目任务在结果确认前过期”；再次采集的客户端线程快照仍停在 `JFileChooser.showOpenDialog`。因此过期后本机模态窗口仍占用轮询线程，尚未验证自动关闭和轮询恢复；不要自动重试该选择请求。
- 本次仅记录调查，未修复代码、未完成目标验收；不得据此将 T1、T9 或 T17 标为完成。

## 本次修复与验证命令（2026-11-10）

- 本机 JDK 21.0.11 / Maven 3.9.4；`mvn -q -pl cm-agent-local-client -am test package` 通过，客户端 47 项、0 失败/错误/跳过；包括服务停止再启动的真实本地 HTTP 轮询测试、409 回执只发一次并继续轮询，以及过期/迟到选择不登记、窗口独占测试。该测试服务器不代替真实业务端到端。
- `mvn -q -pl cm-agent-server -am "-Dtest=ClientProjectCommandServiceTest,ClientProjectServiceTest,LocalClientSelectionCapabilityTest,LocalProjectRunCompletionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过，四类 29 项、0 失败/错误/跳过；覆盖旧客户端签名正文拒绝领取、来源期限、UNKNOWN/终态恢复回执及完成屏障。
- `node --test src/test/js/client-projects.test.cjs` 在 `cm-agent-console` 通过 9/9，含页面显示原请求编号；`node --check` 对设备项目脚本通过。
- Windows 本机确认框和系统 IFileOpenDialog 的独立到期探针各约 8 秒返回未选择；真实请求由用户操作后项目页出现 `cm-agent-e2e-project-20261103`，并可列目录。另一次请求返回失败且无详细原因；不能断言原因或整体验收通过。
- Server PID 8588 停机时命令 `229de3ba-51b4-4d45-89d2-9346c799f6d2` 的 node PID 39560 已启动；旧客户端 PID 17200 因连接失败退出后该进程不再存在，marker 为 1。修复并重启后，设备项目状态查询回报 `CANCELLED`、退出码 1，显示历史输出；Server 从 PID 24796 重启为 PID 24240 时新客户端 PID 26020 在空闲状态持续运行。此为失败复现、修复及终态对账，不是修复后运行中接管成功。
- 历史尝试：为准备新的真实长命令，`测试Qwen` 和 `企业助手` 返回 HTTP 403 额度耗尽，`opencode-glm5.3-falsh` 返回 HTTP 429 使用上限；当时新命令未启动；用户随后恢复模型，复验见下一节。
- 本次未修改数据库迁移、JDBC 仓储或数据库配置，未重跑 Rocky 双库；原历史双库证据仍仅适用于原提交。既有 `.gitignore`、设计文件、用户 `application*.yml` 和 `.workbuddy/` 保留。提交前本轮改动未提交；最终提交标识以 Git 历史为准。

### 用户恢复模型后的真实重启复验

- 请求通过真实模型、固定工具 `node-e2e`、版本/摘要精确预览及 HIGH 批准；`8bcca448-827a-49bc-99e3-01268ddd053b` / Run `ccbb3f7f-fac6-4472-97e0-02e2ff47894d`，客户端 PID 26020、node PID 39956。Server PID 24240 停机时两进程均存在，启动标记为 2；新 Server PID 30276 健康后两进程仍存在，状态查询 RUNNING 且输出持续。
- 受限停止后设备回报 CANCELLED、退出码 1，node PID 不存在，启动标记仍为 2。原 Run 显示 RUNNING，停止返回 409 RUN_CONTROL_UNAVAILABLE，不能把命令停止当成 Run 恢复。
- 首次终态重复停止返回 500（errorId `bc133084-f72b-4078-94d8-03d7e5284d12`）；修复创建任务和领取两侧门禁，定向测试覆盖终态仅状态查询。修复后真实 `POST .../stop` 返回 `COMMAND_STATUS` 任务 `812f1660-5ee1-42f2-b6a0-ebfa52239981`，任务 SUCCEEDED，命令仍 CANCELLED/退出码 1/序号 74。
- 此次验收揭示 Run 归属与恢复仍缺持久栅栏；不能依据内存句柄缺失假定旧实例已退出。没有改库或伪造 Run 终态，保留原 Run 供后续受控恢复。

### 目录选择失败诊断补充

- 客户端对根目录与已登记项目重叠返回 `CLIENT_PROJECT_ROOT_CONFLICT`，其他选择异常返回 `CLIENT_PROJECT_SELECTION_FAILED`，过期返回 `CLIENT_PROJECT_SELECTION_EXPIRED`；日志和安全回执包含同一个随机错误编号，异常堆栈去掉原始消息及因果链。单元测试核对编号一致、路径与凭据不泄漏。此前请求 `edb7e9f3-14e1-4c27-912f-419bb9910476` 使用旧版本执行，未保留足够诊断信息，原因仍不能倒推。
