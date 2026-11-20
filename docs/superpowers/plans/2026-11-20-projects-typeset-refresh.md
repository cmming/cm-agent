# 本地项目页字阶与会话态收敛计划

日期：2026-11-20；主题：`projects-typeset-refresh`；修订：R2；模式：`mode=default`。

1. T0：读取当前设计上下文、`projects.html`、`local-projects.css`、`project-development.js` 和相关测试；测量标题、正文、控件和元数据的计算字号。
2. T0：登录真实页面，打开已有项目会话，检查最近会话、状态、审批、消息、输入区和运行操作。
3. T1：在 `DESIGN.md` 记录 compact product scale，并在 `local-projects.css` 落地对应字重、行高、字距、换行和等宽数字。
4. T2：调整 `projects.html` 的会话容器，将会话头、运行状态、审批、消息、输入和运行操作分成连续任务层；保留全部 ID 和表单。
5. T3：收敛消息气泡、最近会话列表、输入区、授权说明和已知运行操作的间距与响应式结构。
6. T3：把共享样式缓存版本更新为 `1.10.0`，同步所有 v2 入口和版本断言。
7. T4：增加会话顺序与结构断言，运行 Node、JDK 21 Maven、type 检测和 layout 检测。
8. T4：检查 1440px、1024px、390px 的实际会话截图、消息排布、表单高度和水平溢出；把结果与限制写入账本。

## R1 已部署页面文件区修复

9. T5：核对实时文件接口与 DOM 文本，确认是否存在替换字符或误解码。
10. T5：检查 `projectFilesPane` 的父节点与块级容器闭合；移除多余闭合标签。
11. T5：增加容器栈测试，通过本地静态服务器让浏览器实际解析 `projects.html`，验证文件面板位于 `projectWorkingGrid` 内。

## R2 左侧项目与会话导航

12. T6：把会话工具栏、会话列表和状态移动到左侧项目段下方；右侧移除重复历史列表。
13. T6：增加会话选中态、点击会话切回聊天视图和未选择空态行为；调整左栏高度与滚动。
14. T6：检查桌面与移动端项目/会话顺序、可视区域和水平溢出。

[清单](../checklists/2026-11-20-projects-typeset-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-typeset-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-typeset-refresh-design.md) · [计划](../plans/2026-11-20-projects-typeset-refresh.md) · [实现说明](../implementation/2026-11-20-projects-typeset-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-typeset-refresh-ledger.md)
