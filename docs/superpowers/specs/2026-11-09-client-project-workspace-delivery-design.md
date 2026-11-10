# 本机客户端项目工作区实施设计

日期：2026-11-09；当前修订 R3（2026-11-10）；mode=default。需求设计以[R2 设计](2026-11-09-client-project-workspace-design.md)为权威，本文件描述实施边界、当前代码落点和实际差异。R0 建立实施包，R1 记录只读纵切面，R2 记录文件变更审批与条件写，R3 记录固定工具链命令、Run 完成屏障和停止/对账生命周期。

## 实施原则

- 新功能使用独立配置和来源，默认关闭；既有 Server 本机项目、普通聊天和通用本机客户端保持原行为。
- 新来源的关键数据链为：已认证浏览器创建选择请求 → 配对设备签名领取 → 用户在本机确认目录 → 客户端返回 rootId/身份摘要/展示名 → Server 建立项目及长期来源 → 后续请求按设备任务执行。
- Server 不保存客户端绝对路径；客户端状态和根绑定使用当前 OS 用户私有目录及跨进程锁。文件 I/O 不持有 JDBC 事务。
- UI 查询采用有界异步任务；模型工具可在受控时间内等待，但命令启动后的生命周期由持久操作和独立状态入口承载。

## 当前代码落点

- 客户端：`ClientProjectRootStore`、`ClientProjectFileOperations`、`ClientProjectChangeOperations`、`ClientProjectOperationJournal`、Swing 目录选择器和 Windows 文件对象身份共同完成根约束、读取、条件变更与私有回执；`LocalClientApplication` 交替领取普通任务和协议 5 设备项目任务。
- 命令客户端：`ClientProjectToolchains` 只读取当前 OS 用户显式配置的固定工具链；`ClientProjectCommandOperations` 形成冻结清单、显式环境异步启动、有界输出、状态/停止/进程树观察；`ClientProjectCommandJournal` 保存不含 argv、环境和输出的私有回执。
- Server：`ClientProjectService` 编排选择、任务、项目、计划和终态；`ClientProjectRunService` 固定会话/Run 来源并初始化读取快照；`ClientProjectReadSnapshots` 固定本轮正文；`ClientProjectToolCatalog`/`ClientProjectTools` 提供 12 个固定工具；`ClientProjectController` 暴露 `/api/client-projects`。
- 命令 Server：`ClientProjectCommandService` 编排工具链清单、命令计划、精确 HIGH 审批、QUEUED 一次许可、状态/停止、UNKNOWN 对账和 Run 完成屏障；`RunExecutionService` 在模型结束后异步等待命令，失败/超时保留停止入口，终态后才收口 Run；`ConversationService`/`ToolApprovalService` 在异步屏障期间保留执行控制句柄。
- 持久化：`cm-agent-core` 新增客户端项目、任务、变更操作、会话/Run 绑定领域与 Repository；`cm-agent-persistence` 新增 JDBC 实现；V29 在 PostgreSQL 和 MySQL 同版本增加五张专用表、任务 Run/会话字段和协议 5 约束。
- 前端：`projects.html` 增加独立来源分栏和变更审批区域；`client-projects.js` 提供 capability、设备选择、202 轮询、项目列表、目录/文本/搜索、会话 requestId 幂等恢复、精确变更预览和 HIGH 审批。
- 适配器：AgentScope ReAct/Harness 识别服务端固定的协议 5 工具 ID，并对成功、失败、超时、审批恢复、ToolCall、SSE 和检查点沿用项目敏感载荷保护。
- 配置与说明：客户端项目使用独立 `cm-agent.client-projects` 开关；`configuration.md`、`operations.md` 和 `release-notes.md` 明确默认关闭、显式工具链配置、逐次文件/命令审批、Run 屏障与 UNKNOWN 根隔离边界。

## R2 实际范围与差异

| 差异 | 实际采用 | 与 R2 的边界 |
|---|---|---|
| R1-01 读取能力先交付 | 目录、脱敏 UTF-8 分页读取和字面搜索先形成可运行纵切 | 不开放写入、撤销、命令或完整开发能力 |
| R1-02 任务队列 | SELECT_ROOT 与 LIST/READ/SEARCH 共用加密任务表和条件状态更新 | 三类任务语义尚未扩到启动许可与命令生命周期 |
| R1-03 来源隔离 | 协议 5、专用工具目录和固定设备任务端点不与协议 4/`project_*` 混用 | 双库与所有派生入口仍需完整验收集 |
| R1-04 根约束 | 当前 OS 用户私有登记、跨进程锁、Windows 文件 ID、父子根冲突拒绝 | 串行项目代次、旧许可和写后快照依赖后续副作用实现 |
| R1-05 页面 | 独立来源分栏和异步任务适配已接入项目页 | 完整离线历史、变更/命令审批及真实浏览器代际验收未完成 |
| R1-06 开发运行 | 只读项目会话默认使用 ASK_ON_EFFECT，自动授予三个只读工具 | 服务端本机项目与客户端项目仍可能共享 Agent 工具定义，因此 Agent 级授权不是唯一隔离边界；Run 侧固定目录和协议校验必须保留 |
| R2-01 只读门禁 | 增加协议 5 请求头、每设备单在选择窗口、文件对象身份前后核对和 Run 级加密读取快照 | 真实 Windows 外部编辑器竞争仍由 T9 验证 |
| R2-02 变更计划 | 设备先读取当前对象并形成原始/脱敏摘要、原文和对象身份计划，计划阶段零写入 | 客户端私有回执只保存编号、rootId、摘要和状态 |
| R2-03 审批闸门 | Apply 为 HIGH；后端审批决定复核 operationId/version/digest 与 `displayExact`，掩码掩盖差异只能拒绝 | 页面不是唯一门禁，API 直接提交也会被拒绝 |
| R2-04 条件应用 | 写前复核对象身份和 SHA-256；创建/替换/删除/移动文件及空目录动作可应用，部分成功返回 UNKNOWN | 不宣称多文件原子性 |
| R2-05 撤销与许可 | 成功操作可生成新的反向计划并重新审批；PROPOSED→APPLYING 由操作版本和任务登记共同约束一次派发 | R2 当时仅覆盖文件；命令许可由 R3-02 补齐，T12 仍因真实竞态待验而进行中 |
| R2-06 页面恢复 | 新会话 requestId 作为会话幂等键；审批预览、决策和状态轮询绑定当前来源与加载代次 | 真实浏览器 320/390px 与断开恢复仍待 T9 |
| R2-07 组合冲突 | 计划阶段拒绝重复源/目标、大小写别名、普通文件父路径和不可安全排序的父子重叠 | 显式创建目录作为后续动作父级仍允许；真实多进程竞态待 T9 |
| R2-08 回执恢复 | 新 Journal 实例可读取旧 RUNNING/UNKNOWN 回执，同 operationId 再次执行被拒绝 | 自动恢复已完成状态而非重放，真实崩溃/断电待 T9 |
| R2-09 迁移门禁 | MigrationTest 纳入 V29 六表、外键、索引和协议 5 注释；MySQL 补齐既有列注释 | 静态及编译通过，双库运行仍待 Rocky 环境 |
| R3-01 固定工具链 | 当前用户显式配置绝对可执行文件、固定前缀、完整环境和允许覆盖项；Shell、敏感参数和配置后变化拒绝 | 脚本、插件、依赖与外部服务不能完整冻结 |
| R3-02 精确启动许可 | PROPOSED 计划绑定版本/完整摘要/展示摘要，HIGH 审批后原子登记 QUEUED 和唯一启动任务；重复/迟到任务由命令状态拒领 | 分布式“撤销先于本机 syscall”不做绝对承诺 |
| R3-03 进程生命周期 | 固定环境启动，有界脱敏输出，状态/停止，已观察后代进程清理，客户端退出有界等待；重启把孤立运行回执改为 UNKNOWN | 未观察到的快速脱离后代、断电和注销仍可能无法证明静止 |
| R3-04 Run 完成屏障 | 仅提议未启动命令不阻断成功；已启动命令全部收敛后才允许成功；失败/拒绝也登记停止；屏障异步不占 HTTP/JDBC 事务 | 真实设备离线、Server 重启和浏览器重连仍由 T9 |
| R3-05 停止与对账 | 重复停止只查询状态；超时先派发停止再将未决命令收口 UNKNOWN；人工确认只终结逻辑意图，根隔离随 UNKNOWN 保留 | 设备撤销后的受限回执和本机独立停止 UI 尚未完成 |

## 当前已知未闭合点

- 文件正文搜索只检索当前 Run 已冻结文本，其余路径搜索仍为实时设备任务并显式声明 `partial`。
- 多文件条件写可产生部分成功后失败；服务端和客户端都必须保留 UNKNOWN，不能自动重放。
- V29 已完成静态双方言检查并通过 MigrationTest 编译；Rocky PostgreSQL 16/MySQL 8.4 迁移、原生注释和并发合同未运行。远端根分区约余 1.8 GiB 且已有多套无关服务，本轮未启动新 Testcontainers。
- 8080 服务可访问，但浏览器因无已登录会话被重定向到登录页；项目页桌面、390px、320px、键盘和迟到响应仍待具备会话后的真实交互。
- R3 已完成固定工具链、命令启动许可、远端 Run 完成屏障、停止/查询/UNKNOWN 对账和客户端根隔离的模块级实现；仍缺真实 Windows 进程树/断电/注销、设备撤销后的本机独立停止 UI、Server 重启、真实 Provider、浏览器和双库证据。

## 批次

1. 基础：T0 完成；T10 的独立装配和默认关闭已落地，升级/回滚验证待做。
2. 远端根与任务：T1/T2/T11/T16 已完成只读子集，故障、重启和双库门禁待补。
3. 来源与只读开发：T3/T4/T15 已形成可运行纵切，快照、全入口伪造和恢复矩阵待补。
4. 副作用：T5/T12/T13 已形成模块级文件变更纵切；R3 为 T6/T17/T12 增加固定工具链、命令许可、异步屏障、停止去重和 UNKNOWN 对账，真实故障矩阵仍待 T9。
5. 生命周期与页面：T7/T8/T18 已接入部分安全收口和页面，完整历史和交互验收待补。
6. 验收：T14 已建立 C01–C10 部分映射并保持进行中；T9 受未提交版本、Rocky 容量和已登录浏览器会话阻塞。

关联：[清单](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md)｜[计划](../plans/2026-11-09-client-project-workspace-delivery.md)｜[提示词](../prompts/2026-11-09-client-project-workspace-delivery-prompt.md)｜[实现说明](../implementation/2026-11-09-client-project-workspace-delivery-implementation-design.md)｜[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)。
