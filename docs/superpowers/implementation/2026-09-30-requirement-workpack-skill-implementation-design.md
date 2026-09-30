# 需求工作包技能实现说明

## 实际交付

- `.agents/skills/requirement-workpack/SKILL.md`：定义需求输入、当前事实复核、稳定编号拆解、六份生成与连续维护，允许按需求改变任务数量。
- `references/artifact-contract.md`：定义清单、提示词、设计、计划、实现说明、账本的职责与一致性检查。
- `agents/openai.yaml`：中文界面元数据和 `$requirement-workpack` 调用示例。
- `AGENTS.md`：文档规则扩展为六份，新增 `checklists`、`prompts`；明确文档状态与业务状态、历史工作包和独立实施工作包的关系。
- `.gitignore`：仅新技能目录纳入可跟踪范围，其余 `.agents` 内容保留原忽略语义。
- 原六份 `next-iteration` 分析产物：前两份迁入专用目录，统一 `next-iteration-analysis` 主题，链接修正，原分析基线保留并引用后续 A 批次账本。
- 本次 `requirement-workpack-skill` 六份工作包：按新契约生成并记录本次改造，作为实际使用案例。

## 方案差异与边界

连续需求增加“使用本次提示词继续问答”快捷入口：技能和产物契约要求在最终回复中输出 `:codex-followup`，使用当前提示词、清单和账本绝对路径读取最新内容；AGENTS.md 同步交付规则。无指令渲染能力时提供文件链接和简短输入。问答入口只建立上下文，提示词中的实施命令不会自动执行。本次六份记录同步增加 T6，不新增工作包主题。

原清单和提示词名称随主题统一而调整，旧根目录文件移除。没有业务代码、数据库、接口或服务行为变化；文档不会授予额外执行权限。此技能没有独立生成脚本，内容质量仍需依赖当前证据和人工审查。没有发布相关行为变化，因此不更新 release-notes。

实际校验结果与暂存状态见账本；未提交。

[清单](../checklists/2026-09-30-requirement-workpack-skill-checklist.md)；[提示词](../prompts/2026-09-30-requirement-workpack-skill-prompt.md)；[设计](../specs/2026-09-30-requirement-workpack-skill-design.md)；[计划](../plans/2026-09-30-requirement-workpack-skill.md)；[账本](../progress/2026-09-30-requirement-workpack-skill-ledger.md)。
