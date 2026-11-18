# 项目优先聊天进度账本

日期 `2026-11-18` · 修订 `R0` · 模式 `mode=interactive`

范围为 CM Agent 控制台 v2 的目录项目优先聊天及普通聊天入口，任务编号 `T0` 至 `T4`。

## 当前进度

Git 基线 `ccccd8ddae43`，工作模式 `mode=interactive`。

| 任务 | 状态 | 证据 |
| --- | --- | --- |
| T0 | 完成 | 用户选择 Q1 至 Q8 的推荐方案，并确认修改仓库和验证。 |
| T1 | 完成 | 浏览器覆盖项目直链、登录返回、前进后退、账号偏好隔离、无效链接与普通入口。 |
| T2 | 完成 | 浏览器验证服务端项目新建、读取和同步发送，预览引用只在选定会话后加入草稿。新增回归覆盖选历史会话时 Agent 候选仍能加载。 |
| T3 | 完成 | 浏览器验证设备项目新建、发送、专用请求头与归档阅读。Node 回归覆盖来源门禁、只读历史分页、精确审批与停止。 |
| T4 | 完成 | 通过 Node、Maven Console、浏览器预览、目标静态资源与文档链接核验。 |

## 验证证据

- 改造前从内网目标地址读取静态页面返回 HTTP 200，页面标题为「会话聊天」。当时未使用认证会话读取项目数据。
- 改造前运行聊天与两类项目相关的 81 项 Node 测试，80 项通过，1 项使用过时脚本版本断言失败。更新为各 v2 入口统一引用新项目脚本的断言后，使用 `Get-ChildItem` 枚举 Console 的 `*.test.cjs` 并执行 `node --test --test-reporter=tap @tests`，241 项通过。
- 切换到 `F:/java21` 后用 `java -version` 和 `mvn -v` 确认 Maven 3.9.4 使用 JDK 21.0.11。`mvn -q -pl cm-agent-console -am test` 通过，`ConsoleResourceTest` 16 项通过。
- Playwright CLI 使用独立预览与模拟 API 验证 1366 × 900 和 390 × 844。覆盖两个项目来源、文件引用后手动发送、两类项目新建、归档历史、普通入口与登录返回。窄屏横向溢出检查通过。留存桌面与窄屏预览。
- 从内网目标服务读取三个 HTML 入口请求和五个版本化资源，均返回 HTTP 200 且具备新入口或脚本标识。最终复查项目脚本已包含 Agent 加载竞态修正。
- 使用 `git diff --check` 核对本次修改并检查同主题六份文档的链接。样式检测提示共享导航色值等既有约定及局部尺寸偏差，已按术语和视觉基线调整本次新增导航圆角与标题字号。
- Git 交付前复跑 Console 的 Node 测试，241 项通过。使用 JDK 21.0.11 复跑 `mvn -q -pl cm-agent-console -am test`，通过。

## 内网真实项目会话验收

2026-11-18 在独立的 Playwright Chrome 会话中访问 `http://192.168.5.149:8080/console/v2/chat.html`，通过控制台正常登录，核实账号具有 `project:read`、`project:manage`、`tool:grant` 和 `agent:run`。选中现有的服务端电脑项目 `native-picker-final-20261112`，选择绑定 `glm-5.3-flash` 的 Agent 并新建会话，创建返回 HTTP 201，会话 ID 为 `3e2e9bb1-f9c3-458d-a31b-e97e1ca4ec50`。

页面以「副作用前确认」发送一次纯文本验收消息。请求返回 HTTP 200，Run `8c85cbc2-edca-4165-89fb-fd433087a2ab` 状态为 `SUCCEEDED`，发送到页面显示成功约 2.7 秒。页面的 Agent 消息显示「项目聊天实测通过 CM-CBB291」，工具调用数为零。

同日在独立浏览器中用用户指定的验收账号复测。选中同一服务端电脑项目，新建会话返回 HTTP 201，会话 ID 为 `118de2e9-5817-440b-a377-0597c3eeb21d`。发送返回 HTTP 200，`glm-5.3-flash` 的回复「项目聊天实测通过 CM-5EF449」约 1.7 秒后显示在页面。Run `3bd08d70-657d-497b-afb3-c3101e782c95` 为 `SUCCEEDED`，工具调用数为零。

## 发布与限制

本地交付的提交说明为「将 v2 聊天改为项目优先并记录验收」。通过 `git log -- docs/superpowers/progress/2026-11-18-project-first-chat-ledger.md` 查询这份账本的提交。

目标服务已返回新版静态资源，本次未执行单独的发布或重启操作。实机验证覆盖服务端电脑项目。配对电脑项目的实机验收待开展，其非回环访问需要 HTTPS 和允许的来源。此次未运行数据库或容器集成测试。

[清单](../checklists/2026-11-18-project-first-chat-checklist.md) · [执行提示词](../prompts/2026-11-18-project-first-chat-prompt.md) · [设计](../specs/2026-11-18-project-first-chat-design.md) · [计划](../plans/2026-11-18-project-first-chat.md) · [实现说明](../implementation/2026-11-18-project-first-chat-implementation-design.md)
