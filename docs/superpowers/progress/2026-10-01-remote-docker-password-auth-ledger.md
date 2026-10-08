# 远程Docker账号密码认证进度账本

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
## 本轮验证证据

1. 本地Microsoft JDK21.0.11、Maven3.9.4。快速单元通过；最终补充旧v1实际密文/4096字节边界后重跑SandboxSecurityTest、PasswordSshRelayTest、SandboxEndpointControllerTest、ConsoleResourceTest，结果见收尾记录。
2. Rocky Docker23.0.6，maven:3.9.9-eclipse-temurin-21内Maven3.9.9/JDK21.0.7；隔离工作区/root/cm-agent-sandbox-r1-bc57060-20261001。HEAD与本地相同；仅明确覆盖项目文件，排除用户application.yml/mysql/ok及私有目录，覆盖SHA256核验。
3. 全量命令：mvn -q -pl cm-agent-server -am clean test -DargLine=-Xmx384m -Dcm-agent.agentscope.studio.enabled=false；环境CM_AGENT_TEST_SANDBOX=true、CM_AGENT_TEST_REMOTE_SANDBOX=true、CM_AGENT_TEST_SSH_PACKAGE_DIR=/workspace/validation-ssh-packages、TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1。通过，退出0。可信验证容器挂项目目录、Maven缓存、Docker CLI/socket管理Testcontainers；技能与嵌套daemon无宿主socket挂载。
4. 报告汇总931项、0失败、0错误；已含独立浏览器夹具报告（并行执行，不能将它算为全量命令中的普通单元）。Linux跳过Windows条件专项1项；Windows单独实际运行1项通过。模块汇总adapter87、console14、core91、persistence60、server674、starter5；报告累计930通过/1跳过。
5. 真实远程三项通过：私钥、双向TLS及密码。PASSWORD覆盖空格/中文口令、探测/默认/执行/输出限额/超时/中断/零容器残留、轮换固定原连接、旧密码与错误host key拒绝、sshd禁密码、禁Unix转发、断连失败不回退。双库专项JDBC2项+Migration3项通过，验证V16→V17默认KEY及逐表逐字段中文注释。
6. Windows命令：CM_AGENT_TEST_PASSWORD_WINDOWS=true时mvn -q -pl cm-agent-server -am test -Dtest=PasswordSshWindowsFixtureTest -Dsurefire.failIfNoSpecifiedTests=false，并提供测试夹具名称/公开端口/公开主机公钥。仅ssh rocky创建一次性Docker夹具，密码随机生成后经chpasswd stdin传入；Windows读取Docker/_ping成功、轮换在途连接保留、旧密码拒绝；夹具已删除。本机未运行Docker。
7. Node：node --test cm-agent-console/src/test/js/*.test.cjs，92项全部通过。包括真实页面事件编排的掩码/密码原样传递、提交失败清空材料但保留元数据、认证切换强制新材料、只读/部署关闭/密钥未就绪禁写。
8. 独立浏览器夹具SandboxBrowserFixtureTest与ConsoleResourceTest运行退出0；端口18097、test/memory、未修改8080用户服务。桌面1440×1000与移动390×844验证；移动客户区375且scrollWidth375无横向溢出。密码端点由API预置，省略凭据名称更新后revision2/credentialVersion1，失败显示503/稳定code/errorId并阻止默认；本地保存/探测/默认成功。浏览器日志未见脚本错误。截图位于本机临时目录cm-agent-password-desktop.png和cm-agent-password-mobile.png，不加入版本控制。

## 失败、修复与外部条件

- 首轮双库迁移测试写死总数16，随V17更新到17并增加V16旧SSH记录升级断言，重跑通过。
- Rocky时钟滞后导致新JSch Maven中央证书PKIX有效期拒绝。通过本机正常TLS下载的JSch jar/pom填入测试Maven缓存，两端SHA256一致，未关闭证书校验或改宿主时钟。jar SHA256：4819f453e5d3dc277be2b91d49a0f87b7c4db36dc35c72c0d4bfeba56d2f94bd；pom SHA256：2c2893c00f354ba6dae2a64800d7fd5989a2763d051d4a9f0b878c9216bc0c46。
- 初次密码探测超时：通道IO需先装配、缓冲请求需flush；实际复测和全量通过，不以首轮认证成功冒充Docker探测成功。
- 全页截图曾超时；改为当前视口截图成功。临时标签显式close被浏览器权限拒绝，未标记为交付或handoff，交由自动临时标签清理；已恢复默认视口。测试服务、SSH转发与Windows夹具已释放。
- git diff --check的全工作树存在用户application.yml末尾空行，保持原样；本轮相关路径另做检查。没有目标生产凭据/服务接入验证，部署不是本轮授权范围，不构成代码实施外部阻塞。

## R1最终收尾核对

最终本地命令：mvn -q -pl cm-agent-server -am test -Dtest=SandboxSecurityTest,PasswordSshRelayTest,SandboxEndpointControllerTest,ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.agentscope.studio.enabled=false，退出0；四类30项通过，已覆盖旧四字段真实v1密文和4096/4097字节边界。Node最终92/92通过。

原技能沙箱六份及三份用户application配置共9个受保护文件SHA256与本轮实施前一致；工作包6份、无无效相对链接；本轮相关路径git diff --check退出0，暂存区为空、HEAD未变化。Rocky本项目cm-agent-password-*及cm-agent-skill-*容器查询为空，浏览器服务和本地SSH转发已释放。原application.yml末尾空行未改动。

代码实施没有剩余外部阻塞。生产主机接入、生产凭据、部署/重启及提交均未执行，仍需用户另行明确授权。浏览器手工更换密码未执行，凭据通过临时API预置，密码输入/失败清空/权限由Node事件测试及后端集成组合覆盖，不冒充生产凭据验收。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R1为准</summary>

# 远程Docker账号密码认证进度账本

日期：2026-10-01；主题：remote-docker-password-auth；修订：R0；工作模式：mode=default。

用户需求：“远程docker 新增账号密码的方式授权连接”。这是独立新增需求，引用既有[技能沙箱R1清单](../checklists/2026-09-30-skill-sandbox-checklist.md)及[原账本](../progress/2026-09-30-skill-sandbox-ledger.md)，保留原六份文档和未提交实现。本轮只做需求分析与六份文档；业务实施尚未授权，不提交、推送、合并、部署，不修改运行中服务。

Git基线：分支codex/skill-sandbox-r1，HEAD bc57060fac00c671cd247525b6082516194afd20。工作树包含既有沙箱R1未提交代码与用户无关修改；不能仅以HEAD代表已有R1代码，实施前必须核对工作树。暂存区为空。

关联文档：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md) · [提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md) · [设计](../specs/2026-10-01-remote-docker-password-auth-design.md) · [计划](../plans/2026-10-01-remote-docker-password-auth.md) · [实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md) · [账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)。

## 需求与决定来源

| 记录 | 类别 | 内容与来源 | 采用状态 |
|---|---|---|---|
| N0-01 | 新增 | 用户新增远程Docker账号密码授权连接；原R1仅SSH私钥/TLS | 纳入本包T1～T5，尚未实施 |
| N0-02 | 澄清 | A1将账号密码解释为SSH主机认证；复用原socket隧道，不新增daemon账号体系 | 默认模式自主假设，未冒充用户确认 |
| N0-03 | 调整 | 新增sshAuthType/密码材料与Secret引用；旧缺省KEY及旧v1密文兼容 | 方案已记录，待T2/T3验证 |
| N0-04 | 新增 | 控制台密码认证与轮换流程、单次认证、安全输入/清理和跨平台证据 | 待T1/T4/T5 |
| N0-05 | 保留 | 原R1的租户、权限、审计、TLS/KEY/LOCAL、固定在途连接与R0安全上限 | 保留原源码/文档/历史，不重复实现 |
| N0-06 | 澄清 | 用户此前反馈入口不可见，诊断被中断；本包不声称用户8080实例已更新 | 作为T4/T5实际资源一致性检查；不修改运行服务 |

## 范围与自主假设

| 编号 | 类型/状态 | 内容、依据与影响 |
|---|---|---|
| A1 | 默认模式自主假设，未获用户单独确认 | “账号密码”解释为远程Linux主机SSH用户名/密码，通过SSH访问Docker Unix socket；沿用既有SSH拓扑。Docker Registry登录与反向代理HTTP Basic认证不在本轮范围。若用户后续明确指向其他方式，修订范围并保留本假设来源。 |
| A2 | 工程方案，待实现验证 | mode仍为SSH；新增sshAuthType=KEY/PASSWORD，省略默认KEY，旧部署/旧API/旧密文按KEY处理；LOCAL/TLS不得使用PASSWORD。对外返回认证类型及hasCredential状态，密码与密文永不回显。 |
| A3 | 工程方案，待实现验证 | 密码非空，原样使用、不trim，UTF-8最多4096字节，拒绝NUL/CR/LF以适配单次输入协议；全部材料继续受现有128KiB总限额。只有credentials整体省略且类型不变表示保留，空密码不表示清除。 |
| A4 | 工程方案，待兼容性验证 | 优先复用原生OpenSSH隧道，加可信ASKPASS助手和受限短期IPC；不把密码放在进程参数、URL、子进程环境、临时文件或脚本中。Windows/Linux可用性及助手包装方式须先验证，未证实不能承诺跨平台支持。 |
| A5 | 范围约束 | 部署者自行保证远程账户允许SSH密码认证、Unix socket转发及Docker访问。只覆盖普通password认证，不自动处理键盘交互、MFA、sudo密码、强制修改密码，不修改远端sshd或docker配置。 |
| A6 | 继承约束 | 保留租户隔离、精确目标允许清单、严格known_hosts、独立凭据权限/审计、默认选择、固定在途连接及全部R0容器限制。默认仍为KEY；不得因增加PASSWORD自动换认证方式或连接主机。 |

## 任务状态及证据

| 编号 | 状态 | 目标及主要文件 | 依赖 | 验收条件与验证 |
|---|---|---|---|---|
| T0 | 完成 | 当前证据核对、范围和六份工作包 | 无 | 六份日期/主题/编号/链接一致；已有54个改动文件SHA256保持；仅新增文档 |
| T1 | 待做 | OpenSSH密码输入安全原型；DockerDaemonConnection.java，可信助手/IPC类为拟新增路径 | T0 | 在无TTY条件下单次密码认证、严格主机身份与Unix socket转发；密码不出现在参数/环境/临时文件/日志；Linux及Windows原生客户端兼容验证；错误或能力缺失明确拒绝，无回退 |
| T2 | 待做 | core领域/Repository、SandboxCredentials/Cipher、部署配置、memory/JDBC、双库新迁移 | T1 | sshAuthType默认KEY；旧v1密文可读；密码加密不回显、AAD防跨租户/资源复制；类型/材料原子CAS，轮换失效探测；两个数据库注释与升级测试 |
| T3 | 待做 | SandboxEndpointService/Controller、ManagedSkillSandbox与连接/错误治理 | T2 | 五项原管理权限继续有效；认证类型切换需credential:write；错误密码502、参数400、超时504等原因/编号对应安全日志；运行时按快照连接，轮换不改在途清理，不自动重试密码或回退 |
| T4 | 待做 | v2技能页sandbox-endpoints.js/css及必要HTML、Node测试 | T3 | SSH可选私钥/账号密码；密码框掩码、只写、不缓存，提交及切换/关闭即清空；元数据失败保留，材料清空；只读禁写；桌面/移动浏览器实际可见并走保存/测试/默认/轮换流程 |
| T5 | 待做 | 本轮专项与原回归；README、docs/skill-sandbox-endpoints.md、release-notes及本六份文档 | T3、T4 | ssh rocky指定Maven21容器完成密码真实连接、故障/轮换/隔离清理、PG16/MySQL8.4；旧KEY/TLS/LOCAL回归；Node及浏览器；精确记录命令、HEAD+覆盖哈希与残留 |

## 实际执行与验证

- 读取AGENTS.md、requirement-workpack/SKILL.md及产物契约，核对Server/Console POM和相关源码、配置、生产说明及R1记录。
- git status --short、git branch --show-current、git rev-parse HEAD、git diff --cached --name-only：当前codex/skill-sandbox-r1，HEAD bc57060fac00c671cd247525b6082516194afd20，既有R1及用户改动存在，暂存区空。
- codegraph explore先查相关SSH连接类型，未返回新沙箱匹配源码，回退直接读当前文件和rg定向查询。未重新建立索引。
- 官方Docker/OpenSSH文档只提供协议和提示机制依据；Windows客户端实际能力未测试，不由官方手册推断已兼容。
- 生成后核对六份文档日期/主题/任务/链接/围栏，任务状态只有T0完成；新增文档前后54个既有改动文件SHA256一致，含原六份工作包、业务源码及用户配置。仅本包六份文件新增，diff检查无新增空白错误。核验记录完成后再交付，不以文档生成冒充实现完成。
- 未运行Maven/Node/SSH容器/双库/浏览器业务测试：本轮仅需求工作包；历史R1证据不计入密码认证验收。

## 未验证项、阻塞与后续

T1～T5均待做，非完成。当前无已实际触发的外部阻塞；ASKPASS跨平台启动/IPC限制、目标主机密码策略/转发/socket权限、运行产物一致性为未验证项。后续实施先完成T1安全原型；失败记录实际错误和受影响平台，不静默降级或让用户提供真实凭据。真实生产验证凭据不保存、不打印、不写文档，项目夹具仅临时生成测试认证材料。

已存在的用户application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/改动保持；R1源码/工作包未覆盖。此次未提交、未推送、未合并、未部署，未启动/停止用户服务；原“不提交、不推送、不合并、不部署”约束继续有效。


</details>
