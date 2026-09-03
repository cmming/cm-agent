# AgentScope Permission System 接入实施计划

## 关联文档

- [设计说明](../specs/2026-09-02-agentscope-permission-system-integration-design.md)
- [实现说明](../implementation/2026-09-02-agentscope-permission-system-integration-implementation-design.md)
- [进度账本](../progress/2026-09-02-agentscope-permission-system-integration-ledger.md)

## 当前边界与执行状态

本计划已经执行第一版在线审批代码纵切。产品基线为：HIGH 工具每次具体调用审批；发起人自批需要同时拥有 `agent:run` 与独立 `agent:approve`；当前不提供无人值守暂停入口、长期授权规则或独立审批中心。任务 1—8 的第一版代码和文档已落地，但以下验证清单不是全部通过的声明；实际完成证据及待验收项见进度账本。主动过期扫描、无人值守暂停模式和检查点密钥版本列为后续能力，没有暴露无实现配置。

## 实施顺序

### 任务 1：扩展权限与运行领域合同

- 涉及文件：
  - `cm-agent-core/src/main/java/com/cmagent/core/domain/RunStatus.java`
  - `cm-agent-core/src/main/java/com/cmagent/core/domain/RunRecord.java`
  - 新增审批策略、审批状态、审批请求和审批明细领域类型
  - `cm-agent-core/src/main/java/com/cmagent/core/runtime/AgentRuntime.java`
  - 新增等待审批与恢复结果合同
  - 新增审批和运行检查点 Repository SPI
- 实现：
  - 增加非终态 `WAITING_APPROVAL`；
  - 分离 `ToolRiskLevel` 与审批策略；
  - 定义不依赖 AgentScope 类型的 Core 暂停/恢复合同；
  - 维护 record 组件、枚举常量、公开 SPI 的完整中文 JavaDoc。
- 验证：
  - Run 状态与完成时间不变量；
  - 审批状态迁移；
  - 原工具 ID 与规范化输入哈希绑定，拒绝同名替换工具沿用旧批准；
  - 旧 Runtime 默认实现的源码兼容性。

### 任务 2：使工具桥接进入 AgentScope Permission Engine

- 涉及文件：
  - `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeToolBridge.java`
  - `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java`
  - `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeOptions.java`
  - adapter 单元测试与合同测试
- 实现：
  - `AgentScopeToolBridge` 改为继承 `ToolBase`；
  - 输入 Schema、工具名称、描述和 `readOnly=false` 通过 `ToolBase` 构造；
  - 从可信 ToolDefinition 生成 LOW/MEDIUM ALLOW、HIGH ASK 和明确 DENY 规则；
  - 在 `ReActAgent` 构建时注入 `PermissionContextState`；
  - 保留现有工具网关、超时、中断、进度和工具记录语义。
- 验证：
  - 普通 `AgentTool` 绕过行为的回归测试；
  - 规则优先级、DEFAULT、DONT_ASK 和禁止 BYPASS；
  - ASK 前治理网关零调用；
  - 允许后仍只调用网关一次。

### 任务 3：识别 ASK 并建立持久化恢复合同

- 涉及文件：
  - `AgentScopeReActExecutor`、`AgentScopeExecutionResult`、`AgentScopeRuntimeAdapter`
  - 新增 AgentScope 状态存储适配器或检查点协作者
  - 对应 adapter 测试
- 实现：
  - 消费 `RequireUserConfirmEvent`；
  - 检查 `GenerateReason.PERMISSION_ASKING`；
  - 返回待审批工具安全快照，不映射为成功；
  - 使用 `runId` 作为 AgentScope `sessionId`；
  - 审批后由服务端持有的原始状态构造 `ConfirmResult` 并继续调用；
  - 终态后删除检查点。
- 验证：
  - 单工具与多工具 ASK；
  - APPROVE、DENY、全部拒绝、过期和缺失检查点；
  - 新建 Agent 实例后恢复；
  - Core 仅传递限长摘要和输入哈希，不传递原始 Map/AgentState；Server 对摘要再次脱敏，前端不接收哈希或状态载荷。

### 任务 4：实现审批与加密检查点持久化

- 涉及文件：
  - `cm-agent-persistence/src/main/resources/db/migration`
  - PostgreSQL/MySQL 方言迁移目录（如语法需要）
  - `cm-agent-persistence` 中的 Jdbc Repository
  - memory 模式 Repository 或 server store
  - 迁移与 Repository 测试
- 实现：
  - 新增审批请求、审批明细和运行检查点表；
  - 所有表与字段提供非空中文数据库原生注释；
  - 使用 tenant 条件和必要唯一索引；
  - 检查点载荷加密并保存过期时间，不明文保存凭据；第一版复用现有主密钥，独立密钥版本尚未实现；
  - 使用条件更新或版本字段实现审批幂等和竞争控制。
- 验证：
  - tenant 隔离；
  - APPROVE/DENY 竞争最多一个成功；
  - 检查点密文不含可识别原始载荷；
  - 过期惰性收口和终态清理；主动扫描留待异步审批阶段；
  - PostgreSQL 16 与 MySQL 8.4 表字段注释非空。

### 任务 5：贯通运行、会话、授权和审计

- 涉及文件：
  - `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`
  - `cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java`
  - `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunPersistenceService.java`
  - `GovernedToolInvocationService` 及审批编排 Service
  - RBAC、bootstrap 权限和审计相关配置
  - server 测试
- 实现：
  - Run 从 RUNNING 进入 WAITING_APPROVAL；
  - 每轮 ASK 前已执行的工具记录及时脱敏落库，避免只保存最后恢复片段；
  - 不在等待阶段追加虚假 assistant 完成消息；
  - 新增 `agent:approve` 并分别记录 requestedBy、decidedBy、executionPrincipal；
  - 审批恢复前校验 tenant、Agent、conversation、Run、状态、版本和过期时间；
  - 恢复后的工具调用继续经过现有 ToolGrant 和状态复核；
  - 审批创建、决定、过期、恢复和工具终态分别审计。
  - 决定接受后的恢复预检或运行失败必须尝试终结活动 Run 并删除检查点；清理或审计失败不得吞掉。
- 验证：
  - 发起人有/无 `agent:approve`；
  - 他人审批预留路径不会借用审批人执行权限；
  - 等待期间撤销 Grant、禁用工具或禁用 Agent；
  - errorCode、errorId、日志关联和敏感信息脱敏。

### 任务 6：实现审批 REST/SSE 固定合同

- 涉及文件：
  - `cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java`
  - 新增审批 Controller、请求/响应 record 和 SSE 事件 DTO
  - `cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java`
  - 新增审批查询与恢复编排 Service
  - Web、Service 与安全测试
- 实现：
  - `ToolApprovalView` 和 `ToolApprovalItemView` 严格采用设计说明字段，不暴露 tenant、输入哈希、原始参数或检查点；
  - 消息 SSE 在保存审批和检查点后发送 `approval-required`，随后正常关闭且不发送 `completed`；
  - 实现 `GET .../approvals?status=PENDING` 供刷新和会话切换恢复，并实现 `GET .../approvals/{approvalId}` 供冲突、过期和恢复错误后读取权威终态；
  - 实现 `POST .../approvals/{approvalId}/decision/stream`，接收 `expectedVersion` 和覆盖全部 item 的决定数组；
  - 按全部允许、全部拒绝和混合决定分别写入 `APPROVED`、`DENIED` 和 `PARTIALLY_APPROVED`；
  - 在建立 SSE 前完成认证、`agent:run`、`agent:approve`、租户/资源、版本、过期和决定完整性校验，原子提交时再次校验；
  - 决定原子落库后先发送 `approval-decision`，再复用现有 progress、message-started、delta、completed/error 事件；
  - 当前会话存在 PENDING 审批时，新的消息接口返回 409。
- 验证：
  - 单 item、多 item、混合决定、全部拒绝和部分批准；
  - 400、401、403、404、409、410 的状态与安全中文响应；
  - `approval-required` 后不出现虚假 `completed`；
  - `approval-decision` 后恢复事件顺序稳定；
  - 原始 ToolUseBlock、Secret、内部 URL 和堆栈不出现在响应或受控日志。

### 任务 7：实现聊天页审批状态机

- 涉及文件：
  - `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`
  - `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
  - `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`
  - `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`（仅通用 SSE 合同确有缺口时）
  - `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
  - `cm-agent-console/src/test/js/console-core.test.cjs` 及新增审批交互测试
- 实现：
  - 增加 `pendingApprovals` Map 和 `approvalSubmissions` Set，并在 Agent/会话切换时清理重建；
  - 加载代次、登录会话和 Agent/会话标识共同隔离旧请求回调；状态查询失败不开放发送或不确定重试；
  - 分离待审批查询、SSE 接收、卡片渲染、全量决定校验、恢复流和会话发送锁职责；
  - 请求级卡片包含 HIGH 标签、发起人、Run、过期时间、item 列表、脱敏参数和状态提示；
  - 每个 item 提供允许/拒绝二选一，支持全部允许/全部拒绝，全部 item 已选择后才允许提交；
  - `canDecide=false` 显示只读卡片；PENDING 时只锁定当前会话发送，其他会话可继续使用；
  - 处理 LOADING、PENDING_READONLY、PENDING_DECIDABLE、SUBMITTING、RESUMING、COMPLETED、CONFLICT、EXPIRED 和 ERROR；
  - 参数摘要只通过 `textContent`/`element(..., {text})` 写入 `<pre>`，不走 Markdown 或 HTML；
  - 增加键盘、焦点、`role=alert`、`aria-live`、窄屏单列和长文本换行支持。
- 验证：
  - 单 item、多 item、全部允许、混合决定、只读权限和提交前完整性；
  - 刷新恢复、会话切换、重复点击、409、410、网络中断和恢复流错误；
  - `<script>`、Markdown、超长参数按纯文本显示且不破坏 DOM；
  - 窄屏样式、长工具名、调用标识和参数换行；
  - PENDING 时当前会话发送按钮禁用，终态后恢复。

### 任务 8：安全配置、生产文档与验证收口

- 涉及文件：
  - `AgentScopeRuntimeProperties`、`ProfileSafetyValidator` 和 profile 配置
  - `README.md`
  - `docs/configuration.md`
  - `docs/deployment.md`
  - `docs/operations.md`
  - `docs/release-notes.md`
  - 本主题四份过程文档
- 实现：
  - 增加权限引擎开关和审批过期配置；在线模式固定为 DEFAULT，不暴露尚未实现的无人值守模式；
  - 不开放 BYPASS、EXPLORE/ACCEPT_EDITS；
  - 文档明确审批不替代业务授权、检查点加密和无人值守 fail-closed。
- 验证：
  - `java -version` 和 `mvn -v` 使用 JDK 21；
  - Core、adapter、server、console 针对性测试；
  - 环境允许时执行 `mvn -q test`；
  - 数据库迁移和 Testcontainers 必须在 `ssh rocky` 的 `maven:3.9.9-eclipse-temurin-21` 容器中，对 PostgreSQL 16 和 MySQL 8.4 验证。

## 实现顺序约束

- 先完成领域状态和 ToolBase 桥接，再开放 ASK，避免无恢复能力时产生悬挂运行。
- 持久化、幂等和加密检查点完成前，不允许在生产启用在线 ASK。
- Web 和控制台不能直接依赖 AgentScope 类型；跨模块只使用 CM Agent 领域合同。
- 动态接受 suggestedRules、参数编辑和长期规则管理必须另立需求，不得夹带进第一版。
- 任何阶段都不能移除现有 ToolInvocationGateway 的执行前复核。

## 完成定义

- [设计说明](../specs/2026-09-02-agentscope-permission-system-integration-design.md)中的验收标准逐项区分已验证与待验证；当前第一版代码交付不等同于全部上线验收完成。
- 四份主题文档同步记录最终实现、方案差异、验证结果、遗留问题和提交状态。
- 生产文档和发布说明反映实际交付范围，不将阶段 C 能力描述为已完成。
- 不包含主工作区既有配置修改、真实凭据、生成物或无关重构。

## 剩余验收顺序

1. 在 Rocky 双数据库上补充新审批/检查点 Repository 的租户隔离、事务回滚和并发决定专门测试。
2. 使用测试模型与工具跑真实浏览器全流程，覆盖混合决定、刷新、切换、409/410、网络断开、纯文本注入样例及键盘/窄屏交互。
3. 在测试环境演练批准前撤销授权、服务重启、跨实例恢复和决定后崩溃收口；明确无自动接管时的运维处理方式，再评估生产开关。
