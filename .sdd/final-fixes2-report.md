# 工具管理最终修复（二）报告

## 1. 修复范围

本次基于 `c11c7ad` 收口最终审查发现的五类竞态和输入边界：

1. `PUT /api/tools/{id}` 不再在更新命令提交并成功审计后重新查询摘要。更新命令返回本次提交的工具定义、HTTP 配置和 MCP 发布状态快照，Controller 直接构造响应；同一工具的后续更新或删除不会污染前一个请求的响应，也不会把已经成功的更新误报为“已更新工具未找到”。
2. 工具删除采用安全软删除。管理、授权、调试和 MCP 查询立即看不到墓碑工具，HTTP 配置、MCP 发布与授权仍按原事务删除，但 `tool_definitions` 行继续作为 `tool_calls` 外键锚点。删除前已开始、删除后才持久化的调用可以完整进入运行历史。
3. 控制台解除关联完成时，仅在原 Agent 仍被选中时刷新其详情，不会把用户强制切回旧 Agent。
4. 工具 A 保存期间若用户已开始编辑工具 B，A 的完成回调不会清空 B 的表单或覆盖 B 的选择。
5. HTTP 工具 `secretHeaders` 的空键、null 键或 null 值通过容器元素校验返回 `400 Bad Request`，不再由 `Map.copyOf` 抛出空指针并返回 `500`。

## 2. 根因与设计

### 2.1 PUT 响应摘要竞态

旧流程在命令事务结束、`TOOL_UPDATE` 审计写入成功并释放工具锁后，Controller 再通过 `ToolQueryService.findByTenantAndId` 发起一次独立查询。第二个更新或删除可以插入这两个步骤之间，使第一个请求返回第二个请求的数据，或因查不到工具而返回 `500`。

修复后 `ManagementCommandService.updateTool` 返回 `ToolUpdateResult`。该结果持有事务中实际写入的 `ToolDefinition`、`HttpToolConfig` 和 MCP 发布状态，Controller 只进行无仓储读取的摘要转换。MockMvc 并发测试在第一个命令返回后暂停其响应、完整执行第二个 PUT，再放行第一个请求，分别断言两个响应只包含各自提交的名称。

### 2.2 运行中 ToolCall 与删除竞态

旧删除流程只能检测已经存在于 `tool_calls` 表或内存列表中的历史。运行时已经执行工具、但 Run 完成阶段尚未保存 ToolCall 时，管理员可以先解除 Agent 关联再物理删除工具；随后 ToolCall 写入触发数据库外键失败，memory 模式也会因工具不存在拒绝批次，导致运行完成持久化失败和历史丢失。

本次没有引入仅对单 JVM 有效的活动计数，也没有增加需要租约回收的跨实例活动调用表，而是将既有 `ToolDefinitionRepository.delete` 契约收紧为“从管理面删除但保留历史引用锚点”：

- JDBC 新增 `deleted_at` 和 `deleted_name`。删除保存原名称副本，将活动名称替换为内部唯一墓碑名、禁用工具并记录删除时间；原名称立即可复用。
- JDBC 工具定义的更新、普通查询、锁定查询和租户列表全部要求 `deleted_at IS NULL`。
- JDBC HTTP 配置和 MCP 发布仓储的工具锁定与查询增加活动工具过滤；MCP catalog 最终解析工具时也继续经过活动工具查询。
- memory 模式保留 `tools` 中的定义作为 ToolCall 引用锚点，同时以 `deletedToolIds` 从管理查询和更新中隐藏；ToolCall 批次仍可校验同租户墓碑定义。
- 已有调用历史的工具仍返回原有明确 `409 Conflict`，不会删除历史、审计或旧 Flyway 迁移。

### 2.3 控制台异步完成覆盖

旧的解除关联完成回调无条件调用 `selectAgent(agent.id)`；用户期间选中另一个 Agent 后仍会被旧回调切回。旧的工具保存完成回调也无条件重置表单；用户期间从 A 切换到 B 后，A 的响应会清空 B 的编辑内容。

修复通过两个纯函数判断完成回调是否仍拥有目标状态，并在 `app.js` 中按当前选择/编辑 ID 接线。异步请求仍会刷新共享工具列表，但不会夺回用户已经改变的 Agent 或表单上下文。

## 3. 数据库迁移影响

- 新增 `V5__soft_delete_tool_definitions.sql`，为 `tool_definitions` 增加可空的 `deleted_at TIMESTAMP`、`deleted_name VARCHAR(160)`，以及 `(tenant_id, deleted_at)` 索引。
- 不修改 V1 至 V4，不删除 `tool_calls`、`audit_events` 或任何既有历史。
- 活动工具仍使用原有 `(tenant_id, name)` 唯一索引；软删除时把原名保存到 `deleted_name`，活动 `name` 改为 UUID 驱动的内部墓碑名，从而允许重新创建同名工具。
- PostgreSQL 16 和 MySQL 8.4 使用同一份兼容 SQL；升级时由既有 Flyway 链路执行。

## 4. TDD 证据

生产实现前新增回归测试并实际观察到失败：

- `ToolControllerTest.concurrentHttpUpdatesEachReturnTheirOwnCommittedSnapshot`：第一个响应期望 `orders-first-update`，旧实现实际返回 `orders-second-update`。
- `ToolControllerTest.httpCreateRejectsNullSecretHeaderValueAndBlankKeyAsBadRequest`：旧实现对 null value 返回 `500`，期望 `400`。
- memory 运行中调用测试在旧实现删除定义后无法保存 ToolCall。
- PostgreSQL/MySQL 运行中调用测试在旧实现中缺少 V5 墓碑列，且物理删除后 ToolCall 外键无法满足。
- 控制台测试在旧实现中缺少状态所有权判断函数及接线。

实现后，本地 JDK 21 的 Controller、命令服务、查询服务和控制台资源专项均已转绿。数据库和全仓结果见下一节。

## 5. 验证结果

本地环境：Temurin 21.0.11、Maven 3.9.4。涉及 Docker/Testcontainers 的验证未在本机执行。

远程环境：Rocky Linux 9.3、Docker 23.0.6；构建容器为 `maven:3.9.9-eclipse-temurin-21`（Maven 3.9.9、Java 21.0.7），数据库镜像为 PostgreSQL `16-alpine` 和 MySQL `8.4`，控制台测试使用 `node:22-alpine`。远程验证前已确认工作区 HEAD 与本地候选提交 `643944d7cc7791409693d058bee85acf4e97b8ba` 完全一致且工作区干净。

| 验证命令 | 结果 |
| --- | --- |
| `mvn -q -pl cm-agent-server -am -Dtest=ToolControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 22/22 通过 |
| `mvn -q -pl cm-agent-server -am -Dtest=ManagementCommandServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过，含 memory 运行中 ToolCall 删除竞态 |
| `mvn -q -pl cm-agent-server -am -Dtest=ManagementCommandServiceTest,ToolControllerTest,ToolQueryServiceTest,ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过 |
| `docker run --rm ... node:22-alpine node --test cm-agent-console/src/test/js/console-core.test.cjs` | 35/35 通过 |
| `mvn -q -pl cm-agent-server -am -Dtest=ManagementCommandServiceJdbcPersistenceTest,MigrationTest,JdbcToolDefinitionRepositoryTest,JdbcHttpToolConfigRepositoryTest,JdbcMcpToolPublicationRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test` | 32/32 通过；PostgreSQL 16.14、MySQL 8.4 均完成 V1→V5，0 failure、0 error |
| `mvn -q test` | 673 个 Java 测试全部通过，0 failure、0 error、0 skipped；耗时 276.1 秒 |
| `git diff --check` | 通过 |

第一次全仓远程命令因外层 SSH 命令 124 秒时限被中止，期间没有产生有效的 Maven 最终结果；随后提高外层时限并从头重跑，以上 673 项和退出码 0 均来自完整重跑，不将中止轮次计入通过证据。

## 6. 风险与注意事项

- 软删除是有意的历史完整性边界：管理 API 返回删除成功后，数据库仍保留不可见的工具定义墓碑。运维统计物理行数时应使用 `deleted_at IS NULL` 区分活动工具。
- 删除事务仍先移除 HTTP 配置、MCP 发布和残余授权；墓碑只保留 ToolCall 外键所需的工具定义，不允许新的管理、授权、调试或 MCP 调用。
- `ToolDefinitionRepository` 的第三方实现必须遵守新的删除契约；若实现支持持久化运行历史，就不能在删除返回后让已开始调用的历史写入失去引用目标。
- 本次无依赖升级，不改变 REST 路径或 JSON 成功响应字段；`secretHeaders` 只收紧无效输入行为。
