# 本机客户端项目工作区实施账本

日期：2026-11-09；当前修订 R3（2026-11-10）；mode=default；业务代码已提交并推送。

## R3 续验结果（2026-11-10）

当前仍为 R3、mode=default；这是原任务的实现与验收收口，不新增需求修订或重复工作包。业务代码已提交并推送至 `d85f785`，客户端输出修复为 `c8d6ad3`。T6/T17 的真实 Windows 命令子集通过；T9 改为进行中，整体验收仍未闭合。

- Rocky：`17bf309` 上持久化全量 94/94 通过；`9e1745e` 上迁移/检查点/设备仓储及 core 命令定向通过；最终 `c8d6ad3` 对齐双库命令仓储增量 2/2 和 core 命令 3/3 通过。当前持久化/core 与该次验证无差异，不把全量 94 项冒充在最终提交重跑。
- Windows：真实已配对客户端、认证浏览器和 `OPENAI_COMPATIBLE / glm-5.3` 完成工具链查询、提案、HIGH 精确预览/批准、命令成功、Run 屏障、进程树停止、重复停止查询、客户端重启 UNKNOWN 回报与人工对账。所用 Agent 显示名为“测试Qwen”，实际模型以运行配置的 `glm-5.3` 为准。
- UNKNOWN：本机孤立回执为 UNKNOWN；服务端恢复后仍为 UNKNOWN/version=17，输出序号 69 与已有 1579 字节输出保留。人工对账 HTTP 200 不改变状态、版本、序号；真实审计存在；归档 HTTP 409 `CLIENT_PROJECT_TASK_PENDING`，测试进程数量为 0，未自动重放。
- 边界：使用既有登记的验收项目；原生目录窗口在当前自动化桌面不可操作，不能据此宣称完整“选择目录到开发”验收通过。远端 Server/TLS、多实例、设备撤销、Windows 注销/断电、文件写入竞争和完整窄屏/迟到响应矩阵仍未验。

具体命令、Run/命令编号、失败修复与证据适用范围以[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)为准。

## R0 历史基线

- R3 续验起点（2026-11-10）：当前 HEAD `1b19863`，远端已有主体 `21344f4`；本轮六个代码/测试文件和六份工作包、发布说明尚未提交。无关配置、设计、忽略规则和工作目录保留不动。
- 本轮 `mvn -q -pl cm-agent-local-client,cm-agent-server -am "-Dtest=ClientProject*Test,LocalClientServerClientTest,LocalClientTaskExecutorTest,LocalProjectRunCompletionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：JDK 21.0.11/Maven 3.9.4，退出码 0；客户端匹配 19 项、Server 匹配 27 项，均无失败/错误/跳过。未运行数据库测试（该命令没有匹配持久化测试）。
- Rocky 当前根分区余量约 1.7 GiB、可用内存约 3.7 GiB；既有 `/tmp/cm-agent-verify-v29fix-20261110` 对应 `1b19863` 的 MigrationTest 报告 3/3 通过。这是恢复发现的历史报告，不作为本轮完整双库复跑或端到端通过。接下来用对齐新提交的隔离目录和已有 Maven 缓存串行验证，禁止清理无关容器/卷。
- HEAD `0a96c6a0a70c77c20d29e325e276a7eba7ce5010`，工作树已有用户配置、设计文件及原 R2 工作包改动，保持不动。
- Maven 3.9.4；`F:\java21` 提供 JDK 21.0.11。默认 shell 为 JDK 17，后续命令显式切换。
- R0 形成时最高迁移为 V28；候选 V29 已在本 R2 实施并通过静态双方言检查。

## R3 当前任务状态（含续验）

| 编号 | 状态 | 证据或未执行项 |
|---|---|---|
| T0 | 完成 | 已核对 Git/JDK/Maven、模块边界、V29 与主要入口；环境/代码调查证据见本账本 |
| T1 | 进行中 | 窗口、取消、无桌面与协议合同已实现；真实选择请求已领取，但当前桌面没有可操作窗口并过期为 UNKNOWN，原生选择验收未通过 |
| T2 | 进行中 | 既有登记项目在真实客户端多次重启后仍可读取；双库协议 5 会话/Run/任务合同通过；真实原生登记和超时恢复仍待验 |
| T3 | 进行中 | 已补齐协议 5 首次 Run、消息/审批/历史闸门与行动头；真实模型命令会话通过；跨入口/权限撤销完整矩阵待补 |
| T4 | 进行中 | 真实目录与 README 文本预览通过；来源时间统一微秒并有加密快照回归；真实文件竞争与搜索预算矩阵待补 |
| T5 | 进行中 | 六类文件/空目录动作、精确预览、HIGH 审批、条件应用/撤销和私有回执已实现；新增计划冲突与跨 Journal 实例回执测试，真实多进程竞争、断线和断电仍待验 |
| T6 | 进行中 | 真实 quick 命令成功；tree 父子进程停止、无重复输出、重启 UNKNOWN 恢复与对账通过；注销/断电、脱离进程树与跨主机故障仍待验 |
| T7 | 进行中 | 客户端重启 UNKNOWN、完成屏障、停止与对账保留隔离已验；设备撤销、任务全 TTL 和完整故障矩阵待补 |
| T8 | 进行中 | 真实认证桌面页面、命令预览、SSE 审批、轮询保留草稿、停止和 UNKNOWN 对账通过；窄屏/键盘/迟到响应完整矩阵待验 |
| T9 | 进行中 | Rocky 双库与真实 Windows 命令子集通过；原生目录窗口、远端 TLS、多实例、设备撤销及完整故障矩阵仍未通过，不以代码完成代替 |
| T10 | 进行中 | 独立属性、默认关闭和 local/test+JDBC+设备通道校验已实现；停机升级/回滚/旧程序栅栏未验 |
| T11 | 进行中 | 选择、只读、变更及命令任务使用独立签名队列并交错轮询；命令异步启动后由状态/停止任务控制；真实断线、TTL、客户端重启和故障注入未完成 |
| T12 | 进行中 | 文件与命令均具备一次派发许可；命令 QUEUED 前复核状态/摘要，设备领取再复核 claimable；重复停止只查询；旧进程、迟到许可和真实崩溃竞态待 T9 |
| T13 | 完成 | 原始/展示摘要分离、HIGH 决策后端复核、`displayExact` 拒绝和页面门禁均有合同 |
| T14 | 进行中 | C04/C07 已取得真实 Windows 命令子集与双库证据；C02/C08/C10 补充真实模型/认证页面证据；各条目的完整撤销、旧版、跨入口或物理故障矩阵仍待补 |
| T15 | 进行中 | 12 个专用工具 ID、风险分类、命令审批定位摘要和 ReAct/Harness 载荷保护已有定向测试；全入口伪造、旧版、MCP、断线和恢复矩阵未完成 |
| T16 | 进行中 | Windows 目录/文件身份、跨进程冲突域、写前预算和写后快照已实现；项目代次、旧许可和真实竞争待验 |
| T17 | 进行中 | 仅提案 Run 成功、命令成功 Run 成功、模型结束仍等待运行命令、停止后 Run 不成功、重启/屏障 UNKNOWN 与对账不解隔离均有真实证据；重启中多实例接管、注销/断电仍待验 |
| T18 | 进行中 | 长期来源历史与运行快照分离、微秒往返和轮询交互回归通过；真实重启后原会话/回执读取通过；完整离线/撤销/归档浏览器矩阵待验 |

## R1 历史验证

- 历史 R0：`mvn -q -pl cm-agent-agentscope-adapter,cm-agent-local-client,cm-agent-server -am test` 使用 JDK 21 启动，在 `cm-agent-persistence` 因本机无 Docker 环境失败，相关 Testcontainers 未形成有效断言。该失败保留为“本机不能替代 Rocky”的历史证据。
- `mvn -q -pl cm-agent-local-client -am clean test`：退出码 0。
- `mvn -q -pl cm-agent-server -am "-Dtest=ClientProjectServiceTest,ClientProjectRequestBoundaryTest,ClientProjectToolCatalogTest,ToolOperationCategoriesTest,ProjectRuntimeContextTest,ClientProjectRootStoreTest,LocalClientServerClientTest,AgentScopeProtectedToolPayloadTest,ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" clean test`：退出码 0。
- `mvn -q -pl cm-agent-agentscope-adapter -am test`：退出码 0；日志中的故意异常、超时与 `onErrorDropped` 场景均被对应合同断言观察，未使构建失败。
- `node --test src/test/js/*.test.cjs`（工作目录 `F:\java\cm-agent\cm-agent-console`）：232 项通过，0 失败、0 跳过。
- `mvn -q -DskipTests package`：退出码 0。
- V29 静态双方言检查：`MIGRATION_STATIC_OK tables=5 bothDialects=V29 comments=complete`。
- 六份实施文档检查：`DOC_CHECK_OK files=6 revisions=R1 tasks=T0-T18`，相对链接有效、代码围栏成对、清单与账本状态一致。
- 曾并行运行多个 Maven reactor，因共享模块 `target/` 出现 `NoClassDefFound`/缺失 class；后续全部改为串行并重新执行上述命令。该并发现象不是业务失败证据，也不应作为最终结果引用。
- `git diff --check`：通过，仅有 Windows 下既有文件 LF→CRLF 转换提示；未发现空白错误。工作树中的用户配置、`.gitignore`、`.impeccable/design.json`、`.workbuddy/` 和 `application-ok.yml` 保留不动。

## R2 验证

- R2 定向 Java：`ClientProjectServiceTest,ClientProjectRunServiceTest,ClientProjectToolCatalogTest,ClientProjectApprovalGuardTest,ClientProjectOperationTest,ToolOperationCategoriesTest,ProjectRuntimeContextTest,ClientProjectRootStoreTest,ClientProjectChangeOperationsTest,ClientProjectOperationJournalTest,LocalClientServerClientTest,AgentScopeProtectedToolPayloadTest,ConsoleResourceTest`：通过。新增合同覆盖审批版本/摘要分别不匹配、重复目标/大小写别名、普通文件父子冲突以及跨 Journal 实例回执复用拒绝。
- `mvn -q -pl cm-agent-local-client -am clean test`：通过，32 项，0 失败、0 错误、0 跳过。
- `mvn -q -pl cm-agent-agentscope-adapter -am test`：通过，181 项，0 失败、0 错误、5 项跳过；跳过项为 1 个外部停止条件项和 4 个 live Provider 项。
- `node --check assets/client-projects.js` 与 `node --test src/test/js/*.test.cjs`：语法检查通过；全量 232 项通过，0 失败、0 跳过。
- `mvn -q -pl cm-agent-persistence -am "-DskipTests" package`：通过，更新后的 MigrationTest 可编译。
- `mvn -q -DskipTests package`：退出码 0，全模块跳过测试打包通过。
- V29 静态双方言检查：`MIGRATION_STATIC_OK tables=5 columns=67 tableComments=5 preexistingColumnComments=2 bothDialects=V29 comments=complete`。MigrationTest 已加入五张表、关键外键/索引和协议 5 注释断言，但未在数据库执行。
- 浏览器探测：`http://127.0.0.1:8080/console/v2/projects.html` 返回 200，但 Playwright 被重定向至 `/console/v2/login.html`，当前无已登录会话，不能形成项目页桌面、390px、320px、键盘或迟到响应证据。
- Rocky 只读环境探测：Docker 23.0.6 可用，三套指定镜像已存在；根分区 70 GiB 已用 69 GiB、仅余约 1.8 GiB，并有大量无关运行容器。本轮未启动新 Testcontainers 或执行全局清理。
- `git diff --check`：通过；仅保留 Windows 换行转换提示。

## R3 初始验证（历史）

- `mvn -q -pl cm-agent-local-client -am test`：通过，37 项，0 失败、0 错误、0 跳过；新增固定工具链配置变化、敏感参数和真实子进程停止合同。
- 客户端命令合同 `ClientProjectCommandOperationsTest`：包含异步启动/停止和子进程树清理两项；`ClientProjectCommandJournalTest` 证明新进程实例把孤立 RUNNING 回执改为 UNKNOWN 且禁止复用命令编号。
- `mvn -q -pl cm-agent-server -am "-Dtest=ClientProjectCommandServiceTest,...,LocalProjectRunCompletionTest,ToolApprovalServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过；命令服务 9/9、Run 屏障 5/5。
- R3 Server 定向合同合计 7 个测试类、36/36，0 失败、0 错误、0 跳过。
- Server 合同覆盖：仅提议命令不阻断成功、已执行 FAILED/超时不通过、超时 STOP_REQUESTED→UNKNOWN、重复停止转 COMMAND_STATUS、异步 RUNNING 保留执行句柄并在后台终态后释放。
- `node --check assets/client-projects.js` 与 `node --test src/test/js/*.test.cjs`：通过，233/233，0 失败、0 跳过。
- `mvn -q -pl cm-agent-agentscope-adapter -am test`：通过，182 项，0 失败、0 错误、5 项 external/live 条件跳过。
- `mvn -q -pl cm-agent-core -am test` 与 `mvn -q -pl cm-agent-console -am test`：均退出码 0。
- V29 静态双方言检查：`MIGRATION_STATIC_OK tables=6 pgColumnComments=91 myColumnComments=89 tableComments=6/6`；表集合一致，89 个新增列注释完整，PostgreSQL 另更新 2 个既有列注释。
- `mvn -q -pl cm-agent-persistence -am "-DskipTests" package` 与 `mvn -q "-DskipTests" package`：均退出码 0。
- 配置示例与文档门禁：`TOOLCHAIN_JSON_EXAMPLE_OK`、`DOC_CHECK_OK files=6 revisions=R3 links=32`；代码围栏和相对链接有效。
- 真实环境复核：项目页静态 URL 返回 HTTP 200，但仅证明服务入口可响应，不证明已登录项目会话或命令交互；Rocky Docker 23.0.6 可用，`maven:3.9.9-eclipse-temurin-21`、`postgres:16-alpine`、`mysql:8.4` 镜像均存在；根分区 70 GiB 已用 69 GiB、仅余 1.8 GiB（98%）。
- 远端 Testcontainers 未启动：本地仍是未提交工作树，无法按仓库门禁与远端提交对齐；远端默认 Java 17/Maven 3.6.3 也不能替代指定 JDK 21 容器，且当前磁盘余量不支持新增高影响验证。该项继续阻塞 T9。
- `git diff --check`：通过；仅有工作树既有 Windows 换行转换提示。

## R3 续验命令与证据

- 本地构建：JDK 21.0.11，Maven 3.9.4；远端 `maven:3.9.9-eclipse-temurin-21` 实测 Maven 3.9.9/JDK 21.0.7，Docker 23.0.6。远端根分区约 1.5–1.7 GiB 可用，复用已有依赖缓存，串行 Testcontainers，未清理无关资源。
- `mvn -q -pl cm-agent-persistence -am test`：`0903bb5` 首轮 94 项中 2 项失败，均为检查点升级测试固定 7 次迁移与实际 10 次不符；不是运行成功。修正为核对解析出的全部待迁移版本。
- 相同全量命令在 `17bf309` 上 94/94、0 失败/错误/跳过。迁移 V29、全部表字段注释、既有仓储与新增设备项目命令/任务断言通过。
- `9e1745e` 远端定向 `MigrationTest,JdbcLocalClientDeviceRepositoryTest,JdbcRuntimeCheckpointRepositoryTest,ClientProjectCommandTest`：退出码 0。
- 最终数据库/core 代码在 `c8d6ad3` 对齐远端后运行 `mvn -q -pl cm-agent-persistence -am -Dtest=JdbcLocalClientDeviceRepositoryTest,ClientProjectCommandTest -Dsurefire.failIfNoSpecifiedTests=false test`：退出码 0，双库 2/2、core 3/3；本地后续 d85f785 仅改 Server 恢复回执与测试，`git diff c8d6ad3..d85f785 -- cm-agent-core cm-agent-persistence` 为空。
- 客户端 `mvn -q -pl cm-agent-local-client -am test package`：39 项、0 失败/错误/跳过，包含累计输出不重复合同。
- 适配器 `mvn -q -pl cm-agent-agentscope-adapter -am test`：182 项、0 失败/错误，5 项 external/live 条件跳过；真实 Provider 证据由下表单独证明。
- Server：协议/来源/快照/命令/审批/Run 相关 55 项在 6bbe6b7 通过；d85f785 的 `ClientProjectCommandServiceTest,LocalProjectRunCompletionTest` 增量 16 项通过；最终 d85f785 定向全套 12 类 56/56、0 失败/错误/跳过；`mvn -q -DskipTests package` 全模块退出码 0，不把打包当成全量 Java 测试。
- Node `node --test src/test/js/*.test.cjs`：235/235 通过；设备项目 8 项覆盖通用路由行动头、真实 SSE 决定接口、轮询草稿保留、断流错误编号与禁止重提。
- 最终文档门禁：`DOC_CHECK_OK files=6 revision=R3 links=38 tasks=T0-T18`，清单/账本状态一致、提示词范围一致、代码围栏成对；`git diff --check -- docs` 通过。本轮文档提交范围为原六份工作包与配置/运维/发布说明共九份文档，没有新增业务代码、迁移或配置值。
- 本轮曾误用默认 JDK 17，Surefire 拒绝 class 65/运行时 61；切换 JDK 21 后串行重跑通过。运行中 Console JAR 锁导致一次 install 失败，按受控停机→install→启动恢复，没有修改配置来规避。

| 实例 | 真实验收结果 | 可复核标识 |
|---|---|---|
| W01 浏览/读取 | 既有项目 LIST/README 预览成功，客户端多次重启后根绑定可用 | project `8fb8c76c-0609-4096-a0e4-a65324181804`，device `91cbb6cc-c9e2-479f-971b-f749a69af2dc` |
| W02 仅提案 | 真模型只调用查询/提案后 Run SUCCEEDED，未启动进程 | Run `f645d25f-31f1-4fdf-8a28-56ea751d85dd` |
| W03 首次 quick 失败与核对 | 本机命令 SUCCEEDED，但 JDBC NULL 映射导致 Run FAILED；修复后由真实回执核对命令 SUCCEEDED，不把旧 Run 改成成功 | command `6997a62c-0215-47bb-836e-e3cc891adbe4`，Run `e8812644-2c52-40c3-bd50-497b19803944` |
| W04 精确批准成功 | HIGH 预览/勾选/批准→签名设备执行→command/Run 均 SUCCEEDED，退出码 0、输出可用 | command `ead91102-11d2-4324-8304-0b36faf868f5`，Run `811fa0f2-8f94-424a-abbf-909bdad4343e` |
| W05 屏障与停止 | 模型流已结束而 command/Run 仍 RUNNING；停止后 command CANCELLED/Run FAILED；父子 PID 均退出；重复停止返回 COMMAND_STATUS，查询任务 SUCCEEDED；启动标记各一次 | command `9c52da99-eada-413c-b4da-d8296fc274ca`，Run `8d63843d-00e6-4e8e-8d86-0b31d0f7e170`，PID 38412/40992，重复查询 `d4d4b30f-6a8c-4beb-9375-93c1e95797ed` |
| W06 重启/UNKNOWN/对账 | 强制重启客户端后本机回执 UNKNOWN；旧服务拒收 seq=0，完成屏障按预算收口 UNKNOWN，原主体停止使 Run CANCELLED；d85f785 部署后真实客户端成功回报，保留 seq=69/1579 字节输出，version=17；人工确认 HTTP 200 后仍 UNKNOWN/version=17，归档 409，测试进程 0，没有自动重放 | command `5a2e984f-64ab-4e13-bb7c-d0736a353038`，Run `fad25c6d-42dd-4069-a854-2b53ac4a8289`，对账审计 `ddbcbf4e-50e5-4eaf-a30a-00c22d6f64a2`，拒绝编号 `8b188e5f-af3b-40df-86a9-7afeff9de090` |

W06 的查询/回报任务成功只表示未知状态已可靠接收，命令本身仍 UNKNOWN，不能称命令执行成功。实际模型配置为 `OPENAI_COMPATIBLE/glm-5.3`，未采用 fake runtime、直接插队列或改库制造上述结果。原生目录窗口未通过，以上从既有登记项目开始。

| 提交 | 范围 | 状态 |
|---|---|---|
| `21344f4` / `1b19863` | T6/T17 主体、V29 外键修复（续验前形成） | 已在远端 |
| `0903bb5` | taskId 与列表查询边界、六份初始续验记录 | 已提交并推送 |
| `17bf309` | 协议 5 Run/派生闸门、请求头、双库验收 | 已提交并推送 |
| `9e1745e` | 微秒来源、长期历史、SSE/草稿/摘要、快命令退出 | 已提交并推送 |
| `6bbe6b7` | JDBC 空退出码与调度清理归属 | 已提交并推送 |
| `c8d6ad3` | 累计输出只接收新尾部 | 已提交并推送 |
| `d85f785` | UNKNOWN 恢复回执保留权威输出/序号 | 已提交并推送，已部署验收 |

本轮文档与发布说明在上述代码之后核对、提交并推送；不包含 `.gitignore`、设计文件、用户 application*.yml、`.workbuddy/` 等无关变更。前述“未提交/未登录/双库未运行”属于早期历史，不能继续作为当前阻塞。

## C01–C10 当前证据映射

| 编号 | 自动化证据 | 真实环境证据 | 结论 |
|---|---|---|---|
| C01 | `ClientProjectToolCatalogTest`、`AgentScopeProtectedToolPayloadTest` 覆盖独立命名空间、伪造名称和载荷保护 | 普通会话/MCP/调试/旧版全入口矩阵未运行 | 部分，T15/T14 进行中 |
| C02 | `AgentScopeProtectedToolPayloadTest`、`HarnessProtectedToolPayloadTest` 覆盖成功/失败/审批恢复和事件泄漏 | 合成 Provider 合同及真实 glm-5.3 命令链通过；Harness/全入口矩阵仍待验 | 部分，T15/T14 进行中 |
| C03 | `ClientProjectRootStoreTest` 覆盖同根别名、父子根和选择任务重放 | 不同 `--state-dir`、重新配对、真实多进程未运行 | 部分，T16/T14 进行中 |
| C04 | `ClientProjectCommandServiceTest` 覆盖提议/失败/超时；`LocalProjectRunCompletionTest` 覆盖异步 RUNNING、停止句柄保留和后台终态 | 真实客户端重启、屏障/停止/UNKNOWN 对账已验；多实例/运行中 Server 接管和注销/断电未验 | 真实命令子集通过，完整故障待补；T17/T14 进行中 |
| C05 | `ClientProjectOperationTest`、审批和 T12 服务合同覆盖单次许可及冲突计划 | 旧进程/迟到许可/真实崩溃未运行 | 部分，T12/T14 进行中 |
| C06 | 计划冲突、条件应用、审批摘要和读快照合同通过 | 回执丢失、快照保存失败、真实外部编辑器竞争未运行 | 部分，T5/T16/T14 进行中 |
| C07 | `ClientProjectCommandOperationsTest` 覆盖敏感参数、构建摘要变化、异步启动和子进程停止；`ClientProjectToolchains` 拒绝 Shell/敏感覆盖 | 真实用户配置、进程树停止、累计输出与客户端重启已验；ACL、注销/断电和脱离进程树未验 | 真实命令子集通过，完整故障待补；T6/T14 进行中 |
| C08 | Node 能力关闭与来源切换相关合同通过 | 真实登录与桌面命令交互通过；跨来源迟到 202、浏览器代际/窄屏矩阵未完成 | 部分，T18/T14 进行中 |
| C09 | 归档拒绝未决任务及后端来源限制有合同 | 设备离线/撤销后的完整历史读取未运行 | 部分，T7/T18 待补 |
| C10 | `ProjectRuntimeContextTest` 与客户端审批合同约束 ASK_ON_EFFECT、HIGH 决策和跨主体入口 | 真实浏览器精确批准与 UNKNOWN 对账通过；跨租户/退出/全权限矩阵未完成 | 部分，T13 完成但 C10 整体未闭合 |

## R1→R2→R3 差异与未验证门禁

- R1/R2 形成目录/读取、文件变更与审批合同；R3 完成命令/屏障/停止/对账模块。本轮发现并修复 taskId、空搜索词、协议 5 首发/派生路由、精度、SSE、审批草稿和摘要、JDBC NULL、调度 owner、累计输出及重启 UNKNOWN 序号兼容问题；对应证据见续验结果，不覆盖原失败记录。
- T6/T17 的真实 Windows 命令子集已通过；T9 进行中，不再受未提交、未登录或双库未运行阻塞。最终数据库代码的双库门禁通过，完整故障矩阵仍未完成。
- 未通过/未执行：原生目录窗口（已尝试但不可操作）、真实文件写入/ACL/多进程竞争、权限/设备撤销全矩阵、脱离进程树、Windows 注销/断电、远端 Server/TLS、多实例/旧版/回滚栅栏和完整窄屏/键盘/迟到响应矩阵。
- 运行中 Server 重启后的自动接管未完成；本轮 Server 重启是在 Run 已收口之后，不能作为运行接管通过证据。当前页面长轮询有预算，超过后需重新选择原会话核对，不能误报成永久自动跟踪。
- 当前验收项目有意保持 UNKNOWN 隔离；人工确认已有审计、物理测试进程数为 0，但不能据此改成 SUCCEEDED 或解除隔离。不要删除回执或改库。
- 本工作包继续承接原 R2 需求包，保留其历史；无新增迁移，V29 和协议 5 未再修改。本轮六份产物保持 R3、mode=default、T0–T18 稳定编号。

关联：[清单](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md)｜[设计](../specs/2026-11-09-client-project-workspace-delivery-design.md)｜[计划](../plans/2026-11-09-client-project-workspace-delivery.md)｜[提示词](../prompts/2026-11-09-client-project-workspace-delivery-prompt.md)｜[实现说明](../implementation/2026-11-09-client-project-workspace-delivery-implementation-design.md)。

## 2026-11-10 续验补充：目录窗口与运行中重启

- T1/T9 仍进行中。已登录项目页选择设备 `91cbb6cc-c9e2-479f-971b-f749a69af2dc` 并发起真实选择请求；页面显示等待目标电脑打开选择器。客户端 PID 5116 的 JDK 21 线程快照显示 EDT 在 `JFileChooser.showOpenDialog` 模态等待，但当前桌面工具未发现可操作的客户端窗口；未取得选择、取消或登记结果，不能判定验收通过。该观察仅限当时进程及桌面。
- 源码核对：`LocalClientTaskExecutor.selectRoot` 直接调用 `SwingClientDirectoryPicker.choose(deviceName)`；当前选择器仅将设备名用于窗口标题，未实现需求要求的先展示请求来源、关联码及用户确认；Swing `JFileChooser` 本身也不能作为操作系统原生目录窗口的通过证据。应先补齐来源绑定、确认、有效期和窗口关闭/超时处理及回归，再在交互桌面验收；不得伪造选择结果或自动登记。
- T17/C04 的运行中 Server 重启接管本轮未执行：当前客户端停在选择器模态等待，既有验收项目仍有 UNKNOWN 隔离；不能以历史 Run 收口后重启或线程快照替代该场景。需恢复可用客户端并使用独立的受控验收项目，记录重启前后命令、Run、停止/查询与隔离证据。历史已通过项仍以账本 W01–W06 及双库记录为准，其他未完成矩阵不变。
- 本次仅记录调查，未修复代码、未完成目标验收；不得据此将 T1、T9 或 T17 标为完成。
