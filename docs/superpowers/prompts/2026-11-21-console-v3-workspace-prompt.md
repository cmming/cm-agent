# V3 聊天工作区执行提示词

日期：2026-11-21；主题：`console-v3-workspace`；修订：R1；工作模式：`mode=default`；任务：T0～T6。

```text
仓库：F:/java/cm-agent。
需求：在已接入真实后端的 V3 聊天工作区基础上，将 /、/console、/console/ 的默认入口切到 V3。保留显式 V2 入口。按用户本次指令提交并推送 master，排除本地配置，重启后验证两个版本。
当前工作包：2026-11-21 / console-v3-workspace / R1 / mode=default。

先读取 AGENTS.md 和以下工作包：
- docs/superpowers/checklists/2026-11-21-console-v3-workspace-checklist.md
- docs/superpowers/specs/2026-11-21-console-v3-workspace-design.md
- docs/superpowers/plans/2026-11-21-console-v3-workspace.md
- docs/superpowers/implementation/2026-11-21-console-v3-workspace-implementation-design.md
- docs/superpowers/progress/2026-11-21-console-v3-workspace-ledger.md
进度账本是状态、实际验证结果、遗留问题以及提交/发布信息的唯一入口；其余文档按固定职责维护。

需求的布局参考见本工作包设计文档中的独立 Sites 原型。
布局参考源码为 F:/java/cm-agent-sites/cm-agent-codex-20261120/，只继承其导航、排版和上下文空间关系，不能移植示例业务状态、模拟回答或虚构执行结果。

参照清单 T0～T6 的依赖，按账本定位本轮 T6。T0～T5 给出已有 V3 的功能与安全契约：
T0 核对当前 V2 十一个入口、共享 JS、服务端路由、接口与测试契约；存在 .codegraph 时先使用 CodeGraph。
T1 提供 /console/v3/ 版本化外壳及固定短路径，保持 V2 可用，V3 登录后默认进入聊天。同版本 returnTo 和显式版本白名单约束版本内导航。
T2 在项目/会话侧栏、中央聊天、按需上下文中接入现有普通聊天、项目文件、授权、审批、停止与错误反馈。
T3 保留能力总览、Agent、模型、工具、技能、运行、审计、本机客户端和独立本地项目工作流，复用既有 API。
T4 运行适用的回归、真实后端与浏览器检查，清楚区分模拟契约检查、真实 API 联调和真实模型/设备执行。
T5 同步生产说明、发布说明、视觉说明和六份工作包，校验链接及职责。
T6 将三个默认入口以 HTTP 302 导向 /console/v3/login.html，保留 /console/v2 和 /console/v2/ 到 V2 的跳转。更新路由与生产 profile 冒烟测试及入口说明；仅提交本次文件、推送 master，重启后验证 302、两版登录页的 200 与版本标记，以及健康状态。

认证与安全边界不得改变：使用现有受控 API 与会话 Cookie；JWT 不写浏览器存储或 URL；租户和权限仍来自服务端可信上下文；工具执行保留授权、审批与审计。模型名称与 Agent 的绑定以服务端模型配置为准，不以 Site 的自由选择模型替代。设备页面只显示有依据的配对状态。
保留中文组合输入保护、停止发送锁、断线核对和草稿生命周期；新增或变更失败分支按仓库规范验证中文原因、errorCode/errorId、日志关联和脱敏，不把秘密、堆栈或内部地址带到前端。

所有改动文字使用中文。保留五个既有脏文件和六份旧原型文档；不做无关重构、依赖升级或默认安全降级。Java 变更按触发清单直接补中文注释/JavaDoc；无需数据库迁移时不增加迁移。
构建前确认 Maven 使用 JDK 21。Docker/JDBC/Flyway/Testcontainers 验证必须经 ssh rocky，在项目所需容器环境执行并核对源码，不使用本机 Docker、不全局清理。
当前为默认模式：合理工程取舍自主执行并记录假设，不逐步征询；用户明确切换模式时按新指令处理。发现外部凭据、运行环境或真实执行不可验证时，记录确切限制，继续可独立推进的工作，不伪造验收结果。

交付前核对清单、实际差异和验证证据，按仓库规定的格式提供入口、提示词及快捷问答入口。Git 和服务操作以当前会话的明确授权为准。
```

保存本提示词只是形成当前修订的执行输入，不代表自动执行其中命令，也不增加修改或外部操作授权。

关联：[清单](../checklists/2026-11-21-console-v3-workspace-checklist.md) · [设计](../specs/2026-11-21-console-v3-workspace-design.md) · [计划](../plans/2026-11-21-console-v3-workspace.md) · [实现说明](../implementation/2026-11-21-console-v3-workspace-implementation-design.md) · [账本](../progress/2026-11-21-console-v3-workspace-ledger.md)
