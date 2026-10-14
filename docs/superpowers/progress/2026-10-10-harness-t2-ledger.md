# T2 Harness 生产接入账本

日期：2026-10-10；主题：harness-t2；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

| 编号 | 状态 | 工作与验收 | 依赖 | 位置与证据 |
| --- | --- | --- | --- | --- |
| H0 | 完成 | 核实当前基线、POM、配置与来源，保护原修改 | 无 | CodeGraph、before-hashes.json；原十八份 Harness 文档及无关脏文件哈希未变 |
| H1 | 完成 | 生产拒绝型宿主能力、私有空目录、持续拒绝及清理 | H0 | AgentScopeHarnessIsolation；Windows/Linux 隔离合同各5项通过 |
| H2 | 完成 | Harness包装复用治理循环、最终白名单、审批/取消/固定Skill | H1 | HarnessExecutor、ReAct生命周期、ExecutionMode；共用30项事件合同及14项构造边界通过，Skill双策略4项通过 |
| H3 | 完成 | 同版正式依赖、模式装配、排空与旧实例拒绝 | H2 | RuntimeProperties/Configuration、模式仓储、双库V21；配置10项及冲突3项通过，依赖树核实 |
| H4 | 完成 | 当前源码本地与Rocky双库验证 | H3 | Adapter146项：145通过1外部用例跳过；Core96及Server51全过；双库5项与Linux Harness49项全过 |
| H5 | 完成 | 六份工作包、生产说明与保护检查 | H4 | README、Adapter README、configuration、operations、release-notes同步，链接/格式/差异检查通过 |

当前验收证据（均为本轮源码，未用历史通过代替）：

| 场景 | 实际命令/环境 | 结果 | 报告 |
| --- | --- | --- | --- |
| 编译与本地Adapter合同 | JDK21.0.11/Maven3.9.4，mvn -B -pl cm-agent-agentscope-adapter -am test | Core96全过；Adapter146项，145通过1跳过 | cm-agent-server/target/harness-t2/adapter-test-final.log |
| 配置/Run/审批回归 | mvn -B -pl cm-agent-server -am "-Dtest=AgentScopeRuntimeConfigurationTest,RunControllerTest,ToolApprovalControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test | 51项全过：配置10、Run32、审批9 | server-test-final.log |
| 双库迁移/门禁/检查点 | ssh rocky；maven:3.9.9-eclipse-temurin-21内mvn -B -pl cm-agent-persistence -am "-Dtest=MigrationTest,JdbcRuntimeCheckpointRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test | PostgreSQL16/MySQL8.4共5项全过；V21全部表/字段注释，Run/TEST/过期孤立检查点排空，同模式重启及并发行锁通过 | persistence-test.log |
| Linux实际Harness | 同一Rocky工作区与镜像；mvn -B -pl cm-agent-agentscope-adapter -am "-Dtest=AgentScopeHarnessIsolationTest,AgentScopeHarnessBoundaryTest,AgentScopeHarnessRuntimeContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test | 49项全过：隔离5、边界14、共用事件30；符号链接清理实际执行 | harness-contract-test.log |
| Linux模式拒绝日志 | 同一镜像；mvn -B -pl cm-agent-agentscope-adapter -am "-Dtest=AgentScopeExecutionModeConflictTest" "-Dsurefire.failIfNoSpecifiedTests=false" test | 3项全过，resourceType/resourceId、errorId/errorCode与可信tenant关联且无堆栈泄露 | mode-conflict-test.log |
| 正式依赖 | mvn -B -pl cm-agent-server -am "-Dincludes=io.agentscope:*,io.modelcontextprotocol.sdk:*" -Dverbose dependency:tree | Core/Harness/OpenAI/DashScope/Studio均2.0.2，Server MCP2.0.0保持 | dependency-tree.log |
| 旧嵌入式工厂 | dependency:build-classpath后移除唯一Harness JAR，以javac/java运行ReActOptionalDependencyProbe（无模型/网络） | 构造成功且取消能力true，未出现ClassNotFound/NoClassDefFoundError | optional-react-probe.log及探针源码 |
| 最终编译 | 本地mvn -B -pl cm-agent-server -am compile；Rocky同镜像mvn -B -pl cm-agent-persistence,cm-agent-agentscope-adapter -am compile | 全部成功；最后仅补充模式SPI输入约束及事务同数据源JavaDoc，不改变已验收行为 | compile-final.log；远端同名报告 |

上表短报告路径均位于cm-agent-server/target/harness-t2/；所有生成物不跟踪。Linux49项与本地合同有重叠，不累计成独立总数。外部停止用例因CM_AGENT_EXTERNAL_STOP_TEST未设置跳过，不能写成通过。

Rocky验证基线：旧远端目录HEAD不同，另建/root/cm-agent-harness-t2-6752bf8-20261010-1638/repo；使用当前HEAD Git bundle与未提交生产模块Java/SQL/POM覆盖。HEAD=6752bf88c173dfd8fd31c58339328da9ac2fe825，558项源文件SHA-256一致，记录source-sha256.txt/hash-verification-final.log；并非声称已产生新提交。未同步无关用户配置或工作目录。Docker23.0.6，容器Maven3.9.9/JDK21.0.7。

实际失败与修复：新冲突测试初编译使用错误结果访问器，改为实际run()后全部通过；双库首次2项失败来自新测试Run ID漏一组0000，修正并增加更新行数断言，重跑5项全过。哈希清单首次包含Windows换行，改为LF后558项一致。无Harness JAR探针初次误用不存在的defaults()及未引用PowerShell点号参数，改为实际构造器和引用参数后通过；无生产代码降级或静默放行。

原文件保护：before-hashes.json对照显示原十八份Harness文档、.gitignore、Console入口、application.yml/application-mysql.yml及原无关修改未改变；既有准入修复保留。git diff --check本轮文件通过；全工作树仍有原application-mysql.yml尾部空行提示，未修改无关文件。

实际环境限制：Rocky时钟读数为2026-10-03 UTC，与本地2026-10-10不一致，tar产生未来时间戳提示；未修改虚拟机时钟，源哈希一致且本轮测试通过，不据此声称跨主机时间/审批TTL验收。Flyway仍警告MySQL8.4高于该版本已测试8.1，测试通过未消除官方支持范围差异。

遗留与下一步：T3生产审批/状态/停止竞态、T4真实Skill沙箱/产物、T5上下文、T6真实模型/浏览器/部署回退待后续。未运行全量Server/JDBC业务套件、真实Provider、Studio/MCP端到端；本轮按变更范围运行相关合同。默认仍react，原T1完整安全准入不得用本轮覆盖代替，未上线。

本轮未提交、未推送、未部署；生产文档与六份工作包生成和同步已完成。

## 关联产物

- [清单](../checklists/2026-10-10-harness-t2-checklist.md)
- [提示词](../prompts/2026-10-10-harness-t2-prompt.md)
- [设计](../specs/2026-10-10-harness-t2-design.md)
- [计划](../plans/2026-10-10-harness-t2.md)
- [实现说明](../implementation/2026-10-10-harness-t2-implementation-design.md)
- [账本](../progress/2026-10-10-harness-t2-ledger.md)
