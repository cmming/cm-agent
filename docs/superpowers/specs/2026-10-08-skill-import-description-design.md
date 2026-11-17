# 技能导入描述长度修复设计

日期：2026-10-08；主题：skill-import-description；当前修订：R1；修订日期：2026-11-13；模式：mode=default。

R0 原文保留在文末归档；R1 只记录设计和决定。

## 当前设计

- 包解析器允许最多 1024 个 Unicode 码点，而历史数据库列仅允许 500，合法描述会在版本插入阶段失败。
- 新增 PostgreSQL 与 MySQL 同版本迁移，将技能版本描述扩展为 1024；不修改已发布旧迁移，不裁剪描述或资源。
- 旧版本、摘要、治理指针、租户、权限、事务、审计和发布语义保持不变。
- 只读诊断继续脱敏；不通过放宽 SQL 诊断暴露底层 schema 或用户数据。
- 生产生效要求部署包含新迁移的构建，并按发布流程备份、迁移和验证；隔离测试通过不能替代用户运行库升级。

## 当前状态与证据

当前修订的状态、验证和提交信息只以[进度账本](../progress/2026-10-08-skill-import-description-ledger.md)为准。相关入口：[清单](../checklists/2026-10-08-skill-import-description-checklist.md)、[计划](../plans/2026-10-08-skill-import-description.md)、[提示词](../prompts/2026-10-08-skill-import-description-prompt.md)、[实现说明](../implementation/2026-10-08-skill-import-description-implementation-design.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留 2026-10-08 R0 原文</summary>

# 技能导入描述长度修复设计

日期 2026-10-08；修订 R0；mode=default；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443；分支 codex/skills-version-console；未提交。

解析器允许描述最多1024个Unicode码点，V12在两个数据库中使用VARCHAR(500)。docx描述835，pptx732，pdf437，故前两包解析通过仍可能写入失败；日志指向版本插入而非资源写入。

采用新增V18双库方言迁移，将skill_versions.description扩为VARCHAR(1024)。MySQL显式utf8mb4，PostgreSQL使用原列类型扩容；字段原生中文注释同步。保留V12发布历史，不截断描述、不修改原ZIP或摘要、租户/权限/事务/审计/发布语义。

T1～T5验收覆盖旧schema复现、升级保留历史值、1024 Unicode字符（含补充平面字符）回读、242277字节资源、拒绝1025描述及实际三个ZIP经POST /api/skills进入候选。第三方ZIP由外部验收路径提供，不进入版本控制。未提供外部路径时专项原包用例条件跳过，常规自生成回归仍运行。

不调整通用持久化错误映射或诊断脱敏，也不自动执行用户数据库迁移。API合同通过cm-agent.test.skill-database选择postgresql或mysql，默认postgresql；未知值明确拒绝。实际生产生效要求交付含V18的构建、按既有发布流程备份并迁移；本轮只验证隔离Testcontainers。

## 关联工作包

- [清单](../checklists/2026-10-08-skill-import-description-checklist.md)
- [提示词](../prompts/2026-10-08-skill-import-description-prompt.md)
- [设计](../specs/2026-10-08-skill-import-description-design.md)
- [计划](../plans/2026-10-08-skill-import-description.md)
- [实现](../implementation/2026-10-08-skill-import-description-implementation-design.md)
- [账本](../progress/2026-10-08-skill-import-description-ledger.md)

</details>
