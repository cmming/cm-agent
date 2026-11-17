# 技能导入描述长度修复清单

日期：2026-10-08；主题：skill-import-description；当前修订：R1；修订日期：2026-11-13；模式：mode=default。

R0 原文保留在文末归档；R1 起本清单只定义任务、依赖和验收。实时状态与验证证据以[进度账本](../progress/2026-10-08-skill-import-description-ledger.md)为准。

## 任务与验收

| 编号 | 目标 | 依赖 | 验收条件 |
|---|---|---|---|
| T1 | 核对错误、解析器上限和数据库列长 | 无 | 能够区分包解析、版本插入和诊断脱敏问题 |
| T2 | 以双数据库迁移扩容技能描述字段 | T1 | 新列为 1024，旧迁移与历史数据保持，字段有中文原生注释 |
| T3 | 覆盖迁移、仓储和 API 边界 | T2 | 旧失败可复现；Unicode、长度上限、大资源和跨租户隔离均有测试 |
| T4 | 完成 Rocky 双数据库验证 | T3 | 指定 Maven/JDK/数据库环境与源码快照一致，结果可追溯 |
| T5 | 同步生产说明与生效步骤 | T4 | 用户运行数据库迁移边界、风险和验证范围清晰 |
| T6 | 按新契约迁移六份文档职责 | T0–T5 | 非账本文件不复制状态和测试结果；R0 原文仍可追溯 |
| T7 | 验证修订、链接和状态目录 | T6 | 六份文档统一为 R1，链接和状态目录一致 |

## 当前状态与证据

当前修订的任务状态、命令、验证结果、阻塞和提交信息只在[进度账本](../progress/2026-10-08-skill-import-description-ledger.md)维护。其他入口：[设计](../specs/2026-10-08-skill-import-description-design.md)、[计划](../plans/2026-10-08-skill-import-description.md)、[提示词](../prompts/2026-10-08-skill-import-description-prompt.md)、[实现说明](../implementation/2026-10-08-skill-import-description-implementation-design.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留 2026-10-08 R0 原文</summary>

# 技能导入描述长度修复清单

日期 2026-10-08；修订 R0；mode=default；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443；分支 codex/skills-version-console；未提交。

用户导入 docx.zip 得到503 PERSISTENCE_UNAVAILABLE。现有日志去除了底层SQL原因，只能直接确认版本插入阶段发生完整性错误；结合包描述835字符与schema500上限定位长度失配，数据库复现待验证。

| 编号 | 目标与文件 | 依赖 | 验收 | 状态 |
|---|---|---|---|---|
| T1 | 日志与当前实现核对 | 无 | 错误编号对应 JdbcSkillVersionRepository.insert；解析器1024与数据库500不一致 | 完成 |
| T2 | 双库 V18 描述扩容及中文注释 | T1 | VARCHAR(1024)，保留旧版本、摘要和治理指针，既有迁移不变 | 完成 |
| T3 | 迁移、仓储和 API 回归 | T2 | 旧835字符失败复现、Unicode1024边界、大资源和实际ZIP落库；跨租户拒绝 | 完成 |
| T4 | Rocky 实际容器验证 | T3 | 同HEAD加明确覆盖哈希，指定Maven21，PG16/MySQL8.4，无失败 | 完成 |
| T5 | 六文档与生产说明同步 | T4 | 记录当前证据及生效步骤；未操作用户数据库 | 完成 |

保留用户的256KiB默认值、1MiB可配置上限及资源白名单修改；不改前端、不写用户业务数据、不执行脚本、不提交/推送/合并/部署。

## 关联工作包

- [清单](../checklists/2026-10-08-skill-import-description-checklist.md)
- [提示词](../prompts/2026-10-08-skill-import-description-prompt.md)
- [设计](../specs/2026-10-08-skill-import-description-design.md)
- [计划](../plans/2026-10-08-skill-import-description.md)
- [实现](../implementation/2026-10-08-skill-import-description-implementation-design.md)
- [账本](../progress/2026-10-08-skill-import-description-ledger.md)

</details>
