# Skill 支持实现说明

## 文档状态

- 主题日期：2026-09-20；本次更新：2026-09-21。
- 当前阶段：设计与计划已编写，业务实现未开始。
- 本文记录本阶段实际交付及拟接入位置；后续实施时用实际代码、验证结果和方案差异更新，不能将下述拟实现内容作为功能已上线的依据。
- 配套文档：[设计规格](../specs/2026-09-20-skill-support-design.md)、[实施计划](../plans/2026-09-20-skill-support.md)、[进度账本](../progress/2026-09-20-skill-support-ledger.md)。

## 本阶段实际交付

本阶段只修改项目文档：整理现有运行、工具治理、持久化和控制台边界，形成指令与文本资源型 Skill 方案，并将后端、前端、数据库、权限、诊断和回归拆为 T1～T11。用户已确认书面设计，并明确要求前端在同一需求中交付。

目前没有新增 Java 类、数据库迁移、接口、前端页面或配置项，也没有启用 Skill。设计中的类名、路由、表名、错误码和容量限制属于未来实现契约。

设计文档此前分别形成两个本地提交：`326c86a`（设计规格）、`d68599c`（前端交付范围与验收）。当前补充的计划、实现说明、进度账本及规格澄清未提交，未推送。

## 当前代码与拟接入位置

| 现有位置 | 已核对的当前职责 | 计划中的变化 |
| --- | --- | --- |
| `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java` | 运行请求及租户约束 | T1 增加框架无关技能集合，保留旧构造入口 |
| `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java` | 准备运行、执行与失败收口 | T6 固定快照、恢复校验并保留技能错误码和编号 |
| `cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolApprovalService.java` | 审批与恢复 | T6 恢复时复核撤销状态和读取预算 |
| `cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java` | 运行接口及流式错误出口 | T6 增加独立读取记录接口及技能错误映射 |
| `cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java` | 会话接口及流式错误出口 | T6 保持聊天错误与运行错误编号一致 |
| `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java` | 每次执行创建模型、工具与 Agent | T7 装配原生目录提示和受控读取包装器 |
| `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRunGate.java` | 工具调用门控及基础设施故障保留 | T7 保留致命技能错误，阻止框架吞错后继续调用业务工具 |
| `cm-agent-persistence/src/main/resources/db/migration/` | Flyway 历史迁移 | T4 新增 V12 双方言迁移；不修改历史版本 |
| `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js` | 前端公共请求和状态辅助函数 | T8 增加 multipart 支持，保持 JSON/SSE 与会话隔离 |
| `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js` | 页面路由、数据加载和交互装配 | T9/T10 接入技能工作区、Agent 绑定与运行读取记录 |
| `cm-agent-console/src/main/resources/META-INF/resources/console/v2/` | 现有七页中文工作区 | T9 新增技能页并同步全部 v2 导航；T10 增加局部组件 |

以上文件仅为已核对的接入点，本次未修改这些文件。新增类和测试的完整路径见实施计划各任务。

## 拟实现的数据与调用链

### 管理与绑定

`认证主体 → Controller 权限入口 → 无落盘 ZIP 校验 → SkillManagementService 工作单元 → 版本/资源/绑定 Repository + 严格审计 → 前端详情`

新增六表保存定义、不可变版本、文本资源、Agent 绑定、Run 快照和读取记录。新上传默认停用；更新创建新版本；绑定不会授予业务工具权限。memory 和 JDBC 采用相同合同，生产沿用 JDBC/Flyway。

### 运行与读取

`可信运行主体 → 固定版本及绑定身份快照 → 原生技能目录摘要 → 模型请求读取 → 受控网关复核租户/撤销/预算 → 内存原生读取 → 记录与审计提交 → 正文交给模型`

审批恢复复用原快照和累计预算。停用后重新启用、解绑后重新绑定均不恢复旧运行权限。无法撤回已经交给模型的文本，因此撤销边界是后续读取与恢复。

读取记录独立于业务工具调用记录，不伪造 `toolId`。未知故障、审计或持久化故障不能变成普通文本后继续运行；聊天、运行和审批恢复的错误出口必须保留同一个错误码与编号。

### 前端完整闭环

`能力查询 → ZIP 上传 → 技能详情与文本预览 → 启用 → Agent 绑定 → 正常聊天 → 运行读取记录 → 更新及撤销验证`

前端复用原生 HTML/CSS/JavaScript、现有中文布局、权限与会话状态管理。新增技能页和独立技能组件脚本，保留现有页面职责；T8～T10 是独立交付任务，T11 执行完整联调。

## 与原设计的差异及补充

- 已确认的功能范围不变：指令与文本资源型 Skill、控制台管理、自动按需读取、CM Agent 治理和 AgentScope 原生能力。
- 用户要求增加的前端范围已落入规格第 9 节和 T8～T11，不再作为后续独立需求。
- 计划自查补充聊天流错误出口与新增 `ConversationControllerTest`，避免后端保留的错误码在会话层丢失。
- 明确当前版本采用事务内校验的非空软指针，避免定义与版本的即时外键形成循环插入。
- 明确第一版配置上限只能调低，并限制包含目录在内的总归档条目；这是原有资源限制的具体化。
- 尚无实际产品实现，因此不存在已验证的实现偏差；后续若修改公开契约，必须同步规格和本说明。

## 验证与未完成边界

本阶段实际完成的是代码结构、依赖接口和文档一致性核对，包括现有 AgentScope 接口及前端请求行为的检查。历史页面截图仅用于理解原有风格，不属于本次 Skill 功能的浏览器验证。

未运行 Skill Java/JavaScript 功能测试、双数据库迁移测试或浏览器闭环，因为对应业务代码尚未实现。远程容器环境也未因本次文档工作执行验证，不能据此宣称环境可用或不可用。

实施计划已安排 Java 21 本地快速测试，以及 Rocky 上 PostgreSQL 16/MySQL 8.4 的 JDBC/Flyway/Testcontainers 验证；执行前核对远端提交与本地一致。实际命令和结果持续写入进度账本。

本次未更新 `README.md`、配置说明或 `docs/release-notes.md`：产品行为未变化，避免提前宣称支持 Skill；正式功能文档更新已列入 T11。
