# 会话聊天页面设计说明

## 背景

控制台 v2 已在“运行记录”页提供会话选择与连续发送能力，但其核心仍是面向调试和运行详情回看的双栏工作台。平台使用者缺少一个聚焦于消息历史、流式回答和会话切换的独立聊天入口。

## 目标

- 新增 v2 独立页面 `/console/v2/chat.html`，并在所有 v2 已登录页面的左侧导航与能力总览中提供入口。
- 页面按 Agent 组织会话；用户可选择 Agent、新建会话、切换历史会话、加载历史消息和连续发送消息。
- 使用现有会话 SSE 接口显示实时回答，并以服务端最终持久化结果为准刷新消息列表。
- 复用既有认证 Cookie、JWT 兼容、租户隔离、`agent:read`/`agent:run` 授权、审计和错误编号；不新增服务端 API 或数据库结构。

## 范围

### 页面交互

1. 左侧显示当前 Agent 下的会话列表，按服务端返回的最近更新时间排序；点击后回放该会话消息。
2. 顶部可切换 Agent，并可显式创建空白会话。未创建会话时，首次发送会自动创建一个会话。
3. 右侧显示 USER、ASSISTANT 文本消息和既有受控工具摘要；文本使用现有安全 Markdown 渲染。
4. 提交消息后立即在页面上显示本次用户输入与“正在生成”占位，随后按 `delta` 实时追加。SSE 完成后重新从服务端加载该会话消息，避免浏览器与持久化结果不一致。
5. 请求失败时展示后端提供的脱敏中文消息、稳定错误码和错误编号；不自动重试写请求。发送期间禁用提交和会话切换，防止回答交错。

### 非目标

- 不实现消息编辑、删除、复制、归档、搜索、重命名、附件、多模态、手动取消、自动摘要或幂等重放。
- 不改动 `/api/agents/{agentId}/conversations` API、会话持久化、权限模型或数据库迁移。
- “运行记录”页保留现有调试、运行历史和工具调用查看能力，不迁移或删除。

## 方案

在现有 `app.js` 内扩展 v2 多页面路由和聊天状态，避免新增一套独立认证与 API 客户端。聊天页复用 `ConversationController` 的如下既有端点：

- `GET /api/agents`：加载可选 Agent；
- `GET/POST /api/agents/{agentId}/conversations`：加载或创建会话；
- `GET /api/agents/{agentId}/conversations/{conversationId}/messages`：加载会话历史；
- `POST /api/agents/{agentId}/conversations/{conversationId}/messages/stream`：发送消息并消费 `delta`、`completed`、`error` 事件。

页面只使用 `textContent` 和现有受控 Markdown 渲染器插入内容；工具块只展示接口返回的受控摘要。页面切换继续由现有同文档加载机制完成，因此内存中的兼容令牌可在嵌入式浏览器环境延续，常规浏览器则使用 HttpOnly 会话 Cookie。

## 约束

- 客户端不得传入或保存 tenant、role、sequence、runId、认证令牌、模型 Key 或工具原始输入输出。
- 历史窗口、消息脱敏、运行审计、模型调用与会话顺序继续完全由服务端负责。
- DOM 更新必须保持 XSS 安全边界；流式文本和历史文本均经过相同 Markdown 安全渲染路径。
- 页面在窄屏下改为纵向布局，消息区域保留可用高度且输入区不遮挡历史。

## 验收标准

- 直接访问聊天页时，已登录用户可加载 Agent；未登录用户遵循现有登录跳转机制。
- 可选择 Agent、新建会话、打开历史会话，历史 USER/ASSISTANT 与受控工具块的显示顺序同接口顺序一致。
- 发送时出现实时增量；完成后以服务端消息列表刷新，失败时显示安全原因与可检索错误信息。
- 发送中按钮、Agent 切换和会话切换不可用；失败或完成后恢复。
- 所有 v2 页面均可从左侧导航到聊天页，能力总览提供“开始会话”快捷入口。
- 现有控制台 JavaScript 测试、静态资源冒烟测试和服务端模块测试通过。

## 关联文档

- [实施计划](../plans/2026-08-31-console-session-chat-page.md)
- [实现说明](../implementation/2026-08-31-console-session-chat-page-implementation-design.md)
- [进度账本](../progress/2026-08-31-console-session-chat-page-ledger.md)
