# 远程Docker账号密码认证执行提示词

工作包日期：2026-10-01；主题：remote-docker-password-auth；当前修订：R2；修订日期：2026-11-13；mode=default。

以下是 R2 文档职责迁移的执行输入。它只描述本轮文档整理；R0/R1 原始提示词保留在文件末尾，不自动执行。

## 当前执行输入（R2）

```text
仓库：F:/java/cm-agent
范围：将 2026-10-01-remote-docker-password-auth 六份历史工作包迁移到新契约。

先读取清单、设计、计划、实现说明、进度账本、AGENTS.md 和当前 Git 状态。保护工作树中的其他修改，不修改业务代码，不重做 T1–T5 已形成的业务结论。

保留 R0/R1 历史材料。把清单收敛为任务、依赖和验收；设计保留当前方案与决定；计划保留实施顺序和验证方法；提示词只保留可执行输入；实现说明只记录实际改动及设计差异。任务实时状态、命令结果、测试证据、阻塞和提交信息只写入账本。其他五份只在末尾链接账本。

更新六份文档的当前修订为 R2，并更新 docs/superpowers/WORKPACK-STATUS.md。完成后检查任务编号、相对链接、代码围栏、空白、重复状态段和受保护文件；不提交、不推送、不部署。
```

## 当前状态与证据

当前修订的状态、验证和提交信息只以[进度账本](../progress/2026-10-01-remote-docker-password-auth-ledger.md)为准。其他入口：[清单](../checklists/2026-10-01-remote-docker-password-auth-checklist.md)、[设计](../specs/2026-10-01-remote-docker-password-auth-design.md)、[计划](../plans/2026-10-01-remote-docker-password-auth.md)、[实现说明](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留2026-10-01需求分析原文，当前以R2为准</summary>

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
