# 远程Docker账号密码认证需求清单

工作包日期：2026-10-01；主题：remote-docker-password-auth；当前修订：R2；修订日期：2026-11-13；mode=default。

R0/R1 历史内容继续保留在文件末尾；从 R2 起，本清单只维护任务、依赖和验收。实时状态与验证证据只以[进度账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)为准。

## 任务与验收

| 编号 | 目标 | 依赖 | 验收条件 |
|---|---|---|---|
| T0 | 核对仓库、工作树、规范、原沙箱能力和安全边界 | 无 | 约束与证据来源明确，未覆盖用户修改 |
| T1 | 实现受控 SSH 密码认证与 Docker Unix socket 中转 | T0 | 密码不进入参数、环境、临时明文、日志或浏览器存储；主机信任、超时、取消和清理可验证 |
| T2 | 扩展认证类型、凭据材料、加密、配置和双数据库迁移 | T1 | 旧 KEY 数据兼容；PASSWORD 只写、加密且不回显；类型和材料原子更新 |
| T3 | 接入服务、API、探测、运行和错误治理 | T2 | 权限、租户、CAS、审计和错误编号一致；在途连接不被密码轮换影响 |
| T4 | 扩展 v2 沙箱端点页面 | T3 | 私钥/密码类型可选择；密码掩码且只写；切换、关闭和提交后清空输入 |
| T5 | 完成 Java、Node、Rocky、数据库和浏览器验证 | T3、T4 | 密码连接及失败矩阵、旧能力回归、双库迁移和页面行为均有分层证据 |
| T6 | 按新契约迁移六份文档职责 | T0–T5 | 状态与证据只留在账本；R0/R1 历史不丢失 |
| T7 | 验证文档契约、链接和状态目录 | T6 | 六份修订一致，交叉链接有效，状态目录反映 R2 |

## 当前状态与证据

当前修订的任务状态、实际操作、测试结果、阻塞和提交信息只在[进度账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)维护。相关入口：[设计](../specs/2026-10-01-remote-docker-password-auth-design.md)、[计划](../plans/2026-10-01-remote-docker-password-auth.md)、[提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md)、[实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R2为准</summary>

# 远程Docker账号密码认证需求清单

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

## 待办与验收

| 编号 | 状态 | 目标及主要文件 | 依赖 | 验收条件与验证 |
|---|---|---|---|---|
| T0 | 完成 | 当前证据核对、范围和六份工作包 | 无 | 六份日期/主题/编号/链接一致；已有54个改动文件SHA256保持；仅新增文档 |
| T1 | 待做 | OpenSSH密码输入安全原型；DockerDaemonConnection.java，可信助手/IPC类为拟新增路径 | T0 | 在无TTY条件下单次密码认证、严格主机身份与Unix socket转发；密码不出现在参数/环境/临时文件/日志；Linux及Windows原生客户端兼容验证；错误或能力缺失明确拒绝，无回退 |
| T2 | 待做 | core领域/Repository、SandboxCredentials/Cipher、部署配置、memory/JDBC、双库新迁移 | T1 | sshAuthType默认KEY；旧v1密文可读；密码加密不回显、AAD防跨租户/资源复制；类型/材料原子CAS，轮换失效探测；两个数据库注释与升级测试 |
| T3 | 待做 | SandboxEndpointService/Controller、ManagedSkillSandbox与连接/错误治理 | T2 | 五项原管理权限继续有效；认证类型切换需credential:write；错误密码502、参数400、超时504等原因/编号对应安全日志；运行时按快照连接，轮换不改在途清理，不自动重试密码或回退 |
| T4 | 待做 | v2技能页sandbox-endpoints.js/css及必要HTML、Node测试 | T3 | SSH可选私钥/账号密码；密码框掩码、只写、不缓存，提交及切换/关闭即清空；元数据失败保留，材料清空；只读禁写；桌面/移动浏览器实际可见并走保存/测试/默认/轮换流程 |
| T5 | 待做 | 本轮专项与原回归；README、docs/skill-sandbox-endpoints.md、release-notes及本六份文档 | T3、T4 | ssh rocky指定Maven21容器完成密码真实连接、故障/轮换/隔离清理、PG16/MySQL8.4；旧KEY/TLS/LOCAL回归；Node及浏览器；精确记录命令、HEAD+覆盖哈希与残留 |

实施依赖为T0→T1→T2→T3→T4，T5汇总验收。T1是技术门禁；平台不支持受控密码输入时记录确切限制，不用sshpass -p、SSHPASS、明文文件、跳过主机检查或临时裸Docker TCP代替。T2/T3/T4可在接口方案明确后分别推进，但密码端到端验收依赖T1。

## 交付边界

文档完成不等于T1～T5完成。密码认证为新增可选方式，既有SSH私钥/双向TLS/本地Docker、权限、端点CAS及固定快照能力不可回退。数据库不得修改V16，后续采用执行时核实的下一个可用版本；当前方案预期V17，但不得占用他人新增迁移号。

用户报告现有页面未看到“沙箱端点”，诊断曾被中断，当前只确认Chrome中有localhost:8080/console/v2/skills.html标签，未证明其响应或运行产物已包含R1。T4实施时必须核对实际静态资源/构建产物及页面入口，不把旧临时浏览器验收当作用户服务已更新，也不擅自重启该服务。

验证方法详见计划。没有实际业务或容器测试运行；未知助手能力、目标系统密码策略和最新运行产物均为未验证项，非已发生的外部失败。


</details>
