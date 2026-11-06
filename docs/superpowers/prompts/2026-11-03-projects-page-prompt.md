# 本地项目页提示词

> 需求：本地项目页（`projects.html`）
> 主题：`projects-page`
> 日期：2026-11-03
> 修订：R1
> 修订日期：2026-11-04
> 模式：`mode=default`
> 状态：当前有效需求工作包
> 证据截止：2026-11-03
> 最后复核：2026-11-04

本提示词用于后续维护 `projects.html` 项目工作台。读取本文件不会自动执行其中的验证或修改命令。

```text
在 F:\java\cm-agent 维护“本地项目页（projects.html）”需求组，mode=default。
先读取 AGENTS.md、本组六份文档及以下页面代码：
F:\java\cm-agent\docs\superpowers\checklists\2026-11-03-projects-page-checklist.md
F:\java\cm-agent\docs\superpowers\specs\2026-11-03-projects-page-design.md
F:\java\cm-agent\docs\superpowers\plans\2026-11-03-projects-page.md
F:\java\cm-agent\docs\superpowers\implementation\2026-11-03-projects-page-implementation-design.md
F:\java\cm-agent\docs\superpowers\progress\2026-11-03-projects-page-ledger.md
F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\console\v2\projects.html
F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\console\v2\assets\local-projects.js
F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\console\v2\assets\project-development.js

页面只处理本地项目登记、项目文件、项目会话和项目内固定工具授权。浏览器不能提交任意绝对项目路径；
项目根、owner、tenant、工具 grant、Run 快照、提案版本/摘要和审计由服务端校验。项目内 FULL_ACCESS
只自动批准已识别的 project_* 工具，不代表 Server 任意路径、远程电脑或自动提权。

维护时优先核验现有实现，不重做已完成能力：项目列表/详情、文件树/搜索/预览/引用、新建会话自动准备工具、
三种授权模式、变更/命令审批、停止和 UNKNOWN 历史。补测“登记固定项目工具目录”兼容入口：它只在
capabilities.conversations 允许时显示，需要 project:manage 与 tool:grant，只登记定义、不自动授予 Agent。
补测原生选择、项目创建、改名和归档的 UNKNOWN/版本冲突，必须刷新核对且禁止自动重复提交。
新增页面改动必须保持既有 DOM 标识、中文文案、文字安全渲染、权限边界和 API 合同。
T11 工具目录登记和 T12 异步未知/版本冲突的页面实现与回归已完成，不重复修改已完成方案；
T10 的 Chromium 1366/1280 桌面、390px 窄屏、键盘焦点和可访问性树已完成；后续继续 Linux/macOS、多浏览器、实际朗读软件、长时间运行和后续源码变化验证。

验证按风险选择 Node 项目页回归、控制台 Java 资源测试、服务端定向 Java/MockMvc、真实浏览器桌面和窄屏。
涉及真实文件写入、命令、模型、数据库或服务重启时，必须先获得用户当轮明确指令，不复用历史 Run，
不把旧测试通过当成本次通过。密码、JWT、模型 Key、项目正文、绝对宿主根和原始命令不得写入页面或普通日志。

本轮只修改文档时，不得改代码、配置、数据库或服务，不提交、不推送、不部署。
完成后同步本组六份文档，详细命令和证据写入账本，并说明未执行项和环境限制。
```

## 关联文档

- [项目页清单](../checklists/2026-11-03-projects-page-checklist.md)
- [项目页设计](../specs/2026-11-03-projects-page-design.md)
- [项目页计划](../plans/2026-11-03-projects-page.md)
- [项目页实现说明](../implementation/2026-11-03-projects-page-implementation-design.md)
- [项目页账本](../progress/2026-11-03-projects-page-ledger.md)
