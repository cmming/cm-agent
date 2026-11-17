# 文档导航清理：实现说明

日期：2026-11-13；主题：`doc-navigation-cleanup`；修订：R4；模式：`mode=default`。

## 实际改动

- 清理 `AGENTS.md` 第 230–248 行的外来提示词模板，项目规则在最终输出格式后正常结束。
- 将根 README 和配置说明中的已删除授权账本链接改指 `2026-11-03-projects-page-ledger.md` 或 `2026-11-03-local-client-page-ledger.md`。
- 将发布说明中的已删除项目工作区账本链接改指 `2026-11-03-projects-page-ledger.md`。
- 复核 2026-07-28 工具开发计划的链接候选。该链接位于 fenced code sample，展示仓库根 README 应写入的内容，原路径在其目标文档中有效，因此保留历史示例。
- 新增 `docs/README.md`，按使用与生产、架构与开发、产品与设计、工作包与历史组织导航。
- 根 README 的“生产文档”改为“文档导航”，首项指向总入口，并把历史实现设计从生产文档列表移出。

## 与方案的差异

没有扩大范围到技术架构刷新、部署路线更新或工作包重建。3 个真实失效链接改到现有后继文档；误报的代码示例保持原文。

## 兼容性

所有历史目标继续使用原相对路径；仅把已删除旧账本的链接改到现有后继账本，并为新增总入口添加新路径。2026-11-09 和 2026-11-12 工作包未修改。

## 关联文档

- [清单](../checklists/2026-11-13-doc-navigation-cleanup-checklist.md)
- [设计](../specs/2026-11-13-doc-navigation-cleanup-design.md)
- [计划](../plans/2026-11-13-doc-navigation-cleanup.md)
- [提示词](../prompts/2026-11-13-doc-navigation-cleanup-prompt.md)
- [进度账本](../progress/2026-11-13-doc-navigation-cleanup-ledger.md)

## 修订 R1：P2 实现

- `docs/README.md` 增加当前事实来源矩阵，覆盖产品、当前能力、配置、部署、运维、架构、发布、设计、路线图和过程证据，并写明冲突优先级。
- `technical-architecture.md` 更新为 ReAct/Harness、持久化会话、SSE、显式停止、审批、Skill/沙箱/工作区和默认关闭本机能力；迁移说明收敛为 V1–V31 分组。
- `deployment.md` 更新部署适用范围、可选依赖、V31 迁移分组、local/test bootstrap 边界和启动后的能力边界。
- `roadmap.md` 增加阶段 3 后已交付增量，保留阶段 4/5 未交付状态，并隔离默认关闭能力与生产承诺。
- 根 README 将 v2 页面数从 9 修正为 11，并补充阶段 3 后增量摘要，避免事实矩阵指向的根入口继续滞后。

没有修改业务代码、配置、数据库迁移或 2026-11-09/2026-11-12 工作包。迁移文档没有复制未来所有版本，当前版本以仓库迁移目录和 Flyway 历史交叉核对。

## 修订 R2：P3 实现

- 新增 `docs/superpowers/WORKPACK-STATUS.md`，按月份和日期倒序列出当前全部工作包主题。
- 每组记录文档集合、显式修订、最新账本、Git 未提交状态和后续入口。
- 历史 112 组基线之外，收录本次 `doc-navigation-cleanup` 维护工作包，使当前目录与实际工作树一致，共 113 组。
- `docs/superpowers/README.md` 增加完整状态目录入口，并保留原有长期需求组摘要。
- `docs/README.md` 在事实来源矩阵和历史导航中增加状态目录链接。

目录不移动或改写历史工作包。Git 状态是 2026-11-13 的工作树快照，提交后需要重新读取或更新对应行。

## 修订 R3：P4 六件套职责收紧

- `AGENTS.md` 将清单、提示词、设计、计划和实现说明改为固定职责，明确状态、测试结果和提交信息只写入账本。
- `requirement-workpack/SKILL.md` 增加“六份职责与单一状态源”规则，并同步生成、迭代和一致性流程。
- `artifact-contract.md` 增加单一状态源，重写六份产物职责、各产物边界、一致性检查和迭代表。
- `agents/openai.yaml` 更新为以账本唯一承载实时状态与证据的描述。
- `docs/README.md` 与 `docs/superpowers/README.md` 加入新职责说明和契约入口。
- 当前维护工作包提升为 R3；R0–R2 的历史状态保留，R3 的实际状态和验证证据仅写入本工作包账本。
- `2026-10-01-remote-docker-password-auth` 提升为 R2：清单、设计、计划、提示词和实现说明的当前区域按固定职责重写，R0 原文保留，R1 状态与验证证据集中到该主题账本。

没有批量改写既有工作包，也没有修改业务代码、数据库、配置或 2026-11-09/2026-11-12 工作包。

当前修订的执行状态、验证结果和提交信息只以[进度账本](../progress/2026-11-13-doc-navigation-cleanup-ledger.md)为准。

## 修订 R4（2026-11-13）：下一批高重复历史组迁移

本修订实际修改三组历史工作包和状态目录，没有改变业务实现。

- skill-import-description：补齐 R1 账本，原 R0 账本全文归档。
- workspace-directory-selection-implementation：五份非账本活动区按 R3 固定职责重写，账本追加 R3 迁移状态；完整 R2 及更早原文归档。
- skill-sandbox：五份非账本活动区按 R2 固定职责重写，账本追加 R2 迁移状态；完整 R1 及更早原文归档。
- WORKPACK-STATUS：同步上述三组及当前整理工作包的当前修订和未提交状态。

与设计的差异：没有扩大迁移批次，也没有重跑历史业务测试。workspace 组的 T11–T14 历史未完成边界和 skill-sandbox 的历史条件跳过均保留原证据含义。

R4 状态与验证证据见[进度账本](../progress/2026-11-13-doc-navigation-cleanup-ledger.md)。
