# cm-agent-core 模块 README 实现说明

对应设计：[specs/2026-09-01-core-module-readme-design.md](../specs/2026-09-01-core-module-readme-design.md)
对应计划：[plans/2026-09-01-core-module-readme.md](../plans/2026-09-01-core-module-readme.md)

## 实际产出

新增 `cm-agent-core/README.md`，共 10 个编号章节，全部中文：

1. **模块定位**：说明 Core 是领域与契约层而非执行层，给出 `server → starter/persistence/console/agentscope-adapter → core → api` 依赖方向，以及四个实现模块各自落地的契约（adapter 实现 `AgentRuntime`、persistence 实现 10 个仓储与审计、server 实现治理编排、starter 提供默认实现）。
2. **依赖原则：零框架**：记录仅依赖 `cm-agent-api` 的事实与零框架依赖的三个理由。
3. **包结构**：六个子包及实测类型数量——`core.audit` 3、`core.domain` 33、`core.repository` 10、`core.runtime` 9、`core.security` 5、`core.tool` 6，合计 66；与全量 Java 文件清单逐包统计一致。
4. **核心 SPI**：
   - `AgentRuntime` 三个方法的兼容层语义（同步 run、非破坏性流式 run、`runStructured` 自动构造消息快照），与 `AgentRuntime.java` 源码一致。
   - 工具治理：`ToolRegistrationSnapshot` 一致快照、`ToolExecutionRequest` 按 `ToolInvocationSource` 校验（AGENT 必须绑定 agentId/runId，DEBUG/MCP 不得绑定）、`ToolInvocationGateway` 复核入口、`ModelCredential.toString()` 恒定脱敏。
   - 安全策略：`DefaultToolAuthorizationPolicy` 的三步校验顺序（同租户 → 已启用 → Agent 级 `ToolGrant` 授权）。
   - 持久化与审计：tenantId 首参、游标分页、`RunRepository.keysetOrder()` 与数据库 `CHAR(36)` 字符串序对齐、`AuditEventRepository.appendAll` 批量原子约定、`supportsCursorPagination()` 显式声明。
5. **领域模型核心类型**：租户一致性是构造期不变量；`RunRecord` 状态机（`create()`/`complete()`、RUNNING 与终态对 `finishedAt` 的互斥约束）；`ModelConfig` baseUrl 的 HTTP(S)/无 userinfo/无 fragment 校验及 API Key 分离；`AgentDefinition` 范围约束（temperature 0–2、maxIterations 1–30）；工具/HTTP/MCP 类型；会话消息模型；关键枚举表。
6. **运行链路**：mermaid 流程图展示被治理运行中各 Core 契约节点的位置，并配文字链路说明。
7. **测试结构**：16 个测试类按包分组的覆盖表，命令 `mvn -pl cm-agent-core -am test`。
8. **扩展注意事项**：新领域概念（record 不变量 + Flyway 双数据库中文注释要求）、新运行时实现（信任边界）、新工具执行方式（不再 Core 内联网，统一走网关）。
9. **常见问题**：四条设计理由 FAQ——多租户显式契约而非注解式拦截、凭据恒定脱敏是最后防线、DEBUG/MCP 拒绝绑定运行上下文防审计错归因、审计批量原子与严格失败语义。
10. **相关文档**：指向根 README、roadmap、技术架构、工具开发指南、adapter README。

## 关键代码/数据位置

- 变更文件：`cm-agent-core/README.md`（新增，唯一代码库内变更）。
- 事实核对来源：`cm-agent-core/src/main/java/com/cmagent/core` 下全部 66 个源文件清单（终端 `Get-ChildItem` 全量列出）、`cm-agent-core/pom.xml`、`AgentRuntime.java`、`ToolRegistry.java`、`ToolExecutionRequest.java`、`DefaultToolAuthorizationPolicy.java`、`AuditEventRepository.java`、`RunRepository.java`、`AgentDefinition.java`、`ModelConfig.java`，以及 `src/test` 下 16 个测试类清单。

## 与原方案的差异

无实质差异。撰写过程中修正了三处草稿笔误（繁体“契約”改“契约”，“绽态”改“终态”，FAQ 删除误留符号），并把初步估计的 domain 包类型数从 40 修正为逐包统计后的 33，总类型数 66 不变。

