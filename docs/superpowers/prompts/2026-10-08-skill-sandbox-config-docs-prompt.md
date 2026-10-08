# 技能沙箱配置文档对齐：执行提示词

日期：2026-10-08；修订：R0；工作模式：`mode=default`。

下列提示词用于同范围后续维护；文本本身不授予提交或外部操作权限。本轮文档修改已执行，完成状态以账本为准，不能重复实施原沙箱业务。

```text
仓库：F:/java/cm-agent。
需求：将当前技能沙箱改动新增的配置对齐到配置文档。
工作模式：mode=default，自主核对并记录假设，不主动咨询普通工程细节。
先读取 AGENTS.md 和以下文档：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-08-skill-sandbox-config-docs-checklist.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-08-skill-sandbox-config-docs-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-08-skill-sandbox-config-docs.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-08-skill-sandbox-config-docs-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-08-skill-sandbox-config-docs-ledger.md
当前范围仅文档：docs/configuration.md、README.md、docs/skill-sandbox-endpoints.md 及本组六份文档。
按 T0→T1→T2 核对账本，只处理尚未完成或源代码变化导致的配置差异。
以当前 Server 两个配置类及 application-skill-sandbox.yml 为准，核对属性、默认值、限额、显式环境变量、连接材料互斥、profile 和租户默认优先级。
存在 .codegraph 时先使用 CodeGraph；索引缺失则核对当前源文件并记录限制。
保留原工作包历史与用户已有配置及其他脏文件；不打印或保存真实凭据，不修改服务或配置，不提交、推送、合并、部署。
进行文档属性和环境变量覆盖检查、相对链接检查、示例/默认值复核及 git diff --check；记录实际结果，旧测试不能充作本轮结果。
仅文档修改不要求重跑 Maven 或容器测试；若后续授权扩展到 Docker/JDBC/Flyway，按仓库规范在 ssh rocky 的指定 JDK 21 Maven 容器验证，并核对远程版本。
无法验证的项写明原因及继续条件。同步六份文档，最终按仓库格式报告实际变更、验证、影响、注意事项和相关下一步。
```

关联文档：[待办清单](../checklists/2026-10-08-skill-sandbox-config-docs-checklist.md) · [设计](../specs/2026-10-08-skill-sandbox-config-docs-design.md) · [计划](../plans/2026-10-08-skill-sandbox-config-docs.md) · [实现说明](../implementation/2026-10-08-skill-sandbox-config-docs-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-sandbox-config-docs-ledger.md)。
