# 技能导入描述长度修复实现说明

日期：2026-10-08；主题：skill-import-description；当前修订：R1；修订日期：2026-11-13；模式：mode=default。

R0 原文保留在文末归档；R1 实现说明只记录实际改动与设计差异。

## 实际改动

- PostgreSQL 和 MySQL 增加同版本迁移，将技能版本描述扩展到 1024 个字符，并同步字段中文原生注释。
- 既有 V12 和其他已发布迁移保持不改；版本摘要、资源、治理指针、租户、权限、事务、审计和发布语义不变。
- 测试补充旧长度失败、升级后旧值保留、Unicode 边界、大资源、实际 ZIP 候选落库和跨租户隔离。
- R1 文档迁移把原 R0 的状态和验证内容移出非账本文件的当前区域，完整记录集中于账本。

## 设计差异

没有改变通用持久化错误映射、脱敏策略或用户容量配置。当前实现证据和历史运行库尚未迁移的边界由账本记录。

## 当前状态与证据

当前修订的状态、验证和提交信息只以[进度账本](../progress/2026-10-08-skill-import-description-ledger.md)为准。其他入口：[清单](../checklists/2026-10-08-skill-import-description-checklist.md)、[设计](../specs/2026-10-08-skill-import-description-design.md)、[计划](../plans/2026-10-08-skill-import-description.md)、[提示词](../prompts/2026-10-08-skill-import-description-prompt.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留 2026-10-08 R0 原文</summary>

# 技能导入描述长度修复实现说明

日期 2026-10-08；修订 R0；mode=default；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443；分支 codex/skills-version-console；未提交。

已新增双库V18并更新字段注释；未修改V12。MigrationTest增加17→18与旧长度失败复现，保留旧版本值/治理数据并核对列长度和全部表列注释。JdbcSkillRepositoriesTest覆盖Unicode长描述、大资源和跨租户读取。SkillManagementJdbcPersistenceTest接入完整候选创建API回归，逐资源与解析结果核对，验证未自动发布；外部真实ZIP通过系统属性提供。

没有修改用户SkillProperties、application配置或原ZIP。底层插入SQL与事务保持原状。双库迁移/仓储5项和最终PG/MySQL接口各3项通过，合计11项执行无失败/错误/跳过。原版三个ZIP在两库都通过完整API落库与正文回读，未自动发布。用户运行库未执行V18，生效步骤见账本。

## 关联工作包

- [清单](../checklists/2026-10-08-skill-import-description-checklist.md)
- [提示词](../prompts/2026-10-08-skill-import-description-prompt.md)
- [设计](../specs/2026-10-08-skill-import-description-design.md)
- [计划](../plans/2026-10-08-skill-import-description.md)
- [实现](../implementation/2026-10-08-skill-import-description-implementation-design.md)
- [账本](../progress/2026-10-08-skill-import-description-ledger.md)

</details>
