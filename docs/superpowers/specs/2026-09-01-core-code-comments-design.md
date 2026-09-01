# cm-agent-core 代码注释治理设计

## 背景

`cm-agent-core` 既有 JavaDoc 由机器批量生成，存在系统性缺陷：大量成员级 JavaDoc 缩进错位（` *` 写成两空格）；参数描述与实际参数张冠李戴（如 `@param executor AgentScope 或异步任务执行器`、`@param source 待转换的源对象`、无参构造器标注 `@param cause`）；约 8 处 SPI 契约说明为英文（违反仓库中文注释规范）；`@FunctionalInterface`、`@Override` 注解写在 JavaDoc 之前导致“悬空 Javadoc”；多数 SPI 方法缺少 `@return` 与失败语义说明。

这些缺陷使维护者难以从注释理解租户闸门、脱敏边界、审计严格失败等关键设计约束，部分错误注释（如错误的 `@param`）具有误导性。

## 目标

在不改变任何代码行为的前提下，将 `cm-agent-core` 全部 66 个主源码文件的 JavaDoc 修正为规范中文注释：

1. 统一修正如缩进、注解顺序等格式缺陷。
2. 修正或删除全部错误参数描述，确保 `@param` 与实际参数语义一致。
3. 英文契约说明改写为准确中文，代码标识符保留英文。
4. 命中 AGENTS.md 触发清单的位置（安全边界、失败分级、租户隔离、序列幂等、兼容代码等）补充“为什么”级别的说明； простой线性赋值/校验不为覆盖率堆砌注释。

## 范围

- 仅修改 `cm-agent-core/src/main/java` 下的 Java 注释（JavaDoc 与行注释），不改任何可执行代码、签名、注解位置以外的结构。
- 按仓库文档规则在 `docs/superpowers` 生成同日期、同 topic（`core-code-comments`）的四份文档。

## 非目标

- 不修改测试代码注释（测试注释规范以测试意图不明确为前提，现有测试命名已清晰）。
- 不修改 `cm-agent-api`、adapter、server 等其他模块。
- 不为通过验证而扩大注释范围或重排既有代码。

## 方案

按包分批处理：tool → security → audit → runtime → domain → repository。

- SPI 接口（`AgentRuntime`、`ToolRegistry`、`ToolInvocationGateway`、`ModelCredentialProvider`、`PermissionEvaluator`、`ToolAuthorizationPolicy`、`ToolExecutor`）在类级 JavaDoc 说明治理边界与实现方义务；方法级补齐 `@return`、失败语义（普通失败返回结果、基础设施失败抛异常的分级）。
- record 紧凑构造器的 JavaDoc 保持“校验入口”定位，额外说明不变量的设计原因（如 `RunRecord` 状态机互斥、`AgentRunRequest` 租户一致性总闸、`MessagePageRequest` 用序号不用时间戳）。
- 领域 record 类级说明按 AGENTS.md 示例裁剪补充脱敏责任（`AgentRunResult`、`AuditEvent`、`MessageContentBlock` 等）。
- 仓储接口补充 CRUD 之外的隐式约束：删除幂等语义、`ForUpdate` 行锁差异、墓碑外键锚点、`keysetOrder` 与数据库 `CHAR(36)` 字符串序对齐原因、RESTORE 方法防复活边界。
- 修复过程中全部注释保持“为什么”导向，不复述代码字面含义，不引入真实凭据。

## 约束

- 只允许注释变更：每文件 diff 不得出现可执行代码行变化。
- 注释一律中文；框架行为描述须与已核对源码一致（如 `RunRepository.compareIdsByDatabaseOrder` 的字符串序理由来自实现本身）。
- 验证必须覆盖：模块编译、全部 16 个单元测试、下游全模块编译。

## 验收标准

- 全部 66 个主源码 JavaDoc 缩进一致，无错位；`@FunctionalInterface`/`@Override` 位于 JavaDoc 之后。
- 不再存在英文类级/方法级契约注释；错误 `@param` 全部修正或移除。
- 有实质风险的拦截类（脱敏、租户、审计失败、删除墓碑）位置均有中文注释说明安全边界与原因。
- `mvn -pl cm-agent-core test` 与全模块 `compile` 在 JDK 21 下通过；`git diff` 证实仅注释行变化。

