# 需求工作包技能待办清单

日期：2026-09-30；主题：`requirement-workpack-skill`；Git 基线：`a72f9228eb07242f303d5e033adaf8b1a56e6241`。

## 需求与范围

将暂存区的待办清单、执行提示词和原有四份过程文档提炼为项目技能，输入一个需求即可生成六份产物。新增清单、提示词目录，更新 AGENTS.md 文档规则，并迁移现有六份分析文档作为案例。

连续需求：文档生成后，可快捷使用此次提示词进行问答，无需复制文件全文。

当前证据：暂存区含六份 `next-iteration` 分析文档；原清单与提示词位于 `docs` 根目录，四份过程文档位于 `docs/superpowers`；`.agents/` 原本整体被忽略；AGENTS.md 只要求四份过程文档。CodeGraph 查询返回不匹配代码，已直接复核当前文档与引用。

本次仅修改技能、文档规范、忽略规则和文档组织，不改变业务实现或运行服务。技能采用项目级 `.agents/skills/requirement-workpack/`；任务数量、批次与验证方式按需求确定。已有用户配置和工具目录改动保持不动。

## 任务与验收

| 编号 | 状态 | 目标与文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| T0 | 完成 | 核实暂存区六份文件、AGENTS.md、POM、README 和忽略规则 | 无 | 输入文件与原规范可核对，工作树范围已记录 |
| T1 | 完成 | 创建技能入口、六份产物契约及调用元数据 | T0 | 输入需求可定位六类输出；不固定复制案例任务，不授权额外操作 |
| T2 | 完成 | 新增 `checklists`、`prompts`；更新 AGENTS.md 和 `.gitignore` | T1 | 六份契约与目录一致，仅新技能目录纳入版本控制 |
| T3 | 完成 | 迁移六份分析产物并修正引用 | T2 | 六份日期、主题一致，历史事实保留，旧路径引用已移除 |
| T4 | 完成 | 本任务六份产物、技能格式及链接校验 | T3 | `quick_validate.py` 通过；两组文档共 65 个相对链接有效，无行尾空白，代码围栏成对 |
| T5 | 完成 | 核对相关差异并更新暂存区 | T4 | 精确暂存 17 个相关文件；`git diff --cached --check` 通过；未提交，无关改动未被纳入 |
| T6 | 完成 | 增加本次提示词快捷问答入口，维护技能、契约、规范和本工作包 | T5 | 技能格式校验通过；66 个相对链接及快捷入口三个绝对路径有效；最终回复提供跟进指令，问答不自动实施，非支持界面有短输入替代 |

执行顺序：T0 → T1 → T2 → T3 → T4 → T5 → T6。没有业务或容器改动，本次不运行 Maven、Node 或 Rocky 业务验证。

[提示词](../prompts/2026-09-30-requirement-workpack-skill-prompt.md)；[设计](../specs/2026-09-30-requirement-workpack-skill-design.md)；[计划](../plans/2026-09-30-requirement-workpack-skill.md)；[实现说明](../implementation/2026-09-30-requirement-workpack-skill-implementation-design.md)；[账本](../progress/2026-09-30-requirement-workpack-skill-ledger.md)。
