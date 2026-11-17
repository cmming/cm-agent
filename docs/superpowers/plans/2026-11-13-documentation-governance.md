# 文档治理规则落地：实施计划

日期：2026-11-13；主题：`documentation-governance`；修订：R0；模式：`mode=interactive`。

## 文件范围

以下路径相对 `F:/java/cm-agent`，共 16 份；已存在文件在当前工作树内容上定点修改。

| 操作 | 文件 |
|---|---|
| 修改 | `AGENTS.md` |
| 修改 | `.agents/skills/requirement-workpack/SKILL.md` |
| 修改 | `.agents/skills/requirement-workpack/references/artifact-contract.md` |
| 修改 | `.agents/skills/requirement-workpack/agents/openai.yaml` |
| 修改 | `docs/README.md` |
| 修改 | `docs/superpowers/README.md` |
| 修改 | `docs/superpowers/WORKPACK-STATUS.md` |
| 追加 | `docs/superpowers/progress/2026-11-13-doc-organization-followup-ledger.md` |
| 新增 | `GLOSSARY.md` |
| 新增 | `docs/adr/0001-documentation-governance.md` |
| 新增 | `docs/superpowers/checklists/2026-11-13-documentation-governance-checklist.md` |
| 新增 | `docs/superpowers/prompts/2026-11-13-documentation-governance-prompt.md` |
| 新增 | `docs/superpowers/specs/2026-11-13-documentation-governance-design.md` |
| 新增 | `docs/superpowers/plans/2026-11-13-documentation-governance.md` |
| 新增 | `docs/superpowers/implementation/2026-11-13-documentation-governance-implementation-design.md` |
| 新增 | `docs/superpowers/progress/2026-11-13-documentation-governance-ledger.md` |

## 顺序与验证方法

1. T0：记录 HEAD、暂存区及已有文件摘要；新增路径必须不存在，避免覆盖用户内容。
2. T1：以产物契约保存详细要求，同步 AGENTS、技能读取条件和入口文案。
3. T2：更新文档职责及两类事实依据，增加术语与 ADR 的导航。
4. T3：在原整理账本末尾追加归档事件，逐字保留旧正文。
5. T4：目录登记本包及归档样例，保留完整主题目录和 Git 历史快照。
6. T5：六份文件按固定职责记录已确认决定、范围和实施方法。
7. T6：检查 Markdown 链接与本地锚点、YAML 结构、工作包分组和修订、目录统计、状态来源和空白；对比范围外摘要、HEAD 与暂存区。实际结果只写账本，文档修改不安排业务测试或外部操作。

[清单](../checklists/2026-11-13-documentation-governance-checklist.md) · [设计](../specs/2026-11-13-documentation-governance-design.md) · [提示词](../prompts/2026-11-13-documentation-governance-prompt.md) · [实现说明](../implementation/2026-11-13-documentation-governance-implementation-design.md) · [账本](../progress/2026-11-13-documentation-governance-ledger.md)
