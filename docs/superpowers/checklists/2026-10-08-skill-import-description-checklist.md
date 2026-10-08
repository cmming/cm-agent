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
