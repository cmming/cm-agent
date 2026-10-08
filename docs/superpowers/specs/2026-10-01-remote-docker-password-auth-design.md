# 远程Docker账号密码认证需求设计

工作包日期：2026-10-01；主题：remote-docker-password-auth；当前修订：R1；修订日期：2026-10-02；mode=default。

用户“按照执行提示词执行”已授权实际实施T1～T5。本轮保持codex/skill-sandbox-r1，HEAD bc57060fac00c671cd247525b6082516194afd20；未提交、未暂存、未推送、未合并、未部署。原2026-09-30技能沙箱六份文档及用户配置保留，R0仅作为需求分析历史归档，不再代表当前实施状态。

关联：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md) · [提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md) · [设计](../specs/2026-10-01-remote-docker-password-auth-design.md) · [计划](../plans/2026-10-01-remote-docker-password-auth.md) · [实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md) · [账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)。

| 任务 | 当前状态 | 交付与验收 | 依赖 |
|---|---|---|---|
| T0 | 完成 | 已核对仓库、工作树、规范、相关POM、部署配置、CodeGraph及原工作包；保留R0分析历史 | 无 |
| T1 | 完成 | JSch内存密码认证、严格主机信任、固定IP与Docker Unix socket、单次认证与取消/清理；Rocky真实执行、Windows传输专项、黑洞超时/取消通过 | T0 |
| T2 | 完成 | KEY/PASSWORD领域与只写材料、AES/GCM、部署Secret文件、旧Java/JSON兼容、JDBC与双库V17；升级默认KEY与原生注释通过 | T1 |
| T3 | 完成 | 服务/API/探测/运行按固定快照选择认证；独立凭据权限、CAS、轮换探测失效、错误码/状态/同编号日志与脱敏验证通过 | T2 |
| T4 | 完成 | v2认证选择、掩码只写字段、切换/提交清空、失败保留元数据、只读/部署禁用；Node事件测试与桌面/移动浏览器组合验收通过 | T3 |
| T5 | 完成 | Rocky全量与双库/密码/旧KEY/TLS/LOCAL回归、Windows专项、Node、浏览器、生产说明与六份文档同步 | T3、T4 |

验收采用分层组合证据：真实PASSWORD保存/探测/默认/执行/轮换由Rocky集成测试覆盖；浏览器通过API预置的临时密码端点验证只写状态、元数据保存、失败与类型切换，并实际走LOCAL保存→探测→默认链路；密码新输入与提交失败清空由Node真实页面事件编排测试覆盖。浏览器未输入/更换真实主机凭据，没有目标生产主机的验收。Windows专项仅验证JVM认证与Unix socket中继，容器执行仍仅在Rocky验证。
## R1修订差异与工程决定

| 差异 | 类别 | R0 → R1 | 依据与影响 | 采用 |
|---|---|---|---|---|
| C1 | 澄清 | 仅生成文档 → 用户明确授权T1～T5实施 | 用户本轮执行指令；Git/部署限制继续有效 | 已采用 |
| C2 | 调整 | 优先原生OpenSSH ASKPASS/IPC → 仅PASSWORD使用JSch 2.28.7内存传输 | OpenSSH readpass存在1024字节缓冲限制，Windows助手还需原生打包；不能兑现4096字节上限且增加Secret传递面。工程自主采用保持安全边界的库实现；KEY/TLS不重写 | 已采用，T1 |
| C3 | 新增 | 控制台共享资源旧版本缓存 → 更新全部v2页面共享app/core版本及技能端点脚本版本 | 隔离浏览器实际复现旧app缓存导致入口不挂载；刷新新版本后标签与密码选择出现 | 已采用，T4 |
| C4 | 调整 | 部署未启用仅禁新建 → 同时禁表单写入；类型变化需完整材料 | 浏览器观察与安全入口契约；Node只读/主密钥/关闭状态回归 | 已采用，T4 |

账号密码仍指远端Linux主机SSH认证，不涉及Registry、HTTP Basic、keyboard-interactive/MFA。不存在用户新增交互决定；这些为默认模式工程选择，保留原需求安全边界。未修改用户8080服务、Rocky宿主sshd或daemon。

技术依据：[Docker SSH/TLS官方说明](https://docs.docker.com/engine/security/protect-access/)、[OpenSSH Windows readpass源码](https://github.com/PowerShell/openssh-portable/blob/latestw_all/readpass.c)、[JSch 2.28.7发布](https://github.com/mwiede/jsch/releases/tag/jsch-2.28.7)、[direct-streamlocal源码](https://github.com/mwiede/jsch/blob/jsch-2.28.7/src/main/java/com/jcraft/jsch/ChannelDirectStreamLocal.java)、[通道IO与连接源码](https://github.com/mwiede/jsch/blob/jsch-2.28.7/src/main/java/com/jcraft/jsch/ChannelDirectTCPIP.java)。没有实施ASKPASS助手/IPC，不把替代方案写成原生客户端原型通过。
## 实际调用链与文件

- core新增SandboxSshAuthType；SandboxEndpoint末尾认证类型缺省KEY，保留旧构造器并拒绝非SSH PASSWORD。SandboxCredentials增加password，只写且toString脱敏；旧四字段JSON/Java调用兼容。
- PasswordSshRelay由每次DockerDaemonConnection独占；原host匹配显式known_hosts，SocketFactory连接策略解析后的固定IP。仅password认证，无UserInfo/agent/私钥fallback；预打开固定/var/run/docker.sock，不能由模型指定Unix路径或远端命令。
- 中继只监听随机127.0.0.1端口、并发上限8，逐批flush SSH数据；IO在connect前装配。认证预算与取消监视有界，close终止监听、SSH通道、socket和线程并恢复中断标记。清理失败保持原治理配额拒绝策略。
- DockerConnectionProperties与application-skill-sandbox.yml新增ssh-auth-type/password-file。部署Secret原样读取，密码不进入Docker/SSH CLI参数或环境，也不生成密码文件。JVM String受GC管理，不宣称完整堆擦除。
- SandboxEndpointService/Controller新增认证类型；材料整体省略仅同协议/同类型保留，类型切换须新完整材料和sandbox:credential:write。加密AAD仍绑定tenant/endpoint/version；配置与材料CAS原子更新、探测失效；运行中持有旧连接，不重放脚本。
- JdbcSandboxEndpointRepository映射ssh_auth_type；MySQL/PostgreSQL新增V17，不改V16，原记录默认KEY。中文原生注释同步更新；MySQL材料列保持MEDIUMTEXT上限。
- console/v2/assets/sandbox-endpoints.js增加认证选择、密码掩码、禁用状态与凭据清空；端点脚本1.1.0。九个v2入口共享app.js版本2.0.27、core版本2.0.14，避免跨页入口缓存旧脚本。ConsoleResourceTest同步版本；CSS及原技能行为保留。
- 专项测试：PasswordSshRelayTest、PasswordSshWindowsFixtureTest、RemoteDockerSandboxIntegrationTest、SandboxSecurityTest、SandboxEndpointControllerTest、JdbcSandboxEndpointRepositoryTest、MigrationTest、SandboxBrowserFixtureTest、Node sandbox-endpoints.test.cjs。
- README.md、docs/skill-sandbox-endpoints.md、docs/release-notes.md与本六份文件同步；原沙箱工作包不重写。

## R1最终收尾核对

最终本地命令：mvn -q -pl cm-agent-server -am test -Dtest=SandboxSecurityTest,PasswordSshRelayTest,SandboxEndpointControllerTest,ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.agentscope.studio.enabled=false，退出0；四类30项通过，已覆盖旧四字段真实v1密文和4096/4097字节边界。Node最终92/92通过。

原技能沙箱六份及三份用户application配置共9个受保护文件SHA256与本轮实施前一致；工作包6份、无无效相对链接；本轮相关路径git diff --check退出0，暂存区为空、HEAD未变化。Rocky本项目cm-agent-password-*及cm-agent-skill-*容器查询为空，浏览器服务和本地SSH转发已释放。原application.yml末尾空行未改动。

代码实施没有剩余外部阻塞。生产主机接入、生产凭据、部署/重启及提交均未执行，仍需用户另行明确授权。浏览器手工更换密码未执行，凭据通过临时API预置，密码输入/失败清空/权限由Node事件测试及后端集成组合覆盖，不冒充生产凭据验收。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R1为准</summary>

# 远程Docker账号密码认证设计

日期：2026-10-01；主题：remote-docker-password-auth；修订：R0；工作模式：mode=default。

用户需求：“远程docker 新增账号密码的方式授权连接”。这是独立新增需求，引用既有[技能沙箱R1清单](../checklists/2026-09-30-skill-sandbox-checklist.md)及[原账本](../progress/2026-09-30-skill-sandbox-ledger.md)，保留原六份文档和未提交实现。本轮只做需求分析与六份文档；业务实施尚未授权，不提交、推送、合并、部署，不修改运行中服务。

Git基线：分支codex/skill-sandbox-r1，HEAD bc57060fac00c671cd247525b6082516194afd20。工作树包含既有沙箱R1未提交代码与用户无关修改；不能仅以HEAD代表已有R1代码，实施前必须核对工作树。暂存区为空。

关联文档：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md) · [提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md) · [设计](../specs/2026-10-01-remote-docker-password-auth-design.md) · [计划](../plans/2026-10-01-remote-docker-password-auth.md) · [实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md) · [账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)。

## 当前证据

- 已核对根AGENTS.md、Server/Console POM、application-skill-sandbox.yml、生产说明docs/skill-sandbox-endpoints.md及当前R1文档。相关模块未发现更近AGENTS.md。
- 已先调用CodeGraph；查询未给出相关新沙箱类型的匹配源码，结果主要指向旧运行/安全类型，随后直接核对当前未提交源码，不以索引推断能力。
- server/runtime/DockerDaemonConnection.java的ssh方法要求privateKey与knownHosts；固定BatchMode=yes、IdentitiesOnly=yes、IdentityAgent=none、StrictHostKeyChecking=yes，并通过本地回环转发远程/var/run/docker.sock。当前不能提交主机密码认证。
- server/config/DockerConnectionProperties.java只有协议、主机、端口、用户名和材料文件引用；server/runtime/SandboxCredentials.java只有私钥、knownHosts、CA、客户端证书四个材料字段。server/service/SandboxEndpointService.java统一校验远程私钥；不能只加页面密码框。
- server/runtime/SandboxCredentialCipher.java使用v1 AES/GCM密文，AAD绑定tenant/endpointId/credentialVersion；新增认证类型必须与材料一起做受版本约束的更新。core/domain/SandboxEndpoint.java尚无SSH认证类型字段。
- console/v2/assets/sandbox-endpoints.js的buildPayload和clearSecrets仅处理现有材料；密码输入、认证选择、轮换、省略保留语义都需扩展。
- 当前错误映射：SKILL_SANDBOX_INVALID为400、SKILL_SANDBOX_AUTH_FAILED为502；其余认证/不可用/超时错误应保持实际语义及同一errorId，不返回远端原文。
- R1账本记载完整回归及受影响复验汇总921项Java通过、1条件跳过、Node89通过以及SSH/TLS/双库/浏览器证据。它们只证明原私钥/TLS范围，未在本轮重跑，不能证明密码连接成功。

## 范围与自主假设

| 编号 | 类型/状态 | 内容、依据与影响 |
|---|---|---|
| A1 | 默认模式自主假设，未获用户单独确认 | “账号密码”解释为远程Linux主机SSH用户名/密码，通过SSH访问Docker Unix socket；沿用既有SSH拓扑。Docker Registry登录与反向代理HTTP Basic认证不在本轮范围。若用户后续明确指向其他方式，修订范围并保留本假设来源。 |
| A2 | 工程方案，待实现验证 | mode仍为SSH；新增sshAuthType=KEY/PASSWORD，省略默认KEY，旧部署/旧API/旧密文按KEY处理；LOCAL/TLS不得使用PASSWORD。对外返回认证类型及hasCredential状态，密码与密文永不回显。 |
| A3 | 工程方案，待实现验证 | 密码非空，原样使用、不trim，UTF-8最多4096字节，拒绝NUL/CR/LF以适配单次输入协议；全部材料继续受现有128KiB总限额。只有credentials整体省略且类型不变表示保留，空密码不表示清除。 |
| A4 | 工程方案，待兼容性验证 | 优先复用原生OpenSSH隧道，加可信ASKPASS助手和受限短期IPC；不把密码放在进程参数、URL、子进程环境、临时文件或脚本中。Windows/Linux可用性及助手包装方式须先验证，未证实不能承诺跨平台支持。 |
| A5 | 范围约束 | 部署者自行保证远程账户允许SSH密码认证、Unix socket转发及Docker访问。只覆盖普通password认证，不自动处理键盘交互、MFA、sudo密码、强制修改密码，不修改远端sshd或docker配置。 |
| A6 | 继承约束 | 保留租户隔离、精确目标允许清单、严格known_hosts、独立凭据权限/审计、默认选择、固定在途连接及全部R0容器限制。默认仍为KEY；不得因增加PASSWORD自动换认证方式或连接主机。 |

## 目标与非目标

管理员无需配置SSH私钥，可填写远程主机用户名/密码与可信known_hosts，保存后测试连接并选为租户默认。部署默认连接也可用密码方式。认证选择明确且不会替代已使用的私钥/TLS。密码是SSH主机认证材料，未建立Docker daemon原生账号体系。

非目标：Registry登录、HTTPS代理Basic/OAuth、新沙箱后端、任意SSH命令、MFA/交互式改密、sudo提权、自动放开主机认证或修改目标服务器。用户未要求实施，本轮不写业务代码。

## 数据与兼容设计（待实施）

1. 拟新增核心枚举SandboxSshAuthType，常量KEY/PASSWORD；SandboxConnectionMode维持LOCAL/SSH/TLS。为SandboxEndpoint、部署连接属性、写入/响应DTO及Repository增加sshAuthType；请求省略按KEY兼容，非SSH只接受KEY默认值，明确PASSWORD组合必须拒绝。旧Java构造调用通过兼容重载保留原语义，字段JavaDoc同步。
2. 密码仅放在SandboxCredentials.password，不作为Domain密码字段或独立明文列。创建PASSWORD端点需username、password、knownHosts；KEY需privateKey/knownHosts；TLS材料规则保持。密码有独立长度及控制字符校验，不清理空格或Unicode。
3. 旧v1加密材料缺少password时映射为空，旧认证类型缺省KEY；显式测试Jackson解码、源级构造兼容与密文往返，不声称添加record组件自动兼容。沿用AES/GCM和现有AAD；认证类型/材料变更在同一事务递增credentialVersion与revision，禁止只改类型而保留不匹配材料。
4. 请求credentials整体省略时只允许同类型保留；提供credentials则完整替换对应类型材料，不用空字符串“自动保留”密码。KEY↔PASSWORD需新完整材料及sandbox:credential:write；切换LOCAL清除材料，沿用原独立权限。每次改类型/密码/配置使probeRevision失效并要求新探测。
5. JDBC新增非空ssh_auth_type列默认KEY；当前预期mysql/postgresql/V17__add_sandbox_ssh_auth_type.sql，实施前确认下一版本。两种方言原生中文列注释，memory、显式mapper及SQL同步；保留tenant过滤、默认唯一、审计事务及现有索引。不能修改既有V16。
6. 部署连接新增ssh-auth-type，默认KEY；PASSWORD使用password-file指向部署Secret挂载。配置仅保存引用，不提供可用默认密码，不把密码复制到CLI环境或Server生成临时文件；控制台不能提交宿主路径。TLS/KEY原文件配置继续有效。
7. 响应仅新增sshAuthType与已有hasCredential/credentialVersion等状态，不返回password、密码长度、哈希或密文。审计仅记录认证类型/版本变化，禁止把credentials对象序列化进入审计。

## 密码连接方案与技术门禁

优先保留当前OpenSSH -N -T、本地回环到远端/var/run/docker.sock的连接及清理路径。PASSWORD分支拟使用BatchMode=no、PreferredAuthentications=password、PasswordAuthentication=yes、PubkeyAuthentication=no、KbdInteractiveAuthentication=no、NumberOfPasswordPrompts=1；KEY维持既有BatchMode=yes。不读取用户agent/SSH配置，不自动轮询旧密码、私钥或其他认证方式。

ASKPASS为服务端可信固定助手；密码由短期内存持有，通过具有当前用户限制和单次能力校验的IPC供助手读取，助手仅向ssh专用输出管道写一次，服务端不得将该管道接入日志。程序路径、IPC标识和一次性能力参数必须与客户端输入隔离，密码本身不得在参数、环境、URL、脚本、生成文件中出现。IPC初始化后建立资源句柄；失败/超时/取消须关闭助手、IPC、SSH与缓冲，禁止共享静态密码或跨调用读取；受控测试覆盖并发和错配拒绝。不能承诺擦除不可变String的所有JVM副本，需限制生命周期并擦除可控byte/char缓冲。

Windows助手启动及无TTY的SSH_ASKPASS_REQUIRE兼容、IPC访问限制、打包后可执行路径/权限和Linux头less行为尚未验证。T1必须先做原型和威胁边界验证；必要的实现替换需有官方API/依赖源码和测试证据，记录与本方案差异，不为了绕过密码输入把远端daemon暴露到未经保护的TCP。没有证据不预先引入Java SSH依赖或宣称其支持Unix socket转发。

主机身份仍用明确known_hosts、StrictHostKeyChecking=yes、固定IP及原host别名，排除系统额外信任。身份拒绝须在供给密码前收口；测试验证错误host key不会取走密码。登录成功与拥有Docker访问权限分开判断；探测必须实际访问Docker，不能只把本地转发端口监听成功当作认证/权限通过。

## 调用、失败与界面

JWT可信租户→权限/目标策略→固定端点/认证类型/凭据版本→解密→受控密码认证→原Docker执行与同daemon清理→撤销/策略复核与严格审计→脱敏交付。在途句柄固定旧密码，轮换只影响新调用；连接断开导致清理不确定时继承保留实例配额策略，不使用新密码重建旧连接或重放脚本。

错误密码/认证拒绝用SKILL_SANDBOX_AUTH_FAILED和502；参数非法400、缺权403、CAS409、凭据不可用503、超时504沿用现有映射。认证拒绝使用WARN，内部依赖/不可恢复执行失败ERROR；日志包含同响应errorId、操作、可信tenant、endpointId及稳定code，未知异常保留脱敏堆栈。密码、内部地址、SSH提示原文均不返回，错误建议更换材料或检查部署者认证策略，避免枚举远程账户是否存在。

控制台SSH认证选择为“私钥 / 账号密码”，用户名复用；PASSWORD显示掩码密码框及known_hosts，不显示私钥。密码只写、无回填、无Web Storage；切换端点/类型、取消、关闭、提交后立即清空，失败保留普通元数据。旧端点默认显示KEY；readonly及无credential权限禁相关写控件，服务端重复授权。原页面可见性另须结合用户实际资源核对。

## 验收与依据

| 编号 | 状态 | 目标及主要文件 | 依赖 | 验收条件与验证 |
|---|---|---|---|---|
| T0 | 完成 | 当前证据核对、范围和六份工作包 | 无 | 六份日期/主题/编号/链接一致；已有54个改动文件SHA256保持；仅新增文档 |
| T1 | 待做 | OpenSSH密码输入安全原型；DockerDaemonConnection.java，可信助手/IPC类为拟新增路径 | T0 | 在无TTY条件下单次密码认证、严格主机身份与Unix socket转发；密码不出现在参数/环境/临时文件/日志；Linux及Windows原生客户端兼容验证；错误或能力缺失明确拒绝，无回退 |
| T2 | 待做 | core领域/Repository、SandboxCredentials/Cipher、部署配置、memory/JDBC、双库新迁移 | T1 | sshAuthType默认KEY；旧v1密文可读；密码加密不回显、AAD防跨租户/资源复制；类型/材料原子CAS，轮换失效探测；两个数据库注释与升级测试 |
| T3 | 待做 | SandboxEndpointService/Controller、ManagedSkillSandbox与连接/错误治理 | T2 | 五项原管理权限继续有效；认证类型切换需credential:write；错误密码502、参数400、超时504等原因/编号对应安全日志；运行时按快照连接，轮换不改在途清理，不自动重试密码或回退 |
| T4 | 待做 | v2技能页sandbox-endpoints.js/css及必要HTML、Node测试 | T3 | SSH可选私钥/账号密码；密码框掩码、只写、不缓存，提交及切换/关闭即清空；元数据失败保留，材料清空；只读禁写；桌面/移动浏览器实际可见并走保存/测试/默认/轮换流程 |
| T5 | 待做 | 本轮专项与原回归；README、docs/skill-sandbox-endpoints.md、release-notes及本六份文档 | T3、T4 | ssh rocky指定Maven21容器完成密码真实连接、故障/轮换/隔离清理、PG16/MySQL8.4；旧KEY/TLS/LOCAL回归；Node及浏览器；精确记录命令、HEAD+覆盖哈希与残留 |

官方依据仅用于方案边界，不证明本项目已经实现：
- [Docker daemon连接保护](https://docs.docker.com/engine/security/protect-access/)说明SSH/TLS与远程用户socket权限，支持A1的SSH拓扑解释。
- [OpenSSH ssh配置](https://man.openbsd.org/ssh_config)说明BatchMode禁用密码提示、PasswordAuthentication、PreferredAuthentications和提示次数。
- [OpenSSH ssh环境](https://man.openbsd.org/ssh)说明SSH_ASKPASS与SSH_ASKPASS_REQUIRE机制；不能据此直接断言当前Windows版本可用。


</details>
