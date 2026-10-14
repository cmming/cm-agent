# Harness 2.0.2 准入问题修复与后续提示词

日期：2026-10-10；主题：harness-entry-hardening；修订：R0；模式：mode=default。

基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；本次未提交、未推送、未部署。

本文件记录已交付的 E0–E4 及后续门禁。读取文档进行问答不等于执行；新生产接入任务须按用户明确要求确定范围，不因提示词生成自动启动。

~~~text
仓库 F:/java/cm-agent，mode=default。读取 AGENTS.md、本包六份文档和来源实验包；先核实当前 HEAD、脏文件、POM 与 CodeGraph，不沿用旧通过代替新验证。所有新增注释/文档为中文，原无关修改保持。

用户最新版本决定是全部 io.agentscope 构件保持 2.0.2。Server MCP2.0.0、独立客户端示例0.17.0及其他厂商版本不得改为2.0.2；Harness仍只位于独立experiments模块，生产默认ReAct。

E0–E4已完成：无会话且permission-enabled=true的HIGH规则为显式DENY；框架原生拒绝没有进入桥接器，因此按可信工具定义补记未授权记录，Run返回DENIED与固定中文原因，终态删除检查点。持久化会话HIGH保留ASK，permission-enabled=false保持原策略，所有实际工具调用仍须治理网关二次授权。DONT_ASK不覆盖显式ASK。日志用errorId=runId关联、FORBIDDEN原因码，不回显原始参数或凭据。

本轮任务编号保持：E0基线核对、E1无审批入口权限与拒绝终态、E2实验白名单和宿主隔离、E3实际回归、E4文档与历史保护；只对本轮范围标记完成，不代表原T1–T6完成。

独立Harness受控构造只接受CM Agent工具/固定Skill桥接器。构建后过滤全部白名单外工具，核对实例身份，默认wait_async_results不得进入模型清单。每次构建私有空目录；文件系统替身仅返回AGENTS.md不存在，其他操作断言拒绝；消息总线仅空inboxDrain、异步登记仅空findStale，其他操作均验收失败。宿主AGENTS/MEMORY/tools.json/共享skills注入负向通过。Mockito替身是实验手段，不能复制到生产。

当前clean test107项，106通过1跳过（CM_AGENT_EXTERNAL_STOP_TEST未设置），Harness15项全过。服务端配置/Run/审批API46项全过；这些配置/API测试使用隔离或替代运行时，不证明生产Harness端到端。JDK21.0.11/Maven3.9.4。原十二份分析/实验文档保持历史。

如授权重复本轮回归，使用JDK21执行：
mvn -B -f experiments/harness-compatibility-2.0.2/pom.xml clean test
mvn -B -pl cm-agent-server -am "-Dtest=AgentScopeRuntimeConfigurationTest,RunControllerTest,ToolApprovalControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test
按新增改动选择必要测试，不无故全量重复；报告放target，不提交生成物。

如用户明确授权生产接入，从原T2范围建立独立工作包，引用本包；先实现生产受控文件系统/空消息总线/异步登记与清理、最终工具白名单和生命周期，再完成模式装配、默认react、未知模式拒绝、自定义Runtime兼容、配置切换排空门禁。T1完整安全合同不通过不得进入生产。随后按T3审批/状态/停止、T4固定Skill/沙箱/产物、T5可信上下文、T6真实验收推进，不隐式扩展候选能力或以本轮绿色测试宣称生产可用。

当前Adapter仍零Spring/JDBC运行依赖，AgentScope依赖optional。状态历史唯一来源、tenant:principal/runId身份、原Run恢复、二次授权、严格审计失败、终态清理与停止信号保持。没有副作用后自动切ReAct重跑，没有第二套长期历史。

Docker/JDBC/Flyway/Testcontainers只ssh rocky，以项目规定容器/JDK21并核对远程提交；本轮无此类改动。真实模型需运行时凭据，不得保存/记录；确切缺口记账。连续修复同步当前六文档，新独立任务新建工作包。不自动提交、推送或部署。

本包路径：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-10-harness-entry-hardening-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-10-harness-entry-hardening-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-10-harness-entry-hardening-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-10-harness-entry-hardening.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-10-harness-entry-hardening-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-10-harness-entry-hardening-ledger.md
~~~

## 关联产物

- [清单](../checklists/2026-10-10-harness-entry-hardening-checklist.md)
- [提示词](../prompts/2026-10-10-harness-entry-hardening-prompt.md)
- [设计](../specs/2026-10-10-harness-entry-hardening-design.md)
- [计划](../plans/2026-10-10-harness-entry-hardening.md)
- [实现说明](../implementation/2026-10-10-harness-entry-hardening-implementation-design.md)
- [账本](../progress/2026-10-10-harness-entry-hardening-ledger.md)

