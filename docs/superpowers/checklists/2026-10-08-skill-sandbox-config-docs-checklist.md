# 技能沙箱配置文档对齐：待办清单

日期：2026-10-08；修订：R0；工作模式：`mode=default`。

需求来源：用户要求“当前修改新增了哪些配置，对齐到配置文档中”。本次为独立文档对齐任务，关联既有 [沙箱工作包](2026-09-30-skill-sandbox-checklist.md) 和 [SSH 密码工作包](2026-10-01-remote-docker-password-auth-checklist.md)，不改变它们的历史状态。

Git 基线：`codex/skill-sandbox-r1`，HEAD `bc57060fac00c671cd247525b6082516194afd20`，暂存区为空；已有大量业务与用户配置改动。范围限 `docs/configuration.md`、`README.md`、`docs/skill-sandbox-endpoints.md` 和本组六份文档。不修改配置、代码、数据库或运行服务，不提交、推送、合并、部署。

| 编号 | 状态 | 目标 | 依赖 | 验收与证据 |
| --- | --- | --- | --- | --- |
| T0 | 完成 | 核对规范、Git 和当前配置源 | 无 | 配置类、可选 profile、现有说明已核对；保护既有脏文件 |
| T1 | 完成 | 补齐配置目录及入口 | T0 | 总配置文档覆盖 20 个属性、默认值及 15 个显式环境变量；最近新增的两项明确标识 |
| T2 | 完成 | 核验配置覆盖、链接及修改范围 | T1 | 20/20 属性与默认值、15/15 环境变量；PASSWORD 示例解析、32 个相对链接和 2 个目录锚点通过；74 个非目标文件哈希不变，见账本 |

当前证据：Server 的 `SkillSandboxProperties`、`DockerConnectionProperties` 和 `application-skill-sandbox.yml`；目标校验与部署读取由 `SandboxTargetPolicy`、`SandboxEndpointService` 支持。环境部署和实际凭据不是本轮验证对象，无新增业务实施待办。

关联文档：[执行提示词](../prompts/2026-10-08-skill-sandbox-config-docs-prompt.md) · [设计](../specs/2026-10-08-skill-sandbox-config-docs-design.md) · [计划](../plans/2026-10-08-skill-sandbox-config-docs.md) · [实现说明](../implementation/2026-10-08-skill-sandbox-config-docs-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-sandbox-config-docs-ledger.md)。
