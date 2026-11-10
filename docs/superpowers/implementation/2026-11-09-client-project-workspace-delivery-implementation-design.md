# 本机客户端项目工作区实施说明

日期：2026-11-09；当前修订 R3（2026-11-10）；mode=default；未提交。

R0 只建立了独立实施工作包并完成 T0 的初始环境/代码调查。R1 交付“协议 5 只读纵切面”。R2 补充只读门禁和文件变更审批纵切：协议版本头、单在途选择、Windows 文件身份、Run 级读取快照、变更计划、HIGH 审批、条件应用、条件撤销和客户端私有回执。R3 完成固定工具链命令、命令启动许可、有界输出、进程树停止、Run 完成屏障和 UNKNOWN 对账的模块级纵切。

## R1 实际实现

- 客户端选择与根：`ClientDirectoryPicker`/`SwingClientDirectoryPicker` 在当前用户交互桌面打开系统目录窗口；`WindowsClientProjectDirectoryIdentity` 通过只读 Windows 句柄取得卷和目录对象身份；`ClientProjectRootStore` 在用户私有目录用跨进程锁保存绝对路径，向 Server 只返回 `rootId`、身份摘要和展示名，并拒绝 UNC/网络盘、私有状态区、同根别名及父子根。
- 客户端只读执行：`ClientProjectFileOperations` 对每个任务重新核验根身份，限制相对路径、链接、排除目录、条目数、递归深度、文本大小和偏移；目录结果游标化，文本结果按 UTF-8 边界分页并返回原文摘要，搜索限制扫描/命中预算并声明 `partial`。
- 客户端任务循环：`LocalClientTaskExecutor` 按固定类别执行 `PROJECT_SELECT_ROOT` 和 `PROJECT_READ_ONLY`；`LocalClientApplication` 交替领取普通本机任务与协议 5 项目任务；`LocalClientServerClient` 使用独立签名端点和回报合同，结果丢失不自动重放。
- Server 任务与项目：`ClientProjectService` 在短事务外读取设备，在短事务内登记任务/项目，只保存认证加密请求与结果；选择成功后同事务创建项目和推进任务，领取/完成/过期使用版本条件更新，项目存在未决任务时拒绝归档。
- 来源与工具：`ClientProjectRunService` 新增协议 5 的会话/Run 来源、设备/根/Agent/所有者一致性和 `ASK_ON_EFFECT` 限制；`ClientProjectToolCatalog` 生成租户稳定的 `cm-agent/client-project/v1` 工具 ID 和 `client_project_list_files`、`client_project_read_file`、`client_project_search_files`；`ClientProjectTools` 将调用转为有界设备任务。
- 适配器保护：`ProjectRuntimeContext` 可同时表达 Server 本机来源或客户端项目来源，但构造时强制二者只能有一个；AgentScope ReAct/Harness 仅按服务端提供的固定工具 ID 识别受保护载荷，不信任模型给出的工具名。
- 页面：`projects.html` 和服务端项目保持原入口，新增隐藏的“已配对电脑”来源；`client-projects.js` 分开请求 capability、设备和项目合同，使用专用行动头、202→轮询→终态、`sessionEpoch` 与加载代次，离页/切项目丢弃迟到结果。
- 持久化：V29 以语义一致的 PostgreSQL/MySQL 方言新增 `client_projects`、`client_project_tasks`、`client_project_conversations`、`client_project_runs`，将协议 5 纳入运行和会话约束，并为全部表和字段提供原生中文注释。

## R2 追加实现

- 只读门禁：专用设备项目端点要求 `X-CM-Client-Project-Protocol: 5`；目录选择在设备行锁内检查并限制一个未决窗口；文本读取在内容前后核对 Windows 文件对象身份、大小、修改时间与 SHA-256；模型读取使用 `ClientProjectReadSnapshots` 在 Run 内固定加密快照。
- 变更计划：新增 `client_project_operations` 和 `ClientProjectOperation`，计划任务由设备核对当前文件后返回原文/脱敏差异、对象身份与 SHA-256；Server 只保存认证加密计划，计划阶段没有项目文件副作用。
- 精确审批：新增 `client_project_propose_changes`、`client_project_apply_changes`、`client_project_propose_undo`、`client_project_change_status`。Apply 为 HIGH，只接受 operationId/version/digest；后端审批决策复核计划状态、版本、摘要和 `displayExact`，掩码掩盖真实差异时返回冲突。
- 条件应用：客户端支持创建/替换/删除/移动文件及创建/删除空目录；执行前重新核对对象身份和原文摘要，使用同目录临时文件和原子移动写入，部分成功转为 UNKNOWN。
- 私有恢复：`ClientProjectOperationJournal` 在 `~/.cm-agent/client-project-operations` 记录 RUNNING/SUCCEEDED/FAILED/UNKNOWN，只保存 operationId、rootId、计划摘要和时间，不保存正文或绝对路径；重复 operationId 不能覆盖旧回执。
- 撤销与许可：`ClientProjectOperation` 只允许 PROPOSED→APPLYING 一次派发；成功操作可生成新的反向计划和新的 HIGH 审批。任务与操作状态联动，任务过期按领取阶段取消或转 UNKNOWN，项目归档被 APPLYING/UNKNOWN 阻断。
- 页面：`projects.html` 增加变更审批区域，`client-projects.js` 使用专用预览接口展示脱敏差异，`canApproveChange` 只有在版本/摘要匹配、预览完整且 `displayExact=true` 时才允许；新会话 requestId 作为幂等键，未知响应后可恢复原会话。
- 快照更新：文件变更成功后，`ClientProjectReadSnapshots` 按已核验的变更结果推进或墓碑化受影响文件，迟到读取不能返回旧正文。
- 组合冲突：`ClientProjectChangeOperations` 在计划阶段拒绝同批重复源/目标、Windows 大小写别名、普通文件父路径及不可安全排序的父子重叠；显式创建目录作为子动作父级仍保留。
- 回执恢复：新增跨 `ClientProjectOperationJournal` 实例测试，证明新进程可读取旧 RUNNING/UNKNOWN 回执，且同一 operationId 不能再次执行。
- 迁移门禁：`MigrationTest` 纳入 V29 六张表及关键外键/索引；MySQL V29 同步更新两列协议 5 注释，使双方言语义一致。

## R3 追加实现

- 固定工具链：`ClientProjectToolchains` 从 `~/.cm-agent/client-toolchains.json` 或 `-Dcm.agent.client.toolchains.path` 读取最多 32 项当前用户显式配置；拒绝相对程序、链接/非普通文件、Shell、超大程序和疑似凭据覆盖。Server/模型只看到逻辑 ID、展示名和程序文件名。
- 冻结清单：`ClientProjectCommandOperations.plan` 固定工具链定义摘要、可执行文件 SHA-256、固定与模型参数、项目相对工作目录、完整显式环境和根目录主要构建配置摘要；敏感参数在计划阶段拒绝。客户端启动前重新核对上述摘要，变化时旧批准失效。
- 精确启动许可：`ClientProjectCommandService` 只接受 `commandId/version/digest`；审批后端再次读取命令状态和摘要，`client_project_run_command` 为 HIGH。短事务同时登记唯一 `RUN_COMMAND` 任务并把 PROPOSED 推进为 QUEUED，设备领取前再核对命令来源和状态。
- 命令运行：`ClientProjectCommandOperations` 使用不含 Shell 的 argv、项目根内工作目录和完全替换的固定环境异步启动；输出上限 512 KiB、合并 stdout/stderr、脱敏后回传。`ClientProjectCommandJournal` 在 `~/.cm-agent/client-project-commands` 保存无 argv、环境、输出和绝对路径的私有回执；客户端重启将孤立 RUNNING/STOP_REQUESTED 回执改为 UNKNOWN。
- 失败分型：本机执行槽占用、进程未创建、清单再校验失败等可证明无副作用的启动拒绝返回 FAILED；进程或监视线程已经创建但随后回报失败、无法取得静止证据时返回 UNKNOWN。服务端按 `possibleSideEffects` 消费本地回报，不再把所有启动失败统一误记为 UNKNOWN。
- 进程树停止：`LocalCommandOperations` 记录本次根进程下已观察后代，停止时逆序终止后代和根进程；只有根进程已退出且已观察后代均不存活时才返回可核验终端状态。根退出但后代仍存活时先清理，成功状态只能用于无残留树的正常退出。客户端关闭会请求停止并最多等待 7 秒让监视线程收口。
- Run 完成屏障：`ClientProjectCommandService.awaitRunSettled` 只把实际进入 QUEUED/RUNNING/STOP_REQUESTED/UNKNOWN 的命令纳入屏障，仅 PROPOSED 未启动命令不阻断 Run；任何已执行命令非 SUCCEEDED 时 Run 不通过。Server 使用独立虚拟线程与超时，不占 HTTP 请求线程或 JDBC 事务。成功后异步回写 Run 终态，失败/拒绝也登记停止。
- 停止与对账：重复停止或 `stopRequested=true` 只创建状态核对任务，不重复派发停止。屏障超时先关闭旧状态任务、登记 STOP_REQUESTED，再把本轮未决命令无条件收口为 UNKNOWN；迟到设备证据仍可在受控状态下推进终态。人工 `resolveUnknown` 只追加元数据审计并返回原 UNKNOWN，不改变版本、不释放隔离、不重放命令。
- 异步控制：`ConversationService` 和 `ToolApprovalService` 在收到 `RUNNING` 命令屏障信号时保留 Run 执行句柄；`RunExecutionService` 的异步最终回写使用同一 Run 短锁并在结束后释放，因此用户在等待期间仍可停止。
- 页面：项目页能力报告 `commands=true`；`client-projects.js` 支持命令精确预览、查看状态/有界输出、请求停止与 UNKNOWN 人工确认，所有命令内容按纯文本渲染，离页或切项目丢弃迟到结果。

## R1/R2 历史验证与 R3 当前验证

- `mvn -q -pl cm-agent-local-client -am clean test`：通过，32/32，失败、错误、跳过均为 0。
- `mvn -q -pl cm-agent-server -am "-Dtest=ClientProjectServiceTest,ClientProjectRequestBoundaryTest,ClientProjectToolCatalogTest,ToolOperationCategoriesTest,ProjectRuntimeContextTest,ClientProjectRootStoreTest,LocalClientServerClientTest,AgentScopeProtectedToolPayloadTest,ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" clean test`：通过。
- `mvn -q -pl cm-agent-agentscope-adapter -am test`：通过，181 项执行，失败和错误均为 0；5 项 live/external 条件项跳过。
- `node --test src/test/js/*.test.cjs`：232/232 通过。
- `mvn -q -DskipTests package`：通过。
- R2 定向 Java：`ClientProjectServiceTest,ClientProjectRunServiceTest,ClientProjectToolCatalogTest,ClientProjectApprovalGuardTest,ClientProjectOperationTest,ToolOperationCategoriesTest,ProjectRuntimeContextTest,ClientProjectRootStoreTest,ClientProjectChangeOperationsTest,LocalClientServerClientTest,AgentScopeProtectedToolPayloadTest,ConsoleResourceTest`：通过。
- R2 追加合同：`ClientProjectOperationJournalTest` 与 `ClientProjectApprovalGuardTest` 的跨实例回执、审批版本/摘要专项通过；`ClientProjectChangeOperationsTest` 的重复目标、大小写别名和父子冲突拒绝通过。
- R2 前端门禁：`node --check` 通过；Node 全量 232/232，失败、错误、跳过均为 0。
- V29 PostgreSQL/MySQL 静态检查：`MIGRATION_STATIC_OK tables=5 columns=67 tableComments=5 preexistingColumnComments=2 bothDialects=V29 comments=complete`；MigrationTest 编译通过，但数据库运行未执行。
- R2 历史文档检查：当时六份均为 R2、`mode=default`，T0–T18 完整，清单与账本状态一致，相对链接有效。
- `git diff --check`：通过；保留工作树已有 CRLF 转换提示，不动用户配置和无关修改。
- R3 客户端模块：`mvn -q -pl cm-agent-local-client -am test` 通过，37/37；新增固定工具链配置变化、敏感参数、子进程树停止合同。
- R3 Server 定向合同：7 个测试类、36/36；`ClientProjectCommandServiceTest` 9/9、`LocalProjectRunCompletionTest` 5/5，覆盖仅提议不阻断成功、已执行失败/超时不通过、启动前确定失败与启动后未知区分、计划和状态原子终态、终态查询幂等、重复停止转状态查询、超时 UNKNOWN 和异步 RUNNING 保留停止句柄。
- R3 适配器：`mvn -q -pl cm-agent-agentscope-adapter -am test` 通过，182 项，0 失败、0 错误、5 项 external/live 条件跳过。
- R3 Node：`node --check` 及全量测试通过，233/233；覆盖命令精确预览、停止不重放和 UNKNOWN 人工对账。
- R3 Core/Console：`mvn -q -pl cm-agent-core -am test` 与 `mvn -q -pl cm-agent-console -am test` 均退出码 0。
- R3 V29 静态双方言检查：`MIGRATION_STATIC_OK tables=6 pgColumnComments=91 myColumnComments=89 tableComments=6/6`；新表集合一致，全部表有注释，89 个新增列均由方言注释覆盖，PostgreSQL 另更新 2 个既有列注释。
- `mvn -q -pl cm-agent-persistence -am "-DskipTests" package` 与 `mvn -q "-DskipTests" package`：均退出码 0。

## 未完成和限制

- Rocky PostgreSQL 16/MySQL 8.4 的 V29 迁移、原生注释、并发和重启验证尚未执行；当前未提交工作树不满足仓库要求的远端提交对齐条件，远端根分区仅余约 1.8 GiB 且已有大量无关容器，本轮未启动新 Testcontainers。
- 尚未进行真实 Windows 目录窗口自动化、远端 Server/TLS 配对、真实 Provider 工具调用、设备撤销/离线、Run 重连和完整浏览器窄屏/键盘验收。8080 的 Playwright 访问被重定向到登录页，当前无已登录项目会话。
- 多文件条件写不保证原子；部分成功后的 T5/T12 真实断线、客户端重启、外部编辑器和 Windows ACL 竞争仍待 T9。
- `ClientProjectController.Capabilities` 当前报告 `changes=true`、`commands=true`；命令入口仍受功能开关、协议 5、Run 固定来源、精确审批、当前设备和当前权限共同限制。
- 真实 Windows 进程树、断线、客户端重启/停机/注销/断电、设备撤销后的受限回执、Server 重启、TLS、真实 Provider、浏览器和 Rocky 双库尚未证明；本轮静态项目页 URL 返回 200 但无已认证会话证据，模块合同不能替代 T9 端到端验收。
- Rocky 只读复核显示指定三套镜像存在，但根分区 70 GiB 已用 69 GiB、仅余 1.8 GiB（98%）；本地未提交工作树无法满足远端提交对齐门禁，因此未启动 Testcontainers。

关联：[清单](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md)｜[设计](../specs/2026-11-09-client-project-workspace-delivery-design.md)｜[计划](../plans/2026-11-09-client-project-workspace-delivery.md)｜[提示词](../prompts/2026-11-09-client-project-workspace-delivery-prompt.md)｜[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)。
