# 会话聊天均衡优化实施：实际实现说明

日期：2026-10-10；主题：chat-page-ux-delivery；修订：R0；工作模式：mode=default。

用户已要求按照原执行提示词继续实施。本包为独立实施记录，引用[原分析提示词](../prompts/2026-10-10-chat-page-ux-prompt.md)与[原分析清单](../checklists/2026-10-10-chat-page-ux-checklist.md)，原分析六份文档保留历史状态。

已确认 D1：均衡优化，保留中文管理控制台风格，不新增后台业务能力。已确认 D2：Enter 发送、Shift+Enter 换行，中文组合输入防误发。常规工程细节自主处理；未改变这两个决定。

## 实际交付

T1：chat.html 保留原表单 ID 与隐藏兼容按钮，新增手机列表开关、搜索、阅读容器、回到最新、审批历史折叠与输入提示。multipage.css 在 v2 范围内收口网格、内容宽度、状态色、代码/表格局部横向滚动。手机布局用外层弹性高度和单列 minmax(0,1fr)，消除内层裁切；历史列表固定可用高度防止可见区压成 0。

T2：assets/app.js 的 renderChatConversations 本地过滤标题、时间分组、aria-current 与完整标题；当前 Agent 和空会话标题明确。手机选中会话关闭列表并返回开关焦点。

T3：createChatUxController 在 v2 聊天启用，保存 follow、消息 key 和位置锚点；scrollChatToBottom 路由新控制器，v1 继续原路径。MutationObserver 跟随异步内容时尊重阅读模式。createChatMessage 为非用户 TEXT 回复和 Markdown code 增加独立复制按钮与安全反馈，原执行折叠和 artifactManager 挂载保留。

T4：控制器按页面内 Agent/会话 key 保存草稿/提交快照，不使用 localStorage/sessionStorage；导航/退出清理 observer 和内存。组合输入事件、229、重复键和修饰键阻止误发送。sendChatMessage 只在接收证据到达后清空，失败分类保留输入；新增未知结果发送锁。旧同文消息和单独活跃 Run 不确认本次请求，新增同文 USER 才确认。接受后的流式失败不复原已发送内容。迟到回调按登录 epoch/Agent/会话隔离。

T5：审批历史空状态一行、默认折叠、有错误自动展开；原错误码/编号保留，内联历史与分页重试未改变。状态标签区分读取/运行/待审批/停止等待/结果未知。停止写请求、canDecide、逐项审批及等待终态的锁不变。

T6：所有 9 个 v2 HTML 更新共享脚本/样式缓存版本；其他页没有新增 UI。Node 测试执行真实控制器与既有共享流程；Java 资源测试新增聊天控件兼容断言。模拟验收服务仅绑定回环，使用虚构数据，无模型/工具调用。README 和 release-notes 已同步。

## 关键代码位置

资源根：cm-agent-console/src/main/resources/META-INF/resources。

- assets/app.js：createChatUxController、chatConversationGroups、renderChatConversations、loadChatMessages、renderApprovalHistory、renderChatMessages、renderChatWelcome、createChatMessage、updateChatSendAvailability、sendChatMessage、closeChatSessionPanel。
- console/v2/chat.html：聊天页结构与无障碍标记。
- console/v2/assets/multipage.css：文件末尾“聊天工作区自行分配视口高度”段。
- 测试：src/test/js/chat-ux.test.cjs、chat-ux-preview.cjs、console-core.test.cjs、console-navigation.test.cjs 与 src/test/java/com/cmagent/console/ConsoleResourceTest.java。
- 生产文档：README.md 的“会话聊天页面操作”，docs/release-notes.md 的“会话聊天页面均衡优化”。

## 与原分析的差异

复用共享 app.js 内的可抽取真实控制器，没有修改 console-core.js，也没有新依赖。手机列表改为固定 340px 工作区并提供独立滚动，避免可见内容被压掉；读取提示在切换上下文时清理，回到未知结果上下文保留对应错误。其他入口只更新缓存引用以兼容当前文档内导航。

原计划要求真实 IME 与缩放，当前工具只支持浏览器自动化、原生桌面 API 禁用，尚不能自动验证系统选词和软键盘；已追加 T7，保留待验收状态。模拟证明界面及协议处理，不证明真实模型、工具、数据库或正式发布通过。本次代码与文档纳入本地提交，未推送、未部署；提交记录见账本。

## 状态

| 编号 | 当前状态 | 内容与验收 | 依赖 |
|---|---|---|---|
| T0 | 完成 | 复核当前源码、规范、授权及脏文件，建立独立实施包 | 原分析 T0 |
| T1 | 完成 | 独立消息滚动、紧凑输入、唯一可见新会话入口；桌面/手机输入可达 | T0 |
| T2 | 完成 | 本地标题过滤、覆盖范围、今天/昨天/更早、选中与完整标题、手机列表切换 | T1 |
| T3 | 完成 | 上翻暂停跟随、回到最新、终态阅读锚点、正文/代码复制、保留文件展示 | T1 |
| T4 | 实现完成，实机输入法待验收 | Enter/换行/组合输入保护、空白/忙碌防提交、输入增长、页面内草稿和未知发送锁；自动化通过 | T1、D2 |
| T5 | 完成 | 空历史紧凑、历史错误展开、待审批/只读/停止状态与发送锁兼容 | T1、T3、T4 |
| T6 | 部分完成 | 133 个 Node 与 15 个 Java 测试、模拟浏览器及文档完成；实机 IME、浏览器缩放和真实运行验收尚未执行 | T2、T3、T4、T5 |
| T7 | 待验收 | 补齐 T4/T6 的真实中文输入法、125%/200%缩放、已授权测试环境真实运行与停止/审批烟测 | T4、T6 |


## 关联产物

- [需求清单](../checklists/2026-10-10-chat-page-ux-delivery-checklist.md)
- [执行提示词](../prompts/2026-10-10-chat-page-ux-delivery-prompt.md)
- [设计](../specs/2026-10-10-chat-page-ux-delivery-design.md)
- [实施计划](../plans/2026-10-10-chat-page-ux-delivery.md)
- [实际实现说明](../implementation/2026-10-10-chat-page-ux-delivery-implementation-design.md)
- [实施账本](../progress/2026-10-10-chat-page-ux-delivery-ledger.md)
