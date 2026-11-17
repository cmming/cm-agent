# 技能导入描述长度修复执行提示词

日期：2026-10-08；主题：skill-import-description；当前修订：R1；修订日期：2026-11-13；模式：mode=default。

R0 原始执行提示词保留在文末归档；以下 R1 提示词只迁移文档职责，不重新实施已交付业务。

## 当前执行输入（R1）

```text
仓库：F:/java/cm-agent
范围：将 2026-10-08-skill-import-description 六份历史工作包迁移到新契约。

读取清单、设计、计划、实现说明、进度账本和 AGENTS.md；保护工作树中其他修改，不修改业务代码，不重做能力修复。
清单只保留任务、依赖和验收；设计保留长度失配、V18 双库迁移、兼容与安全决定；计划保留实施顺序和验证方法；提示词只保留当前执行输入；实现说明只保留实际代码与迁移改动和设计差异。
任务状态、命令结果、测试证据、阻塞和提交信息只写入账本。R0 原文完整归档。完成后更新 WORKPACK-STATUS.md 并检查修订、任务、链接、代码围栏和空白；不提交、不推送、不部署。
```

## 当前状态与证据

当前修订的状态、验证和提交信息只以[进度账本](../progress/2026-10-08-skill-import-description-ledger.md)为准。
## R0历史归档（仅分析，不代表当前状态）

<details>
<summary>保留 2026-10-08 R0 原文</summary>

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

</details>
