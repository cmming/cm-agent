# 会话聊天页面均衡优化：执行提示词

日期：2026-10-10；主题：`chat-page-ux`；修订：R0；工作模式：`mode=default`。

本轮授权为需求分析、建议和文档生成，未实施业务代码。用户明确允许对不确定事项提问，本轮 D1、D2 已问答确认；普通工程方案自主取舍并记录假设。提示词生成不等于授权执行。

下列为后续实施输入，本轮没有执行。状态和验收以清单/账本为准。

```text
仓库：F:\java\cm-agent。需求：会话聊天页面均衡优化。
当前模式：mode=default。用户已明确允许必要的不确定项提问；普通工程细节自主判断并记录假设。
已确认 D1：保留中文受控工作台风格，优化聊天布局、阅读、输入和审批状态，不新增后台业务能力。
已确认 D2：Enter 发送，Shift+Enter 换行；必须防中文组合输入误发送。

清单：F:\java\cm-agent\docs\superpowers\checklists\2026-10-10-chat-page-ux-checklist.md
提示词：F:\java\cm-agent\docs\superpowers\prompts\2026-10-10-chat-page-ux-prompt.md
设计：F:\java\cm-agent\docs\superpowers\specs\2026-10-10-chat-page-ux-design.md
计划：F:\java\cm-agent\docs\superpowers\plans\2026-10-10-chat-page-ux.md
实现说明：F:\java\cm-agent\docs\superpowers\implementation\2026-10-10-chat-page-ux-implementation-design.md
账本：F:\java\cm-agent\docs\superpowers\progress\2026-10-10-chat-page-ux-ledger.md

当前仅 T0 分析/文档完成，T1～T6 未实施。只有收到明确实施指令后执行下列工作；生成或阅读本提示词不等于获得实施授权。
执行前读取 AGENTS.md、相关 POM/README/配置、PRODUCT.md、DESIGN.md 和上述六份文件，核对 Git HEAD 与脏文件。
仓库存在 .codegraph/ 时先尝试 CodeGraph。索引位置与实际不匹配时直接复核源码并记录限制。本分析已遇到 app.js 符号范围不匹配。
保护现有 .gitignore、根 index.html、application.yml、application-mysql.yml、application-ok.yml、.codex/ 和 .workbuddy/ 改动，凭据不输出或存入文件。
创建独立实施工作包并引用本分析包，不把分析历史改写成已经实现。

资源根：cm-agent-console/src/main/resources/META-INF/resources。
T1：chat.html 与 v2 multipage.css 收敛页头、重复新会话和高度，让正文为主、输入稳定可达。
T2：只过滤当前 Agent 已加载会话标题，明确覆盖范围（当前最多请求 50 条），分组和选中状态、完整标题查看，不新增搜索 API。
T3：上翻暂停跟随，回到最新恢复；delta/执行过程/异步文件/同会话终态回读不抢位置。复制回复 TEXT 正文及单独代码块，不复制思考、工具参数或审批。
T4：Enter/Shift+Enter、IME 保护、空白/忙碌防提交、输入自适应；沿用现有输入和发送锁，页面内保留未确认输入。结果未知先 GET 核对，绝不自动重发 POST；已确认接收不得误作草稿。
T5：待审批醒目可达、空历史紧凑、非空历史可展开；加载失败不伪装为空。停止有明确名称，CANCELLING 等待真实终态仍锁发送，CANCELLED 保留部分输出。
T6：Node、ConsoleResourceTest、桌面/小屏/键盘/IME与共享页面回归，更新 README、release-notes 和实施工作包。
顺序 T1→T2/T3/T4→T5→T6。常规实现自主决定，改变 D1/D2 或后台边界才提出必要取舍。

共享 app.js 新交互限制 v2/chatPage，保留 v1、运行页、表单 ID 和既有调用链。公共 helper 改动覆盖实际其他调用者。
不新增会话删除/归档/重命名、消息编辑/再生成、上传、多模态、跨刷新持久草稿、跨实例停止或自动重试。
保留 tenant/权限/严格审计、canDecide、逐项审批及显式提交、Run 发送锁、迟到响应隔离和受控产物下载。批准不等于执行成功；202 不等于已停止。
保留安全 Markdown/链接协议限制，不用未经处理 HTML。错误复用脱敏原因和 errorCode/errorId，不虚构日志编号。新增失败分支按 AGENTS.md 补适用测试。

实际 Node 验证：node --test cm-agent-console/src/test/js/console-core.test.cjs cm-agent-console/src/test/js/chat-stop.test.cjs cm-agent-console/src/test/js/console-navigation.test.cjs；加上真实新增/受影响测试。
Java 先 java -version、mvn -v 确认 JDK21，再 mvn -pl cm-agent-console -am test。
目标浏览器尺寸：1366×768、1920×1080、390×844；补充缩放、短视口、长内容、上翻与回读、文件、IME、状态未知、停止与审批边界。
本次前端范围无需数据库验证。若扩大到 JDBC/Flyway/容器，必须 ssh rocky、规定镜像并验证提交一致，禁止本机 Docker。
纯布局不需要付费模型；真实运行/工具副作用须对应授权。环境缺失则记录确切阻塞、未执行验收和继续条件，不声称模拟或历史结果为真实通过。
运行 git diff --check 和范围/敏感信息检查。没有额外授权不提交、推送、合并、部署。
完成后按仓库格式输出变更、实际命令及结果、影响、风险和直接相关下一步；提供更新后的提示词与问答入口。
```

## 关联产物

- [清单](../checklists/2026-10-10-chat-page-ux-checklist.md)
- [设计](../specs/2026-10-10-chat-page-ux-design.md)
- [计划](../plans/2026-10-10-chat-page-ux.md)
- [实现说明](../implementation/2026-10-10-chat-page-ux-implementation-design.md)
- [账本](../progress/2026-10-10-chat-page-ux-ledger.md)
