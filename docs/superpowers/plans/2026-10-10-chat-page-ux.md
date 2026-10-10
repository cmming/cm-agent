# 会话聊天页面均衡优化：实施计划

日期：2026-10-10；主题：`chat-page-ux`；修订：R0；工作模式：`mode=default`。

本轮授权为需求分析、建议和文档生成，未实施业务代码。用户明确允许对不确定事项提问，本轮 D1、D2 已问答确认；普通工程方案自主取舍并记录假设。提示词生成不等于授权执行。

本计划描述后续方法，本轮仅 T0 完成。实施前核对 HEAD、工作树、更近 AGENTS.md，创建引用本分析包的独立实施包。资源根为 cm-agent-console/src/main/resources/META-INF/resources。

## 顺序与文件

1. **T0：当前证据和文档。** 已检查控制台 POM、README、端口键、产品/设计基线、页面与源码；浏览器没有执行发送、审批、停止、下载。
2. **T1：布局。** 修改 console/v2/chat.html 与 console/v2/assets/multipage.css；收敛标题/说明、重复新会话、列宽/高度与滚动责任。输入可达，正文为主，待审批不被裁切。兼容 ID 的取舍写入实际说明。
3. **T2：列表。** 添加过滤标签、分组与完整标题可达展示；app.js 只处理已加载项，不改 selectedConversationId、不写接口。按需在 assets/console-core.js 提取可测 helper；覆盖跨 Agent、无结果、当前项与迟到响应。
4. **T3：阅读。** app.js 增加跟随/暂停、回到底部，保留同会话重绘锚点；异步文件不抢位置。复制 TEXT 正文与代码，保留 Markdown 安全边界。样式只改 v2。可新建 cm-agent-console/src/test/js/chat-ux.test.cjs（计划路径，本轮不存在）。
5. **T4：输入。** 绑定 keydown/composition 和输入高度，复用发送入口/锁。当前页内未确认输入快照按会话隔离，GET 核对结果，不自动补发；中文 IME、空白、忙碌、连续回车、失败不重放须测试。
6. **T5：状态。** 调整待办入口、历史折叠、紧凑空状态、停止与错误呈现；保留 canDecide、选择汇总、显式提交、核对与过期/取消逻辑。状态用文字与样式共同表达，减少流式正文重复播报。
7. **T6：验收。** 更新真实资源约束和行为测试；桌面/小屏/键盘、中文输入法和共享页面回归；同步 README、release-notes 和独立实施包。批量检查后集中修复，至多一轮复核，避免无限微调。

## 验证与命令

- 现有 Node：node --test cm-agent-console/src/test/js/console-core.test.cjs cm-agent-console/src/test/js/chat-stop.test.cjs cm-agent-console/src/test/js/console-navigation.test.cjs。加上实际新增的 chat-ux 测试和实际受影响的产物/审批相关测试，不引用不存在的文件声称通过。
- Java 前检查 java -version、mvn -v，确认 Maven 使用 JDK21；执行 mvn -pl cm-agent-console -am test。资源测试路径为 cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java。
- 前端范围不安排双库/容器；实际扩大范围时遵守 ssh rocky、指定 Maven/JDK21 镜像和远程提交一致规则，禁止本机 Docker 执行此类验收。
- 浏览器目标：1366×768、1920×1080、390×844，补充低高度与缩放。长中文/代码/表格/文件、滚动上翻/回最新/终态回读、键盘与 IME、锁定/停止等待/只读审批/历史失败/迟到响应均有可观察结果。本轮只观察 1920×901，不算这些验收通过。
- 纯布局无需调用付费模型；真实模型/高风险工具/审批/停止需对应授权和隔离环境，不能操作用户现有 Run 作探测。缺环境时可模拟状态，但与真实证据区分。
- git diff --check；范围与凭据检查；中文文字及适用注释检查。资源字符串断言不能代替 IME、滚动和竞态测试。

## 阻塞和交付

常规 CSS/函数组织自主决定。接口无法满足建议时先保守缩小到已验证能力并记录差异，不能悄悄扩后台或数据库。真实环境不足时继续独立前端工作，写清未执行验收、具体原因和继续条件。不得把代码完成或模拟通过等同全部验收。

六份实施文档同步任务编号和实际命令；没有提交/推送/部署指令保持未提交。原分析包保留形成需求的历史记录。

## 关联产物

- [清单](../checklists/2026-10-10-chat-page-ux-checklist.md)
- [提示词](../prompts/2026-10-10-chat-page-ux-prompt.md)
- [设计](../specs/2026-10-10-chat-page-ux-design.md)
- [实现说明](../implementation/2026-10-10-chat-page-ux-implementation-design.md)
- [账本](../progress/2026-10-10-chat-page-ux-ledger.md)
