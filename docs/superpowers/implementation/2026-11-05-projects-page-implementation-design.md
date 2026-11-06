# Projects 页 2026-11-05 变更实现

> 页面：`/console/v2/projects.html`  
> 日期：2026-11-05  
> 修订：`R1`（2026-11-05）
> 模式：`mode=default`

## 布局实现

- `projects.html`：归档和改名进入“项目管理”折叠区，审批移动到输入之前。
- `local-projects.js`：归档项目隐藏管理入口，活动项目恢复。
- `local-projects.css`：会话最大宽 960px，Agent 控件最大宽 340px，按钮换行。
- 资源版本和 Node 断言同步。

## 安全开关实现

- `LocalProjectProperties`：新增默认 `false` 的 `allowNonLoopback`。
- `LocalProjectConfiguration`：按开关决定 `server.address` 是否必须为回环；开启时输出 WARN。
- `LocalProjectRequestBoundary`：按开关决定是否要求双方回环地址，其他来源检查不变。
- `application-local.yml`：启用本地项目、非回环开关和三个精确 Origin。

## 验证

- Projects 页 Node 40/40，ConsoleResourceTest 16/16。
- `LocalProjectConfigurationTest` 13/13、`LocalProjectRequestBoundaryTest` 3/3、`LocalProjectControllerTest` 6/6。
- 当前服务 PID `12488`，`projects.html` 从本机和 Rocky 均返回 HTTP 200。
- 未认证 `/api/projects/capabilities` 返回 401。

## R1 HTTP 局域网 UUID 修复

- `local-projects.js` 新增 `createRequestId`：优先 `randomUUID`，否则 `getRandomValues` 生成 UUID v4。
- 安全随机数完全不可用时返回明确中文错误，不退化到弱随机。
- 所有加载该脚本的 v2 页面将资源版本从 `1.1.9` 更新为 `1.2.0`。
- 新增 Node 测试覆盖随机数 fallback、优先路径和不可用路径。
- Node 41 项通过；`ConsoleResourceTest` 16 项通过。

## 风险

- 当前通过 HTTP 暴露，且远端可以操作服务端电脑项目目录。
- 尚未完成真实认证浏览器下的项目创建、目录选择、写入和审批全链路验收。
- 未提交。

## 关联文档

- [清单](../checklists/2026-11-05-projects-page-checklist.md)
- [设计](../specs/2026-11-05-projects-page-design.md)
- [计划](../plans/2026-11-05-projects-page.md)
- [提示词](../prompts/2026-11-05-projects-page-prompt.md)
- [账本](../progress/2026-11-05-projects-page-ledger.md)
