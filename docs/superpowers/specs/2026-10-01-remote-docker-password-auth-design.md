# 远程Docker账号密码认证需求设计

工作包日期：2026-10-01；主题：remote-docker-password-auth；当前修订：R2；修订日期：2026-11-13；mode=default。

R0/R1 原文保留在文件末尾。R2 只整理设计与决定，不在这里复制任务状态或验证结果。

## 当前设计

- 账号密码解释为远程 Linux 主机 SSH 用户名/密码，仍通过 SSH 访问 Docker Unix socket；不扩展 Registry 或 daemon HTTP Basic。
- `sshAuthType` 支持 `KEY`、`PASSWORD`，省略时兼容为 `KEY`；LOCAL/TLS 不接受 PASSWORD。
- PASSWORD 使用 JSch 内存传输，严格校验 known_hosts，连接固定解析地址，只开放固定远端 Docker socket。
- 密码只写、AES/GCM 加密、AAD 绑定租户/端点/版本；空值、控制字符和长度按原验收处理。
- 凭据类型与材料整体原子更新，类型切换需要独立凭据写权限并递增凭据版本、使旧探测失效。
- 运行中的连接固定旧认证快照；轮换只影响新调用，不自动回退私钥、密码或其他认证方式。

## 关键工程决定

| 编号 | 决定 | 依据 |
|---|---|---|
| D1 | PASSWORD 使用 JSch 2.28.7 内存传输，替代 ASKPASS/IPC 候选 | 原生 OpenSSH readpass 的缓冲上限与 Windows 打包面无法满足既定密码边界 |
| D2 | 严格主机身份、单次认证、固定 socket 和唯一清理路径不可放宽 | 防止密码提前交付、跨主机连接和静默回退 |
| D3 | 密码新输入、类型切换和权限状态必须在界面与 API 两侧一致约束 | 前端隐藏不能替代服务端权限和凭据治理 |
| D4 | R2 起账本唯一承载执行状态和验证证据 | 六份契约要求降低跨文件状态复制 |

## 当前状态与证据

当前修订的状态、验证和提交信息只以[进度账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)为准。其他入口：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md)、[计划](../plans/2026-10-01-remote-docker-password-auth.md)、[提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md)、[实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R2为准</summary>

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
