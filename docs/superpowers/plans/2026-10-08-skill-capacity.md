# 技能容量同步计划

日期：2026-10-08；修订：R0；mode=default；分支 codex/skills-version-console；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443。

T1：核对 Git 与手工 SkillProperties 差异、旧测试、导入/运行预算检查和用户提交范围选择。

T2：维护 SkillProperties 与 SkillPackageLimits 的准确中文注释；调整 SkillPropertiesTest 的公开默认/硬边界契约；GovernedSkillAccessServiceTest 增加较大资源集及累计预算回归；docs/configuration.md 增加十项完整容量表，release-notes 记录行为。

T3：复制当前全部相关源码到同 HEAD 的 Rocky 隔离目录，逐文件 SHA256；maven:3.9.9-eclipse-temurin-21 运行容量、解析、运行预算、Controller、控制台资源、迁移/仓储、真实原包导入及日志调用方专项；MySQL 与 PostgreSQL 均检查。

T4：检查 docs 相对链接、Git 差异、敏感内容与暂存清单。按用户授权提交当前实现及配套文档；提交记录写入本任务和相关旧账本。无关配置与工具目录保持未暂存，不推送、不合并、不部署。

## 关联产物

执行记录：T1～T3 已完成，T4 提交进行中；具体命令、源码一致性及测试统计以账本为准。

- [清单](../checklists/2026-10-08-skill-capacity-checklist.md)
- [提示词](../prompts/2026-10-08-skill-capacity-prompt.md)
- [设计](../specs/2026-10-08-skill-capacity-design.md)
- [实现说明](../implementation/2026-10-08-skill-capacity-implementation-design.md)
- [账本](../progress/2026-10-08-skill-capacity-ledger.md)
