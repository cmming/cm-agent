# 2026-09-01 agentscope-adapter 注释整改设计

## 背景

`cm-agent-agentscope-adapter` 模块的注释在 2026-08-20 的 `agentscope-adapter-comments` 任务中已初具规模，但本次复查发现四类遗留问题：

1. 测试代码中存在大量 JavaDoc 书写在 `@Test`、`@Override`、`@BeforeEach`、`@AfterEach` 注解**之后**的情况。JavaDoc 必须位于注解之前才能被 javadoc 工具关联到对应声明，注解后置的注释属于无效位置，IDE 亦会报“悬空的 Javadoc 注释”警告。
2. 测试代码混入大量模板化低价值注释，如“验证 `{@code Xxx}` 所描述的行业行为”“`@param xxx` 测试辅助方法使用的 xxx 参数”等，仅复述方法名，无信息增量，违��� AGENTS.md 的低价值注释禁令。
3. 主代码 `AgentScopeReActExecutor#executeStructured`（生产执行主流程）缺少 JavaDoc，三个失败消息常量缺少“为什么必须固定文案”的说明。
4. 模块缺少 `package-info.java` 包级说明，初次接触者无法快速理解包内类型的协作关系与 optional 依赖约束。

## 目标

- 使测试代码中所有 JavaDoc 处于注解之前的可关联位置。
- 清除测试代码中的模板注释，仅保留有实质信息的注释。
- 补齐主代码缺失的 JavaDoc 与常量说明。
- 新增包级 `package-info.java`，说明包内类型协作与 optional 依赖边界。
- 全程不改变任何代码行为，验证以模块测试全部通过为准。

## 范围

- 仅涉及 `cm-agent-agentscope-adapter` 模块的 `src/main/java` 与 `src/test/java`。
- 仅修改注释与新增 `package-info.java`，不改动任何可执行语句、方法签名与 import 语义。

## 非目标

- 不重构 `AgentScopeReActExecutor` 等既有实现逻辑。
- 不为 core、server 等其他模块补注释（分别已有独立任务）。
- 不调整模块对外 API 或 Spring Boot 装配。

## 方案

1. 主代码先行：为 `executeStructured` 补完整 JavaDoc（说明其为生产主流程与脱敏边界）；为三个失败消息常量补充“为何固定文案”和“框架版本依赖”注释；新增 `package-info.java`。
2. 测试代码用 PowerShell 正则做两步机械转换：先将“注解 + 注解后 JavaDoc”位置互换；再将包含“所描述的业务行为”“测试辅助方法使用的”等标记的模板 JavaDoc 删除，并清理由此产生的空 JavaDoc 块与多余空行。
3. 每一步转换后用编译和抽查阅读验证结构完整性，最后运行 `mvn test -pl cm-agent-agentscope-adapter -am` 确认行为不变。

## 约束

- 所有新增注释、文档使用中文。
- 禁止修改测试逻辑；正则必须限定 JavaDoc 块边界（`/**` 到最近的 `*/`），避免越界删除。
- 单模块运行 `-pl cm-agent-agentscope-adapter`（不带 `-am`）会使用本地仓库的旧 core 快照，可能导致 `NoClassDefFoundError` 类启动失败，验证必须带 `-am`。

## 验收标准

- 四个测试文件 grep 不到“所描述的业务行为”“测试辅助方法使用的”模板标记。
- 任意 JavaDoc 不再位于注解与声明之间（`@Test` 后直接是方法签名或下一注解）。
- `package-info.java` 存在且通过编译。
- `mvn test -pl cm-agent-agentscope-adapter -am` 全部通过且测试数量与改动前一致（adapter 模块 54 个）。

