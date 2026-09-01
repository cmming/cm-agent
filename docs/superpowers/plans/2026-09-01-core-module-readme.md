# cm-agent-core 模块 README 计划

对应设计：[specs/2026-09-01-core-module-readme-design.md](../specs/2026-09-01-core-module-readme-design.md)

## 任务拆分

### 任务 1：核对源码事实

- 梳理 `cm-agent-core/src/main/java/com/cmagent/core` 全部子包与 Java 文件清单，统计各包类型数。
- 精读 `AgentRuntime`、`ToolRegistry`、`ToolExecutionRequest`、`DefaultToolAuthorizationPolicy`、`AuditEventRepository`、`RunRepository`、`AgentDefinition`、`ModelConfig` 等关键类型，确认签名、默认方法语义与紧凑构造器校验规则。
- 梳理 `src/test` 下 16 个测试类清单及覆盖点。
- 确认 `cm-agent-core/pom.xml` 依赖只有 `cm-agent-api` 与 test 作用域的 `spring-boot-starter-test`。

### 任务 2：撰写 `cm-agent-core/README.md`

涉及文件：`cm-agent-core/README.md`（新增）。

章节顺序：模块定位 → 依赖原则 → 包结构 → 核心 SPI（AgentRuntime / 工具治理 / 安全策略 / 持久化与审计 / 内置轻量实现）→ 领域模型要点 → 运行链路 mermaid 图 → 测试结构 → 扩展注意事项 → 常见问题 → 相关文档链接。

### 任务 3：生成 superpowers 配套文档

按 AGENTS.md 规则生成其余三份同日期、同 topic（`core-module-readme`）中文文档：

- 本计划文档。
- `implementation/2026-09-01-core-module-readme-implementation-design.md`。
- `progress/2026-09-01-core-module-readme-ledger.md`。

### 任务 4：验证

- 全文检索确认 README 中无凭据、secret 或生产 URL。
- 核对 README 与源码的数量与命名一致（66 个类型、16 个测试类、10 个仓储接口）。
- 本任务为纯文档新增，不运行构建和测试；说明理由：无代码变更，`mvn` 构建结果不受影响。

## 实现顺序

任务 1 → 任务 2 → 任务 3 → 任务 4。

## 验证方式

- 人工比对 README 章节内容与源码事实清单。
- 确认四份文档存在、同日期、同 topic、相互引用。

