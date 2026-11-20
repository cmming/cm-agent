# 本地项目页字阶与会话态收敛清单

日期：2026-11-20；主题：`projects-typeset-refresh`；修订：R2；模式：`mode=default`。

需求来源：用户调用 `$impeccable /typeset`，随后指出 `/console/v2/projects.html` 在真实会话状态下仍然凌乱。Git 基线为 `a61558ccf28d8792e103b82fbb46a1590dc8d97d`；工作树包含上一轮布局优化及大量其他未提交修改。

## 范围

- 为本地项目页建立固定产品字阶，覆盖页面标题、对象标题、分区标题、正文、控件标签和元数据。
- 修正真实项目会话中的视觉顺序、消息排布、授权说明密度和最近会话列表。
- 保持现有接口、权限、审批、运行、文件访问和会话生命周期逻辑不变。
- 不扩大到其他控制台页面的整体改版；仅同步共享样式缓存版本。

## 任务

| 编号 | 目标 | 涉及文件线索 | 依赖 | 验收条件 | 验证方法 |
|---|---|---|---|---|---|
| T0 | 评估现有字阶与真实会话态 | `DESIGN.md`、`local-projects.css`、真实登录页面 | 无 | 识别标题、正文、控件、元数据以及会话表单的层级和密度问题 | 计算样式、会话截图、类型与布局检测 |
| T1 | 建立紧凑产品字阶并文档化 | `DESIGN.md`、`local-projects.css` | T0 | 页面使用固定角色尺度；中文标题字距不过紧；版本与数字使用等宽数字 | 计算样式、类型检测、桌面与窄屏截图 |
| T2 | 重组会话态信息顺序 | `projects.html` | T0 | 会话显示为会话头、状态、审批、消息、输入、运行操作；输入不再排在消息之前 | HTML 结构断言、真实会话截图 |
| T3 | 收紧消息、最近会话与输入区 | `local-projects.css` | T1、T2 | 用户和 Agent 消息左右分列；最近会话单行省略；授权说明 1200px 以上两列，其余宽度单列 | 1440px、1024px、390px 视觉检查与水平溢出检查 |
| T4 | 更新共享缓存版本与回归验证 | 全部 v2 入口 HTML、相关 JS 测试 | T1–T3 | 所有入口引用 `local-projects.css?v=1.10.0`；现有测试通过 | Node、Maven、类型检测和布局检测 |
| T5 | 修复文件面板被解析到详情容器之外的问题 | `projects.html`、`local-projects.test.cjs` | T2 | 文件面板父节点为 `projectWorkingGrid`；文件视图位于项目详情面板内部；块级容器闭合正确 | 浏览器 DOM 父子关系、容器栈测试、文件视图截图 |
| T6 | 将项目会话移到左侧项目列表下方 | `projects.html`、`project-development.js`、`local-projects.js`、`local-projects.css` | T2、T5 | 左栏依次显示项目和当前项目会话；当前会话有选中态；右栏只显示会话或文件内容；点击会话自动切回会话视图 | 结构断言、会话选中态测试、桌面与移动端截图 |

## 门禁

- 不修改服务端接口、会话状态机、权限、审批、审计或文件访问边界。
- 不覆盖用户已有修改，不提交、推送、部署或重启远端服务。
- 不在截图、日志或文档中记录登录凭据或认证状态内容。
- 浏览器注入式截图检查不能替代远端发布验收。
- R1 的“文件区域乱码”复核必须区分字符编码损坏与 DOM 结构错位；没有替换字符时不得把结构问题误写成编码问题。
- R2 中会话列表属于项目下的二级导航；不得同时保留右侧“最近会话”副本，也不得让新建会话表单长期挤占会话列表空间。

[清单](../checklists/2026-11-20-projects-typeset-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-typeset-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-typeset-refresh-design.md) · [计划](../plans/2026-11-20-projects-typeset-refresh.md) · [实现说明](../implementation/2026-11-20-projects-typeset-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-typeset-refresh-ledger.md)
