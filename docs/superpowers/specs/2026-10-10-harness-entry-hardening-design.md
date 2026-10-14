# Harness 2.0.2 准入问题修复设计

日期：2026-10-10；主题：harness-entry-hardening；修订：R0；模式：mode=default。

基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；本次未提交、未推送、未部署。

## 背景与目标

实验 R0 证明关闭默认能力仍注册 wait_async_results，DONT_ASK 仍可能因显式 HIGH/ASK 暂停；另外框架还会访问消息总线、探测 AGENTS.md 并可能扫描共享 Skill。本轮在保持版本与生产执行架构的条件下修复无审批入口的真实运行行为，补强隔离实验，为 T2 提供可核对配方。

## 范围和方案

E0 以当前 POM、配置、源码与 CodeGraph 为证据，保护原脏文件和历史文档。E1 的权限矩阵如下，所有实际工具调用仍经可信治理网关：

| permission-enabled | 可信会话 | 风险 | 框架规则 |
| --- | --- | --- | --- |
| false | 有/无 | 全部 | ALLOW，兼容既有策略 |
| true | 有 | HIGH | ASK，原 Run 审批恢复 |
| true | 无 | HIGH | DENY，无待审批请求 |
| true | 有/无 | LOW/MEDIUM | ALLOW，网关二次授权 |

AgentScope 2.0.2 PermissionEngine 的显式规则优先于模式默认决策，必须用 DENY。原生拒绝在工具桥接器之前发生，E1 在 ToolResultEndEvent(DENIED) 按当前可信请求匹配 HIGH 定义，产生未授权拒绝记录并加入终态映射；没有实际调用时输入/输出为空、耗时未知。即使框架随后输出文本也返回 DENIED，沿用宿主终态删除规则。安全拒绝记录 WARN，关联 errorId=runId，不将框架英文信息、模型参数、凭据或内部 URL 输出给用户。

E2 在独立实验中构造新 Toolkit 与私有空目录，先核对输入工具全部为当前治理桥接器，再在 Builder 后清除任何白名单外工具，校验白名单与实例身份。文件系统只提供合成 AGENTS.md 缺失探测，消息总线与异步登记仅空查询；清理断言核对额外操作，避免框架捕获异常后掩盖旁路。毒化调用方配置/记忆/共享 Skill 的负向实验验证输入和文件无污染。每次运行拥有独立桥接器，临时目录由 JUnit 在资源关闭后清理。

E3 覆盖实际框架事件流、无审批入口 DENIED/检查点/日志脱敏、已有 ASK/恢复/二次授权/取消/严格失败合同，以及服务端配置/API 回归。E4 落地六文档及生产说明，区分实验历史和当前状态。

## 非目标与取舍

本轮没有生产 Harness 工厂/执行器、模式配置、数据库迁移、前端改动或依赖升级；不开放动态 Skill、Shell、子 Agent、持久化 workspace memory。消息总线和文件系统使用测试替身以证明交互合同，生产实现仍是 T2 前置工作，不能把替身当作上线能力。

保持 permission-enabled=false 兼容默认值；不通过降低授权或关闭审计完成回归。原 T1 完整准入、T2–T6 不标记完成。本轮修改未解决生产 Harness 的端到端接入问题。

## 验收

清单 E0–E4 是本轮验收依据。当前 107 项隔离测试（含 Harness15）和46项服务端回归通过，外部停止1项按环境条件跳过。真实模型、浏览器、双库/容器、生产Harness未执行；新文档明确这些边界。

## 关联产物

- [清单](../checklists/2026-10-10-harness-entry-hardening-checklist.md)
- [提示词](../prompts/2026-10-10-harness-entry-hardening-prompt.md)
- [设计](../specs/2026-10-10-harness-entry-hardening-design.md)
- [计划](../plans/2026-10-10-harness-entry-hardening.md)
- [实现说明](../implementation/2026-10-10-harness-entry-hardening-implementation-design.md)
- [账本](../progress/2026-10-10-harness-entry-hardening-ledger.md)

