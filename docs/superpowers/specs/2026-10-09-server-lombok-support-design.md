# Server Lombok 支持设计

日期：2026-10-09；修订：R0；工作模式：mode=default。

Git 基线：`dc541e7ddc59022411a9ed7b3a9727f421d8c073`。本次独立修复 Server 编译期 Lombok 支持，保留工作树已有的 DOCX 技能、协议日志、控制台和环境配置修改。

背景：已有 `@Slf4j` 调用依赖编译器生成日志字段，但 Server 的显式处理器路径排除了 Lombok。

目标：在保留用户代码和配置元数据生成的情况下，使 Server 的 Lombok 注解参与编译。

方案 D1（自主采用）：保留已有 `provided` Lombok，显式增加 Lombok 处理器路径；Compiler Plugin 3.13.0 支持从依赖管理获取未写明的处理器版本，因此依赖与处理器使用同一 BOM 管理版本，不再独立指定版本。

约束：只声明编译期能力；不注入 Spring Bean、不改变协议调用链或错误日志内容；不新增运行时依赖；README 说明 IDE Maven 重载与注解处理。

验收：原始编译失败可复现；修复后编译、协议测试、打包成功；产物 `SkillArtifactProtocol` 包含 `log`，配置元数据仍生成；实际 Lombok 版本已核实。

假设：用户期望当前 Server 模块支持 Lombok；其他模块没有本次使用需求，不扩展范围。IDE 实际设置不能从 Maven 构建证明。

非目标：原始产物传输失败根因、原始日志脱敏行为、Spring 配置或全量 Docker/数据库验收。

关联文档：[清单](../checklists/2026-10-09-server-lombok-support-checklist.md) · [提示词](../prompts/2026-10-09-server-lombok-support-prompt.md) · [计划](../plans/2026-10-09-server-lombok-support.md) · [实现说明](../implementation/2026-10-09-server-lombok-support-implementation-design.md) · [账本](../progress/2026-10-09-server-lombok-support-ledger.md)。
