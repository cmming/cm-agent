# 技能容量同步执行提示词

日期：2026-10-08；修订：R0；mode=default；分支 codex/skills-version-console；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443。

```text
在 F:/java/cm-agent 读取 AGENTS.md 及 2026-10-08-skill-capacity 清单、设计、计划、实现和账本。
mode=default，自主处理工程细节，先核对 T1～T4 当前状态，不重复完成项。
用户已选择：将手工容量修改纳入提交，先同步测试与配置文档；保留当前数值。
同步容量类注释、SkillPropertiesTest、较大资源集沙箱累计预算回归和生产配置表。
独立解析器保守默认值不变；导入、快照准备、运行累计预算分别检查，权限/租户/审计保持。
Docker/JDBC/Flyway/Testcontainers 仅 ssh rocky，在同 HEAD 独立目录校验覆盖文件 SHA256，
使用 maven:3.9.9-eclipse-temurin-21、postgres:16-alpine/mysql:8.4。
覆盖当前容量、解析、运行预算、Controller、控制台资源、迁移/仓储、真实原 ZIP 导入及日志调用方。
精确暂存本轮代码和文档，包含已实施的技能控制台、V18、JDBC 诊断及容量同步。
保留所有 application 配置、.codex/.workbuddy，不输出或保存凭据；不推送、不合并、不部署、不重启服务。
提交授权来自本次用户明确指令；未来重复执行或外部操作需以用户实际指令为准。
记录实际命令、通过结果、未执行项、提交信息和工作树剩余文件，六份文档与关联旧账本同步。
```

提示词文件本身不增加实施或 Git 权限；当前本地提交授权来自用户本次明确选择。

## 关联产物

- [清单](../checklists/2026-10-08-skill-capacity-checklist.md)
- [设计](../specs/2026-10-08-skill-capacity-design.md)
- [计划](../plans/2026-10-08-skill-capacity.md)
- [实现说明](../implementation/2026-10-08-skill-capacity-implementation-design.md)
- [账本](../progress/2026-10-08-skill-capacity-ledger.md)
