# 技能沙箱进度账本

当前修订：R1；修订日期：2026-10-01；工作模式：mode=default。工作包日期与主题沿用2026-09-30 / skill-sandbox。

本轮依据用户明确指令实际实施T6～T11。当前分支codex/skill-sandbox-r1，基线HEAD为bc57060fac00c671cd247525b6082516194afd20；R0已交付提交938d6fd35ee2a4f36f990c9617746e48da503787保持。未提交、未推送、未合并、未部署。下方R0历史正文原样保留，其旧授权与测试结果只属于R0。

已核对AGENTS.md、相关POM/README/配置、六份工作包、Git状态及当前源码。CodeGraph先查但未匹配沙箱符号，按当前源码补核。未改动用户application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/或运行中服务。远程验证使用同一HEAD加本任务明确文件覆盖，不复制用户脏配置或真实凭据。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)。生产说明见[沙箱端点部署与管理](../../skill-sandbox-endpoints.md)。

## R1 修订与决定来源

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

| 决定 | 问题与候选选项 | 状态 | 用户选择、来源与影响 |
|---|---|---|---|
| D1 | A 本地/远程 Docker + 扩展接口；B Docker + HTTP 服务；C Docker + SSH 执行机 | 已确认 | 用户选择 A；只实现 Docker 后端，其他方式保留扩展接口 |
| D2 | A SSH + 双向 TLS；B 仅 SSH；C 仅双向 TLS | 已确认 | 用户选择 A；T7/T9 同时覆盖两种安全连接 |
| D3 | A 仅部署配置；B 部署配置 + 控制台管理 | 已确认 | 用户选择 B；增加端点 API、持久化、权限审计、凭据存储与运行期调整 |
| D4 | A 租户隔离且部署地址白名单；B 平台统一管理共享端点 | 已确认 | 用户选择 A；端点、默认设置及凭据均归属于租户，禁止跨租户使用 |
| D5 | A 加密落库；B 仅外部密钥引用 | 已确认 | 用户选择 A；私钥/证书经服务端加密保存，主密钥仅来自部署环境 |
| D6 | A 每租户一个默认端点；B Agent 指定端点 | 已确认 | 用户选择 A；后续技能调用使用当前租户默认端点，执行中调用固定原连接 |

D1–D6 均来自本轮交互工具的用户明确答复，没有将推荐项或等待超时视为确认。未发生已确认决定的替代；后续若改变范围，用新决定保留替代关系。


## R1 任务状态

| 编号 | 状态 | 实际交付 | 依赖 | 验收证据 |
|---|---|---|---|---|
| T5 | 完成 | R1证据复核、D1～D6交互确认与原六份文档维护 | R0 | 保留修订来源和R0历史，最新实施授权与禁止操作明确 |
| T6 | 完成 | Core后端SPI、唯一注册标识、统一限额、固定单次句柄 | T5 | 未知/重复后端拒绝；全局并发、输入输出、扩展超时及幂等关闭测试 |
| T7 | 完成 | LOCAL、SSH隧道、内存双向TLS中继及固定daemon清理 | T6 | 两个独立daemon真实执行、身份拒绝、轮换、超时、中断、断连、输出超量与零残留 |
| T8 | 完成 | 租户管理/探测API、独立权限、严格审计、运行默认选择和错误诊断 | T6、T7、T10 | MockMvc认证/权限/租户/CAS/凭据不回显/日志编号，治理与真实路由回归 |
| T9 | 完成 | 本轮Java、Node、双库、真实SSH/TLS、浏览器及交付核验 | T7、T8、T10、T11 | 完整回归与最终受影响复验：921项Java通过，1项条件跳过；Node89通过，浏览器另行实际验收 |
| T10 | 完成 | 租户领域/Repository、AES/GCM、memory/JDBC与V16双库迁移 | T6 | 两库CAS、跨租户、审计事务回滚、默认唯一并发及逐表字段原生中文注释 |
| T11 | 完成 | v2技能页沙箱端点列表/详情/编辑/探测/默认/删除与凭据轮换输入 | T8、T10 | 桌面1440×1000、移动390×844实测；操作流程、失败保留元数据/清空材料、同编号提示 |

## R1 实际实现

| 任务 | 关键实际文件 |
|---|---|
| T6 | core/runtime/SkillSandboxBackend.java；server/runtime/ManagedSkillSandbox.java；GovernedSkillAccessService.java |
| T7 | server/runtime/DockerDaemonConnection.java、DockerSkillSandbox.java、DockerSkillSandboxConnection.java、SandboxTargetPolicy.java；config/DockerConnectionProperties.java、SkillSandboxProperties.java |
| T8 | server/web/SandboxEndpointController.java、ApiExceptionHandler.java、AuthController.java；service/SandboxEndpointService.java；config/SandboxEndpointConfiguration.java |
| T10 | core/domain/SandboxEndpoint.java、SandboxConnectionMode.java；core/repository/SandboxEndpointRepository.java；persistence/JdbcSandboxEndpointRepository.java；mysql/postgresql/V16__add_skill_sandbox_endpoints.sql；server/runtime/SandboxCredentialCipher.java、SandboxCredentials.java；store/InMemorySandboxEndpointRepository.java |
| T11 | console/v2/skills.html、assets/sandbox-endpoints.js/css；共享assets/app.js、console-core.js |
| T9 | ManagedSkillSandboxTest、SandboxSecurityTest、SandboxEndpointControllerTest、RemoteDockerSandboxIntegrationTest、JdbcSandboxEndpointRepositoryTest、MigrationTest、SandboxBrowserFixtureTest及sandbox-endpoints.test.cjs；R0治理/容器测试兼容更新 |

可信Run与固定技能快照 → GovernedSkillAccessService准备/预算/审计 → ManagedSkillSandbox按JWT主体tenant读取默认端点或部署连接 → 策略校验并固定IP/端点版本/凭据版本 → 已注册后端.open → 一次执行 → 使用同一daemon清理/关闭 → 事务外原端点策略复核 → 短事务内Run/技能撤销复核及严格终态审计 → 脱敏交付。

默认选择不缓存；管理修改通过Repository当前值影响后续调用。执行中的连接不再次读取默认或解密新凭据；交付前仅复核启用/撤销和原目标允许策略。停用或删除原端点拒绝交付；轮换失效造成清理不能确认时失败并保留实例配额。远程失败不会降级本地或重放脚本。

## R1 实际验证记录

本地JDK21/Maven3.9.4已确认；实际快速命令：mvn -q -pl cm-agent-server -am "-Dtest=ManagedSkillSandboxTest,SandboxSecurityTest,SandboxEndpointControllerTest,GovernedSkillAccessServiceTest,DockerSkillSandboxTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test；通过。Node命令node --test cm-agent-console/src/test/js/*.test.cjs，89项通过，零失败。

Docker/JDBC/Flyway/Testcontainers仅ssh rocky执行：Docker23.0.6，maven:3.9.9-eclipse-temurin-21，Maven3.9.9/JDK21.0.7。隔离工作区/root/cm-agent-sandbox-r1-bc57060-20261001，HEAD与本地bc57060一致，最终代码及文档覆盖51个明确路径并逐个SHA256核对。用户脏application配置未复制。

最终完整命令：在上述Maven容器中mvn -q -pl cm-agent-server -am clean test -DargLine=-Xmx384m -Dcm-agent.agentscope.studio.enabled=false；显式启用CM_AGENT_TEST_SANDBOX=true、CM_AGENT_TEST_REMOTE_SANDBOX=true，CM_AGENT_TEST_SSH_PACKAGE_DIR=/workspace/validation-ssh-packages，TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1。宿主socket/CLI仅供可信验证器管理项目Testcontainers，技能容器和两个远程daemon无宿主socket挂载。首次完整回归920项中唯一失败为AuthControllerTest旧17项权限断言，修正为22项并显式检查五项新增权限。V16默认表最后补充创建/更新时间后，按最终源码运行AuthControllerTest、SandboxEndpointControllerTest、MigrationTest、JdbcSandboxEndpointRepositoryTest共21项受影响复验，全部通过。最后追加GovernedSkillAccessServiceTest事务外策略复核回归并运行治理/注册器16项通过；只读接口用例扩充后MockMvc4项通过。完整与各次受影响复验的最终报告汇总为922项，不能写成一次完整命令exit0。条件跳过仅为浏览器专用夹具，已另行启动实际服务完成浏览器验收。

真实SSH/TLS专项RemoteDockerSandboxIntegrationTest两项通过：各用专用docker:23-dind daemon/vfs导入可信Python镜像；临时生成认证，管理探测/默认/执行均走实际传输。覆盖固定非root隔离、凭据轮换旧连接固定、新调用探测门禁、超量、超时、中断、错误host key/CA/服务端身份/过期客户端证书、断连及受控清理失败；成功、超时、中断后按本项目随机名称检查零残留。

两库专项JdbcSandboxEndpointRepositoryTest和MigrationTest已通过，最终clean test再次覆盖；镜像PostgreSQL16-alpine与MySQL8.4。浏览器专用SandboxBrowserFixtureTest独立端口18097，仅test/memory，已自动释放；Chrome任务会话和本地SSH转发已关闭。截图保存在本机临时验证目录，非版本控制文件。

首次夹具失败已修复：withCommand(String)拆分脚本，改String[]；后台daemon的父进程生命周期与dind初始化；Rocky时钟落后导致官方APK网络下载验证失败，使用当前本机可信HTTPS下载并保持Alpine签名校验，不更改宿主时钟；tmpfs所有者与中断清理真实缺陷。早期失败不算最终通过，日志保存在隔离验证目录。

## R1 浏览器证据

技能页内“技能与发布 / 沙箱端点”两个视图，原技能导入、详情、发布DOM与流程保留。新增sandbox-endpoints.js/css复用中文受控工作台的列表—详情—操作；桌面并列，900px以下纵向堆叠。保存是主操作，测试/默认为辅助操作；未通过当前配置版本或停用不能设默认，默认端点不能直接删除。

SSH/TLS字段按协议显示，凭据仅写、不回填、不进入localStorage/sessionStorage；提交立即清空材料。失败保留普通表单和操作状态，中文原因/code/errorId不会重复拼接。请求epoch与busy约束防止切换后旧响应覆盖当前内容；每个按钮按独立权限控制，服务端重复授权。只读门禁另在真实Chrome DOM加载实际端点组件，注入仅sandbox:read的测试会话投影：新建及所有详情写控件disabled=true，四个材料输入长度均0；该组件检查使用固定测试列表，不冒充真实只读登录。服务端真实令牌的权限拒绝由MockMvc独立覆盖。console-core仅保留SKILL_SANDBOX_*的安全结构化403/404原因，其余错误行为沿用原流程。

真实Chrome通过SSH转发访问Rocky临时test/memory服务，服务不使用外部模型或生产凭据，结束自动关闭。1440×1000与390×844截图确认选中状态、层级、表单密度和零水平溢出；实际完成保存→探测→默认→取消默认→删除。403失败保留名称/host，四个材料输入为空，页面编号dc7696cb-eb8a-4497-ae3b-de3678cd4e7d可检索同一后台拒绝日志。没有新增JavaScript运行异常；预期403请求和既有favicon401不作为页面脚本故障。

## R1 遗留、阻塞与提交

T6～T11均完成；当前外部阻塞：无。完整回归曾有1处登录权限旧断言失败，最终21项受影响复验已通过；最后的V16默认选择创建/更新时间及注释已在两库复验。最终源码与文档51条覆盖路径已核对同一HEAD及SHA256。各模块最终XML计数：adapter87、console14、core91、persistence60、server665（其中浏览器条件跳过1）、starter5；合计922，失败/错误均0。JavaDoc/枚举/领域属性/生命周期/安全/SPI注释已按触发清单自查。本任务scoped diff --check通过；用户application.yml的既有EOF空行提醒保留，未扩展修复。

本轮未提交；执行器宿主需Docker CLI，SSH需OpenSSH，可信镜像必须在目标daemon预备。生产使用JDBC、同一外部主密钥与显式目标允许清单；授权生产角色并重新登录后再配置端点。Docker共享宿主内核，专用执行主机/强化runtime由部署选择。本轮没有修改运行中服务，也没有使用真实生产SSH/TLS或模型凭据。

清理无法确认时保留实例配额并返回失败，管理员按端点和本项目随机容器名核实；系统不自动接管、释放未知配额或重放脚本。主密钥自动迁移、跨实例资源调度和其他沙箱后端不在本轮交付范围。

提交信息：未提交；暂存区没有本轮修改；HEAD保持bc57060fac00c671cd247525b6082516194afd20。禁止提交/推送/合并/部署的最新指令优先于R0历史授权。

最后连接兼容/身份复验：DockerSkillSandboxIntegrationTest、RemoteDockerSandboxIntegrationTest、SandboxSecurityTest、DockerSkillSandboxTest共17项通过，覆盖R0本地socket固定路由/私有Docker配置以及精确SSH主机别名、排除系统known_hosts。未知异常通过ErrorDiagnosticLogger资源诊断重载明确记录resourceType/resourceId，保留同一errorId和脱敏堆栈，管理API与统一异常处理专项共11项再次通过（SandboxEndpointControllerTest与ApiExceptionHandlerTest）；未重复记录异常。最终统计922项，921通过、1浏览器专用条件跳过、0失败、0错误。上述统计由完整回归及最终受影响复验汇总，浏览器另行实际验收。

## R0 历史基线（保留原实施与验证记录）


日期沿用任务启动日 2026-09-30；验证跨日到 2026-10-01。初始 HEAD 为 `a72f9228eb07242f303d5e033adaf8b1a56e6241`，任务期间 HEAD 前移到 `5c60ad53372f231f004bf7057b96c92ad07cc441`，已重新核对并在远端采用相同 HEAD。用户于 2026-10-01 授权本地提交，本任务代码、测试及六份工作包纳入同一次提交；提交编号以本文件对应的 Git 历史为准，未推送、未部署。

用户已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、`.codex/` 和 `.workbuddy/` 改动保持不动。远程采用 HEAD 配置加本任务明确文件覆盖，不复制这些未提交配置或凭据。CodeGraph 索引无法定位当前 Skill 类，已先调用并回退源码核对。

| 编号 | 状态 | 目标与文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| T0 | 完成 | 核实 Skill 链路、工作树与环境，生成六份文档 | 无 | 六份产物齐全，已有配置及工作包保留 |
| T1 | 完成 | Core 网关默认执行扩展；SkillSandboxProperties、DockerSkillSandbox | T0 | 默认关闭；固定命令；有界 stdin、输出、并发、超时及唯一容器清理 |
| T2 | 完成 | GovernedSkillAccessService、AgentScopeSkillExecutionBridge 与准备状态 | T1 | 固定快照、租户/主体、运行状态、撤销、持久化预算、重复拒绝、严格审计、发布门禁测试 |
| T3 | 完成 | 能力接口、部署 profile、V15 状态注释、中文状态标签与生产说明 | T2 | 策略与导入白名单一致；不改变表结构；准备不显示为成功 |
| T4 | 完成 | Java 21 回归、Rocky 真实容器与双数据库验证、工作包核查 | T1,T2,T3 | 首轮全量通过；最终专项 215 项、JS 85 项通过，哈希/文档/零残留已核对 |

## 已执行证据

- CodeGraph 两次 explore 未定位当前 Skill 类，回退源码核查；默认 java/mvn 为 JDK 17，改用 F:/java21 后确认 Java 21.0.11、Maven 3.9.4。
- 本地 `mvn -q -pl cm-agent-server -am -DskipTests compile` 通过。
- 本地 Java 21 相关领域、Skill、Adapter、治理与 API 测试通过；真实 ReActAgent 与本地 OpenAI 协议 stub 的脚本调用和致命失败停止合同测试通过，不使用真实模型凭据。
- `node --test cm-agent-console/src/test/js/console-core.test.cjs cm-agent-console/src/test/js/skills.test.cjs`：85 项通过，失败 0，跳过 0。
- Rocky Docker 23.0.6、Maven 3.9.9 / JDK 21.0.7 已确认；预拉取 python:3.12-alpine，digest 为 sha256:4c47124a8391cb7a9f571164147d154777cf012a4ece5f86097130d7a4478111。
- 远程验证目录 `/root/cm-agent-skill-sandbox-a72f922-20261001` 的 HEAD 为 5c60ad53372f231f004bf7057b96c92ad07cc441。初始覆盖 24 文件 SHA256 全部匹配，随后最终覆盖 35 文件单独生成 SHA256 清单。
- 在 maven:3.9.9-eclipse-temurin-21 内 `mvn -q test` 返回 0。日志为远端 `validation.log`；该轮在独立准备状态与 V15 最终调整之前，不能冒充最终调整后的全量结果。
- 该轮 DockerSkillSandboxIntegrationTest 7 项通过（失败/错误/跳过均为 0）：成功资源+stdin、隔离、超时与子进程、输出限额、失败脱敏、成功脱敏、镜像缺失拒绝。完成后未发现随机命名沙箱残留。

## 最终专项复验

Rocky Maven 21 内执行：

```text
mvn -q -pl cm-agent-server -am "-Dtest=Skill*Test,AgentSkill*Test,GovernedSkillAccessServiceTest,DockerSkill*Test,AgentScope*Test,MigrationTest,JdbcSkillRepositoriesTest,ApiExceptionHandlerTest,ConsoleResourceTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

设置 CM_AGENT_TEST_SANDBOX=true，仅验证容器挂载 Docker CLI/socket；实际脚本容器不挂载它们。最终复验增加准备状态、预算、发布门禁、能力/导入白名单、V15、原生注释和实际 cgroup 配额断言。第一次 V15 复验发现 MigrationTest 的 V12 升级计数仍为 2，实际为 V13/V14/V15 共 3；已同步修正，不改迁移行为。第二次最终专项运行返回 0，日志为远端 `validation-final.log`。

## 修正记录与限制

首轮旧错误文案兼容断言已保留；测试辅助方法由 insert 改为仓库真实 insertAll、JWT 使用 createToken。首次跨平台 SHA256 清单含 CRLF，已改 LF 并核验。任务 HEAD 前移已同步，不重置用户提交。

本任务路径 diff --check 通过；全仓库检查发现用户既有 application.yml 末尾空行，保持不动。未执行真实公网模型、生产部署、强化 runtime/虚拟机隔离测试；采用本地协议合同与实际 Docker 隔离验证，不将其等同于正式发布或生产接受。旧版本回退和主机故障后的孤立容器处理见 README。提交信息：用户已授权本地提交，提交说明为「新增技能 Python 容器沙箱与受治理执行」；编号以本文件对应的 Git 历史为准。


## 最终验收结果（2026-10-01）

最终专项命令返回 0：35 份待验证文件 SHA256 均与本地一致，远程 HEAD 与本地同为 5c60ad53372f231f004bf7057b96c92ad07cc441。31 份匹配 Surefire 报告共 215 项，失败 0、错误 0、跳过 0：Adapter 79、Console 14、Core 10、Persistence 5、Server 107。Persistence 中 MigrationTest 3 项与 JdbcSkillRepositoriesTest 2 项均通过，覆盖 PostgreSQL 16 / MySQL 8.4 的 V1–V15、逐表逐字段非空注释、V15 状态注释、准备状态往返与 tenant 隔离。DockerSkillSandboxIntegrationTest 7 项通过，包含实际 cgroup 资源配额断言。

最后本地治理/错误 API 复验通过；Node 两个脚本共 85 项通过，失败/跳过 0。六份工作包齐全、内部相对链接均有效；本任务路径 diff --check 通过。Rocky 上 cm-agent-skill-* 容器查询结果为空，无本次执行容器残留。全仓库 diff --check 的 application.yml 既有末尾空行不属于本任务，未修改。

T0–T4 全部完成，必需验证已通过。首轮全量 mvn -q test 返回 0；最终调整后的复验范围为上述 215 项，不声称再次运行最终版本全量测试。真实公网模型、生产部署、强化 runtime 验证未执行：前者使用真实 AgentScope 与本地协议 stub 替代，后两者不属于本次交付范围。仍需部署者按 README 显式启用并选定可信镜像/runtime。用户于 2026-10-01 授权本地提交，本任务代码、测试及六份工作包纳入同一次提交；提交编号以本文件对应的 Git 历史为准，未推送、未部署。


关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md)
