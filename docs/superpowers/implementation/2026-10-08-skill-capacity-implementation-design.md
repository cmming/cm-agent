# 技能容量同步实现说明

日期：2026-10-08；修订：R0；mode=default；分支 codex/skills-version-console；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443。

已保留用户的全部四项默认值和硬上限调整，没有再改变数值。更新 SkillProperties 的默认/硬上限区分、安全边界和字段 JavaDoc；SkillPackageLimits 的保守默认值仅修正文档，不改变独立解析行为。

SkillPropertiesTest 更新 Server 容量快照、16 MiB 运行预算及 ZIP 硬边界，增加可放大到硬上限但不可越界的回归。GovernedSkillAccessServiceTest 增加五份 256 KiB 资源集，模拟超过旧预算的技能准备，并检查重复准备仍会耗尽预算。

docs/configuration.md 容量表包含导入、快照、读取预算十项默认/硬上限，区分单资源与累计大小；release-notes 同步。实际验证及提交状态以账本为准。

## 关联产物

当前验收：当前源码 Java 专项 119 项，MySQL 原包接口补跑 3 项，均无失败/错误/跳过；Node 111 项通过。旧 SkillPropertiesTest 容量断言失败已消除。真实数据库仅使用 Rocky 临时 Testcontainers，沙箱预算使用模拟后端，未进行真实技能脚本执行。

- [清单](../checklists/2026-10-08-skill-capacity-checklist.md)
- [提示词](../prompts/2026-10-08-skill-capacity-prompt.md)
- [设计](../specs/2026-10-08-skill-capacity-design.md)
- [计划](../plans/2026-10-08-skill-capacity.md)
- [账本](../progress/2026-10-08-skill-capacity-ledger.md)
