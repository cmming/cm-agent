# 远程Docker账号密码认证实施计划

工作包日期：2026-10-01；主题：remote-docker-password-auth；当前修订：R2；修订日期：2026-11-13；mode=default。

R0/R1 历史内容保留在文件末尾。R2 计划只描述实施顺序、涉及位置和验证方法，不记录完成状态或测试结论。

## 实施顺序

| 编号 | 实施内容 | 主要位置 | 验证方法 |
|---|---|---|---|
| T1 | 实现 SSH 密码认证与 socket 中转 | `DockerDaemonConnection`、密码中转相关类 | 连接、主机信任、超时、取消、并发和资源清理测试 |
| T2 | 扩展领域、凭据、配置与持久化 | core、sandbox service、JDBC、双数据库迁移 | 旧数据兼容、加密 AAD、CAS、双库迁移和原生注释测试 |
| T3 | 接入服务、API、探测和运行治理 | `SandboxEndpointService`、Controller、运行编排 | 权限、租户、审计、错误码和快照固定测试 |
| T4 | 扩展 v2 控制台 | `sandbox-endpoints.js`、页面资源、Node 测试 | 认证选择、只写字段、清空语义、只读和失败场景 |
| T5 | 完成分层验证和发布边界检查 | 测试、README、运维和发布说明 | 本地 Java/Node、Rocky 容器、双库和浏览器验证 |
| T6 | 迁移六份文档职责 | 本工作包六份文件 | 非账本文件无新增状态表，历史原文保留 |
| T7 | 校验链接和状态目录 | `WORKPACK-STATUS.md`、全部相对链接 | 修订、任务、链接和空白检查 |

## 当前状态与证据

当前修订的状态、命令结果、验证证据和提交信息只以[进度账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)为准。相关入口：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md)、[设计](../specs/2026-10-01-remote-docker-password-auth-design.md)、[提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md)、[实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R2为准</summary>

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
