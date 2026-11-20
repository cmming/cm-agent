# 本地项目页布局优化进度账本

日期：2026-11-20；主题：`projects-layout-refresh`；修订：R0；模式：`mode=default`。

## 基线与范围

- 分支：`master`
- Git 基线：`a61558ccf28d8792e103b82fbb46a1590dc8d97d`
- 开始时工作树含大量既有未提交修改；本轮保留这些修改，只处理本地项目页布局、共享样式版本、相关测试和工作包文档。
- 目标页：`http://192.168.5.149:8080/console/v2/projects.html`

## 任务状态与证据

| 编号 | 状态 | 实际证据 |
|---|---|---|
| T0 | 完成 | 读取 `PRODUCT.md`、`DESIGN.md`、目标 HTML/CSS/JS；登录当前运行页检查桌面空状态、选中项目、文件视图和 390px 基线 |
| T1 | 完成 | `projects.html` 已增加 `.project-context-band`、`.project-detail-header`、`.project-workspace`；现有业务 ID 与脚本挂载点未变 |
| T2 | 完成 | `local-projects.css` 已完成桌面列表/详情、空状态、标签式视图、会话工具栏和文件区布局收敛；布局检测返回 `[]` |
| T3 | 完成 | 11 个 v2 入口均引用 `local-projects.css?v=1.9.0`；390px 下 `.local-project-grid` 计算列为 `362px`，`scrollWidth=390`、`innerWidth=390` |
| T4 | 完成 | Node 27 项通过；JDK 21 下 `cm-agent-console` Maven 测试通过；桌面、1024px 和 390px 截图检查完成；六份文档已建立并加入索引 |

## 验证命令与结果

- `node --test cm-agent-console/src/test/js/local-projects.test.cjs cm-agent-console/src/test/js/console-navigation.test.cjs`
  - 结果：27 项通过，0 失败。
- `$env:JAVA_HOME='F:\java21'; $env:PATH='F:\java21\bin;' + $env:PATH; mvn -q -pl cm-agent-console -am test`
  - 结果：退出码 0。
  - 说明：本机默认 Java 为 17；显式切换到已安装的 JDK 21.0.11 后执行。
- `.agents/skills/impeccable/scripts/impeccable.cmd detect --json --scope layout cm-agent-console/src/main/resources/META-INF/resources/console/v2/projects.html cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/local-projects.css`
  - 结果：`[]`。
- 本次代码、测试和七份文档执行 `git -c core.whitespace=cr-at-eol diff --check`
  - 结果：退出码 0。
- 六份工作包和 `docs/superpowers/README.md` 的普通相对链接存在性检查
  - 结果：`links-ok`。
- Playwright 真实登录页面 + 最终 CSS/DOM 注入：
  - 桌面 1440×960：空状态与选中项目布局已检查。
  - 中间宽度 1024×900：列宽计算为 `250px 438px`，无页面级水平溢出。
  - 移动端 390×844：单列计算为 `362px`，无水平溢出；选中项目、项目头、标签和会话工具区已检查。
  - 截图目录：`output/playwright/projects-redesign/.playwright-cli/`。

## 验证限制与未执行项

- 视觉检查使用当前远端页面加载最终 CSS 和等价 DOM 结构注入；实际工作树页面没有部署到 `192.168.5.149`。
- 未执行远端构建、部署、缓存失效后复测、真实发布环境验收或其他页面逐页人工回归。
- 未修改服务端、接口、数据库、权限、认证、审计和项目运行逻辑。
- 提交状态：已纳入本次提交；未推送、未部署。
- 未更新 `docs/release-notes.md`，因为本轮没有形成版本化发布，也未完成远端发布验收。

[清单](../checklists/2026-11-20-projects-layout-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-layout-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-layout-refresh-design.md) · [计划](../plans/2026-11-20-projects-layout-refresh.md) · [实现说明](../implementation/2026-11-20-projects-layout-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-layout-refresh-ledger.md)
