# T2 Harness 生产接入提示词

日期：2026-10-10；主题：harness-t2；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

T2 的 H0–H5 已完成。此提示词供后续检视或明确授权的修复使用，生成/读取本文件本身不触发实施，不扩展到 T3–T6，不授权提交、推送或部署。

~~~text
仓库F:/java/cm-agent，mode=default，当前工作包R0。读取AGENTS.md及下列六份当前文档，先核实最新HEAD、脏文件与实际代码，不以旧测试报告替代新增行为验收。默认自主处理已授权范围并记录依据，不主动要求逐步确认；用户新指令优先。
F:/java/cm-agent/docs/superpowers/checklists/2026-10-10-harness-t2-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-10-harness-t2-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-10-harness-t2-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-10-harness-t2.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-10-harness-t2-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-10-harness-t2-ledger.md
来源harness-entry-hardening及harness-architecture为历史包，保持原记录。本包H0基线/H1生产隔离/H2执行器/H3装配与门禁/H4实际验证/H5文档保护已完成；当前问答只读，只有明确修复授权才改动相应内容。
io.agentscope全部保持2.0.2，Adapter依赖optional、Server显式装配；MCP Server2.0.0/示例客户端0.17.0及其他厂商版本保持。旧create默认ReAct，接口三参数函数式契约和自定义Runtime兼容；取消能力显式声明。
HarnessLifecycle复用现有治理循环，通过真实包装streamEvents消费事件。关闭原生宿主能力，最终工具全集与实例身份只允许当前CM治理桥接器。每次运行及恢复使用独占私有空目录，必要空探测之外的文件/总线/异步调用持续拒绝，即使框架捕获也不能成功；只清理自有目录，不跟随链接。审批状态仍由同一宿主Store持有。
execution-mode只支持react/harness，默认react。V21系统模式行锁与排空检查共用短事务；非终态Run、TEST及全部检查点阻塞变化，相同模式重启允许。Run登记后凭据读取前每次核对，旧实例模式冲突DENIED，无模型/工具调用；数据库读失败不得默认放行。禁止副作用后自动切策略重跑。
保持tenant:principal/runId、固定Skill快照、二次授权、严格审计、加密状态/产物和停止门控；不新增请求策略字段、长期历史、在线Agent灰度、Shell/Web/子Agent/动态Skill或原生Skill源。
本地JDK21：mvn -B -pl cm-agent-agentscope-adapter -am test；mvn -B -pl cm-agent-server -am "-Dtest=AgentScopeRuntimeConfigurationTest,RunControllerTest,ToolApprovalControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test。按新增行为选择必要回归，不无故重复。
Docker/JDBC/Flyway/Testcontainers仅ssh rocky，在maven:3.9.9-eclipse-temurin-21验证PostgreSQL16/MySQL8.4。新隔离工作区核对HEAD与未提交覆盖源码哈希；执行mvn -B -pl cm-agent-persistence -am "-Dtest=MigrationTest,JdbcRuntimeCheckpointRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test。不能覆盖无关远端项目或全局清理。
当前本地Adapter146项中145通过1外部停止用例跳过，Core96/Server51通过；Rocky双库5项、实际Harness49项通过。旧工厂缺Harness JAR探针通过。真实模型/浏览器/Studio/MCP和完整生产准入未验收，T3–T6不属于本包完成结论。
所有新增代码注释/文档/SQL原生注释为中文。失败应保留严格优先级、关联编号与脱敏原因；修复后记录实际命令及结果。保护原脏文件、凭据和旧历史，报告只放target。同步本包六文档与生产说明；最终按仓库要求说明变更、验证、影响、风险及下一步，未提交明确写出。
~~~

## 关联产物

- [清单](../checklists/2026-10-10-harness-t2-checklist.md)
- [提示词](../prompts/2026-10-10-harness-t2-prompt.md)
- [设计](../specs/2026-10-10-harness-t2-design.md)
- [计划](../plans/2026-10-10-harness-t2.md)
- [实现说明](../implementation/2026-10-10-harness-t2-implementation-design.md)
- [账本](../progress/2026-10-10-harness-t2-ledger.md)
