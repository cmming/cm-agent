# Harness 2.0.2 隔离运行实验

本文记录2026-10-10兼容实验及入口收紧阶段的历史结果。后续正式Harness接入和T2–T6验收已完成，最新状态见[模块说明](../../docs/harness-module.md)。本工程不加入根reactor，但会编译当前工作区源码；重新执行时须记录实际结果，不能沿用下面的历史测试数量。

本工程继承项目的AgentScope2.0.2版本管理，使用工作区api/core/adapter源码和adapter测试。该阶段包含真实Harness事件流的15项合同；当时生产模块仍使用ReAct，正式Harness尚未接入。

~~~powershell
$env:JAVA_HOME='F:/java21'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
java -version
mvn -v
mvn -B -f experiments/harness-compatibility-2.0.2/pom.xml clean test
mvn -B dependency:tree "-Dincludes=io.agentscope:*,io.modelcontextprotocol.sdk:*" -Dverbose
mvn -B -f experiments/harness-compatibility-2.0.2/pom.xml dependency:tree -Dverbose
~~~

该阶段准入修复后的clean test为107项：106通过、1项外部停止测试按环境条件跳过，其中Harness15项全部通过。只有回环模型Stub；不代表真实模型、服务端Harness、数据库或容器验收。原实验R0的104项结果保留在旧账本；两次历史结果都不代替当前工作区重新运行的结果。

G1：关闭 Builder 默认能力后仍有 `wait_async_results`，保留原始构造用例作为缺陷特征。受控构造在模型调用前移除所有白名单外工具，并验证白名单内仍是原 CM Agent 治理桥接器实例；模型请求异步等待工具不会进入网关。

G2：当前生产权限配方已改为无会话 HIGH 显式 DENY，合同验证不产生确认事件或业务调用。生产适配器同步补充原生拒绝记录、`DENIED` 终态和检查点清理；不能把 `DONT_ASK` 当作覆盖显式 ASK 的开关。

G5：受控构造每次创建独立空目录；文件系统替身只允许返回合成的 `AGENTS.md` 不存在，其他文件操作在清理断言中拒绝。消息总线只允许空收件箱查询，异步任务注册表只允许空过期查询；即使框架吞掉异常，额外操作也会被清理断言发现。注入调用方目录中的 AGENTS、MEMORY、tools.json 与共享 Skill 后，模型输入无对应标记，宿主文件无变更或新增。以上替身仅用于实验，生产必须实现并验证对应拒绝策略和生命周期。

fromAgent复制Toolkit容器但保留桥接器实例和共享门控；每个Run必须创建新桥接器。显式Store在关闭后仍可恢复ASK，终态由CM Agent删除；取消通过delegate.interrupt(context)，Harness2.0.2无该重载。该阶段的生产执行器、模式装配、固定Skill脚本、真实模型和双库恢复属于后续T2–T6，现已由正式阶段账本记录限定验收。

该阶段修复详见[实施账本](../../docs/superpowers/progress/2026-10-10-harness-entry-hardening-ledger.md)及其六份工作包；[原实验账本](../../docs/superpowers/progress/2026-10-10-harness-202-compatibility-ledger.md)和原R1分析包保持历史。当时生产Harness准入未完成；本次提交保存实验源码和记录，不将实验配方或测试替身用作生产实现。
