# 本地项目页账本

> 需求：本地项目页（`projects.html`）
> 主题：`projects-page`
> 日期：2026-11-03
> 修订：R1
> 修订日期：2026-11-04
> 模式：`mode=default`
> 状态：当前有效需求工作包
> 证据截止：2026-11-03
> 最后复核：2026-11-04

## 本次文档整理

本组由用户要求合并原本地项目工作区、项目授权和项目页样式三个来源，按 `projects.html` 重新组织。旧日期工作包不再作为当前入口，相关文件已由用户要求删除；本组保留来源主题名，不保留失效链接。

文档整理阶段只新增/调整文档；随后 R1 实施阶段按 T11-T12 修改了项目页脚本、页面资源和测试，未修改配置、数据库或运行服务，没有提交、推送或部署。

## 修订 R1（2026-11-04）

本次按页面级文档复核建议补全：R0 基线说明、手工登记固定工具目录、项目选择/创建/改名/归档的 UNKNOWN 状态、R5 证据路径、任务状态证据口径和跨组 FULL_ACCESS 边界。文档复核后已实施 T11-T12，并完成 Node 与定向 Java 验证；没有重放真实项目副作用。

| 差异 | 类别 | R0 → R1 | 采用状态 |
|---|---|---|---|
| P-R1-1 | 新增 | 补充 `POST /api/projects/tools` 手工登记兼容路径、能力门禁和最小权限 | 已采用；新增 T11 |
| P-R1-2 | 新增 | 补全选择/创建/改名/归档的 UNKNOWN 和版本冲突验收 | 已采用；新增 T12 |
| P-R1-3 | 澄清 | T2/T5/T6/T9 标注历史证据或当前复验范围 | 已采用 |
| P-R1-4 | 澄清 | 补充 R5 与主要本地证据目录 | 已采用 |

## 当前任务证据

| 任务 | 状态 | 证据与适用边界 |
|---|---|---|
| T0 | 完成 | 页面、脚本、后端合同和历史工作包已核对；本组六份文档生成 |
| T1 | 完成 | 页面结构、空态、工具栏、会话/文件切换和窄屏样式已实现 |
| T2 | 完成（实现与历史证据） | Windows 系统选择器和项目登记流程已有真实操作证据；刷新/重开证据来自 R0 历史记录，R1 未重跑 |
| T3 | 完成 | 文件目录、搜索、脱敏预览、分页和引用已有页面/接口/JS 证据 |
| T4 | 完成 | 项目会话固定项目/Agent；固定工具目录和缺失 grant 补齐已有回归 |
| T5 | 完成（R5 历史证据） | `output/playwright/agent-operation-authorization-r5/` 覆盖三种模式及审批恢复；R4 当前实例另覆盖读取 |
| T6 | 完成（R5 历史证据） | R5 覆盖文件变更、命令、审批恢复和拒绝；停止、过期和 UNKNOWN 由既有隔离记录补足 |
| T7 | 完成 | 权限、owner/tenant、审计、错误编号和日志脱敏已有定向测试记录 |
| T8 | 完成 | 桌面、1024/390px 窄屏和面板互斥有视觉检查记录 |
| T9 | 完成（历史证据，本轮未重跑） | 既有 Node/Java/真实 Provider/JDBC/浏览器证据保留在本账本摘要 |
| T10 | 进行中（Chromium 阶段完成） | 1366/1280 桌面、390px 窄屏、Tab/Enter、焦点恢复和可访问性树通过；Linux/macOS、Firefox/WebKit、实际朗读软件、长时间运行与后续源码变化待做 |
| T11 | 完成 | 工具目录登记已补双击去重、成功锁定、受控失败恢复、网络未知不自动重放和对应回归 |
| T12 | 完成 | 选择/创建 UNKNOWN 既有覆盖；新增归档网络未知和改名版本冲突回归，均不自动重放 |

## R1 实施验证（2026-11-04）

- `node --test cm-agent-console/src/test/js/*.test.cjs`：225 项通过，0 失败。
- `mvn -pl cm-agent-console,cm-agent-server -am -Dtest=ConsoleResourceTest,LocalProjectToolCatalogTest,LocalProjectControllerTest,LocalClientPairingHttpIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`（JDK 21）：26 项通过，0 失败。
- 工具目录网络未知和归档未知均保持锁定，双击只提交一次；改名 409 保留当前项目并要求刷新核对。
- Playwright Chromium 实测 1366×768、1280×720、390×844 无横向溢出；键盘选择项目后焦点保持在同一 pressed 按钮，会话/文件切换保持焦点，归档成功后焦点落到“我的本地项目”标题。
- 正常页面控制台 0 错误；项目列表、详情和工作区切换均进入可访问性树。
- 证据位于 `output/playwright/accessibility-20261104/.playwright-cli/`；隔离夹具不执行真实项目写入、命令或构建。

## 历史验证摘要

- Node 项目页和项目开发状态机回归通过，覆盖模式载荷、活动 Run 禁用、项目工具目录、审批和操作状态。
- 控制台 Java 资源测试通过，页面必需标识和静态资源版本存在。
- Windows 当前实例已验证项目会话、真实 Provider、`project_list_files`、`project_read_file`、授权授予/撤销和审计序列。
- R5 证据覆盖三种项目授权模式、文件变更、命令、审批恢复和恢复拒绝；证据位于 `output/playwright/agent-operation-authorization-r5/`。
- 文件变更/命令审批、停止、外部冲突和 UNKNOWN 由既有隔离项目证据覆盖；本次不重放真实副作用。
- Rocky 上的 PostgreSQL/MySQL 与项目授权相关测试有历史记录，但不能替代 Windows 原生页面和后续源码验收。

主要本地证据目录：`output/playwright/accessibility-20261104/`、`target/local-project-t1-20261029/`、`output/playwright/agent-operation-authorization-e2e/`、`output/playwright/agent-operation-authorization-r5/`、`output/playwright/project-tool-help-r3/`。这些目录被 `.gitignore` 排除；结论和适用边界保留在本账本。

## 未验证与风险

- Linux/macOS 桌面、真实手机、多浏览器、字体缩放和完整无障碍未验收。
- 本轮读屏检查使用 Chromium 可访问性树，未使用 NVDA、Narrator、VoiceOver 等实际朗读软件；Firefox/WebKit 未覆盖。
- 系统文件夹选择器只在其运行桌面环境可用；无桌面或远程 Server 不能伪装为浏览器本机目录。
- 真实文件写入、命令和构建会改变外部状态，必须在隔离自建目录重验，不回放未知结果。
- 页面视觉证据可能被后续功能改动影响，不能以旧截图证明当前源码。

## 提交状态

未提交、未推送、未部署。本轮修改了项目页脚本、页面与测试；仅启动隔离静态验收夹具，未启动或重启业务服务，也未执行真实文件写入、命令或构建副作用。

## 关联文档

- [项目页清单](../checklists/2026-11-03-projects-page-checklist.md)
- [项目页设计](../specs/2026-11-03-projects-page-design.md)
- [项目页计划](../plans/2026-11-03-projects-page.md)
- [项目页提示词](../prompts/2026-11-03-projects-page-prompt.md)
- [项目页实现说明](../implementation/2026-11-03-projects-page-implementation-design.md)
