# 阶段 3 AgentScope Runtime 进度记录

| 计划项 | 状态 | 证据 | 发现的问题 | 修复结果 |
|---|---|---|---|---|
| Task 1：Core 运行契约 | 已完成 | JDK 21.0.11、Maven 3.9.4；审查修复后 Core 指定 22 个、Core 全量 52 个、Adapter 全量 2 个、Server 指定 30 个测试通过；Server `clean compile` 与全部测试源码编译通过；`git diff --check` 通过 | 独立审查发现 Adapter/Server 旧构造调用断裂、Agent 与模型 ID 未绑定、工具调用空白标识及部分上下文可绕过校验、Fake Runtime 未复用 runId；最终回归发现同时间戳审计事件的首项断言不稳定 | 已前移最小 `ModelConfigRepository` memory 契约和运行加载链路，修复全部上下文不变量与调用点；审计测试改为验证目标事件存在；JDBC Repository 仍留给 Task 2 |
| Task 2：模型配置仓储 | 已完成 | RED `21a231dff28179b03ceef6d604a364f4a04e46fb`：本地 Core 10 个测试中新增 2 个失败，Persistence 测试编译因缺少 `JdbcModelConfigRepository` 失败；Rocky 远程同 SHA 复现缺类。GREEN `2240ee8904c3e36761bb04aa33e5a6c48c7625c8`：远程 `maven:3.9.9-eclipse-temurin-21`（JDK 21.0.7）中 PostgreSQL 16 与 MySQL 8.4 指定测试 4 个、JDBC Web 测试 7 个全部通过；本地 memory/JDBC 装配与 Core 分类校验通过，Server `clean test-compile` 通过 | Task 1 前置的 Core/memory 子集导致 JDBC profile 缺少 `ModelConfigRepository` Bean；`AgentRunRequest` 对 Agent 模型 ID 和模型配置 ID 的空值缺少可分类中文消息；跨模块指定测试需设置 `surefire.failIfNoSpecifiedTests=false` | 新增固定租户条件的 `JdbcModelConfigRepository`，完成 JDBC Bean 装配与 PostgreSQL/MySQL 租户隔离覆盖，JDBC profile 上下文可启动；显式拒绝两个空 ID 并给出不同中文消息 |
| Task 3：外部模型凭据 | 已完成 | RED：JDK 21.0.11、Maven 3.9.4 下目标测试编译因缺少 `AgentScopeRuntimeProperties`、`ExternalModelCredentialProvider` 失败；GREEN：指定测试 13 个通过；配置与 profile 合理回归共 60 个测试通过；`git diff --check` 通过 | 首次 RED 命令中的未加引号 `-D` 参数被 PowerShell 错误解析，未进入预期编译阶段；安全自检要求覆盖同模型跨租户隔离、重复复合键、空值与范围、凭据列表复制、异常和字符串脱敏、fake/real 互斥 | 为 PowerShell Maven 属性参数加引号后复现正确 RED；实现复合键不可变凭据快照、完整属性校验及脱敏字符串表示，全部目标与回归测试通过 |
| Task 4：工具治理网关 | 进行中 | 待 Task 4 执行 | 尚无 | 尚无 |
| Task 5：AgentScope 模型与工具桥接 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 6：真实 ReAct 执行器 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 7：Server 装配与运行链路 | 未开始 | 尚无 | 尚无 | 尚无 |
| Task 8：文档与整体验证 | 未开始 | 尚无 | 尚无 | 尚无 |
