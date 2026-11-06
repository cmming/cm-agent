# 项目页面工作区与授权体验进度账本

修订：R3；日期：2026-11-04；模式：mode=default；历史基线 `60d0fd2`；未提交。原工作树含大量用户已有修改和未跟踪文件。R1 文档整理未修改业务代码或其他任务文档。

| 编号 | 状态 | 实际证据 |
|---|---|---|
| T1 | 完成 | 原布局记录：CodeGraph、项目页、样式、README、模块 POM 与工作树检查 |
| T2 | 完成 | 页面布局与样式实现；详情见实现说明 |
| T3 | 进行中 | 39/39 Node 项目测试；桌面和 390×844 手机静态模拟页检查。真实手机、手机输入法、真实服务、发送及审批未验收，服务 8080 未重启 |
| T4 | 完成 | 原布局工作包生成，现归并为本组六份文档 |
| T5 | 完成 | 原 `project-authorization-mobile` T1–T4：Node 39/39；320×740 与 390×844 静态模拟页检查，真实设备和服务未验收 |
| T6 | 完成 | 原 `project-authorization-copy` T1–T4：对照服务端策略；Node 39/39；390×844 模拟页检查三模式说明，无整体横向溢出 |
| T7 | 完成 | 后续只读核对：现有授权测试 2/2；临时内存测试分别覆盖三模式切换和模拟活动运行恢复（各 1 项），不代表真实服务恢复 |
| T8 | 完成 | 用户要求将同主题三组六份文档合并；以原布局六份为目标，迁移后移除另外 12 份；检查其余文档引用 |

## 历史与结果边界

- 初次布局测试因样式版本断言未同步失败，修正后 39 项通过；不能把后续结果追溯为初次通过。
- 手机模拟首次发现授权选项截断，T5 完整显示、T6 简化名称；历史问题已处理，但真实手机显示仍未验证。
- 三模式模拟页采用独立处理器或源码渲染函数，恢复测试采用模拟服务端响应。真实发送、审批和运行恢复需要独立验收。
- `java -version` 和 `mvn -v` 当时显示 JDK 17；没有执行要求 JDK 21 的 Maven 测试。前端历史变更未触及后端安全策略。
- 临时预览服务均已停止；部分系统临时预览目录保留。曾有临时目录删除被自动策略拒绝，保留原有事实。

## R1 合并映射

保留 `2026-11-04-project-workspace-layout` 六份路径；原 `2026-11-04-project-authorization-mobile` 六份的内容归入 T5，原 `2026-11-04-project-authorization-copy` 六份归入 T6。两个工作包各自的 T1–T4 属于独立编号空间，不能与保留工作包的同名编号合并为同一历史任务。T3 的真实环境验收仍进行中，未以模拟测试充当通过证据。

关联文档：[清单](../checklists/2026-11-04-project-workspace-layout-checklist.md) · [提示词](../prompts/2026-11-04-project-workspace-layout-prompt.md) · [设计](../specs/2026-11-04-project-workspace-layout-design.md) · [计划](../plans/2026-11-04-project-workspace-layout.md) · [实现](../implementation/2026-11-04-project-workspace-layout-implementation-design.md) · [账本](../progress/2026-11-04-project-workspace-layout-ledger.md)

## R2 文档复核（2026-11-04）

T9 完成：对照本次对话中保留的三组工作包原文片段、当前项目页面与授权渲染源码，核心需求、实现和验证边界均可对应；六份文件互相链接有效，三组路径数量分别为 6、0、0。原被合并的 12 份文件未进入版本控制且已移除，因此无法完整逐字比对；结论限于当前可用证据。没有重新运行历史业务测试。

补充原验证命令：`node --test cm-agent-console/src/test/js/local-projects.test.cjs cm-agent-console/src/test/js/project-development.test.cjs`；历史结果为 39/39，并非本轮新执行。原记录中首次版本断言失败、修正后通过及真实环境未验收的界限继续保留。


## R3 合并说明（2026-11-04）

根据用户要求，将同日另外两组工作包合并到本组；两组分别是设计配套文件刷新和输入框预览修复，与项目页面任务独立。以下为对应类型的历史记录，原 T 编号只在各自历史工作包内有效；旧工作包文件链接已改写为指向本组六份文档。原提示词仅作为历史记录，不得因阅读而执行。

T10、T11 为已完成历史记录的归档，分别对应两组历史工作包的独立 T 编号。T12 为本轮文档整理；不改变 T3 的真实环境未验收状态。以下分别保留两项工作当时的验证结果和限制，不将其当作本轮测试。

### 历史归档：2026-11-04-impeccable-sidecar-refresh（仅供查阅）

# 进度账本

| 编号 | 状态 | 证据 |
|---|---|---|
| T1 | 完成 | `.impeccable/design.json` 与 `workbench.css`；JSON 解析及关键规则检查 |
| T2 | 完成 | DESIGN.md 的 SHA-256 前后相同；六份文档及链接检查 |

验证以本任务执行结果为准；未运行浏览器预览或应用测试，本次未更改应用代码。未提交。

工作模式：mode=default。基线 HEAD：`60d0fd2`；工作区包含已有未提交改动，予以保留。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)


### 历史归档：2026-11-04-impeccable-input-preview-fix（仅供查阅）

# 进度账本

| 编号 | 状态 | 实际证据 |
|---|---|---|
| T1 | 完成 | `.impeccable/design.json` 已添加 `box-sizing:border-box`，JSON 可解析 |
| T2 | 完成 | Chrome 桌面预览截图及 DOM 边界：输入框 447–729px，区域 431–745px；获得焦点 |
| T3 | 完成 | DESIGN.md SHA-256 不变；六份文档链接检查 |

未验证移动端及官方面板；未运行应用测试，因为实际应用代码未变。临时 HTTP 服务已停止。未提交。

工作模式：mode=default。Git 基线：`60d0fd2`；保护现有未提交修改。关联此前工作包：`2026-11-04-impeccable-sidecar-refresh`。

## 关联文档

- [checklists](../checklists/2026-11-04-project-workspace-layout-checklist.md)
- [prompts](../prompts/2026-11-04-project-workspace-layout-prompt.md)
- [specs](../specs/2026-11-04-project-workspace-layout-design.md)
- [plans](../plans/2026-11-04-project-workspace-layout.md)
- [implementation](../implementation/2026-11-04-project-workspace-layout-implementation-design.md)
- [progress](../progress/2026-11-04-project-workspace-layout-ledger.md)

