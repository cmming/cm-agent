# 文档导航清理：计划

日期：2026-11-13；主题：`doc-navigation-cleanup`；修订：R4；模式：`mode=default`。

## T0 基线与保护

- 读取 Git 工作树，确认 2026-11-09、2026-11-12 工作包及业务源码均为用户既有修改。
- 依据合并记录确认 2026-10-29 和 2026-11-02 的当前后继账本。

## T1 指令入口

- 修改 `AGENTS.md`，删除第 230 行开始的 `changeDescription`、建议代码块和 `userPrompt` 尾段。
- 保留第 1–228 行现有规则及最终输出格式。

## T2 链接修复

- 修改 `README.md` 和 `docs/configuration.md` 的两处授权账本链接。
- 修改 `docs/release-notes.md` 的项目工作区账本链接。
- 复核 `docs/superpowers/plans/2026-07-28-tool-development-guide-examples.md:456` 的代码示例；其路径相对于生成后的 README 有效，作为 fenced code sample 保留。

## T3 文档入口

- 新增 `docs/README.md`，按读者目标列出当前权威入口和历史档案入口。
- 修改根 README 的“生产文档”为“文档导航”，将总入口放在首项，并移除混在其中的历史实现设计链接。

## T4 验证与工作包

- 运行 Markdown 本地链接存在性检查。
- 检查 `AGENTS.md` 尾部和 Markdown 围栏。
- 检查 Git diff 只包含本次文档及六份工作包。
- 生成本次六份工作包并记录未提交状态。

## 涉及文件

- `AGENTS.md`
- `README.md`
- `docs/README.md`
- `docs/configuration.md`
- `docs/release-notes.md`
- `docs/superpowers/{checklists,specs,plans,prompts,implementation,progress}/2026-11-13-doc-navigation-cleanup*`

## 验证方式

- 使用只读 Markdown 扫描脚本检查全部可见文档的本地链接。
- 使用 `git diff --check` 检查空白错误。
- 使用 `git status --short` 核对受保护工作包没有被本次任务改写。

## 修订 R1：P2 当前事实刷新

### T5 事实矩阵

- 在 `docs/README.md` 增加事实类型、权威入口、辅助证据和使用规则。
- 明确冲突优先级：代码/配置/测试、专项生产文档、README 摘要、历史记录。

### T6 技术架构

- 更新运行时策略、会话 SSE、显式停止和审批恢复。
- 增加本地客户端模块及 11 个控制台入口。
- 将逐版本迁移清单改为稳定的 V1–V31 分组。
- 将当前能力拆分为已交付、默认关闭或未公开发布、尚未交付。

### T7 部署指南

- 更新文档适用的能力范围和可选部署依赖。
- 将迁移说明更新到 V31，并保留 V4 重复名称检查和 V5 墓碑语义。
- 修正 bootstrap admin 的 local/test 边界。
- 更新启动后的能力边界和默认关闭能力提示。

### T8 路线图

- 补充阶段 3 后的运行时、控制台、工具/Skill、工作区和本机能力增量。
- 保留阶段 4/5 未交付状态，并澄清局部临时清理不等于运行数据生命周期治理。

### T9 验证

- 检索三份文档中的旧能力措辞和旧迁移头描述。
- 核对控制台页面数、cancel/SSE 路由、迁移文件和功能默认值。
- 运行 Markdown 链接检查、`git diff --check` 和受保护路径状态检查。

## 修订 R2：P3 工作包状态目录

### T10 目录契约

- 定义日期、主题、集合类型、当前修订、最新账本、Git 状态和后续入口。
- 修订只读取显式元数据和修订标题；无声明时保留“未声明”。

### T11 生成目录

- 扫描六个工作包目录并按“日期 + 规范化主题”分组。
- 通过 Git porcelain 状态标注“已纳入版本控制”“未提交修改”或“未跟踪”。
- 生成 `docs/superpowers/WORKPACK-STATUS.md`，按月份分组和日期倒序。

### T12 导航

- 在 `docs/superpowers/README.md` 顶部增加完整状态目录入口。
- 在 `docs/README.md` 的工作包与历史一节增加目录入口。

### T13 验证

- 校验目录主题数、文件覆盖数、日期顺序和链接。
- 检查 2026-11-09、2026-11-12、2026-11-13 三组状态。
- 运行 `git diff --check`，确认未移动或删除历史文件。

## 修订 R3：P4 六件套职责收紧

### T14 项目规则

- 修改 `AGENTS.md` 的六份文档职责，删除清单实时状态要求，明确账本唯一状态源。
- 修改 `docs/README.md` 与 `docs/superpowers/README.md` 的维护说明。

### T15 技能契约

- 修改 `requirement-workpack/SKILL.md` 的生成、迭代与一致性流程。
- 修改 `artifact-contract.md` 的职责表、各产物章节、迭代契约和检查项。
- 修改 `agents/openai.yaml` 的短描述与默认提示。

### T16 历史组迁移与当前工作包

- 选择迁移前重复度最高的完整、干净六件套 `2026-10-01-remote-docker-password-auth`。
- 该组提升到自然下一修订 R2；保留 R0 归档，将 R1 验证证据集中到账本，其他五份收敛为固定职责。
- 六份文件统一提升到 R3。
- R3 的任务状态、验证结果和提交状态只写入账本；其他文件只记录 R3 固定职责和账本链接。
- 更新 `WORKPACK-STATUS.md` 的两个当前修订入口。

### T17 验证

- 搜索旧规则中的“清单维护状态”“账本之外记录验证结果”等冲突表述。
- 检查本次 R3 和历史组 R2 的元数据、任务编号和链接。
- 重新计算迁移组两两相似度，对比迁移前的 0.715 最大值和 0.499 平均值。
- 运行 Markdown 链接、空白、`git diff --check` 和受保护路径检查。

## 当前状态与证据

当前修订的执行状态、验证结果和提交信息只以[进度账本](../progress/2026-11-13-doc-navigation-cleanup-ledger.md)为准。其他入口：[清单](../checklists/2026-11-13-doc-navigation-cleanup-checklist.md)、[设计](../specs/2026-11-13-doc-navigation-cleanup-design.md)、[提示词](../prompts/2026-11-13-doc-navigation-cleanup-prompt.md)、[实现说明](../implementation/2026-11-13-doc-navigation-cleanup-implementation-design.md)。

## 修订 R4（2026-11-13）：下一批高重复历史组迁移

| 编号 | 顺序与方法 | 验证方式 |
|---|---|---|
| T18 | 在 skill-import-description 账本顶部新增 R1 责任迁移区，再把原 R0 全文折叠归档 | 六份修订、任务编号和账本唯一状态源检查 |
| T19 | 以 R3 重写 workspace-directory-selection-implementation 的五份非账本活动区，账本追加 R3 状态；完整 R2 原文折叠保留 | 活动区相似度、R0–R2 归档、链接和状态目录检查 |
| T20 | 以 R2 重写 skill-sandbox 的五份非账本活动区，账本追加 R2 状态；完整 R1 原文折叠保留 | 安全性内容保留、活动区相似度、链接和状态目录检查 |
| T21 | 统一扫描修订元数据、Markdown 链接、代码围栏、空白和保护文件哈希 | 脚本检查全部通过且受保护工作包哈希不变 |

执行过程中保留现有业务状态：workspace 组的 T11–T14 仍按历史账本记为未完整收口，skill-sandbox 的历史测试数字和条件跳过不作为新验证。

R4 状态与证据见[进度账本](../progress/2026-11-13-doc-navigation-cleanup-ledger.md)。
