# 会话聊天均衡优化实施：执行提示词

日期：2026-10-10；主题：chat-page-ux-delivery；修订：R0；工作模式：mode=default。

本包来自用户“按照执行提示词继续执行”的实施授权，保留原分析包。D1 均衡优化、保留风格、不新增后台能力；D2 Enter 发送、Shift+Enter 换行，防中文组合输入误发。代码、自动化和模拟浏览器结果已经交付，实机补验收 T7 尚未完成。

~~~text
仓库：F:\java\cm-agent。
模式：mode=default；普通工程细节自主决策，不重复询问已确认的 D1/D2。
先读取：
F:\java\cm-agent\docs\superpowers\checklists\2026-10-10-chat-page-ux-delivery-checklist.md
F:\java\cm-agent\docs\superpowers\prompts\2026-10-10-chat-page-ux-delivery-prompt.md
F:\java\cm-agent\docs\superpowers\specs\2026-10-10-chat-page-ux-delivery-design.md
F:\java\cm-agent\docs\superpowers\plans\2026-10-10-chat-page-ux-delivery.md
F:\java\cm-agent\docs\superpowers\implementation\2026-10-10-chat-page-ux-delivery-implementation-design.md
F:\java\cm-agent\docs\superpowers\progress\2026-10-10-chat-page-ux-delivery-ledger.md
原需求提示词：F:\java\cm-agent\docs\superpowers\prompts\2026-10-10-chat-page-ux-prompt.md

阅读 AGENTS.md、相关 POM/README、PRODUCT.md、DESIGN.md。核对当前 Git HEAD 与脏文件；本包起点为316828a6b3fb30d28b8ea728fe309fd6114bdb40，不能假定之后没有用户修改。仓库有 .codegraph 时先尝试 CodeGraph，索引不一致复核源文件。本轮已有 app.js 索引范围不匹配记录。
保护 .gitignore、根 index.html、application.yml、application-mysql.yml、application-ok.yml、.codex/ 和 .workbuddy/，不能覆盖或输出凭据。原分析包不改写成实施历史。

任务状态：
T0 当前复核及实施工作包完成。
T1 布局与阅读/输入空间完成。
T2 已加载标题过滤、时间分组和手机列表完成。
T3 跟随/锚点、正文与代码复制完成。
T4 输入与发送快照实现及自动边界验证完成，实机 IME 待验收。
T5 审批历史/待办/停止与错误状态兼容完成。
T6 133个 Node、15个 Java、模拟浏览器与文档已完成；实机补验收未完成。
T7 待验收：按当前授权补系统中文输入法、125%/200%缩放、手机软键盘和真实运行烟测。
先读取账本，避免重做 T0～T6 已完成内容。只有新的失败或用户修改才扩大代码改动。工作包问答本身不触发实施或外部写入。

T7 验收：
中文拼音/五笔选词 Enter 不发送，随后独立 Enter 只提交一次，Shift+Enter 换行；检查输入增长与焦点。
浏览器125%和200%缩放、390×844及短视口检查输入、发送、审批和会话列表可达，无整页横向溢出。
真实测试服务中使用明确授权的 Agent/工具，验证上翻时 delta、异步文件与终态回读不抢位置；停止/CANCELLING/待审批保持锁，CANCELLED 保留部分输出；只读 canDecide 不出现可提交操作。
仅在有对应授权时发送真实模型请求或执行可能有副作用的工具。不能把模拟、单测或历史结果当真实后台通过。
原生桌面 API禁用时说明系统 IME/软键盘不能验证；先做独立可推进项目，不绕过限制或改弱安全配置。无法核验保持 T7 待验收，并写继续条件。

代码入口：cm-agent-console/src/main/resources/META-INF/resources 的 assets/app.js、console/v2/chat.html、console/v2/assets/multipage.css。
新行为仅 v2聊天启用；保留原表单ID、v1、运行页和全部调用/权限/tenant/审计/受控下载链路。
禁止后台搜索、会话删除/重命名/归档、上传、多模态、消息编辑/再生成、跨刷新草稿、自动补发写请求或跨实例停止。
未知结果的GET空响应或单独活跃Run不证明本次输入接受/拒绝；不得自动重试POST。已接受请求不能恢复为待发草稿。
历史读取失败显示脱敏原因、原错误码和errorId，剪贴板失败不暴露原始异常或虚构错误编号；安全Markdown与链接协议规则保留。

验证：
node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js
node --check cm-agent-console/src/test/js/chat-ux-preview.cjs
node --test cm-agent-console/src/test/js/*.test.cjs
Java和Maven先确认JDK21；本机已验证F:\java21可用，再执行mvn -q -pl cm-agent-console -am test。
本次无数据库/容器变更，不跑本机Docker。后续若扩大到JDBC/Flyway按ssh rocky、JDK21与提交一致规则另验证。
git diff --check仅对本次文件检查，并核对脏文件保护；不要顺手修application-mysql.yml既有EOF空行。
连续修改维护本包六份文档，保留R0证据，新验收结果追加；需求实质修订再升R1。
未获得额外授权不提交、推送、合并或部署。最终输出中文变更、实际命令与结果、影响、风险、直接后续；链接本提示词和账本并提供问答快捷入口。
~~~

## 关联产物

- [需求清单](../checklists/2026-10-10-chat-page-ux-delivery-checklist.md)
- [设计](../specs/2026-10-10-chat-page-ux-delivery-design.md)
- [实施计划](../plans/2026-10-10-chat-page-ux-delivery.md)
- [实际实现说明](../implementation/2026-10-10-chat-page-ux-delivery-implementation-design.md)
- [实施账本](../progress/2026-10-10-chat-page-ux-delivery-ledger.md)
