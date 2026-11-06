# 本地项目页实现说明

> 需求：本地项目页（`projects.html`）
> 主题：`projects-page`
> 日期：2026-11-03
> 修订：R1
> 修订日期：2026-11-04
> 模式：`mode=default`
> 状态：当前有效需求工作包
> 证据截止：2026-11-04
> 最后复核：2026-11-04

## 实际页面交付

`projects.html` 已形成项目工作台入口，包含：

- 项目添加、系统文件夹选择、能力提示、项目列表、归档过滤和加载更多。
- 服务端允许开发会话时显示“登记固定项目工具目录”兼容入口；调用 `POST /api/projects/tools`，只登记固定定义，不自动授予 Agent。
- 工具目录登记增加进行中锁、双击去重、成功后保持“已登记”、受控失败可重试、网络未知保持锁定并提示刷新核对。
- 项目详情、文件夹身份、改名、归档、会话/文件视图切换。
- 项目会话、Agent 选择、自动工具准备、消息、审批、操作历史、停止和授权模式选择。
- 文件目录、文件名/脱敏正文搜索、分页、文本预览和引用到会话。
- 11 个固定 `project_*` 工具的页面说明。

## 关键代码位置

- [projects.html](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/projects.html)：页面结构和控件。
- [local-projects.js](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/local-projects.js)：项目、文件、搜索、预览和归档状态。
- [project-development.js](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/project-development.js)：会话、Run、授权模式、审批、操作历史和停止。
- [local-projects.css](../../../cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/local-projects.css)：项目页布局、消息和响应式样式。

## 后端合同

项目根和身份由服务端保存与校验；新建会话固定项目和 Agent；固定工具目录自动安装但不覆盖已有合法授权；手工登记入口同时要求 `project:manage` 和 `tool:grant`；Run 快照固定授权模式和本轮工具集合；文件变更和命令在副作用前复核提案、模式、权限、过期和审计；项目会话与设备客户端不能混用。

页面通过 `UNKNOWN`、`CREATE_UNKNOWN` 和版本冲突状态处理原生选择、项目创建、改名与归档的未知结果。未知状态只提示重新读取并核对，不自动重放旧票据或重复提交。

## R1 实施与验证

- `local-projects.js` 修复工具目录登记缺少 `finally` 解锁和未知结果可重复提交的问题；网络未知时保留锁定，受控 4xx 才恢复按钮。
- 项目列表重绘后在键盘选择或新建项目时恢复到同一项目按钮；归档成功后焦点移到稳定的“我的本地项目”标题，避免焦点掉回页面根节点。
- 新增回归覆盖工具目录双击只提交一次、成功文案、403 可重试、网络未知不重放，以及归档未知和改名版本冲突。
- 完整控制台 Node 套件 225 项通过；`ConsoleResourceTest` 16 项通过。
- Playwright Chromium 在 1366×768、1280×720 和 390×844 验证项目页无横向溢出，项目选择、会话/文件切换和归档后的焦点路径正确；可访问性树读出项目 pressed 状态与工作区切换状态。
- 本轮未重启业务服务、未执行真实项目写入或命令副作用；浏览器验收使用隔离静态夹具，未提交或部署。

## 与旧工作包的关系

本组 R0 按 `projects.html` 重新组织需求，合并了原本地项目工作区、项目授权和项目页样式中的相关内容；R1 补入手工工具目录、异步未知状态和证据口径。历史后端实现、测试和证据仍作为来源记录在[账本](../progress/2026-11-03-projects-page-ledger.md)，不把历史页面通过自动等同于后续源码或未测试环境通过。

## 当前边界

项目页命令在 Server 所在电脑执行，继承运行账号权限，不提供操作系统沙箱；`FULL_ACCESS` 仍是项目内固定工具授权；桌面/窄屏视觉证据不等于所有浏览器、真实手机或完整无障碍认证；Linux/macOS 桌面和长时间运行仍未覆盖。

## 关联文档

- [项目页清单](../checklists/2026-11-03-projects-page-checklist.md)
- [项目页设计](../specs/2026-11-03-projects-page-design.md)
- [项目页计划](../plans/2026-11-03-projects-page.md)
- [项目页提示词](../prompts/2026-11-03-projects-page-prompt.md)
- [项目页账本](../progress/2026-11-03-projects-page-ledger.md)
