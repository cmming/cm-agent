# cm-agent-core 模块 README 设计

## 背景

仓库内 `cm-agent-agentscope-adapter` 模块已有模块级中文 README，说明了适配层的定位、边界、执行链与测试结构；而作为整个项目依赖重心的 `cm-agent-core` 模块没有 README。开发者初次接触项目时，缺少一份解释“Core 管理什么、契约是什么、为什么这样设计”的模块级入口文档。

## 目标

为 `cm-agent-core` 新增模块级中文 README，说明模块定位、依赖原则、包结构、核心 SPI、领域模型要点、被治理运行链路中 Core 契约的位置、测试结构与扩展注意事项，使初次接触模块的开发者无需通读全部 66 个类型即可理解 Core。

## 范围

- 新增 `cm-agent-core/README.md`。
- 按 AGENTS.md 文档规则在 `docs/superpowers` 下生成同日期、同 topic 的设计、计划、实现说明和进度账本四份文档。

## 非目标

- 不修改任何 Java 代码、测试、构建脚本或数据库结构。
- 不修改已有的 adapter README、根 README 或其他生产文档。
- 不引入英文文档版本，不改动文档既有语言规范。

## 方案

README 采用与 adapter README 一致的编号章节风格，内容要点：

1. **模块定位**：Core 是领域与契约层，非执行层；列出四个实现模块及其落地的契约。
2. **依赖原则**：仅依赖 `cm-agent-api`，零框架依赖的三个理由（不变量校验即构失败、二进制兼容、实现可替换）。
3. **包结构**：六个子包与实际类型数量（audit 3、domain 33、repository 10、runtime 9、security 5、tool 6，合计 66）。
4. **核心 SPI**：`AgentRuntime`（含流式兼容默认方法）、工具治理（`ToolRegistry`/`ToolExecutor`/`ToolExecutionRequest` 按调用来源校验/`ToolInvocationGateway`/`ModelCredentialProvider`）、安全策略、持久化与审计仓储统一模式（tenantId 首参、游标分页、`keysetOrder` 与数据库序对齐、审计批量原子约定）。
5. **领域模型要点**：租户一致性是构造期不变量；`RunRecord` 状态机；`ModelConfig` baseUrl 校验与 API Key 分离；工具/HTTP/MCP 类型；会话消息模型；关键枚举。
6. **运行链路图**：以 mermaid 流程图表示被治理运行中 Core 契约节点的位置。
7. **测试结构**：16 个测试类的分组覆盖表。
8. **扩展注意事项与常见问题**：新领域概念、新运行时、新工具执行方式的边界；多租户显式契约、凭据脱敏、DEBUG/MCP 不绑定运行上下文、审计批量原子性的设计理由 FAQ。

所有结论取自实际源码核对：`AgentRuntime`、`ToolRegistry`、`DefaultToolAuthorizationPolicy`、`AuditEventRepository`、`RunRepository`、`AgentDefinition`、`ModelConfig`、`ToolExecutionRequest` 的签名与 JavaDoc，以及全量 Java 文件清单统计。

## 约束

- 全文中文，代码标识符保留英文，不写任何凭据、secret 或生产 URL。
- 数量、签名、语义必写真实值，不允许凭记忆描述源码行为。
- 测试命令使用 AGENTS.md 规定的 `mvn -pl cm-agent-core -am test`。

## 验收标准

- `cm-agent-core/README.md` 存在且为纯新增文件，不影响构建。
- 文档中的包数量、类型数量、SPI 签名、枚举取值、测试类清单与源码一致。
- 四份 superpowers 文档齐备、同日期、同 topic（`core-module-readme`）、内容相互引用。

