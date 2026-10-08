# 远程Docker账号密码认证执行提示词

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
## 当前执行入口（R1）

```text
先读取本文件及关联清单、设计、计划、实现说明、账本、AGENTS.md与当前Git/工作树。
mode=default；用户已授权此次T1～T5实施，现已完成，不重做已完成项、不把历史R0未授权文字当当前状态。
如需继续修复，先复现新问题，保护既有R0/R1能力、原工作包、用户application*.yml/.codex/.workbuddy；保持codex/分支。
PASSWORD仅SSH主机账号密码；JSch内存传输固定IP/known_hosts/Unix socket，KEY/TLS/LOCAL保留。密码不进入进程参数、CLI环境、生成明文文件、日志、审计或Web Storage。
任何后续类型/凭据改动保持tenant/JWT、独立凭据权限、AES/GCM AAD、CAS、探测版本、在途固定连接与原限额；不得回退认证或重放脚本。
Java21快速测试与Node；Docker/JDBC/Flyway/Testcontainers必须ssh rocky的maven:3.9.9-eclipse-temurin-21容器，核对HEAD+明确覆盖SHA256。
新增问题按本包追加修订/关联任务和新证据；旧通过不替代新验收。同步六份、生产说明和发布说明。
本轮禁止暂存/提交/推送/合并/部署或修改运行服务；后续必须有用户明确新授权。
```

## R1最终收尾核对

最终本地命令：mvn -q -pl cm-agent-server -am test -Dtest=SandboxSecurityTest,PasswordSshRelayTest,SandboxEndpointControllerTest,ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.agentscope.studio.enabled=false，退出0；四类30项通过，已覆盖旧四字段真实v1密文和4096/4097字节边界。Node最终92/92通过。

原技能沙箱六份及三份用户application配置共9个受保护文件SHA256与本轮实施前一致；工作包6份、无无效相对链接；本轮相关路径git diff --check退出0，暂存区为空、HEAD未变化。Rocky本项目cm-agent-password-*及cm-agent-skill-*容器查询为空，浏览器服务和本地SSH转发已释放。原application.yml末尾空行未改动。

代码实施没有剩余外部阻塞。生产主机接入、生产凭据、部署/重启及提交均未执行，仍需用户另行明确授权。浏览器手工更换密码未执行，凭据通过临时API预置，密码输入/失败清空/权限由Node事件测试及后端集成组合覆盖，不冒充生产凭据验收。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R1为准</summary>

# 远程Docker账号密码认证执行提示词

日期：2026-10-01；主题：remote-docker-password-auth；修订：R0；工作模式：mode=default。

用户需求：“远程docker 新增账号密码的方式授权连接”。这是独立新增需求，引用既有[技能沙箱R1清单](../checklists/2026-09-30-skill-sandbox-checklist.md)及[原账本](../progress/2026-09-30-skill-sandbox-ledger.md)，保留原六份文档和未提交实现。本轮只做需求分析与六份文档；业务实施尚未授权，不提交、推送、合并、部署，不修改运行中服务。

Git基线：分支codex/skill-sandbox-r1，HEAD bc57060fac00c671cd247525b6082516194afd20。工作树包含既有沙箱R1未提交代码与用户无关修改；不能仅以HEAD代表已有R1代码，实施前必须核对工作树。暂存区为空。

关联文档：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md) · [提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md) · [设计](../specs/2026-10-01-remote-docker-password-auth-design.md) · [计划](../plans/2026-10-01-remote-docker-password-auth.md) · [实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md) · [账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)。

以下是交给后续明确授权实施者的完整提示词。本轮未执行此提示词；生成不等于业务、提交、部署或外部操作授权。

```text
请在F:/java/cm-agent完成远程Docker账号密码连接需求，先读取：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-01-remote-docker-password-auth-checklist.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-01-remote-docker-password-auth-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-01-remote-docker-password-auth.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-01-remote-docker-password-auth-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-01-remote-docker-password-auth-ledger.md
以及原2026-09-30-skill-sandbox六份工作包、AGENTS.md、相关POM/配置/README与当前代码。

工作模式mode=default。普通工程细节自主取舍并记录假设，不主动征询选择；用户后续切换模式优先。仅在用户明确要求实施后执行T1～T5，不因本提示词生成或快捷问答自动修改业务。保留T0已完成文档核对，不重复实现R0/R1。
目标按A1解释为远程Linux主机SSH用户名/密码认证，沿用SSH到Docker Unix socket；不是Registry登录或daemon HTTP Basic。保留私钥/TLS/LOCAL、全部Run/tenant权限审计、默认路由、在途固定连接与R0隔离限额。

先核对Git HEAD/工作树；R1当前为未提交实现，HEAD本身不包含其全部源码。使用codex/分支、保护用户application*.yml/.codex/.workbuddy及无关修改，暂存/提交/推送/合并/部署均需用户新明确指令，本提示词不授予。不修改运行中的8080服务或远端sshd/docker配置；没有真实凭据不得编造，项目验证只生成临时测试材料。

按T1→T2→T3→T4→T5推进：
T1验证原生OpenSSH无TTY密码认证与Unix socket转发。优先可信ASKPASS助手+受限短期IPC，Linux/Windows能力与打包启动必须有实际证据。密码不能进参数、子进程环境、URL、脚本、生成明文文件、日志、审计或Web Storage。单次password认证，不重试旧密码，不切私钥或本地兜底；保持严格known_hosts/固定IP/排除系统信任，错误host key不得取走密码。助手/IPC/SSH资源必须在失败、超时、中断关闭，未知清理失败继承配额保留。
T2增加sshAuthType KEY/PASSWORD（旧省略KEY）、密码只写材料与部署password-file，旧v1 JSON/Java构造兼容需测试。密码原样使用，非空、UTF8最多4096字节、拒绝NUL/CR/LF；凭据总128KiB上限保持。类型切换需新完整材料，credentials省略仅同类型保留。AES/GCM和tenant/id/version AAD保持；类型/材料/CAS/credentialVersion/revision原子更新，失效探测。双库新增下一个可用迁移（当前预期V17），中文原生注释；禁止改V16。
T3同步API/服务/运行/部署/探测，类型及材料变更需sandbox:credential:write及原管理权限。探测网络在事务外，回写CAS；错误密码502、参数400、超时504等真实错误语义、同errorId诊断与脱敏堆栈，不回传SSH原文或密码；轮换不变更在途连接/清理，不重放脚本。
T4在v2技能页添加SSH私钥/账号密码选择及掩码密码字段。只写不回填，切换/取消/关闭/提交即清空，失败保留普通元数据；只读及凭据权限前后端一致。先核对入口、源码/构建产物/实际响应，定位此前“沙箱端点”不可见反馈；在独立测试实例验收，不重启用户服务。
T5本地JDK21快速单元与Node；Docker/JDBC/Flyway/Testcontainers仅ssh rocky、maven:3.9.9-eclipse-temurin-21。确认Docker、JDK/Maven、HEAD及明确覆盖文件SHA256匹配，再在隔离工作区执行计划内测试，不能把用户脏配置复制过去。密码真实连接/安全输入/故障/轮换/清理、旧KEY/TLS/LOCAL回归、PG16-alpine/MySQL8.4迁移注释与租户/CAS/审计测试、桌面1440x1000和移动390x844浏览器验收必需。检查项目容器残留，不全局清理。

所有新增Java注释/枚举/record字段/配置/SPI/生命周期/权限/异常规则按AGENTS落地中文说明；不要无关升级依赖或重构。技术原型失败须记录确切错误、平台、受影响验收及继续条件，继续独立工作，但不能把未运行或旧通过写为新验收。
同步维护这六份产物，实施后更新README、docs/skill-sandbox-endpoints.md、docs/release-notes.md。记录实际命令结果、最终文件及实现差异；最终按变更摘要、验证、影响、风险、直接相关后续建议交付。未提交需明确记录未提交。
```

## 当前清单摘要

| 编号 | 状态 | 目标及主要文件 | 依赖 | 验收条件与验证 |
|---|---|---|---|---|
| T0 | 完成 | 当前证据核对、范围和六份工作包 | 无 | 六份日期/主题/编号/链接一致；已有54个改动文件SHA256保持；仅新增文档 |
| T1 | 待做 | OpenSSH密码输入安全原型；DockerDaemonConnection.java，可信助手/IPC类为拟新增路径 | T0 | 在无TTY条件下单次密码认证、严格主机身份与Unix socket转发；密码不出现在参数/环境/临时文件/日志；Linux及Windows原生客户端兼容验证；错误或能力缺失明确拒绝，无回退 |
| T2 | 待做 | core领域/Repository、SandboxCredentials/Cipher、部署配置、memory/JDBC、双库新迁移 | T1 | sshAuthType默认KEY；旧v1密文可读；密码加密不回显、AAD防跨租户/资源复制；类型/材料原子CAS，轮换失效探测；两个数据库注释与升级测试 |
| T3 | 待做 | SandboxEndpointService/Controller、ManagedSkillSandbox与连接/错误治理 | T2 | 五项原管理权限继续有效；认证类型切换需credential:write；错误密码502、参数400、超时504等原因/编号对应安全日志；运行时按快照连接，轮换不改在途清理，不自动重试密码或回退 |
| T4 | 待做 | v2技能页sandbox-endpoints.js/css及必要HTML、Node测试 | T3 | SSH可选私钥/账号密码；密码框掩码、只写、不缓存，提交及切换/关闭即清空；元数据失败保留，材料清空；只读禁写；桌面/移动浏览器实际可见并走保存/测试/默认/轮换流程 |
| T5 | 待做 | 本轮专项与原回归；README、docs/skill-sandbox-endpoints.md、release-notes及本六份文档 | T3、T4 | ssh rocky指定Maven21容器完成密码真实连接、故障/轮换/隔离清理、PG16/MySQL8.4；旧KEY/TLS/LOCAL回归；Node及浏览器；精确记录命令、HEAD+覆盖哈希与残留 |

本提示词只描述未来实施范围。现阶段账号密码支持未实现、T1～T5未验证；本包范围与假设以清单/设计为准，用户明确新指令优先。


</details>
