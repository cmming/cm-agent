# 技能沙箱待办清单

当前修订：R1；修订日期：2026-10-01；工作模式：mode=default。工作包日期与主题沿用2026-09-30 / skill-sandbox。

本轮依据用户明确指令实际实施T6～T11。当前分支codex/skill-sandbox-r1，基线HEAD为bc57060fac00c671cd247525b6082516194afd20；R0已交付提交938d6fd35ee2a4f36f990c9617746e48da503787保持。未提交、未推送、未合并、未部署。下方R0历史正文原样保留，其旧授权与测试结果只属于R0。

已核对AGENTS.md、相关POM/README/配置、六份工作包、Git状态及当前源码。CodeGraph先查但未匹配沙箱符号，按当前源码补核。未改动用户application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/或运行中服务。远程验证使用同一HEAD加本任务明确文件覆盖，不复制用户脏配置或真实凭据。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)。生产说明见[沙箱端点部署与管理](../../skill-sandbox-endpoints.md)。

## R1 需求与范围

支持本地Docker、通过SSH或双向TLS连接远程Docker daemon，并保留通用扩展接口。部署者定义允许目标和执行安全上限，租户管理员管理本租户端点和加密凭据；每租户一个默认端点，后续调用取最新选择，执行中的调用保持原连接。

保留R0：默认关闭、固定版本Python与文本资源、Run/主体/tenant/版本授权、撤销复核、预算、防重、严格审计与TEST发布门禁。容器固定禁网、非root、只读根、无宿主/socket挂载、cap-drop=ALL、no-new-privileges、CPU0.5、内存/交换各128MiB、PID32、nofile64，两个16MiB私有tmpfs；执行默认15秒、输入输出各32768字节、实例并发最多2，只能收紧。

未新增HTTP/E2B/Kubernetes/任意SSH主机命令后端、Agent独立端点绑定、联网安装、持久工作区、文件导出、跨实例任务接管、脚本重放或共享daemon全局调度。

## R1 稳定编号待办

| 编号 | 状态 | 实际交付 | 依赖 | 验收证据 |
|---|---|---|---|---|
| T5 | 完成 | R1证据复核、D1～D6交互确认与原六份文档维护 | R0 | 保留修订来源和R0历史，最新实施授权与禁止操作明确 |
| T6 | 完成 | Core后端SPI、唯一注册标识、统一限额、固定单次句柄 | T5 | 未知/重复后端拒绝；全局并发、输入输出、扩展超时及幂等关闭测试 |
| T7 | 完成 | LOCAL、SSH隧道、内存双向TLS中继及固定daemon清理 | T6 | 两个独立daemon真实执行、身份拒绝、轮换、超时、中断、断连、输出超量与零残留 |
| T8 | 完成 | 租户管理/探测API、独立权限、严格审计、运行默认选择和错误诊断 | T6、T7、T10 | MockMvc认证/权限/租户/CAS/凭据不回显/日志编号，治理与真实路由回归 |
| T9 | 完成 | 本轮Java、Node、双库、真实SSH/TLS、浏览器及交付核验 | T7、T8、T10、T11 | 完整回归与最终受影响复验：921项Java通过，1项条件跳过；Node89通过，浏览器另行实际验收 |
| T10 | 完成 | 租户领域/Repository、AES/GCM、memory/JDBC与V16双库迁移 | T6 | 两库CAS、跨租户、审计事务回滚、默认唯一并发及逐表字段原生中文注释 |
| T11 | 完成 | v2技能页沙箱端点列表/详情/编辑/探测/默认/删除与凭据轮换输入 | T8、T10 | 桌面1440×1000、移动390×844实测；操作流程、失败保留元数据/清空材料、同编号提示 |

实施依赖顺序：T6 → T10/T7 → T8 → T11 → T9。R0的T0～T4不重复实施，仅回归。

## R1 验收门禁

| 验收 | 对应任务 | 本轮证明 |
|---|---|---|
| A1 | T6、T9 | 默认关闭与R0治理兼容；SPI注册拒绝、统一并发/输入输出/超时、单次执行及幂等关闭 |
| A2 | T7、T9 | SSH与双向TLS实际认证；错误host key、CA、服务端身份、过期客户端证书和未允许目标拒绝 |
| A3 | T7、T8、T9 | 原连接执行/清理，轮换、超时、中断、超量、断连与清理失败保留配额；无脚本重放 |
| A4 | T8、T10、T9 | 可信tenant过滤、跨租户读写/默认拒绝、材料加密不回显、CAS与严格审计回滚 |
| A5 | T10、T9 | PostgreSQL16/MySQL8.4新装及V12升级到V16、逐表字段原生中文注释、默认唯一并发 |
| A6 | T11、T9 | 真实浏览器保存→探测→默认→删除，失败恢复与只写凭据，同编号提示、桌面/窄屏 |
| A7 | T8、T9 | 固定技能/Run/撤销/预算/防重/审计/TEST回归，配置/README/发布说明与工作包一致 |

## R1 实际验证

本地JDK21/Maven3.9.4已确认；实际快速命令：mvn -q -pl cm-agent-server -am "-Dtest=ManagedSkillSandboxTest,SandboxSecurityTest,SandboxEndpointControllerTest,GovernedSkillAccessServiceTest,DockerSkillSandboxTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test；通过。Node命令node --test cm-agent-console/src/test/js/*.test.cjs，89项通过，零失败。

Docker/JDBC/Flyway/Testcontainers仅ssh rocky执行：Docker23.0.6，maven:3.9.9-eclipse-temurin-21，Maven3.9.9/JDK21.0.7。隔离工作区/root/cm-agent-sandbox-r1-bc57060-20261001，HEAD与本地bc57060一致，最终代码及文档覆盖51个明确路径并逐个SHA256核对。用户脏application配置未复制。

最终完整命令：在上述Maven容器中mvn -q -pl cm-agent-server -am clean test -DargLine=-Xmx384m -Dcm-agent.agentscope.studio.enabled=false；显式启用CM_AGENT_TEST_SANDBOX=true、CM_AGENT_TEST_REMOTE_SANDBOX=true，CM_AGENT_TEST_SSH_PACKAGE_DIR=/workspace/validation-ssh-packages，TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1。宿主socket/CLI仅供可信验证器管理项目Testcontainers，技能容器和两个远程daemon无宿主socket挂载。首次完整回归920项中唯一失败为AuthControllerTest旧17项权限断言，修正为22项并显式检查五项新增权限。V16默认表最后补充创建/更新时间后，按最终源码运行AuthControllerTest、SandboxEndpointControllerTest、MigrationTest、JdbcSandboxEndpointRepositoryTest共21项受影响复验，全部通过。最后追加GovernedSkillAccessServiceTest事务外策略复核回归并运行治理/注册器16项通过；只读接口用例扩充后MockMvc4项通过。完整与各次受影响复验的最终报告汇总为922项，不能写成一次完整命令exit0。条件跳过仅为浏览器专用夹具，已另行启动实际服务完成浏览器验收。

真实SSH/TLS专项RemoteDockerSandboxIntegrationTest两项通过：各用专用docker:23-dind daemon/vfs导入可信Python镜像；临时生成认证，管理探测/默认/执行均走实际传输。覆盖固定非root隔离、凭据轮换旧连接固定、新调用探测门禁、超量、超时、中断、错误host key/CA/服务端身份/过期客户端证书、断连及受控清理失败；成功、超时、中断后按本项目随机名称检查零残留。

两库专项JdbcSandboxEndpointRepositoryTest和MigrationTest已通过，最终clean test再次覆盖；镜像PostgreSQL16-alpine与MySQL8.4。浏览器专用SandboxBrowserFixtureTest独立端口18097，仅test/memory，已自动释放；Chrome任务会话和本地SSH转发已关闭。截图保存在本机临时验证目录，非版本控制文件。

首次夹具失败已修复：withCommand(String)拆分脚本，改String[]；后台daemon的父进程生命周期与dind初始化；Rocky时钟落后导致官方APK网络下载验证失败，使用当前本机可信HTTPS下载并保持Alpine签名校验，不更改宿主时钟；tmpfs所有者与中断清理真实缺陷。早期失败不算最终通过，日志保存在隔离验证目录。

## R1 已确认决定

| 决定 | 问题与候选选项 | 状态 | 用户选择、来源与影响 |
|---|---|---|---|
| D1 | A 本地/远程 Docker + 扩展接口；B Docker + HTTP 服务；C Docker + SSH 执行机 | 已确认 | 用户选择 A；只实现 Docker 后端，其他方式保留扩展接口 |
| D2 | A SSH + 双向 TLS；B 仅 SSH；C 仅双向 TLS | 已确认 | 用户选择 A；T7/T9 同时覆盖两种安全连接 |
| D3 | A 仅部署配置；B 部署配置 + 控制台管理 | 已确认 | 用户选择 B；增加端点 API、持久化、权限审计、凭据存储与运行期调整 |
| D4 | A 租户隔离且部署地址白名单；B 平台统一管理共享端点 | 已确认 | 用户选择 A；端点、默认设置及凭据均归属于租户，禁止跨租户使用 |
| D5 | A 加密落库；B 仅外部密钥引用 | 已确认 | 用户选择 A；私钥/证书经服务端加密保存，主密钥仅来自部署环境 |
| D6 | A 每租户一个默认端点；B Agent 指定端点 | 已确认 | 用户选择 A；后续技能调用使用当前租户默认端点，执行中调用固定原连接 |

D1–D6 均来自本轮交互工具的用户明确答复，没有将推荐项或等待超时视为确认。未发生已确认决定的替代；后续若改变范围，用新决定保留替代关系。


## R1 差异与影响

| 差异 | 类别 | 调整前 → 调整后 | 来源或依据 | 影响 | 采用状态 |
|---|---|---|---|---|---|
| R1-01 | 新增 | 固定 Docker 实现 → 本地/远程 Docker + 通用扩展接口 | 用户需求与 D1 | T6、T7、T9 | 已采用 |
| R1-02 | 调整 | 直接依赖 Docker → 治理依赖执行合同，Docker 通过装配注册 | GovernedSkillAccessService 当前源码 | T6、T8 | 已实现 |
| R1-03 | 新增 | 无远程连接字段 → SSH 与双向 TLS 明确配置、认证和验证 | D2 | T7、T9 | 已采用 |
| R1-04 | 新增 | 部署策略为主 → 部署配置 + 租户端点控制台管理及运行期调整 | D3、D4 | T8、T10、T11 | 已采用 |
| R1-05 | 新增 | 无沙箱凭据仓储 → 加密落库、只写响应、轮换与部署主密钥 | D5 与现有模型加密链路 | T10、T9 | 已采用 |
| R1-06 | 澄清 | 单一执行器 → 每租户一个默认端点，调用固定连接快照 | D6 | T7、T8、T9 | 已采用 |
| R1-07 | 澄清 | T0–T4 完成 → 历史完成保留，新增 T5–T11 | 旧验收不证明远程新功能 | 全包 | 已采用 |
| R1-08 | 澄清 | R0实施/提交授权 → R1初次交互仅分析和文档修订 | R1规划阶段指令及技能授权边界 | 提示词、账本 | 已采用 |

本轮没有取消旧功能。草稿曾将 T8 写作治理/能力、T9 写作远程验证；D3 选择控制台后扩展其验收并追加 T10/T11，未回收或重用任务编号。


R1-09（2026-10-01）：用户明确授权在codex/分支实际实施T6～T11，恢复mode=default，并明确禁止本轮提交/推送/合并/部署；替代R1-08的当前执行边界，保留其历史来源。R1-10：依据真实独立daemon失败修正tmpfs固定UID/GID和调用中断的清理时序；不放宽R0安全限制。

本轮未提交；执行器宿主需Docker CLI，SSH需OpenSSH，可信镜像必须在目标daemon预备。生产使用JDBC、同一外部主密钥与显式目标允许清单；授权生产角色并重新登录后再配置端点。Docker共享宿主内核，专用执行主机/强化runtime由部署选择。本轮没有修改运行中服务，也没有使用真实生产SSH/TLS或模型凭据。

清理无法确认时保留实例配额并返回失败，管理员按端点和本项目随机容器名核实；系统不自动接管、释放未知配额或重放脚本。主密钥自动迁移、跨实例资源调度和其他沙箱后端不在本轮交付范围。

最后连接兼容/身份复验：DockerSkillSandboxIntegrationTest、RemoteDockerSandboxIntegrationTest、SandboxSecurityTest、DockerSkillSandboxTest共17项通过，覆盖R0本地socket固定路由/私有Docker配置以及精确SSH主机别名、排除系统known_hosts。未知异常通过ErrorDiagnosticLogger资源诊断重载明确记录resourceType/resourceId，保留同一errorId和脱敏堆栈，管理API与统一异常处理专项共11项再次通过（SandboxEndpointControllerTest与ApiExceptionHandlerTest）；未重复记录异常。最终统计922项，921通过、1浏览器专用条件跳过、0失败、0错误。上述统计由完整回归及最终受影响复验汇总，浏览器另行实际验收。

## R0 历史基线（保留原实施与验证记录）


需求：让技能模块支持在沙箱中运行，生成六份工作包并按清单实施验证。

日期沿用任务启动日 2026-09-30；验证跨日到 2026-10-01。初始 HEAD 为 `a72f9228eb07242f303d5e033adaf8b1a56e6241`，任务期间 HEAD 前移到 `5c60ad53372f231f004bf7057b96c92ad07cc441`，已重新核对并在远端采用相同 HEAD。用户于 2026-10-01 授权本地提交，本任务代码、测试及六份工作包纳入同一次提交；提交编号以本文件对应的 Git 历史为准，未推送、未部署。

用户已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、`.codex/` 和 `.workbuddy/` 改动保持不动。远程采用 HEAD 配置加本任务明确文件覆盖，不复制这些未提交配置或凭据。CodeGraph 索引无法定位当前 Skill 类，已先调用并回退源码核对。

## 范围与当前证据

首版只执行导入技能包内的 Python 3 脚本和同版本文本资源，模型不能提交代码、命令、镜像、runtime、环境变量或宿主路径。Docker 参数固定为禁网、非 root、只读根文件系统、无宿主挂载、无额外 capabilities、no-new-privileges、CPU 0.5 核、内存与交换内存各 128 MiB、PID 32、nofile 64；私有 /workspace 与 /tmp 各 16 MiB。默认执行 15 秒、stdin 与原始合并输出各 32768 字节、单实例最多 2 个容器；只允许收紧。沙箱默认关闭，开启后 .py 自动进入有效导入白名单，既有资源类型不改变。

原有 AgentScopeSkillSession 禁用原生代码执行，GovernedSkillAccessService 负责读取治理。实际新增独立 run_skill_script，与原生读取工具共存；保留既有 Skill 发布、绑定与 TEST 流程，不授予业务工具权限。SANDBOX_PREPARED 区分准备与成功读取，V15 仅维护状态字段中文原生注释。控制台只新增中文状态映射，不改布局。

| 编号 | 状态 | 目标与文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| T0 | 完成 | 核实 Skill 链路、工作树与环境，生成六份文档 | 无 | 六份产物齐全，已有配置及工作包保留 |
| T1 | 完成 | Core 网关默认执行扩展；SkillSandboxProperties、DockerSkillSandbox | T0 | 默认关闭；固定命令；有界 stdin、输出、并发、超时及唯一容器清理 |
| T2 | 完成 | GovernedSkillAccessService、AgentScopeSkillExecutionBridge 与准备状态 | T1 | 固定快照、租户/主体、运行状态、撤销、持久化预算、重复拒绝、严格审计、发布门禁测试 |
| T3 | 完成 | 能力接口、部署 profile、V15 状态注释、中文状态标签与生产说明 | T2 | 策略与导入白名单一致；不改变表结构；准备不显示为成功 |
| T4 | 完成 | Java 21 回归、Rocky 真实容器与双数据库验证、工作包核查 | T1,T2,T3 | 首轮全量通过；最终专项 215 项、JS 85 项通过，哈希/文档/零残留已核对 |

按 T0 → T1 → T2 → T3 → T4 推进。代码完成与必需验证分开记录；T4 未通过不得宣称全任务完成。未知语言、依赖在线安装、网络白名单、文件导出与持久工作区不在首版范围。Docker 共享宿主内核，强化 runtime 与专用主机由部署环境选择。

关联文档：[提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)
