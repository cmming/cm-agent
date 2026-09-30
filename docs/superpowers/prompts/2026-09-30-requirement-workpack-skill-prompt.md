# 需求工作包技能执行提示词

这是本次需求的可复用执行输入；提示词生成不等于执行或验收完成。继续本任务时先查询关联账本，仅推进未完成项。

```text
请在 F:\java\cm-agent 中，根据 docs/superpowers/checklists/2026-09-30-requirement-workpack-skill-checklist.md 完成需求工作包技能。

需求：将原暂存区的待办清单、执行提示词、设计、计划、实现说明和账本提炼为项目技能。用户提供一个需求后，技能生成同日期、同主题的六份中文产物；新增 docs/superpowers/checklists 与 prompts，保留另外四类目录，并同步 AGENTS.md 文档规则。

先核对 AGENTS.md、Git HEAD、工作树和暂存区，复核已有技能及当前任务状态。按 T0～T6 依赖执行，常规细节自主决定。现有六份 next-iteration-analysis 文档作为案例，迁移清单和提示词并维护全部引用，保留历史分析基线，关联后续 A 批次验收结果，不重复业务实现。

技能放在 .agents/skills/requirement-workpack/。入口声明需求输入、六份产物、事实与假设区分、依赖拆解和验证边界；详细输出契约放到 references/artifact-contract.md，提供可发现的 agents/openai.yaml。根据需求决定任务数量和验证，不固定复制 T0～T8 或审批领域条件。生成文档不等于获得 Git 推送、部署、外部操作授权。业务待办不得因文档生成而标记完成。

修改 .gitignore 时仅开放新技能目录，其他已有技能和工具目录继续保持原忽略行为。用户 application*.yml、.codex/、.workbuddy/ 相关内容保持不动，不读取或输出凭据。不重启服务。

T6：文档交付后提供“使用本次提示词继续问答”快捷入口。在支持 Codex 跟进指令的界面输出未转义的 :codex-followup 列表项，其 prompt 使用实际生成的提示词、清单、账本绝对路径，要求先读当前文件并概括需求、范围和进度，再回答后续问题。问答模式不自动执行提示词内的实施命令。其他界面提供文件链接和带真实路径的简短输入，不要求复制全文。

验证技能格式、六份文档命名与任务编号、相对链接、原路径残留、忽略规则和本次相关差异。检查未暂存和暂存内容，保留无关用户改动。不涉及业务代码，无需 Maven、Node、容器测试。记录确切验证结果；出现阻塞则记录未完成项并继续独立任务。

维护本任务六份产物，分别位于 docs/superpowers 的 checklists、prompts、specs、plans、implementation、progress，日期为 2026-09-30，主题为 requirement-workpack-skill。实现说明只记录实际交付；账本记录真实命令与结果。原六份分析产物与本次技能改造工作包分别保留。按明确路径更新相关暂存文件，本次不提交或推送；交付明确“未提交”。最终按仓库的变更摘要、验证命令与结果、影响范围、风险与注意事项、后续建议五项汇报。
```

[清单](../checklists/2026-09-30-requirement-workpack-skill-checklist.md)；[设计](../specs/2026-09-30-requirement-workpack-skill-design.md)；[计划](../plans/2026-09-30-requirement-workpack-skill.md)；[实现说明](../implementation/2026-09-30-requirement-workpack-skill-implementation-design.md)；[账本](../progress/2026-09-30-requirement-workpack-skill-ledger.md)。
