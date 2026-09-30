# 需求工作包技能设计

## 背景与目标

暂存区六份文档已展示“一个需求 → 清单、执行提示词和四份过程文档”的工作方式，但产物目录、命名和生成规范尚未统一。将其转为可复用项目技能，降低重复制定流程的成本，并使业务状态与文档完成状态可区分。

## 范围、方案与约束

项目技能为 `.agents/skills/requirement-workpack/`：`SKILL.md` 定义输入和生成过程，`references/artifact-contract.md` 定义六份产物的职责，`agents/openai.yaml` 提供名称和默认调用输入。不增加填充假事实的脚本；内容由执行者结合当前需求和仓库证据生成。

输出使用 `docs/superpowers/{checklists,prompts,specs,plans,implementation,progress}`，同一工作包统一日期、主题和任务编号。AGENTS.md 扩展为六份规则，历史四份工作包不强制批量补齐。原分析六份文件迁移为统一主题案例，保留基线和结论，关联独立实施账本。

`.gitignore` 仅开放新技能目录。现有业务实现、运行服务、用户配置和工具目录内容不在本次范围。需求提供实施授权时技能配合实施；仅要求文档时只生成文档。模板或执行提示词不赋予额外外部操作权限。

## 验收

连续需求采用最终回复的快捷跟进入口。支持时使用 `:codex-followup`，指向本次提示词、清单和账本的真实绝对路径；点击后读取最新文档建立问答上下文，不直接执行文件中的命令。其他界面提供简短输入替代。入口不复制整段提示词，避免内容更新后仍使用旧副本。清单 T6 验收入口指向与问答边界。

清单 T0～T5 给出具体条件：技能格式可验证、产物契约完整、两组各六份文档命名与链接一致、历史事实保留、原路径无残留、暂存区仅含相关路径。当前任务生成自身工作包用于检验该契约的实际可用性；不以静态格式检查宣称所有未来需求均已验证。

[清单](../checklists/2026-09-30-requirement-workpack-skill-checklist.md)；[提示词](../prompts/2026-09-30-requirement-workpack-skill-prompt.md)；[计划](../plans/2026-09-30-requirement-workpack-skill.md)；[实现说明](../implementation/2026-09-30-requirement-workpack-skill-implementation-design.md)；[账本](../progress/2026-09-30-requirement-workpack-skill-ledger.md)。
