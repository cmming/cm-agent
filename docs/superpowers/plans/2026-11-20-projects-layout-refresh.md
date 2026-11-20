# 本地项目页布局优化计划

日期：2026-11-20；主题：`projects-layout-refresh`；修订：R0；模式：`mode=default`。

1. T0：读取 Git 状态、设计上下文、目标 HTML、共享样式、本地项目脚本和现有测试；确认既有修改与真实页面基线。
2. T0：登录当前运行页，检查桌面空状态、选中项目、文件视图和 390px 窄屏，记录主要结构问题。
3. T1：在 `projects.html` 增加项目环境控制带、详情头和项目工作区容器；保持现有 ID、表单、可访问名称和脚本挂载点不变。
4. T2：在 `local-projects.css` 收敛本地项目页样式，调整列表宽度、选中态、详情层次、空状态、会话工具栏、文件搜索和文件行。
5. T3：显式补齐移动端单列；将共享样式引用提升为 `local-projects.css?v=1.9.0`，同步更新所有 v2 入口和相关测试。
6. T3：增加页面结构断言，验证控制带、来源标签、详情头和工作区容器存在。
7. T4：运行 Node 项目页和导航测试，使用 JDK 21 运行 `cm-agent-console` Maven 测试。
8. T4：运行 Impeccable 布局检测，并在 1440px、1024px、390px 下检查截图、水平溢出、选中态和会话/文件切换。
9. T4：检查六份文档链接、日期、主题、修订和状态职责，将实际证据与限制写入账本。

## 验证方法

- `node --test cm-agent-console/src/test/js/local-projects.test.cjs cm-agent-console/src/test/js/console-navigation.test.cjs`
- 以 `JAVA_HOME=F:\java21` 运行 `mvn -q -pl cm-agent-console -am test`
- `.agents/skills/impeccable/scripts/impeccable.cmd detect --json --scope layout ...`
- Playwright 登录真实页面后检查桌面、中间宽度、移动端截图和 `document.documentElement.scrollWidth`

[清单](../checklists/2026-11-20-projects-layout-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-layout-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-layout-refresh-design.md) · [计划](../plans/2026-11-20-projects-layout-refresh.md) · [实现说明](../implementation/2026-11-20-projects-layout-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-layout-refresh-ledger.md)
