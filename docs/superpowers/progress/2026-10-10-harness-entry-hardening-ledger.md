# Harness 2.0.2 准入问题修复账本

日期：2026-10-10；主题：harness-entry-hardening；修订：R0；模式：mode=default。

基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；本次未提交、未推送、未部署。

## 状态与实际证据

| 编号 | 状态 | 实际完成内容 |
| --- | --- | --- |
| E0 | 完成 | 当前来源/源码/配置/HEAD 与 JDK21/Maven核对，原文件哈希保存 |
| E1 | 完成 | 无会话 HIGH 显式 DENY、原生拒绝记录/终态/检查点、日志关联与脱敏回归 |
| E2 | 完成 | 最终工具白名单、桥接器身份、私有目录、限定文件/总线/异步登记交互，宿主注入负向实验 |
| E3 | 完成 | 当前源码隔离 clean test、选定服务端 API/装配回归 |
| E4 | 完成 | 六文档、配置/发布/两个README、历史哈希与差异/链接核对 |

## 实际命令和结果

~~~powershell
$env:JAVA_HOME='F:/java21'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
java -version
mvn -v
mvn -B -pl cm-agent-agentscope-adapter -am test
mvn -B -f experiments/harness-compatibility-2.0.2/pom.xml clean test
mvn -B -pl cm-agent-server -am "-Dtest=AgentScopeRuntimeConfigurationTest,RunControllerTest,ToolApprovalControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test
~~~

- Java/Maven：Java 21.0.11，Maven3.9.4，Maven运行于 F:/java21。
- Adapter初轮回归成功（包含新增无会话拒绝用例）；最终加严日志断言后由独立实验重新编译并执行全部adapter测试。
- 最终隔离 clean test：2026-10-10 15:33:44结束，107项，0失败/0错误/1跳过，BUILD SUCCESS。其中 adapter92项与Harness15项，106项通过。
- 外部跳过：AgentScopeExternalStopTest，未设置 CM_AGENT_EXTERNAL_STOP_TEST；没有真实模型凭据，不把条件跳过记成真实停止通过。
- 服务端最终选定回归：2026-10-10 15:41:47结束，46项全过，BUILD SUCCESS；配置5、Run API32、审批API9。显式关闭 Studio，使用非容器测试与替代运行时，仅证明既有装配/API合同不退化，不证明Harness已服务端接入。
- 总计153个不同用例，152通过/1跳过。没有做全量测试、打包部署、真实模型、浏览器、Studio/MCP外部、沙箱或双库恢复测试；本轮没有对应生产接入/数据库/容器改动。

## 问题定位与处理

首次无会话规则改为DENY后，实际Stub测试发现Run仍SUCCEEDED：原生拒绝未进入桥接器，已有收集器遗漏记录。补原生拒绝事件记录和终态映射后通过；不能只用权限对象断言证明修复。

首次拒绝文件系统实验发现构造器必然调用WorkspaceManager.validate的AGENTS.md exists探测。替身只允许返回合成缺失；额外操作由清理断言拒绝。后续日志断言发现启动/结束INFO也在appender中，将拒绝断言限定为WARN并保留脱敏断言，实际运行结果不变。

补充 Run API 合同验证 HTTP200 的拒绝 Run、固定中文原因、runId/持久化与审计。早期服务端回归观察到测试 profile 连接本地 Studio，保留当次日志；最终显式以系统属性关闭 Studio 后重新验证，不把附带连接作为 Studio 验收证据。

报告位于 cm-agent-server/target/harness-entry-hardening/，包含权限初次失败、Adapter/实验/服务端日志及哈希记录；target和实验target/surefire-reports均不提交。原2.0.4中断记录和2.0.2实验R0历史不覆盖。

## 影响与遗留门禁

G2已在生产ReAct权限配方修复；G1/G5仅实验配方验收，Mockito替身不能用于生产。AgentScope版本保持2.0.2，正式POM和原脏配置/控制台文件未变。无新接口字段/配置键/schema迁移；release-notes已更新。

原分析R1与实验R0十二文档哈希保持。T2生产工厂/执行器与模式、T3审批/状态/停止、T4固定Skill脚本/沙箱/产物、T5可信上下文、T6真实验收仍待做；原T1全量准入未宣称通过。下一步建立T2独立实施包，先实现并测试生产隔离对象与生命周期，再装配执行模式；不得直接上线实验构造。

提交信息：未提交、未推送、未部署；不自动清理无关工作区改动。

## 关联产物

- [清单](../checklists/2026-10-10-harness-entry-hardening-checklist.md)
- [提示词](../prompts/2026-10-10-harness-entry-hardening-prompt.md)
- [设计](../specs/2026-10-10-harness-entry-hardening-design.md)
- [计划](../plans/2026-10-10-harness-entry-hardening.md)
- [实现说明](../implementation/2026-10-10-harness-entry-hardening-implementation-design.md)
- [账本](../progress/2026-10-10-harness-entry-hardening-ledger.md)

