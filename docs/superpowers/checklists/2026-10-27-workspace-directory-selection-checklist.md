# 工作区目录选择待办清单

日期：2026-10-27；主题：workspace-directory-selection；修订：R0；模式：mode=default。

## 来源、基线与范围

用户要求新增“支持选择工作区目录，同时改造前后端”的需求。Git HEAD：`369418b3bdc08b096de7854f9d04c982ec8be050`。本轮交付六份需求文档，业务实施未开始、未提交。

当前范围：登记目录 catalog、服务端权限筛选、持久化会话绑定、Run 固定选择、审批恢复、v2 目录选择与运行详情、安全诊断及双库/浏览器验收。默认采用服务端已登记只读参考目录；不含浏览器本机文件夹、任意项目目录读写或 Shell。事实 E1–E7 与假设 A1–A6 见[设计](../specs/2026-10-27-workspace-directory-selection-design.md)，均未冒充用户确认。

## 当前证据

- `WorkspaceContextProperties.java`：单 tenant/Agent 固定映射、默认关闭、文本文件与字节预算。
- `WorkspaceContextService.java`、`WorkspaceContextFiles.java`：加密快照、恢复守卫与文件系统边界。
- `Conversation.java`、`ConversationController.java`、`ConversationService.java`：尚无目录选择，已有发送锁/事务边界。
- `console/v2/chat.html` 与共享 `assets/app.js`：已有会话/Agent、审批、停止流程，无目录选择。
- `docs/configuration.md`、`docs/operations.md`、adapter README：现有只读资料合同；CodeGraph 部分查询截断已直接复核。
- POM 要求 Java 21；当前方言迁移最高 V21，实施时重新核对；无当前业务运行证据。

以上简写路径的完整位置见设计与计划。已有无关修改见账本，禁止覆盖。

## 任务与验收

| 编号 | 状态 | 目标与涉及文件 | 依赖 | 验收条件 | 验证方法 |
|---|---|---|---|---|---|
| T0 | 完成 | 核查规范、代码、配置、页面及 Git；生成本组六份文档 | 无 | 事实/假设分开、六份职责明确、路径有效、无敏感内容 | 源码检查；链接、状态、模式与编号检查；差异检查，见账本 |
| T1 | 待做 | 多目录 catalog；WorkspaceContextProperties、WorkspaceContextConfiguration；新增领域策略为建议路径 | T0 | 唯一稳定 ID、tenant/Agent 过滤、同物理目录及父子跨租户重叠拒绝、旧配置可用；仅已登记只读目录 | 配置和领域单测；关闭、重复、别名、越界、预算与旧配置负例 |
| T2 | 待做 | 选择元数据 Repository、memory/JDBC、新方言迁移；core/repository、server/store、JdbcPersistenceConfiguration、MigrationTest | T1 | DEFAULT/SELECTED/NONE 可区分；会话/Run 归属与版本完整；短事务/CAS；双库重启持久化和中文表字段注释 | memory/JDBC 合同、跨租户读取、两库迁移与并发测试；JDBC 在 Rocky 执行 |
| T3 | 待做 | 可见目录、绑定、可选创建参数、运行元数据 API；ConversationController、RunController、ApiExceptionHandler、ApiErrorCode | T1、T2 | 认证/权限/会话创建者校验、404 隐藏资源、冲突 409、错误关联脱敏；旧无体创建和 input-only 调用兼容 | test profile MockMvc，校验审计及日志，覆盖同步/SSE 失败与旧调用 |
| T4 | 待做 | 选择进入冻结/恢复/清理链；ConversationService、RunExecutionService、WorkspaceContextService 与 adapter 合同 | T2、T3 | 目录 A/B 实际影响资料；NONE 不注入；Run 固定原选择；审批恢复原正文、撤销失败关闭；停止和清理不回归 | ReAct/Harness 捕获真实组装输入的合同；并发首发、文件变化、撤销、损坏/过期及严格审计故障测试 |
| T5 | 待做 | v2 聊天选择器、运行详情、共享请求与状态；chat.html、runs.html、assets/app.js、console-core.js、必要局部样式 | T3、T4 | 自动建会话带选择；刷新恢复；旧会话锁定并可新建；慢响应不串台；加载/空/关闭/拒绝/冲突等状态可行动 | JS 行为测试、ConsoleResourceTest；DOM 交互与真实 API；覆盖 v1 共享脚本回归 |
| T6 | 待做 | 汇总自动回归；相关 core/server/adapter/persistence/console 测试 | T1–T5 | 编译和有意义测试通过；前端原因、状态/错误码/errorId、后台日志一致且无敏感泄露 | Java 21 Maven、node --test、node --check；Rocky 两库与 Linux 文件合同 |
| T7 | 待做 | 真实部署/浏览器/Provider 验收和生产说明；README、docs/configuration.md、operations.md、release-notes.md | T6 | 桌面/390px/键盘完整流程；真实模型两策略可区分目录资料；JDBC 重启恢复、撤销和审批恢复；部署与回退文档齐全 | 本机浏览器与 Rocky 受控环境证据；环境检查、提交/补丁一致性；验收账本 |

## 执行顺序与完成定义

T1 → T2 → T3 → T4 → T5 → T6 → T7。T0 的文档完成不表示 T1–T7 完成。实施时建立独立实施工作包，引用本包并保留本分析基线。未运行的模型、浏览器、数据库验收保持待做；有确切外部阻塞时记录命令、影响和继续条件，不降低门禁。

## 关联产物

- [提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)
- [设计](../specs/2026-10-27-workspace-directory-selection-design.md)
- [计划](../plans/2026-10-27-workspace-directory-selection.md)
- [实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-design.md)
- [账本](../progress/2026-10-27-workspace-directory-selection-ledger.md)
