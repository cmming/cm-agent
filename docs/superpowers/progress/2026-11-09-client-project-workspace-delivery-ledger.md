# 本机客户端项目工作区实施账本

日期：2026-11-09；当前修订 R3（2026-11-10）；mode=default；未提交。

## R0 历史基线

- R3 续验起点（2026-11-10）：当前 HEAD `1b19863`，远端已有主体 `21344f4`；本轮六个代码/测试文件和六份工作包、发布说明尚未提交。无关配置、设计、忽略规则和工作目录保留不动。
- 本轮 `mvn -q -pl cm-agent-local-client,cm-agent-server -am "-Dtest=ClientProject*Test,LocalClientServerClientTest,LocalClientTaskExecutorTest,LocalProjectRunCompletionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：JDK 21.0.11/Maven 3.9.4，退出码 0；客户端匹配 19 项、Server 匹配 27 项，均无失败/错误/跳过。未运行数据库测试（该命令没有匹配持久化测试）。
- Rocky 当前根分区余量约 1.7 GiB、可用内存约 3.7 GiB；既有 `/tmp/cm-agent-verify-v29fix-20261110` 对应 `1b19863` 的 MigrationTest 报告 3/3 通过。这是恢复发现的历史报告，不作为本轮完整双库复跑或端到端通过。接下来用对齐新提交的隔离目录和已有 Maven 缓存串行验证，禁止清理无关容器/卷。
- HEAD `0a96c6a0a70c77c20d29e325e276a7eba7ce5010`，工作树已有用户配置、设计文件及原 R2 工作包改动，保持不动。
- Maven 3.9.4；`F:\java21` 提供 JDK 21.0.11。默认 shell 为 JDK 17，后续命令显式切换。
- R0 形成时最高迁移为 V28；候选 V29 已在本 R2 实施并通过静态双方言检查。

## R3 当前状态

| 编号 | 状态 | 证据或未执行项 |
|---|---|---|
| T0 | 完成 | 已核对 Git/JDK/Maven、模块边界、V29 与主要入口；环境/代码调查证据见本账本 |
| T1 | 进行中 | Swing 选择器、取消、无桌面和协议 5 版本头合同已实现；真实窗口自动化和超时恢复未验 |
| T2 | 进行中 | 选择任务、根登记、项目创建、加密正文、单在途窗口和版本条件更新已实现；客户端重启与双库未验 |
| T3 | 进行中 | 协议 5、会话/Run 固定来源和 Controller 已接入；全部派生入口、双库并发和权限撤销未完成 |
| T4 | 进行中 | 目录/读取/搜索、路径/链接/预算、文件对象替换见证和 Run 固定读取快照已实现；真实 Windows 竞争未完成 |
| T5 | 进行中 | 六类文件/空目录动作、精确预览、HIGH 审批、条件应用/撤销和私有回执已实现；新增计划冲突与跨 Journal 实例回执测试，真实多进程竞争、断线和断电仍待验 |
| T6 | 进行中 | 固定工具链、冻结清单、HIGH 启动许可、显式环境、有界输出、状态、停止、私有回执、已观察子进程清理和客户端关闭等待已实现；真实 Windows 进程树/断线/注销/断电待 T9 |
| T7 | 进行中 | 未决任务阻塞归档、过期状态和条件终态已实现；撤销、重启恢复和故障注入未完成 |
| T8 | 进行中 | 独立来源分栏、文件/命令精确预览、状态、停止和 UNKNOWN 对账已实现；8080 当前被重定向到登录页，无已登录项目页会话，真实浏览器响应式验收未完成 |
| T9 | 阻塞 | 当前工作树未提交；Rocky Docker 可用但根分区约余 1.8 GiB 且已有大量无关容器，未启动新 Testcontainers。真实 Windows 窗口、远端 TLS、真实 Provider、设备撤销/离线及重连仍待验 |
| T10 | 进行中 | 独立属性、默认关闭和 local/test+JDBC+设备通道校验已实现；停机升级/回滚/旧程序栅栏未验 |
| T11 | 进行中 | 选择、只读、变更及命令任务使用独立签名队列并交错轮询；命令异步启动后由状态/停止任务控制；真实断线、TTL、客户端重启和故障注入未完成 |
| T12 | 进行中 | 文件与命令均具备一次派发许可；命令 QUEUED 前复核状态/摘要，设备领取再复核 claimable；重复停止只查询；旧进程、迟到许可和真实崩溃竞态待 T9 |
| T13 | 完成 | 原始/展示摘要分离、HIGH 决策后端复核、`displayExact` 拒绝和页面门禁均有合同 |
| T14 | 进行中 | C04/C07 已形成模块级合同；C01/C02/C03/C05/C06/C08/C09/C10 仍缺真实 Windows、断线、重启、Provider、浏览器或双库证据 |
| T15 | 进行中 | 12 个专用工具 ID、风险分类、命令审批定位摘要和 ReAct/Harness 载荷保护已有定向测试；全入口伪造、旧版、MCP、断线和恢复矩阵未完成 |
| T16 | 进行中 | Windows 目录/文件身份、跨进程冲突域、写前预算和写后快照已实现；项目代次、旧许可和真实竞争待验 |
| T17 | 进行中 | 有界异步 Run 完成屏障、仅提议不阻断成功、失败/拒绝停止登记、超时 STOP_REQUESTED→UNKNOWN、重复停止查询、人工对账和根隔离已实现；Server 重启/设备撤销/真实故障待 T9 |
| T18 | 进行中 | 独立 capability/列表/详情、202 轮询、sessionEpoch、加载代次和新会话 requestId 恢复已实现；完整离线历史与真实浏览器验收未完成 |

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

## R3 验证

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

## C01–C10 当前证据映射

| 编号 | 自动化证据 | 真实环境证据 | 结论 |
|---|---|---|---|
| C01 | `ClientProjectToolCatalogTest`、`AgentScopeProtectedToolPayloadTest` 覆盖独立命名空间、伪造名称和载荷保护 | 普通会话/MCP/调试/旧版全入口矩阵未运行 | 部分，T15/T14 进行中 |
| C02 | `AgentScopeProtectedToolPayloadTest`、`HarnessProtectedToolPayloadTest` 覆盖成功/失败/审批恢复和事件泄漏 | 使用合成 Provider；真实 Provider 未运行 | 部分，T15/T14 进行中 |
| C03 | `ClientProjectRootStoreTest` 覆盖同根别名、父子根和选择任务重放 | 不同 `--state-dir`、重新配对、真实多进程未运行 | 部分，T16/T14 进行中 |
| C04 | `ClientProjectCommandServiceTest` 覆盖提议/失败/超时；`LocalProjectRunCompletionTest` 覆盖异步 RUNNING、停止句柄保留和后台终态 | 真实设备断线、Server 重启、Windows 注销/断电未运行 | 模块级部分通过，T17/T14 进行中 |
| C05 | `ClientProjectOperationTest`、审批和 T12 服务合同覆盖单次许可及冲突计划 | 旧进程/迟到许可/真实崩溃未运行 | 部分，T12/T14 进行中 |
| C06 | 计划冲突、条件应用、审批摘要和读快照合同通过 | 回执丢失、快照保存失败、真实外部编辑器竞争未运行 | 部分，T5/T16/T14 进行中 |
| C07 | `ClientProjectCommandOperationsTest` 覆盖敏感参数、构建摘要变化、异步启动和子进程停止；`ClientProjectToolchains` 拒绝 Shell/敏感覆盖 | 真实用户配置目录、ACL、断线和异常退出未运行 | 模块级部分通过，T6/T14 进行中 |
| C08 | Node 能力关闭与来源切换相关合同通过 | 真实登录、跨来源迟到 202 和浏览器代际未运行 | 部分，T18/T14 进行中 |
| C09 | 归档拒绝未决任务及后端来源限制有合同 | 设备离线/撤销后的完整历史读取未运行 | 部分，T7/T18 待补 |
| C10 | `ProjectRuntimeContextTest` 与客户端审批合同约束 ASK_ON_EFFECT、HIGH 决策和跨主体入口 | 页面跨租户/刷新/退出和真实浏览器未运行 | 部分，T13 完成但 C10 整体未闭合 |

## R1→R2→R3 差异与未验证门禁

- R2 的文件变更范围已完成模块级实现；当时 T5/T12 的真实 Windows/断线与命令启动许可仍未完成。R3 已补齐命令许可，但 T5/T12 的真实竞态仍需 T9，不能把模块合同当成端到端通过。
- V29 静态双方言和测试编译通过；未在 Rocky PostgreSQL 16/MySQL 8.4 上运行迁移。除未提交/版本对齐外，远端根分区仅余约 1.8 GiB 且已有多套无关服务，继续启动 Testcontainers 会影响现有环境。
- 未执行真实 Windows 目录窗口自动化、远端服务配对/TLS、真实 Provider、设备离线/撤销、跨进程崩溃恢复和浏览器桌面/390px/320px 真实交互；相应任务保持进行中或阻塞。
- T14 当前已映射全部 C01–C10。R3 将 C04/C07 推进为模块级部分通过，但全部条目仍缺至少一类真实 Windows、断线/重启、Provider、浏览器或双库证据，不能标记完成。
- 本工作包与需求工作包不是两组验收：R2 方案包只定义目标；本 R2 实施账本记录真实交付与证据。后续新增业务代码继续复用本包，不创建重复主题。
- R3 将 T6/T17 从待做推进为“模块级完成、进行中”：固定工具链、命令计划/审批/启动/状态/停止、进程树观察、私有回执、异步 Run 屏障和 UNKNOWN 对账已实现；真实 Windows/断线/崩溃/注销、设备撤销后的受限回执、Server 重启、TLS/Provider/浏览器和 Rocky 双库仍是 T9 门禁。
- R3 不修改 V1–V28；命令表、命令 ID 和协议仍然位于 V29 与协议 5，新增文件没有引入新的数据库迁移。

关联：[清单](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md)｜[设计](../specs/2026-11-09-client-project-workspace-delivery-design.md)｜[计划](../plans/2026-11-09-client-project-workspace-delivery.md)｜[提示词](../prompts/2026-11-09-client-project-workspace-delivery-prompt.md)｜[实现说明](../implementation/2026-11-09-client-project-workspace-delivery-implementation-design.md)。
