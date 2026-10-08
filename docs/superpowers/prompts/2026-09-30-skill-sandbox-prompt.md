# 技能沙箱执行提示词

当前修订：R1；修订日期：2026-10-01；工作模式：mode=default。工作包日期与主题沿用2026-09-30 / skill-sandbox。

本轮依据用户明确指令实际实施T6～T11。当前分支codex/skill-sandbox-r1，基线HEAD为bc57060fac00c671cd247525b6082516194afd20；R0已交付提交938d6fd35ee2a4f36f990c9617746e48da503787保持。未提交、未推送、未合并、未部署。下方R0历史正文原样保留，其旧授权与测试结果只属于R0。

已核对AGENTS.md、相关POM/README/配置、六份工作包、Git状态及当前源码。CodeGraph先查但未匹配沙箱符号，按当前源码补核。未改动用户application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/或运行中服务。远程验证使用同一HEAD加本任务明确文件覆盖，不复制用户脏配置或真实凭据。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)。生产说明见[沙箱端点部署与管理](../../skill-sandbox-endpoints.md)。

## R1 当前执行上下文

| 编号 | 状态 | 实际交付 | 依赖 | 验收证据 |
|---|---|---|---|---|
| T5 | 完成 | R1证据复核、D1～D6交互确认与原六份文档维护 | R0 | 保留修订来源和R0历史，最新实施授权与禁止操作明确 |
| T6 | 完成 | Core后端SPI、唯一注册标识、统一限额、固定单次句柄 | T5 | 未知/重复后端拒绝；全局并发、输入输出、扩展超时及幂等关闭测试 |
| T7 | 完成 | LOCAL、SSH隧道、内存双向TLS中继及固定daemon清理 | T6 | 两个独立daemon真实执行、身份拒绝、轮换、超时、中断、断连、输出超量与零残留 |
| T8 | 完成 | 租户管理/探测API、独立权限、严格审计、运行默认选择和错误诊断 | T6、T7、T10 | MockMvc认证/权限/租户/CAS/凭据不回显/日志编号，治理与真实路由回归 |
| T9 | 完成 | 本轮Java、Node、双库、真实SSH/TLS、浏览器及交付核验 | T7、T8、T10、T11 | 完整回归与最终受影响复验：921项Java通过，1项条件跳过；Node89通过，浏览器另行实际验收 |
| T10 | 完成 | 租户领域/Repository、AES/GCM、memory/JDBC与V16双库迁移 | T6 | 两库CAS、跨租户、审计事务回滚、默认唯一并发及逐表字段原生中文注释 |
| T11 | 完成 | v2技能页沙箱端点列表/详情/编辑/探测/默认/删除与凭据轮换输入 | T8、T10 | 桌面1440×1000、移动390×844实测；操作流程、失败保留元数据/清空材料、同编号提示 |

本提示词描述同一R1任务，不构成新的修改或外部操作授权。T6～T11已按用户本轮指令实施；继续时先读取清单和账本最新证据，完成项不重复实现。纯问答只读取文件，不自动运行下列命令或修改仓库。

## 可直接交给执行者的提示词

请在F:/java/cm-agent读取本提示词、同日期skill-sandbox清单、设计、计划、实现说明和账本，核对AGENTS.md、当前Git分支/HEAD/脏文件及实际源码。遵循R1的D1～D6，不重做R0或已完成项。

支持本地Docker、通过SSH或双向TLS连接远程Docker daemon，并保留通用扩展接口。部署者定义允许目标和执行安全上限，租户管理员管理本租户端点和加密凭据；每租户一个默认端点，后续调用取最新选择，执行中的调用保持原连接。

保留R0：默认关闭、固定版本Python与文本资源、Run/主体/tenant/版本授权、撤销复核、预算、防重、严格审计与TEST发布门禁。容器固定禁网、非root、只读根、无宿主/socket挂载、cap-drop=ALL、no-new-privileges、CPU0.5、内存/交换各128MiB、PID32、nofile64，两个16MiB私有tmpfs；执行默认15秒、输入输出各32768字节、实例并发最多2，只能收紧。

未新增HTTP/E2B/Kubernetes/任意SSH主机命令后端、Agent独立端点绑定、联网安装、持久工作区、文件导出、跨实例任务接管、脚本重放或共享daemon全局调度。

依赖顺序T6 → T10/T7 → T8 → T11 → T9。按清单处理未完成或新发现的回归，不能以文档生成替代代码/验证。保持codex/skill-sandbox-r1和mode=default；自主处理工程细节，不再次询问已确认决定。本轮明确禁止提交、推送、合并、部署；不修改用户运行中的服务。

保留用户application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/及所有无关修改。Java注释/record组件/枚举/生命周期/权限/SPI必须按仓库规则落地中文说明；Controller不访问数据库或保存密钥，tenant来自认证，Repository查询与写入带tenant。新迁移不得修改发布历史，PostgreSQL/MySQL逐表字段原生中文注释保持一致。

维护固定版本Python、禁网非root容器、安全限额、Run授权、预算、防重、撤销、严格审计和TEST门禁。SSH只转发Docker socket，TLS严格双向认证；不继承全局客户端配置，不回退其他主机或宿主解释器。端点/凭据/默认属于租户，配置CAS，当前成功探测门禁和固定执行连接必须同时成立。凭据加密落库且不回显，材料提交即清空，不记录真实认证或原始输入输出；响应与日志保持同一errorId。

本地JDK21/Maven3.9.4已确认；实际快速命令：mvn -q -pl cm-agent-server -am "-Dtest=ManagedSkillSandboxTest,SandboxSecurityTest,SandboxEndpointControllerTest,GovernedSkillAccessServiceTest,DockerSkillSandboxTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test；通过。Node命令node --test cm-agent-console/src/test/js/*.test.cjs，89项通过，零失败。

Docker/JDBC/Flyway/Testcontainers仅ssh rocky执行：Docker23.0.6，maven:3.9.9-eclipse-temurin-21，Maven3.9.9/JDK21.0.7。隔离工作区/root/cm-agent-sandbox-r1-bc57060-20261001，HEAD与本地bc57060一致，最终代码及文档覆盖51个明确路径并逐个SHA256核对。用户脏application配置未复制。

最终完整命令：在上述Maven容器中mvn -q -pl cm-agent-server -am clean test -DargLine=-Xmx384m -Dcm-agent.agentscope.studio.enabled=false；显式启用CM_AGENT_TEST_SANDBOX=true、CM_AGENT_TEST_REMOTE_SANDBOX=true，CM_AGENT_TEST_SSH_PACKAGE_DIR=/workspace/validation-ssh-packages，TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1。宿主socket/CLI仅供可信验证器管理项目Testcontainers，技能容器和两个远程daemon无宿主socket挂载。首次完整回归920项中唯一失败为AuthControllerTest旧17项权限断言，修正为22项并显式检查五项新增权限。V16默认表最后补充创建/更新时间后，按最终源码运行AuthControllerTest、SandboxEndpointControllerTest、MigrationTest、JdbcSandboxEndpointRepositoryTest共21项受影响复验，全部通过。最后追加GovernedSkillAccessServiceTest事务外策略复核回归并运行治理/注册器16项通过；只读接口用例扩充后MockMvc4项通过。完整与各次受影响复验的最终报告汇总为922项，不能写成一次完整命令exit0。条件跳过仅为浏览器专用夹具，已另行启动实际服务完成浏览器验收。

真实SSH/TLS专项RemoteDockerSandboxIntegrationTest两项通过：各用专用docker:23-dind daemon/vfs导入可信Python镜像；临时生成认证，管理探测/默认/执行均走实际传输。覆盖固定非root隔离、凭据轮换旧连接固定、新调用探测门禁、超量、超时、中断、错误host key/CA/服务端身份/过期客户端证书、断连及受控清理失败；成功、超时、中断后按本项目随机名称检查零残留。

两库专项JdbcSandboxEndpointRepositoryTest和MigrationTest已通过，最终clean test再次覆盖；镜像PostgreSQL16-alpine与MySQL8.4。浏览器专用SandboxBrowserFixtureTest独立端口18097，仅test/memory，已自动释放；Chrome任务会话和本地SSH转发已关闭。截图保存在本机临时验证目录，非版本控制文件。

首次夹具失败已修复：withCommand(String)拆分脚本，改String[]；后台daemon的父进程生命周期与dind初始化；Rocky时钟落后导致官方APK网络下载验证失败，使用当前本机可信HTTPS下载并保持Alpine签名校验，不更改宿主时钟；tmpfs所有者与中断清理真实缺陷。早期失败不算最终通过，日志保存在隔离验证目录。

若外部环境阻塞，记录确切错误、影响任务、未执行测试及恢复条件，继续可独立完成项；不能使用R0结果充当R1验证。实际代码或测试修正后复验受影响检查，不无故重复全量。结束前更新这六份原文档，保留R0历史、决定与修订来源；输出实际变更、命令结果、T6～T11状态、剩余阻塞和运行限制。暂存区保持不变。

## R1 决定与交付边界

| 决定 | 问题与候选选项 | 状态 | 用户选择、来源与影响 |
|---|---|---|---|
| D1 | A 本地/远程 Docker + 扩展接口；B Docker + HTTP 服务；C Docker + SSH 执行机 | 已确认 | 用户选择 A；只实现 Docker 后端，其他方式保留扩展接口 |
| D2 | A SSH + 双向 TLS；B 仅 SSH；C 仅双向 TLS | 已确认 | 用户选择 A；T7/T9 同时覆盖两种安全连接 |
| D3 | A 仅部署配置；B 部署配置 + 控制台管理 | 已确认 | 用户选择 B；增加端点 API、持久化、权限审计、凭据存储与运行期调整 |
| D4 | A 租户隔离且部署地址白名单；B 平台统一管理共享端点 | 已确认 | 用户选择 A；端点、默认设置及凭据均归属于租户，禁止跨租户使用 |
| D5 | A 加密落库；B 仅外部密钥引用 | 已确认 | 用户选择 A；私钥/证书经服务端加密保存，主密钥仅来自部署环境 |
| D6 | A 每租户一个默认端点；B Agent 指定端点 | 已确认 | 用户选择 A；后续技能调用使用当前租户默认端点，执行中调用固定原连接 |

D1–D6 均来自本轮交互工具的用户明确答复，没有将推荐项或等待超时视为确认。未发生已确认决定的替代；后续若改变范围，用新决定保留替代关系。


本轮未提交；执行器宿主需Docker CLI，SSH需OpenSSH，可信镜像必须在目标daemon预备。生产使用JDBC、同一外部主密钥与显式目标允许清单；授权生产角色并重新登录后再配置端点。Docker共享宿主内核，专用执行主机/强化runtime由部署选择。本轮没有修改运行中服务，也没有使用真实生产SSH/TLS或模型凭据。

清理无法确认时保留实例配额并返回失败，管理员按端点和本项目随机容器名核实；系统不自动接管、释放未知配额或重放脚本。主密钥自动迁移、跨实例资源调度和其他沙箱后端不在本轮交付范围。

最后连接兼容/身份复验：DockerSkillSandboxIntegrationTest、RemoteDockerSandboxIntegrationTest、SandboxSecurityTest、DockerSkillSandboxTest共17项通过，覆盖R0本地socket固定路由/私有Docker配置以及精确SSH主机别名、排除系统known_hosts。未知异常通过ErrorDiagnosticLogger资源诊断重载明确记录resourceType/resourceId，保留同一errorId和脱敏堆栈，管理API与统一异常处理专项共11项再次通过（SandboxEndpointControllerTest与ApiExceptionHandlerTest）；未重复记录异常。最终统计922项，921通过、1浏览器专用条件跳过、0失败、0错误。上述统计由完整回归及最终受影响复验汇总，浏览器另行实际验收。

## R0 历史基线（保留原实施与验证记录）


提交记录（2026-10-01）：用户已授权将本任务 41 个文件一同本地提交，提交说明为「新增技能 Python 容器沙箱与受治理执行」。提交编号通过本文件的 Git 历史查询；未推送、未部署。

```text
仓库：F:/java/cm-agent。需求：让技能模块支持在沙箱中运行，按清单实施并完成验证。
先读 AGENTS.md、docs/superpowers/checklists/2026-09-30-skill-sandbox-checklist.md、specs/2026-09-30-skill-sandbox-design.md、plans/2026-09-30-skill-sandbox.md、implementation/2026-09-30-skill-sandbox-implementation-design.md 和 progress/2026-09-30-skill-sandbox-ledger.md。
按照 T0 至 T4 的依赖与当前状态推进；已完成项核验，不重复创建。首版仅执行包内 Python 3 脚本；模型不能选择代码、命令、镜像、环境、宿主路径或运行策略。默认关闭，继续保留真实 tenant/主体/Agent/Run、固定版本、撤销、业务工具授权与严格审计。
在短工作单元中记录 SANDBOX_PREPARED 与严格 PREPARED 审计，再在事务外执行容器。准备不代表模型读取成功，不放行 TEST 发布门禁；资源预算按固定版本重新计算，同一调用标识不能重放。后置复核和 SUCCEEDED 审计完成后才交付脱敏输出。处理超时、中断、输出超限、清理失败和审计/持久化故障，保持稳定错误码与 errorId。
保护既有 application*.yml、.codex/、.workbuddy/ 与其他工作包。Java 注释与落地文字使用中文。V15 仅维护 status 原生注释，不改历史迁移或新增结构；控制台只维护准备状态中文映射。
本机 Maven 使用 F:/java21。所有 Docker/Testcontainers/JDBC/Flyway 验证使用 ssh rocky 的 maven:3.9.9-eclipse-temurin-21。先核对 HEAD 和逐文件 SHA256，只同步本任务文件；运行领域、治理、API、真实 AgentScope 本地协议合同、真实沙箱隔离/配额/超时/清理、双库迁移与 JDBC 测试。实际命令和最终结果以账本为准；外部阻塞记录确切原因，不宣称未运行项通过。
同步六份文档和状态，按变更、验证、影响、风险、后续顺序简洁交付，提供提示词链接与问答快捷入口。本文不额外授权提交、推送或部署。合理工程细节自主决定，无需逐步确认。
```

文件生成本身不构成执行授权；本会话用户已明确授权实施。后续问答入口仅恢复文档上下文，不自动执行其中命令。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)
