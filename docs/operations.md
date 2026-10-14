# 运维说明

本文档说明阶段3服务的健康检查、真实 AgentScope Runtime、审计严格语义、Run 查询、Flyway 和敏感日志边界。备份恢复、容量治理和自动归档属于阶段4，当前不由应用自动完成。

## 只读工作区参考资料

只读资料由部署管理员按tenant/Agent配置，默认关闭；配置及私有目录要求见[配置说明](configuration.md)。实际输入、审批冻结、错误和严格清理本地合同及Rocky两库/Linux安全目录合同已通过，见[T5账本](superpowers/progress/2026-10-12-harness-t5-ledger.md)；真实Provider、浏览器和临时双库排空回退已完成T6，范围与限制见[T6账本](superpowers/progress/2026-10-12-harness-t6-ledger.md)。

首次Run读取并固定资料，模型只见JSON文本与相对引用；更新源文件不替换旧Run。正常ASK保留原加密快照与守卫键并刷新审批TTL；恢复只用原版本，映射撤销、缺失/过期/损坏拒绝。`WORKSPACE_CONTEXT_PREPARE`成功仅表示冻结成功，不表示模型已消费数据；拒绝/失败记录`WORKSPACE_CONTEXT_FAILURE`，详情仅含错误码与原Run编号。

Run终态/失败/停止的资料状态必须在成功结果或文件发布前清理，清理/审计/状态读取故障按严格失败收口，不降级成无资料的新运行。只有正常等待审批保留资料；停止在ASK返回后赢得锁也会在取消终态之前清理。不要选择性手工删`workspace_context_v1`或`workspace_context_guard_v1`后恢复原Run；全状态槽由原治理生命周期处理。

排障从接口的`code`/`errorId`或SSE对应字段检索后台同一Run：非法路径/ACL/文本、预算、资料不可用、映射撤销、原快照不可用分别有独立类别。资料正文、请求体和主机路径不进入日志；快照诊断文本也隐藏正文。保持宿主状态密钥和TTL，内存仓储重建不能代替进程重启证明。资料已经送入模型或形成正常回答后不能撤回。

Linux使用安全目录句柄；Windows依赖所有者私有ACL、最终路径和时间/大小复核，不具备同等原子目录保护。服务账号及部署管理员为可信文件系统写入者；父目录、挂载来源和本地卷也须受控。协作读取预算不等于操作系统阻塞I/O的硬终止；Run未退出时不能强写成功/取消或自动重放。

启用/移除映射或回退二进制前执行受控排空，避免混合版本接管原资料状态；当前没有热更新/资料上传API、原生文件工具、Shell、记忆或压缩。T6验收不代替实际部署数据集、生产备份恢复及所有实例的排空核查。

## ReAct 与受控 Harness 的部署切换

默认保持 `cm-agent.agentscope.execution-mode=react`。受控 Harness 使用同版 2.0.2 和同一 CM Agent 状态历史；T2–T6已完成各自范围内验收，仍不自动赋予具体部署的生产准入。正式目标、入口、全部实例、可恢复备份和排空状态必须单独核实。

1. 在变更窗口备份数据库并应用 V21，先部署本轮二进制且保持 `react`；停用所有未实现模式门禁的旧二进制，不能混用旧进程承诺跨实例隔离。
2. 停止接收新的 Run/TEST 和自动任务，使用正常运行/审批/停止流程收口非终态 Run、TEST 与全部检查点。过期或孤立检查点同样阻塞，不通过删除业务记录或跳过检查强行切换。
3. 停止各服务实例，统一将外部配置设为目标模式并启动。启动以系统模式行锁重新检查排空；被拒绝时保留原模式，先解决阻塞状态再重试。相同模式重启不要求排空，可恢复原审批状态。
4. 在验证环境核对实际工具全集、审批和停止，再恢复流量。若数据集模式已改变而旧实例仍接收请求，它在凭据解析前返回受控 `DENIED`；凭 `runId` 检索 `RUNTIME_MODE_CONFLICT`，停止该实例并核对部署配置。
5. 本轮二进制从 Harness 回切 ReAct 使用同样的排空流程，不自动重放已执行工具。回滚到 V21 之前的旧二进制须另行验证 Flyway 兼容性与数据库恢复预案；不能把改回 `react` 当作旧二进制回滚已经完成。

私有工作区每次运行/恢复创建并在生命周期结束清理，目录权限限所有者；模型只能通过受治理桥接访问工具和 Skill，不读取宿主 `AGENTS.md`、`MEMORY.md`、`tools.json` 或共享 skills。崩溃后遗留目录需在停机核实归属后按运维流程处理，应用不全局扫描清理。数据库模式记录不保存凭据、正文、路径或历史，不开放租户写入接口。

仓库的普通 `package` 产物没有可执行主清单；准备 `java -jar` 部署时，先安装同一版本的模块依赖，再在 Server 同一次生命周期执行 `package spring-boot:repackage`：

~~~sh
mvn -B -DskipTests install
mvn -B -pl cm-agent-server -DskipTests -Dmaven.jar.forceCreation=true package spring-boot:repackage
~~~

安装和打包不代表测试通过；新旧包必须分别记录 Git 基线、覆盖源码哈希、可执行 JAR 摘要及嵌入依赖。旧包无 V21 模式门禁时先停止全部新旧实例，再按经过验收的数据库/空检查点条件启动；不删除 V20/V21 或强行恢复旧格式 ASK。备份完成后核对恢复预案，未知工具副作用由运维核查。具体可重现回退版本与已验证数据库见 [T6账本](superpowers/progress/2026-10-12-harness-t6-ledger.md)。

T6 已在临时 PostgreSQL16/MySQL8.4 上实际启动新包 ReAct → Harness → ReAct，再启动 Git 基线 `6752bf8` 的旧包，新 Run 成功且 V21 历史保留。仅支持已经排空、无检查点、停掉所有其他实例的已验证旧版本；备份生成及摘要已核对，没有模拟生产备份恢复、强杀或多实例接管。真实协议覆盖 DashScope 原生及其兼容接口，中文控制台在 Harness 下验证。T6期间Rocky时钟落后约一周，测试页曾临时对齐服务端，随后恢复本机时间复验仍正常；2026-10-12已实际校准主机与RTC，不把测试页时钟对齐方式用于生产。

### 时钟与排空的上线复核

审批、检查点、JWT和文件期限依赖服务端时间。先在目标主机核查 `chronyc activity`、`chronyc sources -v`、`chronyc tracking` 和 `timedatectl`；NTP服务active不等于已同步。核查参考时间新鲜、存在选中源、当前校正量受控，再用 `chronyc waitsync 6 0.1 0 5` 等待误差小于100毫秒。RMS偏差可能保留历史跳变，不能替代当前校正量和新样本判断。

时间源offline时，按既有网络/时间源配置恢复；本次通过 `chronyc refresh`、`chronyc online`、`chronyc burst 4/4` 取得新样本后执行 `chronyc makestep`，并以 `hwclock --systohc --utc` 同步RTC。已经同步时不重复跳时；共享主机上先核查全部应用及变更窗口。跳时后可能立即使旧审批、检查点和令牌过期，不通过延长TTL恢复旧执行。确认chronyd active/enabled，并在虚拟机恢复或网络变化后重新检查；本次没有重启虚拟机验证长期同步。

在确认的权威数据集、经授权的只读事务中执行系统级聚合，先确认V21与相关表存在，再核对：

```sql
SELECT COUNT(*) AS nonterminal_runs FROM runs
WHERE status NOT IN ('SUCCEEDED','FAILED','DENIED','CANCELLED');
SELECT COUNT(*) AS nonterminal_tests FROM skill_trials
WHERE status NOT IN ('PASSED','NOT_TRIGGERED','FAILED');
SELECT COUNT(*) AS all_checkpoints FROM runtime_checkpoints;
SELECT COUNT(*) AS pending_approvals FROM tool_approval_requests WHERE status='PENDING';
SELECT execution_mode FROM runtime_execution_modes WHERE id=1;
```

聚合用于部署门禁，不能暴露业务正文或充当租户查询接口。缺表不是零；全部检查点包含过期和孤立状态。一次只读快照也不是排空完成：关闭所有新增Run/TEST/自动任务入口，待治理生命周期收口且所有应用正常退出，启动时仍由V21行锁复核，不手工改模式表。

2026-10-12首轮Rocky复核时，候选项目MySQL为V20、170条Run中8条RUNNING，候选PostgreSQL仅V1；历史见[评估账本](superpowers/progress/2026-10-12-harness-controlled-enablement-ledger.md)。随后用户明确正式数据集为Rocky MySQL `cm_agent`，私有逻辑备份实际隔离还原、全表数据匹配；副本迁移/门禁拒绝通过后，原库已正常补齐V21并保持react，全部业务数据迁移前后相同。迁移刚完成时170条Run仍有8条RUNNING，TEST22条全终态、检查点0、待审批0；无已存工具/完成记录不足以证明旧调用没有副作用。

用户随后确认8条旧Run为已中断测试。先在实际还原副本验证事务回滚、重复执行与排空模式往返，再由现有Run/Audit仓储、固定ID/归属/原状态与共享短事务逐项收口为FAILED，新增8条终态审计及8条修复审计，原输入、消息、旧审计和其他Run保留。G4结束时非终态Run/TEST、全部检查点/待审批均0，原库仍react；此项操作不是通用的自动修复API，不应据此扫描任意历史Run强制终结。

**Rocky生产历史阻塞**：修复前实际连接来自本机IDE的mysql profile实例且bootstrap admin=true。通过Spring Admin正常关闭项目上下文，HTTP/数据库连接释放，当时IDE JVM仍存活，未强杀。用户随后明确Rocky生产，已准备[生产装配文件](../scripts/rocky-harness/README.md)，远端/home/cm-agent-production暂存同包、production/react配置和5个旧密文副本，secrets尚空。前置检查实际拒绝七个缺失Secret，未启动生产应用。生产身份/最小权限DB账号、全部实例退出和旧主密钥/密文真实恢复仍须满足，数据库还原和文件副本不等于全系统恢复。2026-10-13用户改为本机运行，该生产路径移出本次范围，详细历史保留在[接续账本](superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md)。

### 本机 Windows 的 Harness 开发运行

2026-10-13实际启动目标为Windows本机，沿用Rocky MySQL `cm_agent`、既有管理员及原密钥。使用JDK21、`mysql`开发profile、真实AgentScope、`harness`，关闭fake与当前不可达的可选Studio，仅监听127.0.0.1:8080。健康UP、登录页200、真实登录及现有DeepSeek无工具Run已通过；3个临时验收Agent均停用，失败记录保留。原库V21/harness，173Run与22TEST全终态，全部检查点/待审批0。另一个GLM配置返回上游HTTP429/code1113/余额不足；单模型成功不代表全部Provider可用。

当前实例正在运行时先检查健康和进程，不重复启动。重启需正常关闭旧服务，另开终端持有SSH转发；本轮已验证的物理网卡源地址为192.168.0.159，地址变化后需重新核对：

```powershell
ssh -b 192.168.0.159 -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L127.0.0.1:13306:127.0.0.1:3306 rocky
```

在第二个PowerShell终端、仓库根目录检查并前台启动：

```powershell
./scripts/start-local-harness.ps1 -CheckOnly
./scripts/start-local-harness.ps1
```

[启动脚本](../scripts/start-local-harness.ps1)固定本轮已核验候选包摘要，显式使用`F:/java21/bin/java.exe`；包位于忽略的`cm-agent-server/target/harness-local-runtime/cm-agent-server.jar`。源码变化须重新构建并核验包，不继续拿旧包证明新源码。脚本从当前进程或已有`.env`仅内存读取模型主密钥，其他旧密钥/目录由现有mysql外部配置加载，不生成或复制密钥；日志放在当前用户专属目录。`-CheckOnly`证明格式和连接前置条件，不证明全部密文恢复或模型额度。

访问[本机控制台](http://127.0.0.1:8080/console/v2/login.html)，使用原有管理员账号。正常停止时先对服务终端Ctrl+C，待Java退出/数据库连接释放后再关闭SSH终端。仅供本机开发，bootstrap管理员及原开发JWT配置不用于生产开放入口。本轮未重新验收技能容器、审批、停止、产物恢复或生产身份。

同库模式已经变为harness；默认源配置仍react。重新用IDE/react启动前必须按上述统一排空切换流程处理，不能直接SQL改模式或混合旧实例。Rocky生产七Secret及真实恢复条件仍未完成，暂存资产保留。

## 健康检查

服务端暴露 Spring Boot Actuator 健康端点：

```http
GET /actuator/health
```

返回 `UP` 只表示应用进程和基础 Spring 容器就绪。生产环境还应独立检查数据库连接、Flyway 迁移状态、审计写入和下游依赖，再决定是否接收流量。

## 生产认证与 bootstrap login

生产和类生产 profile 使用外部身份系统或受控认证服务签发的 Bearer JWT。服务从受控外部配置读取 JWT 验证密钥并校验令牌；密钥和令牌不得进入 Git、镜像、命令历史、日志、审计消息或 API 响应。

`/api/auth/login` 仅用于本地 `bootstrap admin` 登录。只有 `local` profile 可以显式启用它，生产的 `production`、`prod`、`supabase` profile 必须保持 `bootstrap-admin-enabled=false`，因此生产调用该入口应被拒绝。生产排障应使用受控身份系统签发的临时权限和 Bearer JWT，不应临时打开 bootstrap admin。

生产 profile 还必须设置 `fake-runtime-enabled=false`。本地和测试 profile 可按需启用 fake runtime，但不得把该开关带入生产配置或通过外部覆盖重新打开。

生产还必须设置 `agentscope-enabled=true`，并提供 AgentScope Java 2.0.2 的 OpenAI Compatible 或 DashScope 模型配置。默认凭据按 `tenantId + modelConfigId` 从数据库密文解析，也可由自定义 `ModelCredentialProvider` 对接 secret manager；必须由受控环境提供 Base64 编码的 256 位 AES 加密主密钥。`model_configs` 不保存明文 API Key，接口也不回显。

## 审计严格失败语义

审计是关键动作的严格记录：登录、权限拒绝、Agent 变更、模型配置创建/更新/删除、工具创建/授权和 Run 生命周期都会尝试写入审计。审计 repository 写入失败时：

- 应用保留异常，不吞掉或伪装成成功。
- HTTP API 返回 `503 Service Unavailable`，调用方应按不可用处理并重试或告警。
- 日志只允许包含脱敏后的事件类型、资源标识和错误摘要，不输出 secret、密码、模型 API Key、完整 JDBC URL 或原始输入输出。
- 运行失败收口和失败审计也需要纳入告警，避免只监控普通接口 5xx。

## Run 与 ToolCall 查询

Run API 提供列表和详情：

```http
GET /api/agents/<agent-id>/runs?limit=20
GET /api/agents/<agent-id>/runs?limit=20&cursor=<opaque-cursor>
GET /api/agents/<agent-id>/runs/<run-id>
```

`limit` 范围为 1 到 100；cursor 由服务端生成，按 `started_at` 与 `id` 的稳定顺序继续查询，不应由客户端自行拼接。详情返回 Run 及其同 tenant 的 ToolCall。审计列表也使用同样受限的 cursor 语义。

所有 Run、ToolCall、Audit 查询都使用认证主体的 tenant 条件。运维排查跨租户数据时必须通过受控授权和审计流程，不能直接移除 tenant 条件查询。

## Skill 运行与排障

Skill 管理、绑定、读取允许与拒绝都会写入严格审计。排查一次运行时，先查询 Run 详情，再使用 `GET /api/agents/<agent-id>/runs/<run-id>/skill-loads?page=0&size=100` 查看固定版本、路径、状态、字节数、耗时和 `errorId`；该接口不返回技能正文。使用 `errorId` 检索同一租户的服务日志与审计事件，不能通过增加调试日志、导出 ZIP 或记录模型输入来获取正文。

`SKILL_ACCESS_REVOKED` 与 `SKILL_SNAPSHOT_UNAVAILABLE` 表示运行快照不能安全恢复，应新建 Run，不得重新启用后继续旧审批。`SKILL_LOAD_LIMIT_EXCEEDED` 表示本次 Run 的读取次数或字节预算耗尽，应缩小技能资源或调整经过评审的服务端上限；不要在客户端重复请求。关闭 `cm-agent.skills.enabled` 用于受控停止新增和绑定，历史记录仍保留以支持审计和故障定位。

## 运行数据与生命周期

JDBC 模式下，运行启动阶段写入 `RUNNING` Run 和启动审计；运行完成阶段在同一完成事务边界内更新 Run、写入 ToolCall 和完成审计。运行异常会尝试写入 `FAILED` 收口；审计失败仍按严格错误语义返回 `503`。

当前没有应用自动删除、归档或 TTL 清理 Run、ToolCall、Audit。系统定时扫描会话和独立 TEST 的过期 PENDING，使用服务端固定身份 `system:approval-expiry` 写审计，不冒充发起人。获胜条件更新在同一 JDBC 短事务中将审批标为 EXPIRED、仍等待的 Run 收口为 DENIED、删除该 Run 状态槽并写严格审计；独立 TEST 试运行同步为 FAILED。审批历史和 Run 记录保留，不调用 Runtime 或外部工具。PENDING 查询仍排除已过期行，因此不能单凭待办列表为空确认收口，应结合审批历史和 Run 详情核验。备份恢复、保留期和容量治理仍由生产团队另行建立。

扫描默认开启，结束后间隔 30 秒、每批最多 50 项，配置见 `cm-agent.approval-expiry`。关闭扫描不阻止人工过期处理；JDBC 重启后从数据库继续发现过期请求，多实例以条件更新竞争，失败方不得收口 Run、清理状态槽或写成功审计。JDBC 单项审计/持久化失败全部回滚，记录 `APPROVAL_EXPIRY_FAILED` 及随机 `errorId`、可信 tenant、runId 和 approvalId；游标先处理后续候选，一次完整遍历固定截止时间，空页后重置再尝试失败项，避免持续新增到期请求造成重试饥饿。排障应使用这些标识定位，不获取正文、检查点密文或凭据；数据库或审计恢复后核对终态和审计是否完整。memory 技能状态写入仍遵循工作单元，但审批、Run、审计之间不具备数据库级回滚，禁止用于生产故障恢复。

审批决定最多被原子接受一次，但模型恢复和外部副作用不在该事务内；进程在决定后崩溃不会自动接管，重复点击也不能补偿恢复。检查点写入、Run 等待转换和审批创建同样不是跨阶段原子操作，失败时应关联 Run、审批 ID、审计和 `errorId` 排查。当前没有面向运维的强制恢复/取消接口，不应直接重置审批为 PENDING 后盲目重放工具。主密钥轮换前先停止新审批并收口待审批 Run，因为检查点尚无独立密钥版本。

当前同实例会话发送锁覆盖 `RUNNING`、`WAITING_APPROVAL` 和 `CANCELLING`，批准后原 Run 恢复为 `RUNNING`，仍禁止同会话重叠发送。此锁不是跨实例执行租约；主动过期、停止、审批决定和运行收口通过同一短锁与条件更新竞争。崩溃后未知副作用仍需要人工核查，不得自动重放。历史 A 批次验证结果见[验收账本](superpowers/progress/2026-09-30-approval-expiry-a-ledger.md)，不能以过期测试替代本次停止或真实模型验收。

会话聊天现支持独立只读审批历史：按决定时间和审批标识分页，按 Run 关联消息，找不到对应消息时在历史区兜底展示。历史接口要求 `agent:read`，由认证 tenant 与会话归属限制数据。审批通过并不代表工具或 Run 成功，排障时同时查看运行记录。历史加载失败的 `errorId` 可用于检索 `APPROVAL_HISTORY` 日志，不能通过重复审批补偿查询失败。

2026-09-03 已在 Rocky 的 PostgreSQL 16/MySQL 8.4 完成审批历史专项测试，覆盖仓储重建后的数据库回读、同一时刻分页、同 Run 多轮及多项决定、隔离和新决定插入。该项不是服务进程重启、审批事务故障恢复或浏览器端到端验收。查询不会主动把已过期 PENDING 改成 EXPIRED，因而终态历史区也不能替代过期状态治理。memory 不提供跨进程历史；JDBC 历史与既有审批表采用同一备份和保留策略。

## 真实运行故障与工具副作用

- 每次工具调用都会重新读取租户工具定义并执行授权；运行中撤销授权或禁用工具后，后续调用必须被拒绝并审计。endpoint 字段只作为治理元数据，Adapter 不会据此自动访问网络。
- 模型 timeout、Provider HTTP/传输故障会使运行失败；工具拒绝按拒绝状态收口；审计持久化失败保持严格语义，不能被普通 Provider 错误覆盖。
- AgentScope 2.0.2 的工具 API 只提供通用取消信号，无法可靠区分所有取消来源。系统只把明确生成且与当前配置匹配的结果识别为 timeout；会话用户停止由独立信号判定，不把超时或任意中断误标为 `CANCELLED`。
- timeout 或线程中断不等于外部副作用已停止。工具与下游系统必须以 `runId`、`toolCallId` 或业务键实现幂等；重试前先查询下游结果，避免重复扣款、通知或写入。
- 当前支持持久化多轮会话、流式 REST 及在线 HIGH 工具审批。在线审批依赖加密检查点；检查点损坏、密钥不一致或审批过期时必须按拒绝处理并通过 Run/审批详情排障。当前不支持无人值守暂停、独立审批工作台或长期授权规则。

## 会话停止本轮

接口前缀为 `/api/agents/{agentId}/conversations/{conversationId}`，tenant 与主体仅来自认证上下文，服务端还校验会话所属 Agent、Run 所属会话及对应 USER 消息。查询使用 `agent:read`；停止使用 `agent:run` 且必须是原发起主体，不提供管理员代停或客户端覆盖身份。

| 方法与后缀 | 行为 |
|---|---|
| `POST /runs/{runId}/cancel` | 活跃执行停止登记成功返回 202、`CANCELLING`；等待审批可直接收口，已终态重复请求返回 200、真实状态 |
| `GET /runs/{runId}` | 200 返回原 Run 的权威状态，不执行恢复或工具 |
| `GET /active-run` | 200 返回本会话活跃 Run；无活跃运行返回 204；不依赖最近消息页覆盖范围 |

停止登记、完成和审批处理使用短事务，不在模型或工具调用期间持有数据库事务。等待审批停止将仍 PENDING 的请求标为 `CANCELLED`、递增版本、删除原 Run 检查点并写严格审计；旧批准不能再启动工具。已经接受的决定保留只读历史，不能改写为取消决定。审计失败不发布停止信号，JDBC 相关状态全部回滚。

`CANCELLING` 没有 `finishedAt`，页面仍锁定发送。只有模型订阅结束、已经进入的网关调用实际退出且最终事务成功后，才写 `CANCELLED` 与结束时间。停止不会撤销已经发生的扣款、通知、HTTP 写入或沙箱副作用；调用忽略中断时停止可能持续等待，应查询下游业务结果，不能强行写终态或开始新轮。严格审计、持久化或清理异常仍按失败处理，不能伪装成正常停止。

无活跃句柄、旧 Runtime 不支持停止或服务重启后无法证明执行已退出时返回 409、`RUN_CONTROL_UNAVAILABLE` 和可检索 `errorId`，保留活跃状态与发送锁。并发状态冲突返回 409、`RUN_CONTROL_CONFLICT`；原主体/权限拒绝为 403，跨租户或错资源关联为 404。停止请求网络失败时只手动查询状态，不自动重试 POST，不重新发送原消息。此版本不提供跨实例执行接管、自动补偿或运维强制停止接口。

验收记录见[本次交付账本](superpowers/progress/2026-10-10-chat-stop-delivery-ledger.md)。浏览器 fixture 证明控制台与真实服务端链路，不证明外部副作用撤销；真实 Provider 停止结果另行记录。

## 动态 HTTP 工具与 MCP 运维

动态 HTTP 工具是受治理的出站能力，不是通用代理。生产启用前应将 `cm-agent.http-tools.enabled` 设为 `true`，配置精确的 `cm-agent.http-tools.allowed-hosts`，并保持 `allow-http=false`。空白名单、私有地址、回环地址、不安全协议、跨源重定向和超过总超时的请求会被拒绝。响应大小受 `max-response-bytes` 限制；运维人员不应通过提高上限来传输文件或大对象。

请求头只保存 `secret/...` 引用。`SecretProvider` 必须由部署平台接入 secret manager，且需具有可取消的短 I/O 超时；不得把解析出的值写入日志、审计、异常、控制台或抓包导出。HTTP 客户端的域名解析与连接之间仍可能出现 DNS TOCTOU，因此生产网络必须额外限制应用出站目的地址，例如通过 egress 防火墙、受控 DNS 或受控代理。

为需要外部副作用的 HTTP 工具配置业务幂等键。网络超时、线程中断或调用端重试不表示下游操作已经停止或回滚；应在下游查询业务结果后再重试，避免重复扣款、通知或写入。调试同样可能触发真实下游调用，只授予受信人员 `tool:debug`，并对 HIGH 风险工具执行工具名称完全匹配的二次确认。

## 模型目录发现运维

模型目录发现是携带模型 API Key 的受控出站读取能力，不是通用代理。控制台仅在使用者点击“获取模型列表”时调用同源 CM Agent API；服务端分别处理草稿临时凭据和已保存配置凭据。已保存凭据绝不能因浏览器修改 Provider 或基础地址而发送到新主机。

生产环境保持 `cm-agent.model-catalog-discovery.allow-http=false`，并将 `allowed-hosts` 配为精确的模型供应商主机名。默认白名单只覆盖 OpenAI 和 DashScope 官方主机；接入私有网关前必须由部署人员审核并显式加入目标主机。请求禁止重定向，受 `timeout`、`max-response-bytes` 与 `max-models` 限制；第一版不自动分页和缓存。

服务端会拒绝回环、链路本地、私网和其他受限解析地址，但域名解析与实际连接之间仍可能有 DNS TOCTOU 风险。生产网络必须通过 egress 防火墙、受控 DNS 或受控代理限制实际出站目的地。监控 `MODEL_CATALOG_DISCOVERY` 审计失败和 `MODEL_DISCOVERY_*` 错误码；排障仅使用响应中的 `errorId`，不得记录或索取 API Key、Authorization、完整供应商响应或完整 URL。

MCP 默认关闭。启用 `cm-agent.mcp.enabled=true` 时，同时设置允许的 Origin/Host 白名单，并在反向代理层只公开配置的端点。`POST /mcp` 需要有效 Bearer JWT 和 `tool:mcp:invoke`；`GET /mcp` 固定为 `405`，关闭时为 `404`。每次 MCP 请求都会重新加载当前租户发布目录，取消发布、禁用或 HTTP/LOCAL 配置漂移无需等待缓存失效即可生效。MCP 调用、HTTP 工具和调试的错误与审计文字只应保留受控摘要，排障时不得要求输出 Authorization、Cookie、API Key、完整 URL 或堆栈。

## Flyway 运维

- 迁移文件位于 `cm-agent-persistence/src/main/resources/db/migration`。
- 不修改已经发布的 `V1__init_schema.sql`；阶段2通过新增 `V2__add_runtime_query_indexes.sql` 和 `V3__add_tool_calls_created_at_index.sql` 支持 Run、ToolCall、Audit 查询。
- 发布前备份数据库，记录 `flyway_schema_history` 当前版本，并在变更窗口观察迁移日志。
- 迁移失败时停止应用切流，不执行全局清理；按数据库备份和发布回滚预案处理。
- 生产可由发布账号执行 DDL，再使用最小权限运行账号提供服务。

## 日志与告警

数据库失败先按页面的 `errorId` 检索服务日志，再查看 `sqlState`、`vendorCode`、
`databaseExceptionType`、`databaseReason` 和脱敏后的 `Caused by`。例如 `22001` 对应字段长度超限；
MySQL 的 `23000` 结合厂商编号区分约束错误。数据库原因使用固定分类，不输出 SQL、参数、
重复键值或失败行；未知编号仍保留编号并显示固定脱敏说明，不应通过关闭脱敏获取原文。
异常副本逐层保留原类型名称和堆栈位置，JDBC 后续异常以 `JDBC nextException` 标记；
循环、超过 16 层或 32 节点的异常链会明确截断，每节点最多保留 128 帧。
这些字段仅用于后台定位，HTTP 状态、错误码与前端消息沿用既有规则，没有新增配置或数据库迁移。

至少监控以下信号：

- `/actuator/health` 失败、应用启动失败和数据库连接池耗尽。
- Flyway 迁移失败、JDBC 写入失败和 Run 完成失败。
- 审计写入失败及其返回的 `503` 数量。
- JWT 缺失、认证失败激增、权限拒绝激增和运行失败激增。
- 模型 Provider 不可用、模型/工具 timeout、模型凭据解析失败和工具授权拒绝激增。

日志、审计响应和运维导出不得包含 JWT secret、数据库密码、模型 API Key、完整 JDBC URL、原始 prompt、工具输入输出或堆栈中的敏感配置。敏感字段只保留脱敏摘要和可关联的资源 ID。

`memory` 仅限开发和测试；其运行、工具调用和审计数据会在进程重启后丢失，不得作为生产故障恢复方案。metrics、集中式追踪和自动化容量告警尚未在阶段3交付。

## Harness 审批状态与停止合同

Harness 每次执行及恢复使用新的私有空目录；只有 `WAITING_APPROVAL` 的加密检查点由宿主 JDBC 持有，目录关闭不删除审批状态。恢复仍使用原 `runId` 和可信 `tenantId:principalId`，重新校验工具 ID、入参哈希、当前授权与固定技能快照，长期消息历史仍由会话服务编排。对象重建时必须使用同一数据集、相同执行模式和受控状态密钥；内存仓储不能证明进程重启恢复。

审批检查点不存在、已过期、密文损坏或数据库读取失败均拒绝恢复，不以空状态启动新执行。首次审批创建失败、决定通知失败及恢复前置失败会关闭活动 Run 并删除同一发起人的状态槽；状态清理或严格审计故障继续传播，原失败作为附加原因保留。JDBC 补偿采用短事务；数据库不可用时不能承诺已清理，按错误编号排查后核对权威状态，禁止自动重发可能产生副作用的调用。

已接受审批保留只读历史，重复决定不能重放工具。停止恢复中的 Run 时，`CANCELLING` 等待实际在途工具退出；订阅取消不能证明外部操作已停止，已发生副作用不回滚。严格工具审计失败优先收口为失败，不能误报 `CANCELLED`。等待审批、过期与批准的竞争沿用条件更新和共享短锁；不同实例不可达句柄仍返回 `RUN_CONTROL_UNAVAILABLE`，T3 不提供自动接管或提交决定后崩溃重放。

部署者需保持主机时钟同步，审批 TTL 与检查点 TTL 均依赖服务端时间。T3 的回环协议桩验证不能替代真实模型、浏览器和生产多实例验收；独立 Run/TEST 的非交互 HIGH 调用继续直接拒绝。

## 文件产物持久卷与补偿

Harness 沿用 CM Agent 固定 Skill 快照、受治理读取/脚本桥接与 ManagedSkillSandbox；不使用原生 `run_skill_code`、Shell 或 Harness 文件系统交付产物。新发布版本不会替换等待审批 Run 的版本，停用、解绑、访问纪元和必需工具授权撤销仍立即阻止继续。`SANDBOX_PREPARED` 是资源预算预留，不是脚本成功或 TEST 发布依据。

脚本文件在执行、关闭、二次授权与严格审计完成后仍为 `PENDING`，只有 Run 成功与发布审计完成才转为 `READY`。等待审批、停止、撤销或失败的文件不可下载，并由现有补偿删除。下载仍复核当前租户、原发起主体、TEST 归属、期限、独占租约和密文完整性；文件服务重建须使用同一持久卷与独立密钥。

用户停止实际脚本时，生产治理服务仅在同一可信 Run 已为 `CANCELLING`、执行线程确实中断且关闭没有附加失败的情况下，记录严格 `SKILL_EXECUTE/CANCELLED` 并返回停止信号；执行器等待在途调用退出后才交付 `CANCELLED`。普通超时、审计/状态读取失败和任何附加清理失败仍保持失败，不能按停止请求掩盖。当前实验结果与限制见 [T4 账本](superpowers/progress/2026-10-11-harness-t4-ledger.md)。

启用前创建私有持久卷并通过独立 Secret 注入文件密钥；多实例必须共享同一卷与密钥，配额和租约由 JDBC 条件更新协调。内存模式重启丢失元数据，仅用于 local/test，不能证明生产恢复。新迁移 V19 同时覆盖 PostgreSQL 16/MySQL 8.4；每表每字段有中文原生注释，旧迁移不修改。

同时备份 JDBC 元数据、密文卷与独立密钥，保持备份时点一致；丢失密钥不能恢复文件，首版没有在线密钥轮换。磁盘容量需在租户明文配额外预留每文件 32 字节包头/tag、暂存副本、元数据及备份空间。租户删除确认前继续占用预算；Run 累计预算不退还。不要通过删除数据库记录释放配额，也不要把卷暴露为静态目录。

状态为 STAGING → PENDING → READY。预留后才写暂存密文，完成认证和刷盘后原子移动；因此正常链路不会产生无元数据的新文件。Run 成功持久化和严格审计后才发布，失败标记 DELETING。周期扫描补偿 DELETING、过期 READY 和超过 24 小时的暂存；有效审批的 PENDING 保留且不可下载。删除和下载独占租约默认五分钟，旧令牌不能释放新租约；删除失败保持容量并在下次扫描重试。发生备份恢复、手工移卷等外部不一致时需停机对账，禁止全局删除或擅自发布 PENDING。

下载前完整解密、认证、摘要和大小校验并写严格开始审计，再发送附件。进程只持有每文件最多 4 MiB、每实例最多四个下载的明文；前端最多两个并发下载。响应头提交后的网络中断无法改写 HTTP 状态，按 `X-Error-Id` 定位相同编号的中断日志与审计，浏览器核对实际字节。完成审计故障也记录同编号，不能将开始审计当作下载完成证据。

`SKILL_ARTIFACT_INVALID` 对应 400，文件预算不足为 `SKILL_ARTIFACT_LIMIT_EXCEEDED`/413，资源不可见为 404，已授权过期为 410，存储、认证损坏或租约不可用为 503。日志只含可信归属、错误编号与安全堆栈位置，不输出文件、密钥、脚本或内部路径。
