# 远程Docker账号密码认证实现说明

工作包日期：2026-10-01；主题：remote-docker-password-auth；当前修订：R2；修订日期：2026-11-13；mode=default。

R0/R1 历史内容保留在文件末尾。R2 实现说明只记录实际改动和与设计的差异；测试结果、任务状态和提交信息只在账本维护。

## 实际改动

- 认证领域增加 KEY/PASSWORD 类型；PASSWORD 只存在于凭据材料并以密文持久化，旧记录缺省 KEY。
- 密码连接使用 JSch 内存传输，严格 known_hosts、固定解析地址和固定 Docker Unix socket，不提供私钥或本地路径回退。
- 新增部署密码文件引用、服务/API 认证类型、凭据 CAS、探测失效、运行快照和错误治理。
- 控制台增加认证类型选择和只写密码字段；类型切换、关闭和提交后清空敏感输入。
- 数据库保持 V16 不变，新增 V17 的认证类型字段和双数据库原生注释。
- R2 文档迁移把 R0/R1 状态与验证从非账本文件的当前职责中移出，完整证据集中在进度账本。

## 设计差异

当前实现使用 JSch 内存密码传输，未采用 R0 中优先评估的原生 OpenSSH ASKPASS/IPC 助手。业务行为和限制未在本轮重新验证；证据适用范围由账本记录。

## 当前状态与证据

当前修订的状态、验证和提交信息只以[进度账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)为准。其他入口：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md)、[设计](../specs/2026-10-01-remote-docker-password-auth-design.md)、[计划](../plans/2026-10-01-remote-docker-password-auth.md)、[提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R2为准</summary>

# 远程Docker账号密码认证当前实现说明

日期：2026-10-01；主题：remote-docker-password-auth；修订：R0；工作模式：mode=default。

用户需求：“远程docker 新增账号密码的方式授权连接”。这是独立新增需求，引用既有[技能沙箱R1清单](../checklists/2026-09-30-skill-sandbox-checklist.md)及[原账本](../progress/2026-09-30-skill-sandbox-ledger.md)，保留原六份文档和未提交实现。本轮只做需求分析与六份文档；业务实施尚未授权，不提交、推送、合并、部署，不修改运行中服务。

Git基线：分支codex/skill-sandbox-r1，HEAD bc57060fac00c671cd247525b6082516194afd20。工作树包含既有沙箱R1未提交代码与用户无关修改；不能仅以HEAD代表已有R1代码，实施前必须核对工作树。暂存区为空。

关联文档：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md) · [提示词](../prompts/2026-10-01-remote-docker-password-auth-prompt.md) · [设计](../specs/2026-10-01-remote-docker-password-auth-design.md) · [计划](../plans/2026-10-01-remote-docker-password-auth.md) · [实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md) · [账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)。

## 本轮实际交付

已完成T0：读取适用规范和技能契约、核对当前Git/工作树、源码、配置、旧工作包及官方协议说明，生成本工作包六份中文文档。此次只新增这些文档，没有修改业务源码、POM、配置、迁移、控制台或生产部署说明，未执行计划/提示词内的业务命令。

当前远程Docker仍只有SSH私钥和双向TLS；账号密码连接尚未实现。新增sshAuthType、password材料、password-file、可信ASKPASS助手、IPC及迁移均是设计内容，不是实际代码。原R1实现和原六份记录保持不动，不把旧测试通过作为新认证验收。

## 实际源码限制

现有DockerDaemonConnection#ssh固定BatchMode=yes与私钥；SandboxEndpointService要求privateKey；SandboxCredentials没有password；DockerConnectionProperties没有密码引用；前端没有认证类型选择/密码材料。需后续T1～T4整体扩展，不能只加页面字段即声称支持密码。

## 方案差异与未验证内容

A1按SSH主机密码解释用户请求，并非用户单独确认；若需求指向HTTP代理Basic或Registry，需重新界定，不能复用本验收。A4的ASKPASS/受限IPC只是优先候选；Windows兼容和打包启动未验证，尚未选定新依赖。数据库新迁移号执行时核实，不提前修改V16或创建迁移。密码字段兼容既有v1材料必须由后续实际测试证明。

此前“沙箱端点”页面可见性诊断被中断；当前浏览器标签存在不能证明运行实例已更新。本包将实际资源一致性检查纳入T4/T5，尚未解决或验证该反馈。

## 当前状态

| 编号 | 状态 | 目标及主要文件 | 依赖 | 验收条件与验证 |
|---|---|---|---|---|
| T0 | 完成 | 当前证据核对、范围和六份工作包 | 无 | 六份日期/主题/编号/链接一致；已有54个改动文件SHA256保持；仅新增文档 |
| T1 | 待做 | OpenSSH密码输入安全原型；DockerDaemonConnection.java，可信助手/IPC类为拟新增路径 | T0 | 在无TTY条件下单次密码认证、严格主机身份与Unix socket转发；密码不出现在参数/环境/临时文件/日志；Linux及Windows原生客户端兼容验证；错误或能力缺失明确拒绝，无回退 |
| T2 | 待做 | core领域/Repository、SandboxCredentials/Cipher、部署配置、memory/JDBC、双库新迁移 | T1 | sshAuthType默认KEY；旧v1密文可读；密码加密不回显、AAD防跨租户/资源复制；类型/材料原子CAS，轮换失效探测；两个数据库注释与升级测试 |
| T3 | 待做 | SandboxEndpointService/Controller、ManagedSkillSandbox与连接/错误治理 | T2 | 五项原管理权限继续有效；认证类型切换需credential:write；错误密码502、参数400、超时504等原因/编号对应安全日志；运行时按快照连接，轮换不改在途清理，不自动重试密码或回退 |
| T4 | 待做 | v2技能页sandbox-endpoints.js/css及必要HTML、Node测试 | T3 | SSH可选私钥/账号密码；密码框掩码、只写、不缓存，提交及切换/关闭即清空；元数据失败保留，材料清空；只读禁写；桌面/移动浏览器实际可见并走保存/测试/默认/轮换流程 |
| T5 | 待做 | 本轮专项与原回归；README、docs/skill-sandbox-endpoints.md、release-notes及本六份文档 | T3、T4 | ssh rocky指定Maven21容器完成密码真实连接、故障/轮换/隔离清理、PG16/MySQL8.4；旧KEY/TLS/LOCAL回归；Node及浏览器；精确记录命令、HEAD+覆盖哈希与残留 |

## 验证与提交

本轮只执行文档检查：六份链接/编号/日期/模式、Markdown围栏、新增差异和54个既有改动文件哈希保护核验。结果由账本记录。未运行Maven、Node、Docker/JDBC/Flyway或浏览器业务测试；原因是本轮仅规划且没有新增业务实现。未提交、未推送、未合并、未部署；业务验收仍待做。


</details>
