# 本机客户端页提示词

> 需求：本机客户端页（`local-client.html`）
> 主题：`local-client-page`
> 日期：2026-11-03
> 修订：R3
> 修订日期：2026-11-05
> 模式：`mode=default`
> 状态：当前有效需求工作包
> 证据截止：2026-11-05
> 最后复核：2026-11-05

本提示词用于后续维护 `local-client.html` 设备管理页。读取本文件不会自动执行其中的验证或修改命令。

```text
在 F:\java\cm-agent 维护“本机客户端页（local-client.html）”需求组，当前修订 R3，mode=default。
先读取 AGENTS.md、本组六份文档及以下页面/脚本：
F:\java\cm-agent\docs\superpowers\checklists\2026-11-03-local-client-page-checklist.md
F:\java\cm-agent\docs\superpowers\specs\2026-11-03-local-client-page-design.md
F:\java\cm-agent\docs\superpowers\plans\2026-11-03-local-client-page.md
F:\java\cm-agent\docs\superpowers\implementation\2026-11-03-local-client-page-implementation-design.md
F:\java\cm-agent\docs\superpowers\progress\2026-11-03-local-client-page-ledger.md
F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\console\v2\local-client.html
F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\assets\app.js
F:\java\cm-agent\cm-agent-local-client\pom.xml

页面只负责接收客户端签名配对包、预览设备、提交确认、列出设备、查看详情和撤销。
浏览器不能生成设备有效签名，不能伪造设备 ID、公钥或授权结论，不显示虚构在线状态；
服务端必须验证 requestId、nonce、签名、过期时间、租户和原主体。设备执行能力不在本页面实现，
聊天页只能使用当前主体已配对设备，并绑定当前 Agent、会话、Run 和授权模式。

聊天页的设备授权支持 ASK_ON_EFFECT、ASK_ON_RISK 和 FULL_ACCESS；本页不提供模式选择。
FULL_ACCESS 表示当前 Run 内用户账号权限范围，不等于项目内 project_* 工具授权，也不自动提权。
配对登记/确认/撤销需要 agent:run，设备列表需要 agent:read；设备私钥优先使用系统密钥链，
不可用时必须用本地口令保护。浏览器确认后，客户端还要轮询并保存设备标识；超时、退出或落盘失败
必须提示“服务端已确认但客户端未完成”，不能仅凭浏览器确认声称设备可用。

R3 的当前分发目标是单个可执行 fat JAR：
- 在 JDK 21 和 Maven 3.9+ 环境执行 `mvn -pl cm-agent-local-client -am -DskipTests package`；
- 使用 `target/cm-agent-local-client-0.1.0-SNAPSHOT-all.jar`，版本变化时以构建输出为准；
- 目标电脑只需 JDK 21，配对和启动均使用 `java -jar <fat JAR> pair|run ...`；
- 普通薄 JAR 必须继续保留，fat JAR 通过 `all` 分类器附加，不替代模块主产物；
- 不得重新引入 `target/classes`、`target/lib` 或 `dependency:copy-dependencies` 作为目标电脑运行前提。

优先验证既有实现，不重复已完成 Windows 配对流程。补测设备刷新、活跃/已撤销列表、详情字段、
二次撤销、expectedVersion 冲突和系统健康状态与设备在线状态的文案区分。新增改动需保持中文文案、
现有 DOM 标识、错误码/errorId、日志脱敏和撤销后拒绝策略。检查 local-client.html 的资源引用，
尤其避免重复或无关脚本。桌面、窄屏、键盘、焦点和错误态按风险补测。

本页面不执行文件、网络或命令。任何真实客户端操作、模型调用、远程连接、服务重启、数据库测试或安装验证
必须有当轮明确授权；UNKNOWN 不重放，停止不代表回滚。密码、JWT、模型 Key、设备私钥、签名材料和完整
外部响应不得进入页面、普通日志或文档。

本轮只修改文档时，不得改代码、配置、数据库或服务，不提交、不推送、不部署。
T12 配对生命周期/RBAC、T13 设备管理、T14 系统状态文案、T15-T16 首次连接指引、T18-T20 fat JAR 打包与验证已完成；后续优先 T7-T9 和 T17 的跨平台、真实配对、实际朗读软件、远程 TLS、生产安装与分发验收。
完成后同步六份文档，实际命令和证据写入账本，未执行的 Linux/macOS、TLS、多实例和生产安装保持明确待做。
```

## 关联文档

- [本机客户端页清单](../checklists/2026-11-03-local-client-page-checklist.md)
- [本机客户端页设计](../specs/2026-11-03-local-client-page-design.md)
- [本机客户端页计划](../plans/2026-11-03-local-client-page.md)
- [本机客户端页实现说明](../implementation/2026-11-03-local-client-page-implementation-design.md)
- [本机客户端页账本](../progress/2026-11-03-local-client-page-ledger.md)


## R2 当前范围（2026-11-04）

T15–T16 已实施并有证据，T17 的真实客户端与跨平台验收仍待做。后续仅针对用户明确的新指令操作；阅读提示词不触发配对、设备登记、服务重启或其他副作用。首次连接指引、客户端获取及配对与单轮授权边界的当前情况见清单和账本。

## R3 当前范围（2026-11-05）

T18–T20 已将客户端分发改为单个 `-all.jar`，页面和 Node 回归已同步；普通薄 JAR 保留。当前证据只证明 Windows JDK 21 下的构建和 `help` 启动，不证明真实配对、跨平台运行、原生安装包签名或生产分发。
