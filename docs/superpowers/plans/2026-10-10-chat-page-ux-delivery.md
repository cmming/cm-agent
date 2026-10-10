# 会话聊天均衡优化实施：实施计划

日期：2026-10-10；主题：chat-page-ux-delivery；修订：R0；工作模式：mode=default。

用户已要求按照原执行提示词继续实施。本包为独立实施记录，引用[原分析提示词](../prompts/2026-10-10-chat-page-ux-prompt.md)与[原分析清单](../checklists/2026-10-10-chat-page-ux-checklist.md)，原分析六份文档保留历史状态。

已确认 D1：均衡优化，保留中文管理控制台风格，不新增后台业务能力。已确认 D2：Enter 发送、Shift+Enter 换行，中文组合输入防误发。常规工程细节自主处理；未改变这两个决定。

## 任务顺序与文件

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

实施顺序为 T0→T1→T2/T3/T4→T5→T6，T7 为未执行的环境验收。下列过程已经实施，后续执行者先读账本，不重新执行完成项。

- T0：读取 AGENTS.md、POM、README、PRODUCT.md、DESIGN.md、工作包，尝试 CodeGraph，保护相关脏文件，确认 JDK21。
- T1：console/v2/chat.html 收口入口、阅读容器与输入；console/v2/assets/multipage.css 按 v2/chatPage 分配高度与响应式。
- T2：assets/app.js 的 renderChatConversations 与手机控件，保持原 GET 协议。
- T3：createChatUxController 管理跟随/阅读锚点/异步挂载；createChatMessage 提供 TEXT 与 code 复制。
- T4：控制器处理输入、IME、草稿和提交快照；sendChatMessage 接收事件确认与未知结果处理，保留原发送锁与上下文隔离。
- T5：renderApprovalHistory 折叠/错误展开；updateChatSendAvailability 显示阶段并保留停止/审批锁。
- T6：全部 v2 HTML 统一 app.js 2.0.31、multipage.css 2.3.1；chat-ux.test.cjs、console-core.test.cjs、console-navigation.test.cjs、ConsoleResourceTest.java；README、发布说明、本包。
- T7：在可操作系统输入法与受授权测试服务的环境补验收，记录实际结果，不通过改弱生产策略完成测试。

## 验证命令与方法

~~~powershell
node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js
node --check cm-agent-console/src/test/js/chat-ux-preview.cjs
node --test cm-agent-console/src/test/js/*.test.cjs
$env:JAVA_HOME='F:\java21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn -v
mvn -q -pl cm-agent-console -am test
git diff --check -- cm-agent-console README.md docs/release-notes.md docs/superpowers
~~~

模拟验收可运行 node cm-agent-console/src/test/js/chat-ux-preview.cjs，回环 18081 端口提供虚构协议；用浏览器检查尺寸、列表切换、长回答、复制、未知结果、审批错误和只读审批。不要作为真实后台测试。使用 /__validation/state 只读核对写入次数，不记录输入或凭据。结束后关闭验收服务，截图置于被忽略的 target/chat-ux-qa，不加入版本控制。

无数据库、迁移或容器变更，不需要本机 Docker 或 Rocky 集成测试。若后续扩大范围，按仓库 Rocky 规则另做验证。

## 关联产物

- [需求清单](../checklists/2026-10-10-chat-page-ux-delivery-checklist.md)
- [执行提示词](../prompts/2026-10-10-chat-page-ux-delivery-prompt.md)
- [设计](../specs/2026-10-10-chat-page-ux-delivery-design.md)
- [实施计划](../plans/2026-10-10-chat-page-ux-delivery.md)
- [实际实现说明](../implementation/2026-10-10-chat-page-ux-delivery-implementation-design.md)
- [实施账本](../progress/2026-10-10-chat-page-ux-delivery-ledger.md)
