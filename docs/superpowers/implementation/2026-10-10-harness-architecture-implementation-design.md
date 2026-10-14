# Harness 架构引入分析交付说明

日期：2026-10-10；主题：harness-architecture；修订：R1；修订日期：2026-10-10；模式：mode=default。

基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。R0为六文档分析；R1增加当前项目依赖报告、隔离POM草案与六文档修订，正式业务代码/POM未修改。

## R0 实际完成，历史保留

核对技能/产物契约、规范、Git、POM、README、配置键与运维；CodeGraph先定位再补查关键区间；确认执行策略、Run身份、历史/检查点、Skill和工具治理职责。

Context7查询能力/状态/迁移，读取官方架构页；查本地Harness2.0.0 POM、javap及fromAgent字节码。在线新版Session Log与本地版本不同，不能据此断言Core2.0.2组合可运行。

形成D1渐进接入、D2首项只读工作区、D3排空切换及A1/U1–U3，生成下列六文件。T0是分析完成，T1–T8未实施，T7/T8在首批之外。

## R1 实际交付

用户要求以当前项目为准并允许生成依赖。已用当前Maven/JDK21生成Server有效POM与当前完整依赖树，读取发布POM，下载并检查Harness2.0.2 API；实际本地仓库为F:/mnt/f/java/maven-repository，旧.m2缓存不作为候选依据。

将当前12个POM复制为隔离candidate-reactor，仅在草案根/adapter/server加入管理/optional/运行声明，沿用agentscope.version2.0.2。候选与当前均dependency:tree成功；Server去重选中坐标182→183，仅增加Harness2.0.2，其他版本保持。没有升级Core/Provider/Studio/Jackson/MCP；独立MCP示例解析仍客户端0.17.0。

报告/草案均位于F:/java/cm-agent/cm-agent-server/target/harness-dependency-review-20261010，已被Git忽略，包括current-project-dependencies.txt、server-effective-pom.xml、candidate-project-dependencies.txt、dependency-comparison.json、example-mcp-dependencies.txt、2.0.2签名、发布POM与candidate-reactor。没有代码实验；草案不应用到正式POM，生成物不入Git。

T9为R1依赖评估完成，T1仍待运行兼容验收；R1-01–R1-04差异见设计/账本。报告比R0旧缓存线索更适用于当前版本，不据解析成功宣称运行安全。

| 产物（相对docs/superpowers） | 实际内容 |
| --- | --- |
| checklists/2026-10-10-harness-architecture-checklist.md | 任务、状态、依赖、验收和门禁 |
| prompts/2026-10-10-harness-architecture-prompt.md | 完整提示词与问答/修订/实施授权边界 |
| specs/2026-10-10-harness-architecture-design.md | 当前证据、比较、职责、状态/能力/回退方案 |
| plans/2026-10-10-harness-architecture.md | 后续顺序、文件线索与验证方法 |
| implementation/2026-10-10-harness-architecture-implementation-design.md | 当前真实交付、影响和未实现项 |
| progress/2026-10-10-harness-architecture-ledger.md | 本次核查/检查证据、缺口、状态与提交 |

## 真实行为与差异

当前仍为CM Agent服务→RuntimeAdapter→ReActExecutor→现有治理网关；没有新增Harness分支、模式配置、workspace、记忆、压缩、子Agent或Plan Mode。没有HTTP/SSE、运行/审批/产物或数据库行为变化。

设计中的HarnessExecutor、execution-mode和工作区仍拟建；只给经过依赖解析的POM片段，不给未经运行测试的Builder配方。没有编译/运行实验或执行实施提示词，原源代码/POM/配置不修改。文档与依赖检查不是业务验收。

## 发布与限制

版本控制范围仅这六份文档R1，另有忽略目录内评估生成物和当前Maven本地仓库下载；正式模块/配置/数据库未变。未更新release-notes，因为没有可发布Harness功能；未来T6按实际变更更新。

R0未运行Java/Maven；R1已运行Java/Maven环境检查、依赖解析/下载与javap。没有compile/package/test、模型、浏览器、Rocky、Docker、JDBC/Flyway；纯依赖分析不需容器。默认能力/审批/状态生命周期与二进制兼容仍是T1–T6门禁。

## 关联产物

- [清单](../checklists/2026-10-10-harness-architecture-checklist.md)
- [提示词](../prompts/2026-10-10-harness-architecture-prompt.md)
- [设计](../specs/2026-10-10-harness-architecture-design.md)
- [计划](../plans/2026-10-10-harness-architecture.md)
- [账本](../progress/2026-10-10-harness-architecture-ledger.md)
