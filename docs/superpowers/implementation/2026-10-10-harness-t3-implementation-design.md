# T3 实现说明

日期：2026-10-10；主题：harness-t3；修订：R0；模式：mode=default。
Git 基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

用户明确授权推进架构 T3；来源为 [T2 提示词](../prompts/2026-10-10-harness-t2-prompt.md) 与 [架构清单](../checklists/2026-10-10-harness-architecture-checklist.md)。T2 的只读入口不限制本轮明确实施授权。历史架构、兼容实验、入口收紧、T2 共 24 份文档保持原哈希。

范围为架构 T3 的审批恢复、运行状态和停止合同；默认仍 `react`，显式 Harness 使用全部 AgentScope 2.0.2。不新增接口、配置键或数据库迁移，不改依赖、前端、MCP Server2.0.0/示例客户端0.17.0，不开展 T4–T6、提交或部署。

## 已实现

- Adapter：`AgentScopeReActExecutor` 在恢复前直接校验原 `agent_state`，在 ASK 后关闭失败时删除槽；`AgentScopeRuntimeAdapter` 更正状态槽 JavaDoc。
- Server：`ConversationService`、`ToolApprovalService` 的异常补偿共享 `closeFailedRun`；`RepositoryAgentStateStore` 支持默认槽旧格式只读探测并继续拒绝匿名写入/删除。
- 新测试：`cm-agent-server/src/test/java/com/cmagent/server/runtime/HarnessApprovalFlowTest.java` 19 项；`HarnessApprovalJdbcFlowTest.java` 继承相同合同并追加 2 项 JDBC 回滚。
- 增补测试：`AgentScopeRuntimeContractTest` 的 ASK 关闭失败，由实际 Harness 子类共享重跑；`ToolApprovalServiceTest` 的独立运行前置失败与清理异常优先级；`RepositoryAgentStateStoreTest` 的旧格式匿名探测。

审批决定提交后到整个恢复结果交付之间的异常均可触发补偿，包含原先未覆盖的决定通知和独立运行 Agent/模型前置失败；重复补偿不重复终态更新。JDBC 失败收口与状态删除共用同一事务管理器，模型恢复保持事务外。

恢复同一审批时从新 Store/Runtime/编排对象读取密文、使用同一 runId、原主体及空的新 Harness 目录。测试经生产 Spring 装配，未以 fake Runtime、Mockito Harness 或宿主能力 mock 替代生产对象；Mockito 只注入严格审计/仓储失败。

| 情况 | 可观察合同 |
|---|---|
| 会话 HIGH + 开启权限机制 | 同一 Run 暂停，批准前零工具执行；恢复不新增 USER 或重复历史 |
| 全部拒绝 | DENIED、零工具、状态删除 |
| 部分批准 | PARTIALLY_APPROVED 保留逐项决定；只执行允许项，模型正常完成时沿用 SUCCEEDED |
| 当前授权撤销/密文损坏 | FAILED、零新模型/工具执行、状态清理；不回退新运行 |
| 检查点读取数据库故障 | 原 DataAccessException 严格传播，Run 失败、零工具；清理不可用不假报成功 |
| 重复决定/并发重复 | 409 冲突，一次实际工具；历史只读 |
| 审批到期/到期与批准竞争 | EXPIRED + DENIED 或批准方正常完成，条件更新只允许一方生效 |
| 等待审批停止 | CANCELLED，PENDING 取消且版本递增，旧批准不能恢复，状态删除 |
| 恢复中停止 | 先 CANCELLING；实际工具退出才 CANCELLED，不接纳迟到模型输出 |
| 停止后严格工具审计失败 | FAILED 并传播严格异常，不能误报正常取消 |
| 无会话 Run/TEST | 非交互 HIGH 仍直接 DENIED，不隐式创建审批或会话消息 |

## 与初始方案的差异

- 初始协议桩曾错误使用 JSON 响应，按已有实际 AgentScope 合同改为 SSE；有效复现轮 8项中仅首次审批创建和决定通知两处失败，修复后通过。
- Rocky 初轮使用 Git 基线 YAML，权限机制原默认关闭，工具直接执行后请求第二条模型响应而失败。测试显式配置 `permission-enabled=true`，未修改生产默认值。
- 部分批准初始断言误按“执行授权拒绝”要求 Run DENIED。实际原行为为逐项决定保留、允许项执行、模型成功后 SUCCEEDED；已按当前合同修正测试，没有改变业务状态语义。
- 框架源码证据来自本机实际 `agentscope-core-2.0.2-sources.jar`：`loadOrCreateAgentStateForSlot` 捕获读取异常并回退空状态，`LegacyStateLoader` 继续读取 `memory_messages`；新增兼容处理据此落地，未凭历史版本推断。

## 验证与边界

本地环境：`JAVA_HOME=F:/java21`，JDK21.0.11、Maven3.9.4，实际缓存 `F:/mnt/f/java/maven-repository`。下列命令均另加 `-Dmaven.repo.local=F:/mnt/f/java/maven-repository`，PowerShell 将全部 -D 参数整体引用。

~~~powershell
mvn -B -pl cm-agent-server -am '-Dtest=HarnessApprovalFlowTest,ToolApprovalServiceTest,ChatStopFlowTest,RepositoryAgentStateStoreTest,RunControlRegistryTest,ToolApprovalControllerTest,AgentScopeHarnessRuntimeContractTest,AgentScopeRuntimeContractTest,AgentScopeHarnessBoundaryTest,AgentScopeHarnessIsolationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -pl cm-agent-server -am '-Dtest=HarnessApprovalFlowTest,RunControllerTest,RunPersistenceServiceTest,SkillTrialServiceTest,SkillRuntimeServiceTest,GovernedToolInvocationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
~~~

首个命令：Adapter81项（31 ReAct、31 实际 Harness 共享、14边界、5隔离）全部通过；Server46项全部通过，当时实际Harness17项。新增两项过期竞争/部分审批后，第二命令85项全部通过（Harness19、RunController32、RunPersistence5、SkillTrial8、SkillRuntime7、GovernedToolInvocation14）。另单独重跑当前 Harness19项全部通过。按不同测试去重，本轮本地 Adapter81、Server114项通过；不是全量测试结论。

远程独立验证区：`/root/cm-agent-harness-t3-6752bf8-20261010-1718/repo`。Git bundle 与本地基线一致，Java/SQL/POM 共 `source.sha256` 清单覆盖，`sha256sum --check` 全部通过；不复制用户 YAML 或凭据。Docker23.0.6、镜像 Maven3.9.9/JDK21.0.7，实际 Testcontainers1.21.0，PostgreSQL16-alpine/MySQL8.4。

~~~sh
docker run --rm --network host -v /root/cm-agent-harness-t3-6752bf8-20261010-1718/repo:/workspace -v /root/.m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -w /workspace -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 maven:3.9.9-eclipse-temurin-21 mvn -B -pl cm-agent-server -am -Dtest=HarnessApprovalJdbcFlowTest,ApprovalExpiryJdbcPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.test.database=mysql test
~~~

PostgreSQL 版本将 database 设为 `postgresql`。原审批过期/竞争19项在两库均通过。最后按当前源码将上述命令的 test 改为 `-Dtest=HarnessApprovalJdbcFlowTest`，PostgreSQL21项、MySQL21项均全部通过且 BUILD SUCCESS；按不同合同去重，每库40项，共80项通过。最终覆盖源码537个文件的哈希全部一致。所有容器由本轮 Testcontainers 管理，未进行全局清理。

最终两库合同日志已取回 `cm-agent-server/target/harness-t3/harness-postgresql-final.log`、`harness-mysql-final.log`；过期回归报告为 `postgres-final.log`、`mysql-final.log`。MySQL 的首次40项组合命令因部分批准的旧断言失败（其他39项通过）；断言修正后只重跑受影响的21项Harness合同，两库均绿，不把失败的组合构建写为通过。

真实外部 Provider 的断连、真实浏览器/SSE网络、非空 Skill 经 Harness 的脚本/产物链、Studio/MCP 联调和完整生产准入未执行，属于后续 T4–T6；模型只用回环协议桩。对象重建验证重新创建 Store、Runtime、RunExecutionService、ToolApprovalService，但不等于强杀进程恢复。提交决定后的崩溃不自动重放，无跨实例执行租约/停止接管。数据库不可用时补偿可能失败，仍应核对 Run/审批/检查点，不能承诺强制清理。

Rocky UTC 当前为2026-10-03，本机为2026-10-10，存在约七天偏差；本轮使用同一验证主机的时间或合成截止时间，不调整虚拟机时钟，不宣称跨主机 TTL 验收。部署需同步时钟。

## 关联产物

- [清单](../checklists/2026-10-10-harness-t3-checklist.md)
- [提示词](../prompts/2026-10-10-harness-t3-prompt.md)
- [设计](../specs/2026-10-10-harness-t3-design.md)
- [计划](../plans/2026-10-10-harness-t3.md)
- [账本](../progress/2026-10-10-harness-t3-ledger.md)

