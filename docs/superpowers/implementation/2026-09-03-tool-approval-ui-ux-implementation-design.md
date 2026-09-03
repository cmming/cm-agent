# 工具人工确认 UI 交互优化实现说明

后续状态：本文件保留卡片优化的实现快照和测试结果；历史回显已由独立[审批历史实现](2026-09-03-tool-approval-history-implementation-design.md)补齐。最新资源为 app/styles 2.0.17、console-core 2.0.11；下文旧版本及历史缺口仅用于追溯。

## 关联文档

- [设计说明](../specs/2026-09-03-tool-approval-ui-ux-design.md)
- [实施计划](../plans/2026-09-03-tool-approval-ui-ux.md)
- [进度账本](../progress/2026-09-03-tool-approval-ui-ux-ledger.md)

## 实际实现

### 状态和草稿

`console-core.js` 新增三个组件：

- `summarizeApprovalChoices`：统计已选择/允许/拒绝数并生成明确提交文案；不完整、重复、外来明细或非法决定不能进入可提交状态。
- `approvalUiState`：独立表达提交、恢复、查询、未知结果和审批终态；不把 APPROVED 等同于工具执行成功。
- `createApprovalDraftStore`：仅在页面内存缓存明确决定，通过 Agent、会话、版本及完整调用展示快照绑定；防御性复制，变化后失效，不缓存额外提交字段，不使用 localStorage/sessionStorage。

### 卡片与交互

`app.js` 中 `createApprovalCard/createApprovalItem` 显示仅本次授权说明、完整运行编号、参数标签和逐项选择。单项不显示批量按钮，多项按钮改为“全部选为允许/拒绝”，明确还需提交。选择汇总动态更新，按钮根据全允许、全拒绝和混合决定说明结果。

待审批详情默认展开，终态详情默认折叠。参数直接传递到 `textContent`，不渲染或重新格式化模型输入。提交后卡片 `aria-busy` 生效，状态节点可聚焦；重绘优先恢复原控件焦点，禁用时回退到该请求状态节点。

`state.approvalFeedback` 保存各请求的前端阶段和局部消息，避免所有卡片共享错误。收到 `approval-decision` 后立即重绘为“决定已接受/正在处理原运行”，并展示刚提交的明细决定；最终以权威详情为准。

### 查询和错误

新增 `refreshApprovalFromCard`，通过既有 `refreshApprovalState` 查询消息、待办及指定审批详情，不发出决定 POST。查询失败时保留 UNKNOWN、只读及会话发送锁，展示原错误码和编号；404 移除卡片后在会话区域保留说明，避免错误随卡片消失。

提交和手动刷新均使用当前登录代次与 Agent/会话标识判断旧响应。保留原有重复提交锁、决定完整性校验和后端权威状态；不自动批准，不新增永久授权。

### 资源与样式

`styles.css` 增加说明/汇总/参数标签、终态中性边框、details 与状态焦点、长文案换行和窄屏单列选项。八个 v2 HTML 统一使用 `app.js?v=2.0.16`、`console-core.js?v=2.0.10` 与 `styles.css?v=2.0.16`，避免旧脚本与新帮助函数混用。

## 测试与方案边界

Node 测试共 60 项通过，其中新增 15 项覆盖纯函数、安全草稿、静态交互合同及真实异步函数的隔离编排。隔离编排通过 Node VM 执行仓库中的实际提交/刷新函数，只替换网络和 DOM 边界，覆盖双击、接受后错误、查询失败、GET 刷新和旧会话响应。Console Java 资源测试 12 项通过，Server 及依赖打包成功。

没有新增依赖、后端接口或数据库迁移。未进行真实浏览器 DOM/键盘/视觉验证，隔离测试不覆盖布局和原生浏览器焦点行为。没有新增倒计时或主动轮询，过期与权限原因仍以后端状态为准；原后端过期清理及并发恢复限制不变。

历史回显仍有缺口：`loadChatMessages` 用 PENDING 查询结果重建 Map，`refreshApprovalState` 仅补回刚处理的那一条详情。因此终态折叠只改善当前临时卡片，多轮执行后并不保留完整历史，刷新后也不会重建终态卡片。后端持久化审批记录与前端历史可见性是不同能力，本轮没有实现历史列表及会话时间线回放。
