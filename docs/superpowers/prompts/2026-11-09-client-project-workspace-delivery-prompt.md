# 本机客户端项目工作区实施提示词

日期：2026-11-09；当前修订 R3（2026-11-10）；mode=default。R2 完成只读门禁和文件变更审批/条件应用/撤销；R3 完成固定工具链命令、Run 完成屏障、停止/查询/UNKNOWN 对账和客户端进程树生命周期的模块级实现。本提示词当前用于真实端到端验收与未完成 T1–T18，不修改 R2 需求包历史结论。

关联：[清单](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md)｜[设计](../specs/2026-11-09-client-project-workspace-delivery-design.md)｜[计划](../plans/2026-11-09-client-project-workspace-delivery.md)｜[实现说明](../implementation/2026-11-09-client-project-workspace-delivery-implementation-design.md)｜[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)。

```text
仓库：F:\java\cm-agent
先读取 AGENTS.md、R2 需求工作包及本 R3 实施工作包六份文档。当前 mode=default，工程细节自主决定并记录假设。T0/T13 已完成；T6/T17 已达到模块级可运行和自动化合同，但真实 Windows/断线/崩溃/注销、Server 重启、设备撤销、双库、Provider、TLS 和浏览器证据仍未完成。禁止重复实现或覆盖当前未提交改动，按依赖推进 T1–T18。

R2 追加交付：客户端 Windows 文件对象身份、协议版本头、每设备单在选择窗口、Run 级固定读取快照；`client_project_propose_changes`、`client_project_apply_changes`、`client_project_propose_undo`、`client_project_change_status`；原文摘要/脱敏摘要分离、HIGH 审批后端复核、条件应用、部分成功 UNKNOWN、客户端私有操作回执及项目页逐项预览。

R2 收口验证新增：审批版本或摘要任一不一致都必须拒绝；新本机进程实例读取旧回执后不得复用 operationId；同批重复目标、Windows 大小写别名和普通文件父子路径必须在计划阶段拒绝；MigrationTest 必须包含 V29 六张表并验证协议 5 注释，MySQL 同步更新既有 `workspace_protocol` 列注释。

R3 已实现：当前 OS 用户显式 `client-toolchains.json`、绝对可执行文件与固定前缀、完整环境替换、敏感参数/Shell 拒绝；命令计划冻结程序 SHA-256、argv、工作目录、环境摘要和主要构建配置摘要；HIGH 精确审批后原子登记 QUEUED 与唯一启动任务；有界脱敏输出、状态、重复停止转状态查询、私有命令回执、已观察后代进程清理和客户端退出等待。Run 在模型结束后异步等待已启动命令收敛；仅提议命令不阻断成功，运行失败/拒绝仍登记停止，屏障超时先派发停止再把未决命令收口 UNKNOWN，人工确认不解除根隔离。异步屏障期间停止控制句柄保持可达。

R1 已交付内容仍有效；R2 复核后只补未满足验收，不把 T1–T5、T7–T8、T10–T12、T15–T16、T18 的进行中状态误改为完成。T13 的模块级验收已完成，真实浏览器与双库证据仍归 T9。

保护所有用户已有修改。先使用 .codegraph 和定点源码确认现状；CodeGraph 未索引新增文件时退回定点读取。Java 构建显式使用 JAVA_HOME=F:\java21。新增功能默认关闭，独立于 cm-agent.local-projects。不得复用 project_* 工具 ID 或通用 local_client_* 作为远端客户端项目执行器。

客户端项目必须采用专用来源、设备任务和 Windows 根约束；绝对路径只在客户端保存。浏览器与 Server 只接收不透明 rootId、目录身份摘要和受限展示名。目录选择必须由目标设备用户确认；取消、无桌面、超时、旧协议和未知版本拒绝。

下一次执行先补 T5/T6/T12/T17 的真实 Windows 文件/进程树故障、外部编辑器竞争、断线回报、客户端重启/停机/注销/断电、设备撤销和本机回执恢复证据；再完成 T7/T10/T11/T14/T16/T18 的 Server 重启、离线历史、旧版/回滚栅栏、任务故障注入和浏览器代际。文件与命令只能消费与计划版本/摘要一致的精确 HIGH 审批，不能把客户端 approved 布尔值或模型回答结束当作授权或成功。远端 UNKNOWN 不得自动重放，逻辑收口不得释放物理根隔离；没有进程静止证据不能报告已停止。所有受保护载荷继续接入 ReAct/Harness 的输入、进度、失败、思考、审批、消息、ToolCall、SSE 和检查点。

新增数据库迁移不得修改 V1–V28；PostgreSQL/MySQL 使用同版本方言和原生中文表/字段注释。所有 Controller、Service、Repository、DTO 遵守仓库分层、租户和审计规则。对安全边界、框架隐式行为、恢复和兼容判断补充中文注释。

按功能分层串行运行 Node、Java 单元/MockMvc、客户端合同和浏览器检查。R3 当前证据以实施说明和账本为准，至少覆盖命令审批、只提议不阻断成功、已执行失败/超时不通过、异步 RUNNING 保留停止句柄、重复停止查询、超时 UNKNOWN、配置摘要变化拒绝、固定环境、敏感参数拒绝、已观察子进程停止和重启回执 UNKNOWN。V29 含六张新表；MigrationTest 已更新但未在数据库运行。8080 页面可访问，但浏览器被重定向到登录页，当前无已登录会话，不能把登录页证据当项目页验收。Rocky/浏览器/真实设备条件未变化时记录 T9 阻塞，不能用历史或模块合同替代。涉及 Docker/Testcontainers/数据库时仍只使用 ssh rocky 和 maven:3.9.9-eclipse-temurin-21，先核对 Docker、JDK、远端提交、工作区和磁盘。未经额外授权不提交、推送、部署或重启既有服务。
```
