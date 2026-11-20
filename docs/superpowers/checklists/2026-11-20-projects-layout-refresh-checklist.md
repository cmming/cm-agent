# 本地项目页布局优化清单

日期：2026-11-20；主题：`projects-layout-refresh`；修订：R0；模式：`mode=default`。

需求来源：用户要求美化 `/console/v2/projects.html` 的页面布局。Git 基线为 `a61558ccf28d8792e103b82fbb46a1590dc8d97d`；工作树包含大量既有未提交修改，本轮只增量处理本地项目页相关文件，不回退其他内容。

## 范围

- 保留现有“受控工作台”视觉世界、业务文案、接口、权限、审批和项目操作语义。
- 重新组织页面标题、环境说明、项目来源、项目列表、项目详情和工作视图的空间关系。
- 覆盖桌面、中间宽度和移动端的结构变化；不扩大到其他控制台页面的整体改版。
- 不改变服务端逻辑、接口请求、认证、租户、审计或本地文件访问边界。

## 任务

| 编号 | 目标 | 涉及文件线索 | 依赖 | 验收条件 | 验证方法 |
|---|---|---|---|---|---|
| T0 | 核对现有页面、设计基线、真实桌面与窄屏状态 | `PRODUCT.md`、`DESIGN.md`、`projects.html`、`local-projects.css` | 无 | 识别现有未提交修改、主要布局问题和需要保留的业务控件 | 源码核对、登录后的浏览器截图、布局检测 |
| T1 | 重组页面顶部结构与详情层级 | `projects.html` | T0 | 页面按标题、环境与来源控制带、列表/详情顺序呈现；现有 ID 和可访问名称保留 | HTML 静态断言、浏览器 DOM 检查 |
| T2 | 重定列表、详情、空状态、会话与文件区的视觉节奏 | `local-projects.css` | T1 | 列表具备稳定宽度和选中状态；详情区按身份、工作视图、当前任务分层；空状态与文件搜索不再孤立 | 桌面与中间宽度截图、间距和溢出检查 |
| T3 | 补齐窄屏单列与共享缓存版本 | `local-projects.css`、全部 v2 入口 HTML、相关 JS 测试 | T2 | 390px 无水平溢出；环境控制带和项目来源按单列排列；所有 v2 入口引用 `local-projects.css?v=1.9.0` | 移动端截图、`scrollWidth` 检查、Node 测试 |
| T4 | 完成回归验证和工作包记录 | 控制台测试、`docs/superpowers` 六份文档 | T1–T3 | 模块测试通过；布局检测无遗留；记录明确远端部署未执行 | Node、Maven、检测器和工作包一致性检查 |

## 门禁

- 不覆盖用户已有修改，不提交、推送、部署或重启远端服务。
- 不改变认证、权限、租户、审计、命令执行和文件访问语义。
- 不泄露登录凭据、认证状态或其他敏感信息。
- 浏览器截图与本地测试不能替代远端部署后的最终验收。

[清单](../checklists/2026-11-20-projects-layout-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-layout-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-layout-refresh-design.md) · [计划](../plans/2026-11-20-projects-layout-refresh.md) · [实现说明](../implementation/2026-11-20-projects-layout-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-layout-refresh-ledger.md)
