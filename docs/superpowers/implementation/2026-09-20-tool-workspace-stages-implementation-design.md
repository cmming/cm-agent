# Tool 工作区分阶段实现说明

关联设计：[Tool 工作区分阶段设计](../specs/2026-09-20-tool-workspace-stages-design.md)。

## 实现结果

- `tools.html` 新增 `toolDetailPanel` 和 `toolDetail`；顶部“注册 Tool”改为 `startToolCreateBtn`。
- `app.js` 的 `loadTools` 在存在选中 Tool 时渲染详情并默认进入 `detail` 阶段；无 Tool 时进入 `definition` 阶段。
- `showToolWorkspace` 对详情、定义、授权、调试四个右侧面板互斥设置可见性。
- `renderToolDetail` 展示类型、风险、启用状态、MCP 发布和运行时状态，并提供编辑、授权及可用时的调试入口。
- 内置 LOCAL 目录完整展示；以 `toolId` 与已注册工具合并。已存在条目仅提供“查看详情”，未存在且未安装条目提供“添加示例工具”，安装成功后进入调试阶段。
- `installed` 表示工具与模板完全一致，不能据此判定工具不存在；修改过的 `add` 保持真实 HIGH 风险及运行时状态，不再重复提供安装入口。
- `renderTools` 统一重绘普通工具及内置目录；目录请求完成后也执行完整重绘，确保两种请求完成顺序均不会产生重复或遗漏。刷新按钮同时请求两份数据。
- 详情页为 HTTP 和 LOCAL 工具提供独立 MCP 发布与取消发布按钮，复用 `/api/tools/{id}/mcp-publication` 的 PUT/DELETE 调用与既有错误提示。
- LOCAL 编辑中的发布复选框仅回显且禁用，并提示从详情操作；`console-core.js` 构建 LOCAL 更新载荷时使用已加载工具的发布状态，避免表单更改该状态。HTTP 表单保留原行为。
- v2 所有页面的脚本引用升级为 `app.js?v=2.0.25` 和 `console-core.js?v=2.0.12`。

## 与原方案的差异

修正原方案对已安装内置工具的过滤，改为按 ID 合并展示；既有服务端 API、授权和风险确认逻辑不变。
