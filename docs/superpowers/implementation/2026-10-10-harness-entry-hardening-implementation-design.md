# Harness 2.0.2 准入问题修复实际实现

日期：2026-10-10；主题：harness-entry-hardening；修订：R0；模式：mode=default。

基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；本次未提交、未推送、未部署。

## 实际交付

E0 读取当前实验提示词和来源六文档，核对 CodeGraph、运行源码/POM/配置并保存 before-hashes.json；原十二份文档和原脏文件保持原值。

E1 在 cm-agent-agentscope-adapter 的 AgentScopeReActExecutor.permissionContext 注册无会话 HIGH 的显式 DENY，有会话继续 ASK，关闭开关继续 ALLOW。executeInternal 从 ToolResultEndEvent(DENIED) 匹配可信请求内 HIGH 定义，补未授权 ToolCallRecord；collectRecords 合并真实调用和原生拒绝，沿用 findDenied 的拒绝终态及 finally 的检查点删除规则。记录关联 WARN：errorId=runId、operation=invoke-tool、errorCode=FORBIDDEN 和可信资源上下文，不包含模型输入/输出或凭据。

权限对象测试更新 DENY 断言；新增真实 ReAct 回环 Stub 合同，覆盖业务网关零调用、无待审批请求、DENIED、拒绝记录、检查点不存在及中文原因/日志脱敏。日志 appender 在 finally 中释放，未新增依赖。

RunControllerTest 追加拒绝结果的单轮 API 合同：HTTP 200、运行状态 DENIED、固定中文原因、runId 关联、持久化详情和 AGENT_RUN/DENIED 审计保持。此处用替代运行时验证序列化及持久化，实际权限决定仍由适配层真实事件测试证明。

E2 在 Harness202ContractTest.controlled 中核对输入桥接器类型，构造私有空子目录、文件系统/消息总线/异步登记替身，构造后删除所有白名单外工具并核对原实例身份与空 Skill Repository。所有替身在 AfterEach 校验交互；仅允许 AGENTS.md 缺失探测、inboxDrain 与 findStale 空查询。新增 wait_async_results 负向模型调用，以及调用方 AGENTS/MEMORY/tools.json/skills 注入测试；原 builderOnly 用例继续证明默认旁路工具存在。

E3 当前实验直接编译工作区 api/core/adapter 源码并复用 adapter 测试，不依赖安装的旧 SNAPSHOT：107项（106通过、1外部条件跳过），其中Harness15项通过；服务端配置/Run/审批API46项通过。E4 增加本组六文档，更新配置、发布说明及两个 README。

## 与原方案/历史的差异

原实验 R0 的 G2 是缺陷特征测试，当前改为拒绝成功的合同；R0 原六文档保持历史。首次只改权限规则时，实际框架跳过桥接器导致 Run 被误映射为 SUCCEEDED，本轮增加原生拒绝记录而不放宽断言。

原 E2 希望文件系统无任何交互，实际 WorkspaceManager.validate 仍探测 AGENTS.md，因此替身仅允许合成缺失探测并校验其余交互。没有绕过真实宿主读取后再声称隔离；真实读取始终被拒绝。

G1/G5 配方仍在实验中，未新增生产 HarnessExecutor、工厂、配置模式或第二套历史。原 T2–T6 未实施，T1 完整准入仍须后续验收。

## 资源和数据影响

生产只修改适配层权限与拒绝结果映射；无接口字段、SQL/schema、配置键、正式 POM 或前端变更。检查点继续由宿主持有并只在 ASK 时保留。实验的 JUnit 临时目录、HTTP 服务与线程池由测试方清理；报告留在 target 不入 Git。

## 关联产物

- [清单](../checklists/2026-10-10-harness-entry-hardening-checklist.md)
- [提示词](../prompts/2026-10-10-harness-entry-hardening-prompt.md)
- [设计](../specs/2026-10-10-harness-entry-hardening-design.md)
- [计划](../plans/2026-10-10-harness-entry-hardening.md)
- [实现说明](../implementation/2026-10-10-harness-entry-hardening-implementation-design.md)
- [账本](../progress/2026-10-10-harness-entry-hardening-ledger.md)

