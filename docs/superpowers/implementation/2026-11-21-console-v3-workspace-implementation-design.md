# V3 聊天工作区实现说明

日期：2026-11-21；主题：`console-v3-workspace`；修订：R1；工作模式：`mode=default`；任务：T0～T6。

## 已形成的分析与文档产物

本次六份中文实施工作包承接[2026-11-20 独立 Sites 原型](https://cm-agent-codex-workspace.poetic-lark-9729.chatgpt.site/)的布局，实施范围覆盖 V3、现有功能和真实后端接入。

T0～T5 覆盖十一页入口、普通与项目聊天、管理操作、版本化导航和真实后端验证。T6 按修订后的 D3 将默认入口切到 V3。

数据与执行边界已在清单、设计和执行提示词明确：参考 Site 仅用于视觉空间关系；产品调用仍须经当前共享客户端和真实同源 API，并保持认证、租户、权限、工具授权、审批、审计和错误脱敏契约。

## 版本入口与业务复用

- `cm-agent-console/src/main/resources/META-INF/resources/console/v3/` 新增 login、chat、projects、overview、agents、model-configs、tools、skills、runs、audit、local-client 十一个页面，保留当前 V2 的业务 DOM 和共享 API 客户端能力。
- V3 `assets/` 下的 `compatibility.css` 处理保留业务节点与新外壳的样式兼容；`workspace.js`、`workspace.css` 管理工作台壳；`chat-workspace.js`、`chat-workspace.css` 负责聊天/项目主区和上下文重排。
- 共享 `app.js` 使用显式 V2/V3 白名单、同版本 `returnTo` 和版本化路径；聊天 UX 同样受版本白名单约束。壳通过挂载/释放接口进入同文档导航生命周期，不复制模型、权限或审批 API。
- `local-projects` 增加可选同步布局钩子；切换项目时先还原布局层移动的节点，再释放并克隆项目视图，以防重复 ID 和旧节点归属遗留。
- `ConsoleController.workspaceV3()` 处理 `/`、`/console`、`/console/` 及显式 V3 入口，返回 HTTP 302 并给出同源 V3 登录页。`workspaceV2()` 独立处理 `/console/v2` 和 `/console/v2/`，保留已有书签的目标。固定短路径和 V1 继续沿用原控制器映射。
- `ConsoleControllerRoutingTest` 覆盖两种默认控制台路径及根入口、显式 V2 与 V3 路径。`ConsoleSmokeTest` 在 production 安全配置下覆盖默认跳转、显式 V2 入口和 V3 登录页的版本标记。冒烟测试用 JUnit 临时目录与随机密钥满足导入存储的启动约束。

## 布局与边界修正

- 聊天页面沿用真实消息、项目、审批、停止及文件能力。手机关闭侧栏时同步设置 `inert` 与 `aria-hidden`，上下文关闭后将焦点还给入口。V3 专用样式补足普通会话面板移到侧栏后的显示规则，解除 V2 在 761–900px 范围将聊天主区推离视口的限制，并修正导航文字宽度。
- 项目聊天中的「新会话」在缺少项目或新建条件时引导焦点到项目或 Agent 选择器，保留导航抽屉。共享业务控件保留禁用状态与授权检查。
- `console-core` 支持从 `errorCode` 读取结构化错误码。`local-projects` 仅在服务端明确返回 HTTP 404 `PROJECT_SELECTION_NOT_FOUND` 时解锁旧选择，保留同号诊断并提示重新选择。网络未知继续核对原请求，发起新选择需用户操作。

## 文档与设计范围

根 README 与发布说明加入 V3 入口和兼容关系。视觉决策记录在 `.impeccable/surfaces/console-v3.md`，V3 的设计说明与 sidecar 位于 `docs/design/console-v3/`；保留根设计 sidecar 的既有修改，不以 V3 视觉同步覆盖它。

本次没有迁入 Site 的示例数据、模拟回答或本地业务存储。实际后端、模型、审批与设备的验证结果及未覆盖范围只见账本，不由保留 DOM 或实现代码推定全部验收通过。

关联：[清单](../checklists/2026-11-21-console-v3-workspace-checklist.md) · [提示词](../prompts/2026-11-21-console-v3-workspace-prompt.md) · [设计](../specs/2026-11-21-console-v3-workspace-design.md) · [计划](../plans/2026-11-21-console-v3-workspace.md) · [账本](../progress/2026-11-21-console-v3-workspace-ledger.md)
