# 技能导入描述长度修复计划

日期：2026-10-08；主题：skill-import-description；当前修订：R1；修订日期：2026-11-13；模式：mode=default。

R0 原文保留在文末归档；R1 计划只描述方法和顺序。

## 实施与验证顺序

| 编号 | 实施内容 | 验证方法 |
|---|---|---|
| T1 | 定位版本插入边界与 500/1024 长度失配 | 源码、迁移、日志和 CodeGraph 交叉核对 |
| T2 | 新增双数据库描述扩容迁移和中文注释 | 迁移历史、列定义、旧数据保留检查 |
| T3 | 扩展仓储和 API 边界测试 | 旧 835 字符失败、1024 Unicode、1025 拒绝、大资源和租户隔离 |
| T4 | 在 Rocky 隔离容器验证 | PostgreSQL 16、MySQL 8.4 与源码快照和哈希核对 |
| T5 | 更新生产说明和生效条件 | 部署流程、备份、迁移顺序和未验证边界检查 |
| T6 | 迁移六份文档职责 | 活动区不含状态表或验证结论，R0 归档保留 |
| T7 | 校验文档一致性 | 修订号、任务编号、链接、代码围栏和空白检查 |

## 当前状态与证据

当前修订的状态、命令结果、验证证据和提交信息只以[进度账本](../progress/2026-10-08-skill-import-description-ledger.md)为准。其他入口：[清单](../checklists/2026-10-08-skill-import-description-checklist.md)、[设计](../specs/2026-10-08-skill-import-description-design.md)、[提示词](../prompts/2026-10-08-skill-import-description-prompt.md)、[实现说明](../implementation/2026-10-08-skill-import-description-implementation-design.md)。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留 2026-10-08 R0 原文</summary>

# 技能导入描述长度修复计划

日期 2026-10-08；修订 R0；mode=default；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443；分支 codex/skills-version-console；未提交。

1. T1：核对附件错误编号、AGENTS、Git、POM、配置、CodeGraph和源码。索引未定位当前Skill符号时回退精确源码。
2. T2：新增mysql/postgresql同名V18，扩容描述并补中文原生注释，不改历史脚本。
3. T3：MigrationTest分阶段到17再18，复现835字符错误、确认旧值保留、Unicode边界、列长度和全库注释。JdbcSkillRepositoriesTest添加500/501/835/1024描述与242277字节资源回读及租户过滤。SkillManagementJdbcPersistenceTest增加API边界和外部原ZIP验收。
4. T4：通过git bundle在Rocky新建隔离checkout，HEAD一致；仅覆盖上述文件和用户SkillProperties（无凭据），覆盖SHA256核对。打包三个ZIP到隔离验收目录；maven:3.9.9-eclipse-temurin-21执行MigrationTest、JdbcSkillRepositoriesTest、SkillManagementJdbcPersistenceTest；接口分别设置cm-agent.test.skill-database=postgresql/mysql；不使用本机Docker、不全局清理。
5. T5：同步README、configuration、release-notes与六文档；记录真实命令、结果、未提交和用户库尚未迁移。原配置测试的64KiB旧断言属于用户容量调整遗留，报告而不无关改写。

## 关联工作包

- [清单](../checklists/2026-10-08-skill-import-description-checklist.md)
- [提示词](../prompts/2026-10-08-skill-import-description-prompt.md)
- [设计](../specs/2026-10-08-skill-import-description-design.md)
- [计划](../plans/2026-10-08-skill-import-description.md)
- [实现](../implementation/2026-10-08-skill-import-description-implementation-design.md)
- [账本](../progress/2026-10-08-skill-import-description-ledger.md)

</details>
