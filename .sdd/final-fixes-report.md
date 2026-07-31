# 工具管理最终修复报告

## 1. 修复范围

本次收口工具编辑、删除和 Agent 工具关联中的四类重要缺陷：

1. 工具已经产生 `tool_calls` 调用历史时，删除不再触发数据库外键异常和服务端 `500`，而是返回明确中文 `409 Conflict`，并完整保留工具定义、运行历史、调用记录和审计链路。
2. Agent 的 `toolIds` 并发授权、撤销不再采用无锁的读改写。内存实现使用原子映射变更；JDBC 实现在事务中通过 `SELECT ... FOR UPDATE` 锁定 Agent 行，授权、撤销、Agent 关联和审计共享事务边界。
3. 已发布 LOCAL 工具编辑时不再被按 HTTP 工具校验。编辑可以保持原 MCP 发布状态或取消发布；未发布 LOCAL 工具不能借编辑接口绕过独立发布流程；HTTP 工具必须提交有效 HTTP 配置，其他工具拒绝 HTTP 配置。
4. JDBC 工具更新与删除竞争时统一锁定同一工具行。更新语句命中零行时抛出明确的“工具不存在”，服务层转换为 `404`，不再返回虚假的更新成功。

控制台同时收紧删除冲突识别：只有服务端明确返回“工具仍被 Agent 关联”的 `409` 才展示解除关联引导；调用历史冲突按服务端原始中文原因展示。

## 2. 根因与设计

### 2.1 调用历史删除

原删除流程只检查 Agent 引用，随后直接物理删除工具定义。`tool_calls.tool_id` 仍引用该工具时，数据库外键拒绝删除，异常最终表现为 `500`。修复后，工具 Repository 必须实现租户范围的调用历史检查；删除事务在锁定工具行后、产生任何删除副作用前完成 Agent 引用和调用历史校验。

这里没有采用级联删除或清空 `tool_id`。调用记录属于长期审计证据，保留工具定义是维持历史可追溯性的安全边界。

### 2.2 Agent 工具列表丢失更新

原 JDBC 实现先普通查询 Agent，再在 Java 中修改 `toolIds` 并覆盖 JSON。两个服务实例同时操作同一 Agent 的不同工具时，二者可能从同一旧快照出发，后提交者覆盖先提交者。

修复后，JDBC Repository 在事务内锁定 Agent 行再执行读改写；外层命令服务按 Agent、工具的固定顺序使用 JVM 条带锁，保护内存模式和单实例快速路径。数据库行锁承担多实例一致性，JVM 锁不作为跨实例正确性的前提。

### 2.3 LOCAL 工具与 MCP 状态

原更新校验复用了创建校验，不能表达“LOCAL 工具已经发布，本次只保持状态”的语义，容易把发布状态和 HTTP 配置绑定。修复后按工具类型分别校验，并将 MCP 变更显式区分为保持、写入和删除：

- 已发布 LOCAL + `mcpPublished=true`：保持现有发布记录；
- 已发布 LOCAL + `mcpPublished=false`：删除发布记录；
- 未发布 LOCAL + `mcpPublished=true`：拒绝，要求使用独立发布操作；
- HTTP：必须提供并校验 HTTP 配置，可同步写入或删除 MCP 发布记录；
- 其他类型：不能携带 HTTP 配置，也不能通过编辑接口发布到 MCP。

### 2.4 更新与删除竞争

原 JDBC 更新先普通读取，再执行 `UPDATE`；并发删除可能发生在两者之间，而且 Repository 无论更新行数是否为零都返回输入对象。修复后，事务更新先通过与删除相同的工具行锁读取；Repository 仍检查实际更新行数，零行时明确失败，形成双层保护。

## 3. 测试驱动与回归覆盖

回归用例先围绕缺陷边界补齐，再实现生产代码。新增或收紧的测试覆盖：

- 有调用历史的工具删除返回明确 `409`，工具、Run、ToolCall 和既有审计保持不变；
- 控制台不会把调用历史 `409` 识别为 Agent 关联冲突；
- 已发布 LOCAL 工具保持发布、取消发布，禁用发布记录不能冒充已发布状态；
- HTTP 缺少配置、LOCAL 携带 HTTP 配置均在写入前拒绝；
- 内存模式下，同一 Agent 的不同工具并发授权、撤销后两个变更都保留；
- PostgreSQL 16 和 MySQL 8.4 下，两个隔离服务实例并发授权、撤销同一 Agent 的不同工具，不丢失更新且无死锁；
- PostgreSQL 16 和 MySQL 8.4 下，更新取得工具行锁后与删除竞争，操作按行锁串行化；
- JDBC 更新租户不匹配或目标不存在时明确失败。

开发机 Maven 运行在 JDK 17，不能编译项目要求的 Java 21；因此 Java 的 RED/GREEN 与数据库集成验证全部放在 Rocky Linux 的 `maven:3.9.9-eclipse-temurin-21` 容器中执行。新增内存并发测试首次编译暴露测试断言把 `revokeTool` 返回的 Agent 误当成布尔值，修正测试后通过；新增调用历史集成夹具首次使用了错误的 `runs` 字段名，改为现有迁移定义的 `input_text`、`output_text` 后复跑通过。两处都只修正测试代码，没有降低生产断言。

## 4. 验证结果

验证环境：

- 远程主机：Rocky Linux 9.3；
- 构建容器：`maven:3.9.9-eclipse-temurin-21`，Java 21.0.7；
- 容器运行时：Docker 23.0.6；
- 数据库镜像：PostgreSQL `16-alpine`、MySQL `8.4`；
- 控制台测试：`node:22-alpine`。

实际结果：

| 验证命令 | 结果 |
| --- | --- |
| `mvn -q -pl cm-agent-server -am -Dtest=ManagementCommandServiceTest,ApiExceptionHandlerTest,ServerRepositoryConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过 |
| `node --test cm-agent-console/src/test/js/console-core.test.cjs` | 33/33 通过 |
| `mvn -q -pl cm-agent-server -am -Dtest=ManagementCommandServiceJdbcPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 16/16 通过，PostgreSQL 与 MySQL 均无死锁 |
| `mvn -q test` | 668 个 Java 测试全部通过，0 failure、0 error、0 skipped |
| `git diff --check` | 通过 |

全仓测试包含 Flyway V1 至 V4 在 PostgreSQL 16 和 MySQL 8.4 上的迁移与 Repository 集成验证。

## 5. 数据库与兼容性影响

- 不新增、不修改任何 Flyway 迁移。
- 现有 `tool_calls(tenant_id, tool_id)` 查询能力和既有外键已经足够表达历史保护，不需要 Schema 变更。
- JDBC Agent Repository 构造函数新增 `TransactionTemplate`，由现有 JDBC 自动配置注入同一事务管理器。
- `ToolDefinitionRepository` 新增强制的 `hasToolCallHistory` 契约；现有 JDBC 和 memory 实现均已补齐，后续新增实现必须显式决定历史保护策略。
- HTTP 接口路径和请求字段不变；变化仅收紧错误语义、并发一致性和类型校验。

## 6. 风险与后续注意事项

- Agent 与工具的 JVM 条带锁只优化单实例和 memory 模式；多实例正确性依赖 JDBC 事务与数据库行锁，专项测试已用隔离类加载器模拟独立服务实例验证。
- 工具有调用历史后当前不支持物理删除，这是有意的审计保留策略。若未来需要归档，应设计显式软删除或归档状态，不能通过级联删除历史记录实现。
- 控制台基于受控中文冲突原因区分两类 `409`。如果未来调整服务端关联冲突文案，应同步修改控制台分类测试。
- 本次没有依赖升级、无关重构或数据库迁移。
