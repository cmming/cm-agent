# 本机客户端项目工作区实施说明

## R8 实际交付：T22 重启恢复与迟到回执收口（2026-11-12）

- `LocalClientServerClient.reportTask` 对 400/409/410/422/429 返回新增 `OperationResultRejectedException`；`LocalClientApplication.consumeTasks` 捕获后只输出“不会自动重试”提示并继续轮询。
- `LocalClientReconnectTest` 新增通用操作迟到回执合同：首次领取 `NETWORK` 任务并返回结果时服务端回 409，客户端必须继续领取后续任务。
- 用户手动重启 `8080` 至 PID `44012` 后，重启前设备 `a6d5da12-9f66-4699-8fdb-84ba84d46e25` 无需重新配对即可恢复签名轮询。
- 客户端 PID `680242` 停止并由 PID `717669` 重启后，Run `80e8e563-5a1c-4694-8c8e-e5114b1660b3` 成功。
- 迟到恢复真实验证：操作 `c074a4fc-04f1-4aa1-b3a8-d6a7cf5b9439` 为 `UNKNOWN`、Run `b0ef5c51-...` 为 `FAILED`；客户端收到 HTTP 409 后存活且未重放，后续 Run `22cca63e-4173-4e24-a857-f7d85024894d` 与操作 `bb6a0c93-...` 成功。
- 验证：本地客户端 60/60、控制台 16/16、Server 恢复定向 8/8，均无失败、错误或跳过。客户端最终已停止，随机口令文件已删除。
- **未执行：** 没有由代理重启用户 `8080`，没有启动额外 Server；真实 HTTP 中断恢复由用户手动重启和现有 `LocalClientReconnectTest` fixture 共同覆盖。

## R7 历史实际交付：T21 Rocky 经 HTTP 真实闭环（2026-11-12）

- `LocalClientProperties` 取消“非回环 HTTP audience 必须 HTTPS”的校验，保留固定 audience、无 userinfo、无附加路径、无查询/片段等规范来源要求；新增 `LocalClientPropertiesTest` 覆盖 HTTP/HTTPS 成功及危险来源拒绝。
- `LocalClientServerClient.nextProjectTask` 将设备项目端点 `404` 解释为空队列，允许通用本机客户端在设备项目功能关闭时继续轮询；`LocalClientServerClientTest` 增加对应合同。
- Rocky 使用 JDK 21.0.11 和本次重新构建的 fat JAR；Server 以 `mysql,local` profile、8080 HTTP、显式 audience `http://192.168.0.159:8080` 运行。
- 新设备 `541802a1-67cd-48ed-b3ec-f951b972cc18` 经配对 `23f2f03b-778d-44e3-90d6-091edfdbb772` 确认。简单 Run `50067d93-d8f9-433f-bce1-ed7ed3c2bc6f` 调用 `local_client_network` 一次，操作 `02dba526-73c9-4d45-afcc-1fd72670bf86` 成功，健康端点为 HTTP 200/`{"status":"UP"}`。
- 审计包含 `LOCAL_CLIENT_PAIRING_CREATE`、`LOCAL_CLIENT_PAIRING_CONFIRM`、`LOCAL_CLIENT_OPERATION_QUEUE/DISPATCH/COMPLETE` 和 `AGENT_RUN` 成功记录。
- 客户端进程已停止，随机口令文件已删除；设备、Run、操作和审计记录保留。Server 仍运行且监听 `::8080`。
- 验证：Server 定向 7/7、本地客户端 59/59、控制台 16/16，失败、错误、跳过均为 0。

## R6 历史实际交付：T20 服务端来源可选 HTTP/HTTPS（2026-11-12）

- `DeviceIdentityStore` 删除远端 HTTP 硬拒绝，继续限定服务端为无用户信息、查询、片段和附加路径的 HTTP/HTTPS 规范来源。
- `LocalClientServerClient` 将 `requireFenceHttps` 替换为 `requireFenceOrigin`；HTTP 可签发代次挑战、读取当前代次并提交签名确认。HTTPS 响应仍要求 SSL 会话，JVM 默认证书链与主机名校验没有关闭。
- `ClientProjectFenceHandshake` 的见证来源摘要改为包含 scheme、规范主机和有效端口，HTTP 默认 80、HTTPS 默认 443。
- `local-client.html` 删除远端必须 HTTPS 的说明，配对示例改为 HTTP，并说明 HTTP/HTTPS 都必须与部署配置完全一致、HTTPS 仍校验证书。
- `DeviceIdentityStoreTest` 新增远端 HTTP 来源持久化；`LocalClientServerClientTest` 新增 HTTP 代次挑战/确认，并保留真实本地 TLS 与不可信证书拒绝测试。
- 本机验证：JDK 21.0.11、Maven 3.9.4 下定向测试退出码 0；`cm-agent-local-client,cm-agent-console -am test` 退出码 0。测试日志中的目录选择失败为预期错误分支夹具，Maven 最终结果为成功。
- **未覆盖：** 尚未使用 Rocky 对真实远端 HTTP 服务端完成配对和任务闭环；HTTP 下服务端响应机密性与真实性的部署风险保留。

## R5 历史实际交付：T9/T17 平台恢复续验（2026-11-11）

- `ClientProjectFenceJournal` 新增固定安全的 `MissingWitnessException`，区分文件或指定 Run 记录缺失与一般损坏；`LocalClientApplication` 对缺失见证停止轮询并提示先执行 `init-fence-store`。异常和提示均不包含见证路径、项目路径或设备密钥。
- `ClientProjectFenceHandshake`、`LocalClientServerClient`、`JdbcClientProjectFencedTaskReader/Writer` 与 `JdbcClientProjectFenceAcknowledgements` 中 R4 后已过时的“尚未接入”说明已同步为真实接线状态。
- 真实客户端重启：命令 `525ff639-c6bc-4e12-9459-c839499580de` 启动后强杀客户端 `45772`，同 Job 的 `node.exe` `19684` 同步消失；重启 PID `40480` 后持久命令日志转 `UNKNOWN`，后续状态任务成功回报且命令保持隔离。
- 旧客户端无见证：Run `e8662979-6744-46e8-8152-37f11395e2b2`、任务 `228a519e-1719-4e61-a5c7-7ddcc9354ac0` 保持未领取，代次确认数为 0；修补后的客户端退出并给出显式初始化命令。
- 双实例竞争：Run `cef6c8b3-2abb-4258-88dc-5b48f30fa6ec` 由实例 `3e4f10d7-00bb-491a-a5a6-5d213375d5e3` 持有；强制过期后，`8444` 实例 `1191db8a-3f60-43a7-a542-48a9a8523e39` 赢得 `RECOVERING` 代次 2，设备确认后 Run `FAILED`、命令 `UNKNOWN`、归属 `CLOSED`。
- Rocky 对齐提交 `ce84c3c` 的数据库与客户端门禁：`MigrationTest` 3/3、`JdbcLocalClientDeviceRepositoryTest` 2/2、`ClientProjectFenceJournalTest` 5/5、`LocalClientReconnectTest` 2/2，均无失败或跳过。
- **平台边界：** 强制终止证明了 Job Object 在进程句柄被系统关闭时收口子进程；未在本轮日志中执行真实交互式 Windows 注销或物理掉电，T9/T17 整体仍保持进行中。

## R4 历史实际交付：T19 可信代次与安全恢复（2026-11-11）

- `LocalClientServerClient.Task#fencedProjectTask` 将 `epoch=0` 视为项目级任务；`LocalClientServerClientTest` 新增服务端真实响应形态回归，运行级正代次校验未放宽。
- `ClientProjectService` 在未确认代次时返回 `202 FenceView`，确认后通过 `ClientProjectTaskFencing` 与 `JdbcClientProjectFencedTaskReader` 在同一事务领取任务；`ClientProjectFenceService` 与 Controller 只接受签名、无重定向、带协议头的当前代次/确认请求。
- `ClientProjectRunRecoveryService.finalizeRun` 在 Run 失败与审计同一短事务内调用 `closeCurrent` 关闭恢复归属；`JdbcClientProjectRunRecoveryRepository` 和 `JdbcClientProjectFenceAcknowledgements` 的续租、过期、确认查询均要求 Run 仍为活跃状态。
- `LocalClientProofService` 的固定窗口预算改为来源与设备组合键；测试证明首设备达到上限后，第二设备仍可独立申请挑战。
- 客户端新增 `--credential-backend`、`--password-stdin` 和独立 `init-fence-store` 路径，供受控 Windows 自动化在不把口令写入参数或日志的前提下运行；缺见证仍显式拒绝自动重建代次状态。
- 真实 Windows 证据：设备 `91cbb6cc-c9e2-479f-971b-f749a69af2dc`、项目 `deee891a-6d63-4923-a108-934c53e876d1`、Run `e8eef87f-962d-49a9-8f1b-9be10ae7e0d5`、命令 `c406d958-0585-4eb7-8e76-f3e0dc45043d`。启动时命令 `RUNNING`；Server 停机 36 秒后客户端 PID 保持，恢复代次 2，命令 `UNKNOWN`、`stopRequested=false`，Run `FAILED`，恢复扫描未重放。
- Rocky 提交 `9fff872`：PostgreSQL 16.14/MySQL 8.4 的 `MigrationTest` 3/3、`JdbcLocalClientDeviceRepositoryTest` 2/2；客户端 `ClientProjectFenceJournalTest` 4/4、`LocalClientServerClientTest` 9/9，无失败或跳过。

以下 T19 小节保留形成 R4 前的实现演进；其中“尚未接入、待验收”均不覆盖本节。

## T19 任务代次入队续验（2026-11-11）

- T19 **进行中，未完成、未提交、未推送**。新增任务代次持久绑定及入队事务组件，旧领取和旧过期扫描跳过带代次任务；组件尚未与 Server 的真实任务生成、设备领取、客户端握手或 Run 恢复接线。
- V31 与 V30 均为未提交工作树中的迁移；使用本工作树构建的 JDBC Server 重启可能执行迁移，不能当成已发布或已验收功能。
- 接入前仍需可信服务端响应认证、设备见证防回滚、握手确认与旧进程/UNKNOWN 对账；本轮未进行真实 Server/Windows 端到端验收。
- 新增双方言 V31 `client_project_task_fences` 及中文原生注释；`JdbcClientProjectFencedTaskWriter` 在调用方事务中先锁可信运行归属，复核租户、原主体、项目、Agent、会话及设备，再原子保存任务与实例/代次。旧领取和过期扫描跳过带代次任务。新入队尚未接入 Server，可信领取尚未实现。

## T19 接管屏障与恢复确认续验（2026-11-11）

- T19 **进行中，未完成、未提交、未推送**。公开接管接口不再接受调用方提供的恢复屏障，避免空回调绕过隔离和审计；客户端持久恢复确认支持同代重试。两项仍为未接入运行路径的组件，不能声称真实 Run 已恢复。
- Rocky 临时快照 `7576574366a803514c9e7127236bb32d54089540` 与待验代码对齐，Docker 使用 `maven:3.9.9-eclipse-temurin-21`，`MigrationTest`、`JdbcLocalClientDeviceRepositoryTest` 和 `ClientProjectFenceJournalTest` 分别 3、2、4 项通过，覆盖 PostgreSQL 16/MySQL 8.4 和客户端跨进程锁。该快照不是正式提交。
- 审计主体修订：自动恢复事件使用 `system:client-project-recovery`，避免冒用 Run 原发起人；原主体通过可信 Run 来源追溯。对应临时快照 `cdd0e64fdc2b42364195ed5e4117cf1256f6217a` 已在 Rocky 重跑双库和客户端定向测试，分别 3、2、4 项通过，无失败或跳过；它不是正式提交。
- 接入前必须建立可信的服务端响应认证、客户端持久见证防回滚和代次握手；真实 Server 重启、Windows 端到端和旧 UNKNOWN 对账尚未验收。不要将本机日志、内存句柄或旧任务状态当作副作用已停止的证明。
- `JdbcClientProjectRunOwnershipRepository` 的公开构造与接管入口固定使用同数据源 `JdbcClientProjectRecoveryBarrier`；调用者不能再传入空操作回调。仅包内测试构造器可注入故障。`ClientProjectFenceJournal.advance` 对已落盘的同一恢复代次允许幂等确认，不重复写入，也不恢复执行阶段。

## T19 客户端见证与审计夹具续验（2026-11-11）

- T19 **未完成、未提交、未推送**。新增客户端本机代次见证组件及测试，但尚未接入现有任务领取、设备协议或生产恢复路径；测试不能证明真实端到端接管。
- 服务端隔离测试新增真实 `JdbcAuditEventRepository` 写入，使用唯一键冲突检查归属和隔离回滚；现新增 `JdbcClientProjectRecoveryBarrier`，以同数据源同事务执行隔离和真实 JDBC 审计；它尚未装配到生产自动接管流程。
- Rocky 临时验证快照 `f2a2aa7646caa1925c8b74e1a57f93f57cb6f17a`：在 `maven:3.9.9-eclipse-temurin-21` 中运行 `MigrationTest,JdbcLocalClientDeviceRepositoryTest,ClientProjectFenceJournalTest`，分别 3、2、4 项通过、无失败或跳过；覆盖 PostgreSQL 16/MySQL 8.4、实际 JDBC 审计冲突回滚及客户端进程间锁。快照仅用于对齐验证，不是正式提交。
- 已发出任务、已启动进程以及 UNKNOWN 结果不能通过改数据库状态当作无副作用；必须等待可信新代次确认并保留隔离、停止和对账。V30 处于未提交工作树，使用该工作树构建的 JDBC Server 重启可能自动迁移，禁止当作已完成发布。
- 客户端新增 `ClientProjectFenceJournal`：显式初始化，按租户/设备/项目根/Run/服务端身份摘要保存单调代次；追加后 force，持跨进程锁同步执行动作；恢复阶段单独校验，拒绝使用恢复代次执行原副作用。组件尚未被客户端任务处理器调用，也未实现认证握手和防整体回滚。`JdbcClientProjectRecoveryBarrier` 在归属仓储事务内复用其 JDBC 数据源，先隔离后追加不含载荷的审计事件，失败向上抛出以回滚；目前未装配到 Server。

## T19 隔离和事务边界续验（2026-11-11）

- T19 **进行中，尚未完成或发布**。隔离组件仅在测试中通过恢复回调调用；服务器任务领取、旧实例执行、客户端持久代次和真实严格审计尚未联通，不能据此声称支持安全接管。
- PostgreSQL 16/MySQL 8.4 的 `MigrationTest,JdbcLocalClientDeviceRepositoryTest` 已在 Rocky 对齐的临时快照 `16bd63172fb5f378b41c022e86fb6d1f7f400cb2` 上通过，覆盖隔离及回调失败回滚；其后的跨数据源活动事务检查在快照 `4a40b0b2ece7714eea4cd2935d418f4fbea383dc` 上重跑通过：迁移测试 3 项、设备仓储测试 2 项，均无失败或跳过；测试在 Maven 3.9.9/JDK 21.0.7 容器内执行。临时快照不等于最终提交。
- 已领取任务可能在数据库隔离后迟到执行；客户端必须持久化并确认新代次，Server 还须对入队、领取、回报、审批和终态写使用可信归属栅栏。未知副作用不得重放，旧版无见证 Run 不能猜测已停止。当前真实端到端验收未完成。
- V30 仍在未提交工作树，重启使用该工作树构建的 JDBC Server 可能执行迁移；功能尚未完成，不应作为生产发布。未提交、未推送。
- 实际实现：`JdbcClientProjectRunIsolation` 在恢复归属锁内把待领取任务设为 CANCELLED、已派发任务及可能有副作用的命令/文件操作设为 UNKNOWN，并保留原有隔离；`JdbcClientProjectRunOwnershipRepository` 要求指定数据源与事务管理器一致，使用 MANDATORY 校验该数据源确有活动事务，隔离器复用同一 JDBC 入口。此代码尚未装配到运行路径；回调仍须由生产调用方提供实际严格审计。

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

日期：2026-11-09；当前修订 R8（2026-11-12）；mode=default；R4 业务代码提交 `9fff872`，R5/R6 已收口，R7 主提交 `744bae5` 与证据提交 `5fe3f14` 已推送，R8 本轮待提交。

## R3 续验结果（历史，2026-11-10）

当前仍为 R3、mode=default；这是原任务的实现与验收收口，不新增需求修订或重复工作包。业务代码已提交并推送至 `d85f785`，客户端输出修复为 `c8d6ad3`。T6/T17 的真实 Windows 命令子集通过；T9 改为进行中，整体验收仍未闭合。

- Rocky：`17bf309` 上持久化全量 94/94 通过；`9e1745e` 上迁移/检查点/设备仓储及 core 命令定向通过；最终 `c8d6ad3` 对齐双库命令仓储增量 2/2 和 core 命令 3/3 通过。当前持久化/core 与该次验证无差异，不把全量 94 项冒充在最终提交重跑。
- Windows：真实已配对客户端、认证浏览器和 `OPENAI_COMPATIBLE / glm-5.3` 完成工具链查询、提案、HIGH 精确预览/批准、命令成功、Run 屏障、进程树停止、重复停止查询、客户端重启 UNKNOWN 回报与人工对账。所用 Agent 显示名为“测试Qwen”，实际模型以运行配置的 `glm-5.3` 为准。
- UNKNOWN：本机孤立回执为 UNKNOWN；服务端恢复后仍为 UNKNOWN/version=17，输出序号 69 与已有 1579 字节输出保留。人工对账 HTTP 200 不改变状态、版本、序号；真实审计存在；归档 HTTP 409 `CLIENT_PROJECT_TASK_PENDING`，测试进程数量为 0，未自动重放。
- 边界：使用既有登记的验收项目；原生目录窗口在当前自动化桌面不可操作，不能据此宣称完整“选择目录到开发”验收通过。远端 Server/TLS、多实例、设备撤销、Windows 注销/断电、文件写入竞争和完整窄屏/迟到响应矩阵仍未验。

具体命令、Run/命令编号、失败修复与证据适用范围以[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)为准。

R0 只建立了独立实施工作包并完成 T0 的初始环境/代码调查。R1 交付“协议 5 只读纵切面”。R2 补充只读门禁和文件变更审批纵切：协议版本头、单在途选择、Windows 文件身份、Run 级读取快照、变更计划、HIGH 审批、条件应用、条件撤销和客户端私有回执。R3 完成固定工具链命令、命令启动许可、有界输出、进程树停止、Run 完成屏障和 UNKNOWN 对账的模块级纵切。

## R1 实际实现

- 客户端选择与根：`ClientDirectoryPicker`/`SwingClientDirectoryPicker` 在当前用户交互桌面打开系统目录窗口；`WindowsClientProjectDirectoryIdentity` 通过只读 Windows 句柄取得卷和目录对象身份；`ClientProjectRootStore` 在用户私有目录用跨进程锁保存绝对路径，向 Server 只返回 `rootId`、身份摘要和展示名，并拒绝 UNC/网络盘、私有状态区、同根别名及父子根。
- 客户端只读执行：`ClientProjectFileOperations` 对每个任务重新核验根身份，限制相对路径、链接、排除目录、条目数、递归深度、文本大小和偏移；目录结果游标化，文本结果按 UTF-8 边界分页并返回原文摘要，搜索限制扫描/命中预算并声明 `partial`。
- 客户端任务循环：`LocalClientTaskExecutor` 按固定类别执行 `PROJECT_SELECT_ROOT` 和 `PROJECT_READ_ONLY`；`LocalClientApplication` 交替领取普通本机任务与协议 5 项目任务；`LocalClientServerClient` 使用独立签名端点和回报合同，结果丢失不自动重放。
- Server 任务与项目：`ClientProjectService` 在短事务外读取设备，在短事务内登记任务/项目，只保存认证加密请求与结果；选择成功后同事务创建项目和推进任务，领取/完成/过期使用版本条件更新，项目存在未决任务时拒绝归档。
- 来源与工具：`ClientProjectRunService` 新增协议 5 的会话/Run 来源、设备/根/Agent/所有者一致性和 `ASK_ON_EFFECT` 限制；`ClientProjectToolCatalog` 生成租户稳定的 `cm-agent/client-project/v1` 工具 ID 和 `client_project_list_files`、`client_project_read_file`、`client_project_search_files`；`ClientProjectTools` 将调用转为有界设备任务。
- 适配器保护：`ProjectRuntimeContext` 可同时表达 Server 本机来源或客户端项目来源，但构造时强制二者只能有一个；AgentScope ReAct/Harness 仅按服务端提供的固定工具 ID 识别受保护载荷，不信任模型给出的工具名。
- 页面：`projects.html` 和服务端项目保持原入口，新增隐藏的“已配对电脑”来源；`client-projects.js` 分开请求 capability、设备和项目合同，使用专用行动头、202→轮询→终态、`sessionEpoch` 与加载代次，离页/切项目丢弃迟到结果。
- 持久化：V29 以语义一致的 PostgreSQL/MySQL 方言新增 `client_projects`、`client_project_tasks`、`client_project_conversations`、`client_project_runs`，将协议 5 纳入运行和会话约束，并为全部表和字段提供原生中文注释。

## R2 追加实现

- 只读门禁：专用设备项目端点要求 `X-CM-Client-Project-Protocol: 5`；目录选择在设备行锁内检查并限制一个未决窗口；文本读取在内容前后核对 Windows 文件对象身份、大小、修改时间与 SHA-256；模型读取使用 `ClientProjectReadSnapshots` 在 Run 内固定加密快照。
- 变更计划：新增 `client_project_operations` 和 `ClientProjectOperation`，计划任务由设备核对当前文件后返回原文/脱敏差异、对象身份与 SHA-256；Server 只保存认证加密计划，计划阶段没有项目文件副作用。
- 精确审批：新增 `client_project_propose_changes`、`client_project_apply_changes`、`client_project_propose_undo`、`client_project_change_status`。Apply 为 HIGH，只接受 operationId/version/digest；后端审批决策复核计划状态、版本、摘要和 `displayExact`，掩码掩盖真实差异时返回冲突。
- 条件应用：客户端支持创建/替换/删除/移动文件及创建/删除空目录；执行前重新核对对象身份和原文摘要，使用同目录临时文件和原子移动写入，部分成功转为 UNKNOWN。
- 私有恢复：`ClientProjectOperationJournal` 在 `~/.cm-agent/client-project-operations` 记录 RUNNING/SUCCEEDED/FAILED/UNKNOWN，只保存 operationId、rootId、计划摘要和时间，不保存正文或绝对路径；重复 operationId 不能覆盖旧回执。
- 撤销与许可：`ClientProjectOperation` 只允许 PROPOSED→APPLYING 一次派发；成功操作可生成新的反向计划和新的 HIGH 审批。任务与操作状态联动，任务过期按领取阶段取消或转 UNKNOWN，项目归档被 APPLYING/UNKNOWN 阻断。
- 页面：`projects.html` 增加变更审批区域，`client-projects.js` 使用专用预览接口展示脱敏差异，`canApproveChange` 只有在版本/摘要匹配、预览完整且 `displayExact=true` 时才允许；新会话 requestId 作为幂等键，未知响应后可恢复原会话。
- 快照更新：文件变更成功后，`ClientProjectReadSnapshots` 按已核验的变更结果推进或墓碑化受影响文件，迟到读取不能返回旧正文。
- 组合冲突：`ClientProjectChangeOperations` 在计划阶段拒绝同批重复源/目标、Windows 大小写别名、普通文件父路径及不可安全排序的父子重叠；显式创建目录作为子动作父级仍保留。
- 回执恢复：新增跨 `ClientProjectOperationJournal` 实例测试，证明新进程可读取旧 RUNNING/UNKNOWN 回执，且同一 operationId 不能再次执行。
- 迁移门禁：`MigrationTest` 纳入 V29 六张表及关键外键/索引；MySQL V29 同步更新两列协议 5 注释，使双方言语义一致。

## R3 追加实现

- 固定工具链：`ClientProjectToolchains` 从 `~/.cm-agent/client-toolchains.json` 或 `-Dcm.agent.client.toolchains.path` 读取最多 32 项当前用户显式配置；拒绝相对程序、链接/非普通文件、Shell、超大程序和疑似凭据覆盖。Server/模型只看到逻辑 ID、展示名和程序文件名。
- 冻结清单：`ClientProjectCommandOperations.plan` 固定工具链定义摘要、可执行文件 SHA-256、固定与模型参数、项目相对工作目录、完整显式环境和根目录主要构建配置摘要；敏感参数在计划阶段拒绝。客户端启动前重新核对上述摘要，变化时旧批准失效。
- 精确启动许可：`ClientProjectCommandService` 只接受 `commandId/version/digest`；审批后端再次读取命令状态和摘要，`client_project_run_command` 为 HIGH。短事务同时登记唯一 `RUN_COMMAND` 任务并把 PROPOSED 推进为 QUEUED，设备领取前再核对命令来源和状态。
- 命令运行：`ClientProjectCommandOperations` 使用不含 Shell 的 argv、项目根内工作目录和完全替换的固定环境异步启动；输出上限 512 KiB、合并 stdout/stderr、脱敏后回传。`ClientProjectCommandJournal` 在 `~/.cm-agent/client-project-commands` 保存无 argv、环境、输出和绝对路径的私有回执；客户端重启将孤立 RUNNING/STOP_REQUESTED 回执改为 UNKNOWN。
- 失败分型：本机执行槽占用、进程未创建、清单再校验失败等可证明无副作用的启动拒绝返回 FAILED；进程或监视线程已经创建但随后回报失败、无法取得静止证据时返回 UNKNOWN。服务端按 `possibleSideEffects` 消费本地回报，不再把所有启动失败统一误记为 UNKNOWN。
- 进程树停止：`LocalCommandOperations` 记录本次根进程下已观察后代，停止时逆序终止后代和根进程；只有根进程已退出且已观察后代均不存活时才返回可核验终端状态。根退出但后代仍存活时先清理，成功状态只能用于无残留树的正常退出。客户端关闭会请求停止并最多等待 7 秒让监视线程收口。
- Run 完成屏障：`ClientProjectCommandService.awaitRunSettled` 只把实际进入 QUEUED/RUNNING/STOP_REQUESTED/UNKNOWN 的命令纳入屏障，仅 PROPOSED 未启动命令不阻断 Run；任何已执行命令非 SUCCEEDED 时 Run 不通过。Server 使用独立虚拟线程与超时，不占 HTTP 请求线程或 JDBC 事务。成功后异步回写 Run 终态，失败/拒绝也登记停止。
- 停止与对账：重复停止或 `stopRequested=true` 只创建状态核对任务，不重复派发停止。屏障超时先关闭旧状态任务、登记 STOP_REQUESTED，再把本轮未决命令无条件收口为 UNKNOWN；迟到设备证据仍可在受控状态下推进终态。人工 `resolveUnknown` 只追加元数据审计并返回原 UNKNOWN，不改变版本、不释放隔离、不重放命令。
- 异步控制：`ConversationService` 和 `ToolApprovalService` 在收到 `RUNNING` 命令屏障信号时保留 Run 执行句柄；`RunExecutionService` 的异步最终回写使用同一 Run 短锁并在结束后释放，因此用户在等待期间仍可停止。
- 页面：项目页能力报告 `commands=true`；`client-projects.js` 支持命令精确预览、查看状态/有界输出、请求停止与 UNKNOWN 人工确认，所有命令内容按纯文本渲染，离页或切项目丢弃迟到结果。

## R1/R2 与 R3 初始验证（历史）

- `mvn -q -pl cm-agent-local-client -am clean test`：通过，32/32，失败、错误、跳过均为 0。
- `mvn -q -pl cm-agent-server -am "-Dtest=ClientProjectServiceTest,ClientProjectRequestBoundaryTest,ClientProjectToolCatalogTest,ToolOperationCategoriesTest,ProjectRuntimeContextTest,ClientProjectRootStoreTest,LocalClientServerClientTest,AgentScopeProtectedToolPayloadTest,ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" clean test`：通过。
- `mvn -q -pl cm-agent-agentscope-adapter -am test`：通过，181 项执行，失败和错误均为 0；5 项 live/external 条件项跳过。
- `node --test src/test/js/*.test.cjs`：232/232 通过。
- `mvn -q -DskipTests package`：通过。
- R2 定向 Java：`ClientProjectServiceTest,ClientProjectRunServiceTest,ClientProjectToolCatalogTest,ClientProjectApprovalGuardTest,ClientProjectOperationTest,ToolOperationCategoriesTest,ProjectRuntimeContextTest,ClientProjectRootStoreTest,ClientProjectChangeOperationsTest,LocalClientServerClientTest,AgentScopeProtectedToolPayloadTest,ConsoleResourceTest`：通过。
- R2 追加合同：`ClientProjectOperationJournalTest` 与 `ClientProjectApprovalGuardTest` 的跨实例回执、审批版本/摘要专项通过；`ClientProjectChangeOperationsTest` 的重复目标、大小写别名和父子冲突拒绝通过。
- R2 前端门禁：`node --check` 通过；Node 全量 232/232，失败、错误、跳过均为 0。
- V29 PostgreSQL/MySQL 静态检查：`MIGRATION_STATIC_OK tables=5 columns=67 tableComments=5 preexistingColumnComments=2 bothDialects=V29 comments=complete`；MigrationTest 编译通过，但数据库运行未执行。
- R2 历史文档检查：当时六份均为 R2、`mode=default`，T0–T18 完整，清单与账本状态一致，相对链接有效。
- `git diff --check`：通过；保留工作树已有 CRLF 转换提示，不动用户配置和无关修改。
- R3 客户端模块：`mvn -q -pl cm-agent-local-client -am test` 通过，37/37；新增固定工具链配置变化、敏感参数、子进程树停止合同。
- R3 Server 定向合同：7 个测试类、36/36；`ClientProjectCommandServiceTest` 9/9、`LocalProjectRunCompletionTest` 5/5，覆盖仅提议不阻断成功、已执行失败/超时不通过、启动前确定失败与启动后未知区分、计划和状态原子终态、终态查询幂等、重复停止转状态查询、超时 UNKNOWN 和异步 RUNNING 保留停止句柄。
- R3 适配器：`mvn -q -pl cm-agent-agentscope-adapter -am test` 通过，182 项，0 失败、0 错误、5 项 external/live 条件跳过。
- R3 Node：`node --check` 及全量测试通过，233/233；覆盖命令精确预览、停止不重放和 UNKNOWN 人工对账。
- R3 Core/Console：`mvn -q -pl cm-agent-core -am test` 与 `mvn -q -pl cm-agent-console -am test` 均退出码 0。
- R3 V29 静态双方言检查：`MIGRATION_STATIC_OK tables=6 pgColumnComments=91 myColumnComments=89 tableComments=6/6`；新表集合一致，全部表有注释，89 个新增列均由方言注释覆盖，PostgreSQL 另更新 2 个既有列注释。
- `mvn -q -pl cm-agent-persistence -am "-DskipTests" package` 与 `mvn -q "-DskipTests" package`：均退出码 0。

## R3 续验实际交付

- Task 解码/回报使用一次性 taskId；SELECT_ROOT 按该标识幂等登记，LIST/READ 不再错误拒绝未用的空 query。
- RunPersistenceService 首次保存协议 5；WorkspaceImportAccess 与 ClientProjectRunService 核对持久会话/Run 见证、精确主体及浏览器行动头。历史读取不再依赖会被终态清理的读取快照。
- ClientProjectBinding 在写入 JDBC/快照前统一微秒；ClientProjectReadSnapshotsTest 保留根身份变化拒绝。
- client-projects.js 使用真实 SSE 审批恢复接口并保留错误编号；相同权威审批版本的轮询不重建卡片；ReAct 与桥接记录共用 protectedInputSummary。
- JdbcClientProjectCommandRepository 在读取 exit_code 后立即捕获 wasNull；双库测试补充 RUNNING NULL、CAS 竞争、唯一领取、UNKNOWN 隔离和终态读取。
- 客户端仅追加累计输出的新尾部，重复状态查询不增加输出和序号；ClientProjectCommandService 接受无输出 UNKNOWN 恢复回执，但保留最后已核验输出/序号且不接受 RUNNING 倒退。
- RunExecutionService 的调度清理按持久 Run 原主体定位临时授权槽，租户仍先验证；调度审计主体不再误用为快照 owner。

## 未完成和限制

- 真实原生选择器未通过：请求被领取，Swing 模态等待，但当前自动化桌面未暴露可操作窗口；任务过期 UNKNOWN。使用固定选择器的历史辅助程序不计入本轮真实窗口证据。javaw 启动方式实验未解决并已撤回。
- 真实 Windows 命令子集通过，不能替代文件变更竞争、注销/断电、设备撤销、跨主机 TLS、多实例和旧程序栅栏验收。
- 长运行超过页面轮询预算后，本轮通过重新选择原会话核对终态；完整迟到响应/窄屏/键盘矩阵未验。
- 当前验收项目保持 UNKNOWN 隔离，人工确认不解除；测试父子进程均已不存在。不能删除私有回执或改数据库状态来制造通过。

关联：[清单](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md)｜[设计](../specs/2026-11-09-client-project-workspace-delivery-design.md)｜[计划](../plans/2026-11-09-client-project-workspace-delivery.md)｜[提示词](../prompts/2026-11-09-client-project-workspace-delivery-prompt.md)｜[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)。

## 2026-11-10 续验补充：目录窗口与运行中重启（历史）

- T1/T9 仍进行中。已登录项目页选择设备 `91cbb6cc-c9e2-479f-971b-f749a69af2dc` 并发起真实选择请求；页面显示等待目标电脑打开选择器。客户端 PID 5116 的 JDK 21 线程快照显示 EDT 在 `JFileChooser.showOpenDialog` 模态等待，但当前桌面工具未发现可操作的客户端窗口；未取得选择、取消或登记结果，不能判定验收通过。该观察仅限当时进程及桌面。
- 源码核对：`LocalClientTaskExecutor.selectRoot` 直接调用 `SwingClientDirectoryPicker.choose(deviceName)`；当前选择器仅将设备名用于窗口标题，未实现需求要求的先展示请求来源、关联码及用户确认；Swing `JFileChooser` 本身也不能作为操作系统原生目录窗口的通过证据。应先补齐来源绑定、确认、有效期和窗口关闭/超时处理及回归，再在交互桌面验收；不得伪造选择结果或自动登记。
- T17/C04 的运行中 Server 重启接管本轮未执行：当前客户端停在选择器模态等待，既有验收项目仍有 UNKNOWN 隔离；不能以历史 Run 收口后重启或线程快照替代该场景。需恢复可用客户端并使用独立的受控验收项目，记录重启前后命令、Run、停止/查询与隔离证据。历史已通过项仍以账本 W01–W06 及双库记录为准，其他未完成矩阵不变。
- 随后页面显示“设备项目任务在结果确认前过期”；再次采集的客户端线程快照仍停在 `JFileChooser.showOpenDialog`。因此过期后本机模态窗口仍占用轮询线程，尚未验证自动关闭和轮询恢复；不要自动重试该选择请求。
- 本次仅记录调查，未修复代码、未完成目标验收；不得据此将 T1、T9 或 T17 标为完成。

## 当前续验实际实现

- `ClientProjectService` 从持久任务附加截止时间；`LocalClientController` 验签后检查领取正文能力，保留旧结果回报端点供排空。`LocalClientServerClient` 对受控拒收与网络失败分类，不重发原回执。
- `WindowsInteractiveDirectoryPicker`、`WindowsClientDirectoryPicker` 实现本机确认及 Windows 原生目录选择；看门狗只关闭同进程和当前任务编号的窗口。`TimedClientDirectoryPicker` 对无响应 UI 有界返回，后台仍未退出时拒绝新选择。`LocalClientTaskExecutor` 显示不含用户信息的配对来源并复核截止时间。
- `LocalClientApplication.consumeTasks` 在短时网络故障后保留执行器并延迟轮询，不能判定已领取请求结果；`ClientProjectCommandService` 将带有效终态的无输出回执与既有输出合并，维持版本状态约束。页面显示原任务编号供本机核对，资源版本已更新。
- 当前只完成前述范围及账本实测；模型额度阻断修复后运行中接管复验。第二次选择失败原因未确定；本轮没有实施数据库迁移。
