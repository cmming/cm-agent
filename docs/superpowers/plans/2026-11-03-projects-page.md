# 本地项目页计划

> 需求：本地项目页（`projects.html`）
> 主题：`projects-page`
> 日期：2026-11-03
> 修订：R1
> 修订日期：2026-11-04
> 模式：`mode=default`
> 状态：当前有效需求工作包
> 证据截止：2026-11-03
> 最后复核：2026-11-04

本计划描述 `projects.html` 后续维护和补充验证的方法。当前已完成能力、历史验收和未覆盖边界见[账本](../progress/2026-11-03-projects-page-ledger.md)。

## 当前完成后的维护顺序

1. **T0/T1：页面与契约复核**。先读取 `projects.html`、`local-projects.js`、`project-development.js`、`local-projects.css`，核对服务端能力开关和当前项目工具目录；确认 DOM 标识、无障碍标签和错误状态。
2. **T2/T3：项目与文件链路回归**。验证项目登记、列表、刷新、改名、归档、目录分页、文件名/正文搜索、脱敏预览和引用；使用自建临时项目，不读取用户真实敏感文件。
3. **T4：会话与工具准备回归**。验证新建项目会话时固定 Agent/项目关系、11 个 `project_*` 工具目录、缺失 grant 补齐、已有合法 grant 不重复写入。
4. **T5/T6：授权与副作用回归**。分别覆盖三种模式、审批前零副作用、批准后写入、命令、撤销、停止、外部冲突和结果未知；不得复用历史 Run 作为新验收。
5. **T7/T8：安全与 UI 回归**。检查 owner/tenant 越权、错误码/编号、日志脱敏、桌面和窄屏布局、焦点与空态。
6. **T9/T10：扩大环境验证**。在可用环境补 Linux/macOS 桌面、多浏览器、长时间运行、字体缩放和完整无障碍检查；不可用环境保持明确未验收。
7. **T11：工具目录手工登记**。验证按钮只在服务端能力允许时显示，调用需要 `project:manage` 和 `tool:grant`；确认只登记固定定义、不自动授予 Agent、重复登记幂等，并覆盖失败/未知结果。
8. **T12：异步未知状态**。覆盖原生选择 `UNKNOWN`、项目创建 `CREATE_UNKNOWN`、改名版本冲突和归档未知结果；每种状态都必须刷新核对，禁止自动重复提交。

## 文件与验证方法

- 页面：`cm-agent-console/src/main/resources/META-INF/resources/console/v2/projects.html`
- 交互：`assets/local-projects.js`、`assets/project-development.js`
- 样式：`assets/local-projects.css`、`assets/workbench.css`
- 服务端：`LocalProjectController`、`LocalProjectService`、`LocalProjectTools`、`LocalProjectRunService` 及对应测试
- 验证：Node 项目页回归、控制台 Java 资源测试、服务端定向 Java/MockMvc、真实浏览器桌面/窄屏、适用时使用真实 Provider 和 Rocky 双数据库

## 完成标准

页面行为与设计一致，手工工具目录和异步未知状态有可观察验收，错误和边界可见；真实写操作须有服务端权威证据；任何旧测试只作为历史证据。页面样式变化不能改变项目权限、工具授权、审计或运行状态。
