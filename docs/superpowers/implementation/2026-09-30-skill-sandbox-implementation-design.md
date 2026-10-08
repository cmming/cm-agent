# 技能沙箱实际实现说明

当前修订：R1；修订日期：2026-10-01；工作模式：mode=default。工作包日期与主题沿用2026-09-30 / skill-sandbox。

本轮依据用户明确指令实际实施T6～T11。当前分支codex/skill-sandbox-r1，基线HEAD为bc57060fac00c671cd247525b6082516194afd20；R0已交付提交938d6fd35ee2a4f36f990c9617746e48da503787保持。未提交、未推送、未合并、未部署。下方R0历史正文原样保留，其旧授权与测试结果只属于R0。

已核对AGENTS.md、相关POM/README/配置、六份工作包、Git状态及当前源码。CodeGraph先查但未匹配沙箱符号，按当前源码补核。未改动用户application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/或运行中服务。远程验证使用同一HEAD加本任务明确文件覆盖，不复制用户脏配置或真实凭据。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)。生产说明见[沙箱端点部署与管理](../../skill-sandbox-endpoints.md)。

## R1 实际交付与任务

| 编号 | 状态 | 实际交付 | 依赖 | 验收证据 |
|---|---|---|---|---|
| T5 | 完成 | R1证据复核、D1～D6交互确认与原六份文档维护 | R0 | 保留修订来源和R0历史，最新实施授权与禁止操作明确 |
| T6 | 完成 | Core后端SPI、唯一注册标识、统一限额、固定单次句柄 | T5 | 未知/重复后端拒绝；全局并发、输入输出、扩展超时及幂等关闭测试 |
| T7 | 完成 | LOCAL、SSH隧道、内存双向TLS中继及固定daemon清理 | T6 | 两个独立daemon真实执行、身份拒绝、轮换、超时、中断、断连、输出超量与零残留 |
| T8 | 完成 | 租户管理/探测API、独立权限、严格审计、运行默认选择和错误诊断 | T6、T7、T10 | MockMvc认证/权限/租户/CAS/凭据不回显/日志编号，治理与真实路由回归 |
| T9 | 完成 | 本轮Java、Node、双库、真实SSH/TLS、浏览器及交付核验 | T7、T8、T10、T11 | 完整回归与最终受影响复验：921项Java通过，1项条件跳过；Node89通过，浏览器另行实际验收 |
| T10 | 完成 | 租户领域/Repository、AES/GCM、memory/JDBC与V16双库迁移 | T6 | 两库CAS、跨租户、审计事务回滚、默认唯一并发及逐表字段原生中文注释 |
| T11 | 完成 | v2技能页沙箱端点列表/详情/编辑/探测/默认/删除与凭据轮换输入 | T8、T10 | 桌面1440×1000、移动390×844实测；操作流程、失败保留元数据/清空材料、同编号提示 |

| 任务 | 关键实际文件 |
|---|---|
| T6 | core/runtime/SkillSandboxBackend.java；server/runtime/ManagedSkillSandbox.java；GovernedSkillAccessService.java |
| T7 | server/runtime/DockerDaemonConnection.java、DockerSkillSandbox.java、DockerSkillSandboxConnection.java、SandboxTargetPolicy.java；config/DockerConnectionProperties.java、SkillSandboxProperties.java |
| T8 | server/web/SandboxEndpointController.java、ApiExceptionHandler.java、AuthController.java；service/SandboxEndpointService.java；config/SandboxEndpointConfiguration.java |
| T10 | core/domain/SandboxEndpoint.java、SandboxConnectionMode.java；core/repository/SandboxEndpointRepository.java；persistence/JdbcSandboxEndpointRepository.java；mysql/postgresql/V16__add_skill_sandbox_endpoints.sql；server/runtime/SandboxCredentialCipher.java、SandboxCredentials.java；store/InMemorySandboxEndpointRepository.java |
| T11 | console/v2/skills.html、assets/sandbox-endpoints.js/css；共享assets/app.js、console-core.js |
| T9 | ManagedSkillSandboxTest、SandboxSecurityTest、SandboxEndpointControllerTest、RemoteDockerSandboxIntegrationTest、JdbcSandboxEndpointRepositoryTest、MigrationTest、SandboxBrowserFixtureTest及sandbox-endpoints.test.cjs；R0治理/容器测试兼容更新 |

## R1 调用链与数据变化

可信Run与固定技能快照 → GovernedSkillAccessService准备/预算/审计 → ManagedSkillSandbox按JWT主体tenant读取默认端点或部署连接 → 策略校验并固定IP/端点版本/凭据版本 → 已注册后端.open → 一次执行 → 使用同一daemon清理/关闭 → 事务外原端点策略复核 → 短事务内Run/技能撤销复核及严格终态审计 → 脱敏交付。

默认选择不缓存；管理修改通过Repository当前值影响后续调用。执行中的连接不再次读取默认或解密新凭据；交付前仅复核启用/撤销和原目标允许策略。停用或删除原端点拒绝交付；轮换失效造成清理不能确认时失败并保留实例配额。远程失败不会降级本地或重放脚本。

新增SandboxEndpoint、SandboxConnectionMode、SandboxEndpointRepository及memory/JDBC实现。V16的端点表和默认选择表均维护创建/更新时间及原生中文注释。迁移分别位于db/migration/mysql与postgresql：skill_sandbox_endpoints保存租户配置、认证密文与修订/探测状态；skill_sandbox_defaults以tenant为主键，组合外键阻止跨租户引用。新增两张表及每个字段都有原生中文注释，未修改既有迁移。MySQL MEDIUMTEXT容纳128KiB材料加密后的编码大小，PostgreSQL使用TEXT。

写操作在tenant行锁/短事务中CAS更新并严格审计；网络探测在事务外，按原revision回写，不覆盖并发的新版本。默认唯一由tenant主键与锁保证；默认端点不可直接删除，取消/切换后逻辑删除。memory仅供local/test，并对审计失败恢复事务前快照。

SandboxCredentialCipher使用独立部署Base64 32字节主密钥、AES/GCM随机IV，AAD绑定tenant/endpointId/credentialVersion。材料只加密落库，领域toString与API投影不含材料/密文；缓冲及时擦除。响应只返回hasCredential与版本等状态。省略凭据表示保留；新材料递增credentialVersion并使探测失效；更改协议须提供相应材料，转换LOCAL清除材料也要求credential:write。主密钥缺失/错误或解密失败明确拒绝，不生成生产默认密钥。当前没有跨主密钥自动重加密接口，更换主密钥需先完成旧密钥迁移。

## R1 实际传输与资源所有权

全部连接（含R0本地兼容模式）的Docker CLI使用空私有--config，排除用户context、代理、认证配置及DOCKER_HOST；SSH不读取用户SSH配置/agent，使用BatchMode、IdentitiesOnly、StrictHostKeyChecking及固定IP，HostKeyAlias仍为不附端口的原主机，GlobalKnownHostsFile指向空私有文件以排除系统信任，known_hosts须精确匹配该别名；仅转发到远端/var/run/docker.sock，不执行任意命令。材料写入前建立当前用户独占目录，关闭删除。

TLS仅使用明确CA与客户端PKCS#8 RSA/EC私钥/证书链；HTTPS身份检查原host，TCP实际连接固定IP。短期回环中继在内存持有认证，每句柄最多8连接并在关闭时释放socket和虚拟线程。错误CA/服务端身份/客户端证书拒绝，禁止明文TCP和跳过校验。

allowed-targets精确匹配host:port，拒绝任意URL/参数注入、未指定、链路本地/元数据和组播地址；专用私网或回环也须部署者明确登记。与业务HTTP工具策略独立。租户默认优先，其次部署connection；mode空只保留R0本地unix/npipe环境兼容，旧远程DOCKER_HOST明确提示迁移SSH/TLS。

句柄一次执行并幂等关闭；关闭中断活动执行并等原连接清理。调用中断先取消后台任务，等待清理后恢复中断标记，避免过早关闭SSH/TLS造成残留。失败、超量、超时、取消和关闭都清理原daemon的随机容器；不能确认停止/删除时不释放配额。Docker23独立vfs daemon实测tmpfs挂载点权限恢复为0755，现显式uid/gid=65534维持固定非root可写，保留所有挂载限制。

## R1 管理与失败行为

端点API前缀/api/skill-sandbox-endpoints，tenant只来自JWT会话。GET列表/详情要求sandbox:read；POST创建、PUT/{id}更新及PUT/default要求sandbox:write；材料提交/清除另要求sandbox:credential:write；POST/{id}/probe要求sandbox:test；DELETE/{id}?revision=要求sandbox:delete。V16加入权限定义，测试bootstrap角色同步，生产角色须单独授权；旧令牌需重新登录。管理权限不等于Agent/业务工具执行权限。

更新/删除/探测/默认选择携带revision，冲突409。探测固定当前保存版本，检查Linux、内存/PID/CPU配额支持、可信镜像和可选runtime；失败也保存安全FAILED状态，不能当作技能TEST或发布通过。响应不返回私钥、证书正文或密文；读者只见本租户必要主机元数据和安全配置状态。

目标拒绝403、端点不存在404、配置冲突409、配额429、身份失败502、不可用/清理/凭据失败503、超时504。受控失败记录中文原因、code及可信上下文；未知故障在Controller/治理最终边界记录脱敏堆栈，页面errorId与日志一致。严格审计、存储不可用不会转换成成功；保留事务回滚和独立错误码。

## R1 页面及交互

技能页内“技能与发布 / 沙箱端点”两个视图，原技能导入、详情、发布DOM与流程保留。新增sandbox-endpoints.js/css复用中文受控工作台的列表—详情—操作；桌面并列，900px以下纵向堆叠。保存是主操作，测试/默认为辅助操作；未通过当前配置版本或停用不能设默认，默认端点不能直接删除。

SSH/TLS字段按协议显示，凭据仅写、不回填、不进入localStorage/sessionStorage；提交立即清空材料。失败保留普通表单和操作状态，中文原因/code/errorId不会重复拼接。请求epoch与busy约束防止切换后旧响应覆盖当前内容；每个按钮按独立权限控制，服务端重复授权。只读门禁另在真实Chrome DOM加载实际端点组件，注入仅sandbox:read的测试会话投影：新建及所有详情写控件disabled=true，四个材料输入长度均0；该组件检查使用固定测试列表，不冒充真实只读登录。服务端真实令牌的权限拒绝由MockMvc独立覆盖。console-core仅保留SKILL_SANDBOX_*的安全结构化403/404原因，其余错误行为沿用原流程。

真实Chrome通过SSH转发访问Rocky临时test/memory服务，服务不使用外部模型或生产凭据，结束自动关闭。1440×1000与390×844截图确认选中状态、层级、表单密度和零水平溢出；实际完成保存→探测→默认→取消默认→删除。403失败保留名称/host，四个材料输入为空，页面编号dc7696cb-eb8a-4497-ae3b-de3678cd4e7d可检索同一后台拒绝日志。没有新增JavaScript运行异常；预期403请求和既有favicon401不作为页面脚本故障。

## R1 验证和与原方案差异

本地JDK21/Maven3.9.4已确认；实际快速命令：mvn -q -pl cm-agent-server -am "-Dtest=ManagedSkillSandboxTest,SandboxSecurityTest,SandboxEndpointControllerTest,GovernedSkillAccessServiceTest,DockerSkillSandboxTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test；通过。Node命令node --test cm-agent-console/src/test/js/*.test.cjs，89项通过，零失败。

Docker/JDBC/Flyway/Testcontainers仅ssh rocky执行：Docker23.0.6，maven:3.9.9-eclipse-temurin-21，Maven3.9.9/JDK21.0.7。隔离工作区/root/cm-agent-sandbox-r1-bc57060-20261001，HEAD与本地bc57060一致，最终代码及文档覆盖51个明确路径并逐个SHA256核对。用户脏application配置未复制。

最终完整命令：在上述Maven容器中mvn -q -pl cm-agent-server -am clean test -DargLine=-Xmx384m -Dcm-agent.agentscope.studio.enabled=false；显式启用CM_AGENT_TEST_SANDBOX=true、CM_AGENT_TEST_REMOTE_SANDBOX=true，CM_AGENT_TEST_SSH_PACKAGE_DIR=/workspace/validation-ssh-packages，TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1。宿主socket/CLI仅供可信验证器管理项目Testcontainers，技能容器和两个远程daemon无宿主socket挂载。首次完整回归920项中唯一失败为AuthControllerTest旧17项权限断言，修正为22项并显式检查五项新增权限。V16默认表最后补充创建/更新时间后，按最终源码运行AuthControllerTest、SandboxEndpointControllerTest、MigrationTest、JdbcSandboxEndpointRepositoryTest共21项受影响复验，全部通过。最后追加GovernedSkillAccessServiceTest事务外策略复核回归并运行治理/注册器16项通过；只读接口用例扩充后MockMvc4项通过。完整与各次受影响复验的最终报告汇总为922项，不能写成一次完整命令exit0。条件跳过仅为浏览器专用夹具，已另行启动实际服务完成浏览器验收。

真实SSH/TLS专项RemoteDockerSandboxIntegrationTest两项通过：各用专用docker:23-dind daemon/vfs导入可信Python镜像；临时生成认证，管理探测/默认/执行均走实际传输。覆盖固定非root隔离、凭据轮换旧连接固定、新调用探测门禁、超量、超时、中断、错误host key/CA/服务端身份/过期客户端证书、断连及受控清理失败；成功、超时、中断后按本项目随机名称检查零残留。

两库专项JdbcSandboxEndpointRepositoryTest和MigrationTest已通过，最终clean test再次覆盖；镜像PostgreSQL16-alpine与MySQL8.4。浏览器专用SandboxBrowserFixtureTest独立端口18097，仅test/memory，已自动释放；Chrome任务会话和本地SSH转发已关闭。截图保存在本机临时验证目录，非版本控制文件。

首次夹具失败已修复：withCommand(String)拆分脚本，改String[]；后台daemon的父进程生命周期与dind初始化；Rocky时钟落后导致官方APK网络下载验证失败，使用当前本机可信HTTPS下载并保持Alpine签名校验，不更改宿主时钟；tmpfs所有者与中断清理真实缺陷。早期失败不算最终通过，日志保存在隔离验证目录。

原方案拟返回安全证书摘要，本轮以hasCredential及credentialVersion表达配置状态，没有增加证书正文/摘要回读；D5仍为加密落库和只写材料。SPI直接复用SkillReadRequest和固定资源Map，没有额外引入重复请求/结果领域。控制台端点当前只管理Docker，其他扩展通过可信部署Bean和backend标识装配。真实验证追加固定tmpfs所有者、中断后的有界清理等待及一次句柄禁止重放。

| 差异 | 类别 | 调整前 → 调整后 | 来源或依据 | 影响 | 采用状态 |
|---|---|---|---|---|---|
| R1-01 | 新增 | 固定 Docker 实现 → 本地/远程 Docker + 通用扩展接口 | 用户需求与 D1 | T6、T7、T9 | 已采用 |
| R1-02 | 调整 | 直接依赖 Docker → 治理依赖执行合同，Docker 通过装配注册 | GovernedSkillAccessService 当前源码 | T6、T8 | 已实现 |
| R1-03 | 新增 | 无远程连接字段 → SSH 与双向 TLS 明确配置、认证和验证 | D2 | T7、T9 | 已采用 |
| R1-04 | 新增 | 部署策略为主 → 部署配置 + 租户端点控制台管理及运行期调整 | D3、D4 | T8、T10、T11 | 已采用 |
| R1-05 | 新增 | 无沙箱凭据仓储 → 加密落库、只写响应、轮换与部署主密钥 | D5 与现有模型加密链路 | T10、T9 | 已采用 |
| R1-06 | 澄清 | 单一执行器 → 每租户一个默认端点，调用固定连接快照 | D6 | T7、T8、T9 | 已采用 |
| R1-07 | 澄清 | T0–T4 完成 → 历史完成保留，新增 T5–T11 | 旧验收不证明远程新功能 | 全包 | 已采用 |
| R1-08 | 澄清 | R0实施/提交授权 → R1初次交互仅分析和文档修订 | R1规划阶段指令及技能授权边界 | 提示词、账本 | 已采用 |

本轮没有取消旧功能。草稿曾将 T8 写作治理/能力、T9 写作远程验证；D3 选择控制台后扩展其验收并追加 T10/T11，未回收或重用任务编号。


R1-09（2026-10-01）：用户明确授权在codex/分支实际实施T6～T11，恢复mode=default，并明确禁止本轮提交/推送/合并/部署；替代R1-08的当前执行边界，保留其历史来源。R1-10：依据真实独立daemon失败修正tmpfs固定UID/GID和调用中断的清理时序；不放宽R0安全限制。

## R1 运行限制

本轮未提交；执行器宿主需Docker CLI，SSH需OpenSSH，可信镜像必须在目标daemon预备。生产使用JDBC、同一外部主密钥与显式目标允许清单；授权生产角色并重新登录后再配置端点。Docker共享宿主内核，专用执行主机/强化runtime由部署选择。本轮没有修改运行中服务，也没有使用真实生产SSH/TLS或模型凭据。

清理无法确认时保留实例配额并返回失败，管理员按端点和本项目随机容器名核实；系统不自动接管、释放未知配额或重放脚本。主密钥自动迁移、跨实例资源调度和其他沙箱后端不在本轮交付范围。

最后连接兼容/身份复验：DockerSkillSandboxIntegrationTest、RemoteDockerSandboxIntegrationTest、SandboxSecurityTest、DockerSkillSandboxTest共17项通过，覆盖R0本地socket固定路由/私有Docker配置以及精确SSH主机别名、排除系统known_hosts。未知异常通过ErrorDiagnosticLogger资源诊断重载明确记录resourceType/resourceId，保留同一errorId和脱敏堆栈，管理API与统一异常处理专项共11项再次通过（SandboxEndpointControllerTest与ApiExceptionHandlerTest）；未重复记录异常。最终统计922项，921通过、1浏览器专用条件跳过、0失败、0错误。上述统计由完整回归及最终受影响复验汇总，浏览器另行实际验收。

## R0 历史基线（保留原实施与验证记录）


日期沿用任务启动日 2026-09-30；验证跨日到 2026-10-01。初始 HEAD 为 `a72f9228eb07242f303d5e033adaf8b1a56e6241`，任务期间 HEAD 前移到 `5c60ad53372f231f004bf7057b96c92ad07cc441`，已重新核对并在远端采用相同 HEAD。用户于 2026-10-01 授权本地提交，本任务代码、测试及六份工作包纳入同一次提交；提交编号以本文件对应的 Git 历史为准，未推送、未部署。

用户已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、`.codex/` 和 `.workbuddy/` 改动保持不动。远程采用 HEAD 配置加本任务明确文件覆盖，不复制这些未提交配置或凭据。CodeGraph 索引无法定位当前 Skill 类，已先调用并回退源码核对。

## 实际交付

| 编号 | 状态 | 目标与文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| T0 | 完成 | 核实 Skill 链路、工作树与环境，生成六份文档 | 无 | 六份产物齐全，已有配置及工作包保留 |
| T1 | 完成 | Core 网关默认执行扩展；SkillSandboxProperties、DockerSkillSandbox | T0 | 默认关闭；固定命令；有界 stdin、输出、并发、超时及唯一容器清理 |
| T2 | 完成 | GovernedSkillAccessService、AgentScopeSkillExecutionBridge 与准备状态 | T1 | 固定快照、租户/主体、运行状态、撤销、持久化预算、重复拒绝、严格审计、发布门禁测试 |
| T3 | 完成 | 能力接口、部署 profile、V15 状态注释、中文状态标签与生产说明 | T2 | 策略与导入白名单一致；不改变表结构；准备不显示为成功 |
| T4 | 完成 | Java 21 回归、Rocky 真实容器与双数据库验证、工作包核查 | T1,T2,T3 | 首轮全量通过；最终专项 215 项、JS 85 项通过，哈希/文档/零残留已核对 |

首版只执行导入技能包内的 Python 3 脚本和同版本文本资源，模型不能提交代码、命令、镜像、runtime、环境变量或宿主路径。Docker 参数固定为禁网、非 root、只读根文件系统、无宿主挂载、无额外 capabilities、no-new-privileges、CPU 0.5 核、内存与交换内存各 128 MiB、PID 32、nofile 64；私有 /workspace 与 /tmp 各 16 MiB。默认执行 15 秒、stdin 与原始合并输出各 32768 字节、单实例最多 2 个容器；只允许收紧。沙箱默认关闭，开启后 .py 自动进入有效导入白名单，既有资源类型不改变。

- Core：SkillAccessGateway 保持函数式读取契约，增加默认关闭的执行方法；ApiErrorCode 增加关闭、非法、重复、限额、超时、不可用和脚本失败分类。
- Adapter：AgentScopeSkillExecutionBridge 仅定位固定资源；AgentScopeSkillSession 保留原生执行关闭，按策略注册独立执行工具；AgentScopeRunGate 的业务调用前后检查技能致命失败。
- Server：SkillSandboxProperties 与 SkillProperties 提供可收紧策略；DockerSkillSandbox 固定参数、私有 tmpfs、受控环境、stdin 数据传输、并发限流、管道处理与退出清理；GovernedSkillAccessService 完成事务内准备、持久化防重、STARTED 审计、事务外执行与最终复核。审计或持久化故障不伪装成功，未知异常消息不进入日志。
- 准备记录：新增 SANDBOX_PREPARED，deliveredBytes 为 0；预算按该记录的固定版本计算，普通读取不能重放准备记录；SkillTrialService 的既有 SUCCEEDED 判断不会计入准备状态。准备、开始与终态执行审计独立于模型读取成功。
- 能力/部署：SkillResponses 与 SkillController 公开启用状态、python 与时间/输入输出限额；application-skill-sandbox.yml 默认仍关闭。README 与发布说明维护生产限制，原有用户配置未改。
- 持久化/控制台：V15 双方言迁移仅更新 status 中文原生注释；JdbcSkillRepositoriesTest 与 MigrationTest 验证新状态往返、tenant 隔离、双库注释和升级。console-core.js 只新增“沙箱资源已准备”中性状态文案。

## 方案差异

资源准备采用独立状态，未沿用 SUCCEEDED，避免虚假的模型交付和 TEST 门禁通过。由此补充 V15 注释迁移和中文标签，没有新增表或列。首版不提供其他语言、联网安装、导出文件、持久工作区或自动接管。

## 文件范围

- `README.md`
- `cm-agent-agentscope-adapter/README.md`
- `cm-agent-api/src/main/java/com/cmagent/api/ApiErrorCode.java`
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/SkillAccessGateway.java`
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRunGate.java`
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillSession.java`
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillExecutionBridge.java`
- `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillExecutionBridgeTest.java`
- `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillRuntimeContractTest.java`
- `cm-agent-server/src/main/java/com/cmagent/server/config/SkillProperties.java`
- `cm-agent-server/src/main/java/com/cmagent/server/config/SkillSandboxProperties.java`
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/DockerSkillSandbox.java`
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/GovernedSkillAccessService.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/ApiExceptionHandler.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/SkillController.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java`
- `cm-agent-server/src/main/resources/application-skill-sandbox.yml`
- `cm-agent-server/src/test/java/com/cmagent/server/config/SkillPropertiesTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/DockerSkillSandboxTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/DockerSkillSandboxIntegrationTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/GovernedSkillAccessServiceTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/SkillControllerTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/ApiExceptionHandlerTest.java`
- `docs/release-notes.md`
- `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillLoadStatus.java`
- `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillLoadRecord.java`
- `cm-agent-core/src/test/java/com/cmagent/core/domain/SkillDomainTest.java`
- `cm-agent-persistence/src/main/resources/db/migration/mysql/V15__document_skill_sandbox_preparation.sql`
- `cm-agent-persistence/src/main/resources/db/migration/postgresql/V15__document_skill_sandbox_preparation.sql`
- `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcSkillRepositoriesTest.java`
- `cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`
- `cm-agent-console/src/test/js/console-core.test.cjs`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillTrialServiceTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/SkillSandboxCapabilitiesTest.java`

六份工作包随实现同步。最终专项复验 215 项、JS 85 项通过；真实容器、双库 V15、哈希、六份文档链接与零残留已核对，详细证据见账本。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)
