# 技能导入描述长度修复执行提示词

日期 2026-10-08；修订 R0；mode=default；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443；分支 codex/skills-version-console；未提交。

```text
在 F:/java/cm-agent 读取 AGENTS.md 及本工作包清单、设计、计划、实现、账本。工作模式 mode=default，自主处理工程细节；先核对当前T1～T5状态，不重复完成项。目标修复合法长描述技能在JdbcSkillVersionRepository.insert落库失败：解析器1024码点与历史schema500字符不一致。
新增同版本双库V18扩容skill_versions.description并保留中文原生注释、历史数据、摘要及租户/事务/权限/审计边界；禁止改V12、裁剪原包、覆盖用户配置/容量修改或输出真实凭据。MigrationTest验证旧失败和升级；仓储/API覆盖Unicode1024、1025拒绝、大资源与实际docx/pptx/pdf ZIP完整落库。
Docker/JDBC/Flyway/Testcontainers仅ssh rocky，在独立checkout和maven:3.9.9-eclipse-temurin-21中执行；先确认Docker、JDK21、HEAD与明确覆盖文件SHA256。测试只用隔离容器，真实原ZIP从cm-agent.test.skill-packages-dir外部路径提供。不能操作用户运行服务或数据库、不全局清理、不提交、不推送、不合并、不部署。
同步本六份文档和README/configuration/release-notes；记录真实测试命令、未执行项、失败与外部阻塞。原用户容量测试旧断言不掩盖。最终说明原因、变更、验证、影响和需要用户按发布流程应用V18的事项。提示词自身不赋予额外授权。
```

## 关联工作包

- [清单](../checklists/2026-10-08-skill-import-description-checklist.md)
- [提示词](../prompts/2026-10-08-skill-import-description-prompt.md)
- [设计](../specs/2026-10-08-skill-import-description-design.md)
- [计划](../plans/2026-10-08-skill-import-description.md)
- [实现](../implementation/2026-10-08-skill-import-description-implementation-design.md)
- [账本](../progress/2026-10-08-skill-import-description-ledger.md)
