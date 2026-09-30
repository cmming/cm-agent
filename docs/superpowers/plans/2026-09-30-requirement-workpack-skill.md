# 需求工作包技能计划

| 编号 | 操作与位置 | 验证 |
|---|---|---|
| T0 | 读取暂存区六份分析文档、AGENTS.md、根 POM、README 和 `.gitignore`；核对 HEAD | 识别本次输入和已有无关修改 |
| T1 | 写入 `.agents/skills/requirement-workpack/` 入口、契约与元数据 | 标准技能校验器及内容审查 |
| T2 | 在 AGENTS.md 定义六份文档目录、职责和生命周期；限定 `.gitignore` 放行 | 比较技能与规范；`git check-ignore` |
| T3 | 将两份根目录文件迁到 `checklists`、`prompts`，统一 `next-iteration-analysis`，更新六份引用 | 检查不存在旧路径引用；历史基线不变 |
| T4 | 本任务按新技能契约生成六份文档 | 检查两组文件齐全、相对链接与空白、任务状态和证据一致 |
| T5 | 精确更新相关暂存路径，检查差异与状态，填写最终账本 | `git diff --cached --check`、相关差异、暂存文件范围；未提交 |
| T6 | 在技能、产物契约和 AGENTS.md 中增加快捷问答交付规则，同步六份产物 | 检查技能格式、真实提示词/清单/账本路径和最终跟进指令；不自动执行实施命令 |

仅修改文档与技能，无业务运行验证需求。若格式校验器不可用，记录错误并完成可独立执行的契约、链接和差异检查。此计划只定义操作，实际状态见账本。

[清单](../checklists/2026-09-30-requirement-workpack-skill-checklist.md)；[提示词](../prompts/2026-09-30-requirement-workpack-skill-prompt.md)；[设计](../specs/2026-09-30-requirement-workpack-skill-design.md)；[实现说明](../implementation/2026-09-30-requirement-workpack-skill-implementation-design.md)；[账本](../progress/2026-09-30-requirement-workpack-skill-ledger.md)。
