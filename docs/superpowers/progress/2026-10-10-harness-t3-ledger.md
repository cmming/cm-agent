# T3 进度账本

日期：2026-10-10；主题：harness-t3；修订：R0；模式：mode=default。
Git 基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

用户明确授权推进架构 T3；来源为 [T2 提示词](../prompts/2026-10-10-harness-t2-prompt.md) 与 [架构清单](../checklists/2026-10-10-harness-architecture-checklist.md)。T2 的只读入口不限制本轮明确实施授权。历史架构、兼容实验、入口收紧、T2 共 24 份文档保持原哈希。

| 编号 | 状态 | 交付与验收 | 依赖 |
|---|---|---|---|
| A0 | 完成 | 核对当前 T2、AgentScope 2.0.2、JDK 21、Git 与脏文件，保留历史 | 无 |
| A1 | 完成 | 审批创建/通知/恢复前置失败关闭 Run 与清理状态；恢复读取及 ASK 关闭失败严格传播 | A0 |
| A2 | 完成 | 实际 Harness、加密 Store、编排对象重建、原 Run、单次/多轮/重复/部分审批、撤权与历史 | A1 |
| A3 | 完成 | 等待停止、恢复停止、停止先于模型准入、实际工具退出、严格审计、过期竞态、Web 错误/权限 | A1 |
| A4 | 完成 | Rocky PostgreSQL16/MySQL8.4 的实际 Harness 合同、回滚、到期及竞态，当前源码哈希一致 | A2、A3 |
| A5 | 完成 | 六文档、生产说明、差异和敏感信息检查，报告真实验收边界 | A0–A4 |

## 本地实际验证

本地环境：`JAVA_HOME=F:/java21`，JDK21.0.11、Maven3.9.4，实际缓存 `F:/mnt/f/java/maven-repository`。下列命令均另加 `-Dmaven.repo.local=F:/mnt/f/java/maven-repository`，PowerShell 将全部 -D 参数整体引用。

~~~powershell
mvn -B -pl cm-agent-server -am '-Dtest=HarnessApprovalFlowTest,ToolApprovalServiceTest,ChatStopFlowTest,RepositoryAgentStateStoreTest,RunControlRegistryTest,ToolApprovalControllerTest,AgentScopeHarnessRuntimeContractTest,AgentScopeRuntimeContractTest,AgentScopeHarnessBoundaryTest,AgentScopeHarnessIsolationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -pl cm-agent-server -am '-Dtest=HarnessApprovalFlowTest,RunControllerTest,RunPersistenceServiceTest,SkillTrialServiceTest,SkillRuntimeServiceTest,GovernedToolInvocationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
~~~

首个命令：Adapter81项（31 ReAct、31 实际 Harness 共享、14边界、5隔离）全部通过；Server46项全部通过，当时实际Harness17项。新增两项过期竞争/部分审批后，第二命令85项全部通过（Harness19、RunController32、RunPersistence5、SkillTrial8、SkillRuntime7、GovernedToolInvocation14）。另单独重跑当前 Harness19项全部通过。按不同测试去重，本轮本地 Adapter81、Server114项通过；不是全量测试结论。

原始报告保存在 `cm-agent-server/target/harness-t3/local-final-contract.log`、`local-harness-final.log`、`local-regression-accepted.log`，全部为本轮实际运行。曾失败的试验报告保留，不能当作通过证据。

## Rocky 实际验证

远程独立验证区：`/root/cm-agent-harness-t3-6752bf8-20261010-1718/repo`。Git bundle 与本地基线一致，Java/SQL/POM 共 `source.sha256` 清单覆盖，`sha256sum --check` 全部通过；不复制用户 YAML 或凭据。Docker23.0.6、镜像 Maven3.9.9/JDK21.0.7，实际 Testcontainers1.21.0，PostgreSQL16-alpine/MySQL8.4。

~~~sh
docker run --rm --network host -v /root/cm-agent-harness-t3-6752bf8-20261010-1718/repo:/workspace -v /root/.m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -w /workspace -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 maven:3.9.9-eclipse-temurin-21 mvn -B -pl cm-agent-server -am -Dtest=HarnessApprovalJdbcFlowTest,ApprovalExpiryJdbcPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.test.database=mysql test
~~~

PostgreSQL 版本将 database 设为 `postgresql`。原审批过期/竞争19项在两库均通过。最后按当前源码将上述命令的 test 改为 `-Dtest=HarnessApprovalJdbcFlowTest`，PostgreSQL21项、MySQL21项均全部通过且 BUILD SUCCESS；按不同合同去重，每库40项，共80项通过。最终覆盖源码537个文件的哈希全部一致。所有容器由本轮 Testcontainers 管理，未进行全局清理。

最终两库合同日志已取回 `cm-agent-server/target/harness-t3/harness-postgresql-final.log`、`harness-mysql-final.log`；过期回归报告为 `postgres-final.log`、`mysql-final.log`。MySQL 的首次40项组合命令因部分批准的旧断言失败（其他39项通过）；断言修正后只重跑受影响的21项Harness合同，两库均绿，不把失败的组合构建写为通过。

## 修复与取舍记录

- 初始协议桩曾错误使用 JSON 响应，按已有实际 AgentScope 合同改为 SSE；有效复现轮 8项中仅首次审批创建和决定通知两处失败，修复后通过。
- Rocky 初轮使用 Git 基线 YAML，权限机制原默认关闭，工具直接执行后请求第二条模型响应而失败。测试显式配置 `permission-enabled=true`，未修改生产默认值。
- 部分批准初始断言误按“执行授权拒绝”要求 Run DENIED。实际原行为为逐项决定保留、允许项执行、模型成功后 SUCCEEDED；已按当前合同修正测试，没有改变业务状态语义。
- 框架源码证据来自本机实际 `agentscope-core-2.0.2-sources.jar`：`loadOrCreateAgentStateForSlot` 捕获读取异常并回退空状态，`LegacyStateLoader` 继续读取 `memory_messages`；新增兼容处理据此落地，未凭历史版本推断。

## 差异保护

前置快照 `cm-agent-server/target/harness-t3/before-hashes.json`；当前仅11个既有文件和8个新文件属于T3。前四组24份Harness文档、用户 application YAML、根/模块POM、console入口、.gitignore等原有内容哈希未变化。所选修改 `git diff --check` 通过；原始 RuntimeContractTest 混合行尾，只修正本次新增行的行尾空白，未批量转换旧内容。

真实外部 Provider 的断连、真实浏览器/SSE网络、非空 Skill 经 Harness 的脚本/产物链、Studio/MCP 联调和完整生产准入未执行，属于后续 T4–T6；模型只用回环协议桩。对象重建验证重新创建 Store、Runtime、RunExecutionService、ToolApprovalService，但不等于强杀进程恢复。提交决定后的崩溃不自动重放，无跨实例执行租约/停止接管。数据库不可用时补偿可能失败，仍应核对 Run/审批/检查点，不能承诺强制清理。

Rocky UTC 当前为2026-10-03，本机为2026-10-10，存在约七天偏差；本轮使用同一验证主机的时间或合成截止时间，不调整虚拟机时钟，不宣称跨主机 TTL 验收。部署需同步时钟。

## 关联产物

- [清单](../checklists/2026-10-10-harness-t3-checklist.md)
- [提示词](../prompts/2026-10-10-harness-t3-prompt.md)
- [设计](../specs/2026-10-10-harness-t3-design.md)
- [计划](../plans/2026-10-10-harness-t3.md)
- [实现说明](../implementation/2026-10-10-harness-t3-implementation-design.md)

