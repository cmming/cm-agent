# 远程Docker账号密码认证实施计划

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

实施顺序T1→T2→T3→T4，T5贯穿并汇总；以上文件及证据为已实施记录，不重新排期。

## R1最终收尾核对

最终本地命令：mvn -q -pl cm-agent-server -am test -Dtest=SandboxSecurityTest,PasswordSshRelayTest,SandboxEndpointControllerTest,ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.agentscope.studio.enabled=false，退出0；四类30项通过，已覆盖旧四字段真实v1密文和4096/4097字节边界。Node最终92/92通过。

原技能沙箱六份及三份用户application配置共9个受保护文件SHA256与本轮实施前一致；工作包6份、无无效相对链接；本轮相关路径git diff --check退出0，暂存区为空、HEAD未变化。Rocky本项目cm-agent-password-*及cm-agent-skill-*容器查询为空，浏览器服务和本地SSH转发已释放。原application.yml末尾空行未改动。

代码实施没有剩余外部阻塞。生产主机接入、生产凭据、部署/重启及提交均未执行，仍需用户另行明确授权。浏览器手工更换密码未执行，凭据通过临时API预置，密码输入/失败清空/权限由Node事件测试及后端集成组合覆盖，不冒充生产凭据验收。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R1为准</summary>

# 远程Docker账号密码认证实施计划

日期：2026-10-01；主题：remote-docker-password-auth；修订：R0；工作模式：mode=default。

用户需求：“远程docker 新增账号密码的方式授权连接”。这是独立新增需求，引用既有[技能沙箱R1清单](../checklists/2026-09-30-skill-sandbox-checklist.md)及[原账本](../progress/2026-09-30-skill-sandbox-ledger.md)，保留原六份文档和未提交实现。本轮只做需求分析与六份文档；业务实施尚未授权，不提交、推送、合并、部署，不修改运行中服务。

Git基线：分支codex/skill-sandbox-r1，HEAD bc57060fac00c671cd247525b6082516194afd20。工作树包含既有沙箱R1未提交代码与用户无关修改；不能仅以HEAD代表已有R1代码，实施前必须核对工作树。暂存区为空。

关联文档：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md) · [提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md) · [设计](../specs/2026-10-01-remote-docker-password-auth-design.md) · [计划](../plans/2026-10-01-remote-docker-password-auth.md) · [实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md) · [账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)。

本计划未执行业务步骤；稳定任务编号与清单一致。

| 编号 | 状态 | 目标及主要文件 | 依赖 | 验收条件与验证 |
|---|---|---|---|---|
| T0 | 完成 | 当前证据核对、范围和六份工作包 | 无 | 六份日期/主题/编号/链接一致；已有54个改动文件SHA256保持；仅新增文档 |
| T1 | 待做 | OpenSSH密码输入安全原型；DockerDaemonConnection.java，可信助手/IPC类为拟新增路径 | T0 | 在无TTY条件下单次密码认证、严格主机身份与Unix socket转发；密码不出现在参数/环境/临时文件/日志；Linux及Windows原生客户端兼容验证；错误或能力缺失明确拒绝，无回退 |
| T2 | 待做 | core领域/Repository、SandboxCredentials/Cipher、部署配置、memory/JDBC、双库新迁移 | T1 | sshAuthType默认KEY；旧v1密文可读；密码加密不回显、AAD防跨租户/资源复制；类型/材料原子CAS，轮换失效探测；两个数据库注释与升级测试 |
| T3 | 待做 | SandboxEndpointService/Controller、ManagedSkillSandbox与连接/错误治理 | T2 | 五项原管理权限继续有效；认证类型切换需credential:write；错误密码502、参数400、超时504等原因/编号对应安全日志；运行时按快照连接，轮换不改在途清理，不自动重试密码或回退 |
| T4 | 待做 | v2技能页sandbox-endpoints.js/css及必要HTML、Node测试 | T3 | SSH可选私钥/账号密码；密码框掩码、只写、不缓存，提交及切换/关闭即清空；元数据失败保留，材料清空；只读禁写；桌面/移动浏览器实际可见并走保存/测试/默认/轮换流程 |
| T5 | 待做 | 本轮专项与原回归；README、docs/skill-sandbox-endpoints.md、release-notes及本六份文档 | T3、T4 | ssh rocky指定Maven21容器完成密码真实连接、故障/轮换/隔离清理、PG16/MySQL8.4；旧KEY/TLS/LOCAL回归；Node及浏览器；精确记录命令、HEAD+覆盖哈希与残留 |

## T1：先验证受控密码输入

核对Windows/Linux实际OpenSSH版本及ASKPASS能力，不读取用户SSH配置或密码。用项目专用一次性SSH/Docker夹具与临时生成测试密码构造无TTY原型；验证password-only、单次询问、严格host key及Unix socket转发。检查子进程参数/环境、临时目录与日志时只输出泄漏布尔结果，不输出真实或生成的认证材料。建立受限IPC/单次能力拒绝测试，处理并发、超时/中断、助手失败和打包后的启动路径。拟新增助手和IPC资源类路径须在验证后确定，不作为已存在文件。主机密码策略若仅支持keyboard-interactive/MFA，明确受控拒绝。

## T2：合同、材料、配置与仓储

在core/domain添加拟定SandboxSshAuthType并更新SandboxEndpoint/必要Repository调用；同步memory/JDBC映射。扩展SandboxCredentials和SandboxCredentialCipher兼容旧v1材料，测试总字节限额、空格/Unicode、不回显、防密文跨tenant/id/version复制。新增部署ssh-auth-type/password-file配置，入口只在Server读取可信Secret引用。迁移采用执行时核实的下一版本，双库默认KEY及中文原生注释；不改V16。新增属性、枚举、record组件、安全/生命周期及SPI注释按AGENTS逐一落地。

## T3：管理与执行治理

修改SandboxEndpointService、SandboxEndpointController、DockerDaemonConnection、ManagedSkillSandbox及探测桥接中材料构造/固定快照。KEY↔PASSWORD切换与完整材料更新做同事务CAS，凭据权限与严格审计不可绕过；探测网络不进入事务，回写原revision。部署和租户端点走相同认证策略；旧默认保持KEY。保留原并发/超时/输入输出上限和同daemon清理，错误日志只在最终边界记录一次。同errorId的响应与日志、参数状态、认证失败、未知异常堆栈与密码原值脱敏均补测试。

## T4：控制台与可见性

修改console/v2/assets/sandbox-endpoints.js及必要css/skills.html，复用既有列表—详情—操作结构，不新增独立宽泛导航。认证类型选择+掩码密码框、轮换说明、known_hosts输入；保存、测试、默认选择与错误展示沿用原流程。扩展clearSecrets及payload，防止空字段覆盖已保存密码。Node覆盖类型选择、创建必填、省略保留、切换要求新材料、失败清空/元数据保留、只读权限及旧响应默认KEY。

本次用户此前报告“沙箱端点”不可见，当前诊断未完成。实施时先核对源HTML、target/classes或打包JAR、实际GET页面/JS及用户入口是否一致。测试用独立服务完成，不重启或修改用户正在运行的8080服务；需要用户更新运行实例时只给明确步骤，不擅自操作。

## T5：验证与文档

1. 本地先java -version、mvn -v确认JDK21；快速执行mvn -q -pl cm-agent-server -am test -Dtest=SandboxSecurityTest,ManagedSkillSandboxTest,SandboxEndpointControllerTest,DockerSkillSandboxTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.agentscope.studio.enabled=false。新增助手/IPC测试的实际类名在T1确定后补入；不得用旧测试名冒充新密码覆盖。
2. 执行node --test cm-agent-console/src/test/js/*.test.cjs；浏览器验收1440×1000与390×844，验证入口可见、掩码、只读、保存→探测→默认→轮换→重新探测，失败保留元数据/清空密码，页面errorId能关联后台。
3. Docker/JDBC/Flyway/Testcontainers只ssh rocky。先确认Docker可用、maven:3.9.9-eclipse-temurin-21的Maven/JDK21版本、本地/远程HEAD一致，并用明确覆盖路径SHA256匹配当前未提交源码，隔离工作区不得复制脏配置或凭据。在可信Maven容器启用CM_AGENT_TEST_SANDBOX=true、CM_AGENT_TEST_REMOTE_SANDBOX=true，再mvn -q -pl cm-agent-server -am clean test -DargLine=-Xmx384m -Dcm-agent.agentscope.studio.enabled=false。宿主socket仅供可信测试器管理项目Testcontainers，技能容器不得挂载。
4. 新密码夹具必须覆盖部署和租户入口、密码错误、host key错误且助手未供密、远端禁用密码/禁止转发/无socket权限、超时、输出超量、中断、断连、并发与零残留；生成测试密码含空格/Unicode，单次尝试和材料不泄漏。旧SSH KEY、TLS及LOCAL真实回归。
5. PostgreSQL16-alpine/MySQL8.4验证旧数据升级默认KEY、新字段原生注释、CAS/tenant隔离、审计回滚与默认唯一。不删除用户服务/容器/卷/镜像，不全局清理。
6. 实施后更新README、docs/skill-sandbox-endpoints.md、release-notes及本六份记录；源码/文档/静态资源一致。运行产物更新是后续操作授权，不把打包当作部署。

环境失败必须记录确切命令、错误、影响验收和继续条件；技术原型不通过不得标T1完成。当前尚未发生本需求外部测试失败。


</details>
