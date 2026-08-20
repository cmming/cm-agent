# AgentScope 适配器代码注释优化设计

## 背景

`cm-agent-agentscope-adapter` 已实现真实 AgentScope 2.0.0 ReAct 执行、模型 Provider 映射、工具治理桥接、超时与取消传播，以及领域结果转换。目标包中的原有注释多为一句职责描述，能够说明“做什么”，但没有充分解释响应式执行与同步运行契约的关系、每次运行的资源生命周期、工具治理安全边界，以及部分框架版本相关的绕行逻辑。

## 目标

- 为 `com.cmagent.agentscope` 包内 9 个生产源码类型补充准确、可维护的中文 JavaDoc。
- 重点解释模型、Toolkit、RuntimeContext、ReActAgent 和工具桥接器的完整调用链与生命周期。
- 说明工具调用为何必须经过 `ToolInvocationGateway`，以及可信租户与主体上下文的来源。
- 说明 AgentScope 2.0.0 工具超时事件识别、响应式取消、基础设施失败优先级和 Agent 中断去重的原因。
- 保持所有可执行语句、公开签名、领域结果和外部行为不变。

## 范围

- `AgentScopeRuntimeAdapter`：领域运行边界、凭据解析、流式与非流式结果映射。
- `AgentScopeReActExecutor`：单轮 ReAct 调用链、同步阻塞语义、事件归集、异常优先级和资源关闭。
- `AgentScopeToolBridge`：Schema 暴露、二次治理、响应式包装、取消传播、安全摘要与异常分类。
- `AgentScopeRunGate`：公平串行化、首次基础设施失败、工具超时识别、迟到结果抑制和中断去重。
- `AgentScopeModelFactory`：Provider 映射、模型名覆盖、生成选项和流式模型配置。
- `AgentScopeRuntimeOptions`、`AgentScopeRunSpec`、`AgentScopeExecutor`、`AgentScopeExecutionResult`：配置约束、可信请求视图、测试缝隙和终态不变量。

## 非目标

- 不修改 AgentScope 执行逻辑、超时策略、重试次数、异常映射或工具治理流程。
- 不调整测试源码中已有注释，不进行格式化、重命名或跨模块重构。
- 不修改数据库、配置、API、日志或审计行为。
- 不更新发布说明，因为本次没有任何运行时或对外行为变化。

## 方案

采用“类级调用链说明 + 关键方法契约 + 少量原因型行内注释”的方式：

1. 类级 JavaDoc 描述该类型在 Core 与 AgentScope 之间的职责边界，以及创建、持有和关闭关系。
2. 公开方法和关键包内方法补充输入来源、返回值、失败方式与线程/生命周期要求。
3. 对响应式事件、工具超时文本、基础设施失败恢复、一次性中断和关闭异常 suppressed 规则增加预判式注释。
4. 简单 Getter 和明显赋值不逐行翻译，仅在返回值承载可信安全上下文或框架契约时保留简短说明。
5. AgentScope 版本相关行为仅描述当前依赖和现有合同测试已经体现的行为，不扩展未经验证的推断。

## 约束

- 所有新增或修改文字使用中文，类型、方法、配置项和框架 API 保留原名。
- 不在注释中写入 API Key、真实 URL、JWT、数据库密码或原始工具输入值。
- 不把 `Mono.fromCallable` 误述为自动异步或非阻塞；明确实际调度由订阅方决定。
- 不把响应式取消描述为能够回滚已经发生的外部副作用。
- 保留工作区中既有的 server 配置改动，不纳入本任务。

## 验收标准

- 9 个目标 Java 文件的类级职责和关键调用链无需通读全部实现即可理解。
- 注释准确覆盖多租户安全边界、工具治理、超时/取消、失败优先级和资源关闭。
- Java 源码差异除 JavaDoc、行内注释及注释位置修正外不包含可执行行为变化。
- `git diff --check` 通过，JDK 21 与 Maven 3.9+ 环境确认完成。
- `mvn -pl cm-agent-agentscope-adapter -am test` 通过。
- 同主题计划、实现说明和进度账本内容一致。

## 关联文档

- [实施计划](../plans/2026-08-20-agentscope-adapter-comments.md)
- [实现说明](../implementation/2026-08-20-agentscope-adapter-comments-implementation-design.md)
- [进度账本](../progress/2026-08-20-agentscope-adapter-comments-ledger.md)
