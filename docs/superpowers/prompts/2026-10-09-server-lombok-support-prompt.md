# Server Lombok 支持提示词

日期：2026-10-09；修订：R0；工作模式：mode=default。

Git 基线：`dc541e7ddc59022411a9ed7b3a9727f421d8c073`。本次独立修复 Server 编译期 Lombok 支持，保留工作树已有的 DOCX 技能、协议日志、控制台和环境配置修改。

```text
在 F:/java/cm-agent 维护 Server 的 Lombok 构建支持，mode=default，自主采用范围内的合理方案。
本工作包路径：
docs/superpowers/checklists/2026-10-09-server-lombok-support-checklist.md
docs/superpowers/prompts/2026-10-09-server-lombok-support-prompt.md
docs/superpowers/specs/2026-10-09-server-lombok-support-design.md
docs/superpowers/plans/2026-10-09-server-lombok-support.md
docs/superpowers/implementation/2026-10-09-server-lombok-support-implementation-design.md
docs/superpowers/progress/2026-10-09-server-lombok-support-ledger.md
先读 AGENTS.md 与本工作包清单、设计、计划、实现说明和账本，再确认当前工作树。
按 T0→T1→T2→T3 执行：定位显式注解处理器路径导致 @Slf4j 未生成 log 的问题，将 Lombok 加入 cm-agent-server/pom.xml 的 annotationProcessorPaths，保留 provided 依赖与 Spring Boot 配置处理器；版本沿用现有 BOM，不升级框架或扩展到其他模块。
用 JDK 21 检查 Maven 环境，运行 mvn -q -pl cm-agent-server -am -Dtest=SkillArtifactProtocolTest -Dsurefire.failIfNoSpecifiedTests=false test；检查实际依赖版本、javap 中的 log 字段以及 spring-configuration-metadata.json；必要时运行 -DskipTests package 核实构建。
保护用户已有协议日志、POM、控制台和环境配置修改，不记录凭据。无需改变错误语义、追加运行时依赖或用新测试镜像实现。
失败时记录确切原因与未执行验收，不将计划写为完成；同步本工作包六份中文文档和 README，按仓库格式交付。
本次未授权提交、推送或部署。后续问答先读当前文档，不自动重新执行或改变范围。
```

该提示词描述本次既有授权范围；复制或进入问答不扩大修改和外部操作授权。

关联文档：[清单](../checklists/2026-10-09-server-lombok-support-checklist.md) · [设计](../specs/2026-10-09-server-lombok-support-design.md) · [计划](../plans/2026-10-09-server-lombok-support.md) · [实现说明](../implementation/2026-10-09-server-lombok-support-implementation-design.md) · [账本](../progress/2026-10-09-server-lombok-support-ledger.md)。
