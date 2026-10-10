# 会话聊天页面均衡优化：需求清单

日期：2026-10-10；主题：`chat-page-ux`；修订：R0；工作模式：`mode=default`。

本轮授权为需求分析、建议和文档生成，未实施业务代码。用户明确允许对不确定事项提问，本轮 D1、D2 已问答确认；普通工程方案自主取舍并记录假设。提示词生成不等于授权执行。

## 来源、范围与基线

用户要求新增“优化会话聊天页面”，先给建议，不确定项可询问。D1 选择均衡优化、保留管理控制台风格、不新增后台能力；D2 选择 Enter 发送、Shift+Enter 换行。

Git 基线：`316828a6b3fb30d28b8ea728fe309fd6114bdb40`。开始时暂存区为空。已有脏文件：`.gitignore`、`cm-agent-console/src/main/resources/META-INF/resources/index.html`、服务端 `application.yml`、`application-mysql.yml`；未跟踪 `.codex/`、`.workbuddy/`、`application-ok.yml`。保留这些改动。

采用范围：v2 聊天布局、已加载列表查找、消息阅读/复制、输入交互、审批/运行状态、小屏与回归。保留 API、权限、多租户、审计、停止和受控下载。

非目标：后台全文搜索、会话重命名/删除/归档、消息编辑/再生成、上传附件、多模态、跨刷新草稿保存、跨实例停止、写请求自动重试、重做已有执行过程或审批。列表最多请求 50 条、消息最多请求 200 条，不能宣称查找全部历史。

## 当前证据

资源根为 `cm-agent-console/src/main/resources/META-INF/resources`。

- `console/v2/chat.html`：两个新会话按钮、消息区、审批历史/待办、输入及停止/核对入口。
- `assets/app.js`：loadConversations 2278 行、loadChatMessages 2417 行、createChatMessage 2915 行、scrollChatToBottom 3122 行、sendChatMessage 3163 行、事件绑定 3895 行。已有流式、执行过程折叠、审批历史、停止、文件展示和发送锁。
- 浏览器 `http://localhost:8080/console/v2/chat.html`：当前已登录历史会话可读。1920×901 视口下消息区约 924×315，空审批历史约 91 高，输入约 182 高；聊天面板底部约 913，存在外层滚动。
- delta 每次强制滚底；发送前清空输入；无聊天输入专用键盘绑定。手机已有单列样式，历史列表在消息前；本轮未实测手机。
- `chat-stop.test.cjs`、`console-core.test.cjs`、`ConsoleResourceTest.java` 已存在；本轮未运行业务测试。

## 待办

| 编号 | 状态 | 目标 | 依赖 | 验收条件 | 验证方式 |
|---|---|---|---|---|---|
| T0 | 完成 | 当前页面分析与六份需求文档 | 无 | 事实与建议分开；D1/D2 记录答复；文档齐全且链接有效 | 源码、浏览器只读观察、文档检查 |
| T1 | 待做 | v2 聊天布局与空间分配 | T0 | 输入无需外层滚动即可操作；消息为主要阅读区；新会话主入口唯一 | 截图及尺寸检查 |
| T2 | 待做 | 已加载会话查找与列表呈现 | T1 | 标题本地过滤、明确覆盖范围；当前项可识别；完整标题可查看；无结果有提示 | Node 行为测试与浏览器切换 |
| T3 | 待做 | 阅读滚动、正文和代码复制 | T1 | 上翻不被 delta/回读拉回；可回到最新；复制不混入执行/审批；保留文件卡片 | Node 状态测试与长回答验收 |
| T4 | 待做 | 输入自适应和发送快捷键 | T1、D2 | Enter 发送，Shift+Enter 换行；IME 不误发；空白/忙碌不发送；失败不自动重放 | 键盘/发送边界测试与真实输入法 |
| T5 | 待做 | 审批、停止、加载和错误状态 | T1、T3、T4 | 待审批显著可达；空历史紧凑；读取失败不伪装为空；停止等待仍锁发送 | 既有审批/停止回归与受控状态验收 |
| T6 | 待做 | 响应式、无障碍、兼容与文档 | T2、T3、T4、T5 | 桌面/手机无横向溢出；键盘可达；v1/运行页行为不变；证据如实记录 | Node、ConsoleResourceTest、浏览器和差异检查 |

涉及文件：T1/T2/T4/T5 入口为 chat.html，样式优先限定 `console/v2/assets/multipage.css` 的 v2/chatPage。T2～T5 行为在共享 `assets/app.js`，按需使用 `assets/console-core.js` 可测 helper。T3 保留 `console/v2/assets/skill-artifacts.js` 挂载，未发现实际缺陷不修改组件。T6 更新受影响 Node 测试、资源断言、README 和 release-notes。

T0 完成只代表分析及文档完成，T1～T6 未实施。顺序 T0→T1→T2/T3/T4→T5→T6。用户要求实施后建立独立实施包并引用本包。

## 关联产物

- [提示词](../prompts/2026-10-10-chat-page-ux-prompt.md)
- [设计](../specs/2026-10-10-chat-page-ux-design.md)
- [计划](../plans/2026-10-10-chat-page-ux.md)
- [实现说明](../implementation/2026-10-10-chat-page-ux-implementation-design.md)
- [账本](../progress/2026-10-10-chat-page-ux-ledger.md)
