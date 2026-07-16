# 中文路线图

本文档记录 CM Agent 从当前阶段3到阶段5的交付边界。阶段3已交付，阶段4和阶段5尚未交付；本路线图中的后续内容不是当前版本的能力承诺。

## 阶段 2：生产持久化与安全收口（已完成）

阶段2完成后，服务端形成可审计、可隔离的生产持久化基线：

- Run、ToolCall、Audit 接入 JDBC Repository；Flyway 保留已发布的 V1，并新增 V2、V3 查询索引迁移。
- Run、ToolCall、Audit 的读写均按认证主体 tenant 隔离，查询提供有界 cursor 分页。
- 审计写入采用严格失败语义；审计依赖不可用时保留异常并返回 `503`，错误与运行数据经过敏感信息脱敏。
- JWT secret、profile、bootstrap admin、错误响应和配置注入完成生产边界收口；生产 profile 必须 JDBC，禁用 bootstrap admin 和开发 JWT fallback。
- 运行持久化分为启动和完成两段：启动阶段记录 `RUNNING` 与启动审计，完成阶段更新 Run、写入 ToolCall 并记录完成/失败审计。

阶段2保留的 fake runtime 继续仅用于本地和测试，不作为生产模型能力。

## 阶段 3：真实 AgentScope Runtime（已完成）

阶段3已将 `AgentRuntime` 接到 AgentScope Java 2.0.0，支持 OpenAI Compatible 与 DashScope Provider、同步单轮 ReAct 运行、外部 `ModelCredentialProvider`、运行事件与结果映射，以及模型和工具 timeout。

模型凭据按 `tenantId + modelConfigId` 与外部 Secret 映射，`model_configs` 不保存明文 API Key。工具每次调用重新授权，endpoint 不会自动执行；权限拒绝、审计严格失败和租户隔离继续沿用阶段2边界。对外部副作用，工具和下游仍需自行保证幂等。

阶段3只交付同步单轮能力，不承诺多轮会话持久化、流式 REST、HITL 或手动取消。AgentScope 2.0.0 的通用取消信号也不能证明外部副作用已回滚。

## 阶段 4：可观测性与运维（尚未交付）

阶段4计划补齐 metrics、集中式日志与追踪、运行和审计告警、备份恢复演练、容量阈值、保留期、归档/清理策略和数据库运维自动化。当前应用不会自动删除或归档 Run、ToolCall、Audit；备份恢复与容量治理也不属于阶段2交付。

阶段4依赖阶段3稳定的真实运行事件和结果语义，并依赖阶段2已经落地的租户范围索引、严格审计和可查询运行记录。

## 阶段 5：交付与稳定性（尚未交付）

阶段5计划建立可重复的 CI/CD、发布与回滚流程、版本化兼容策略、性能与故障演练、供应链检查和正式稳定性门禁。当前文档不提前宣称 metrics、CI/CD、正式发布自动化或稳定性承诺已经交付。

阶段5依赖阶段3的真实 runtime 合同和阶段4的可观测性、备份恢复、容量治理数据，完成后才能形成面向生产长期运营的交付闭环。
