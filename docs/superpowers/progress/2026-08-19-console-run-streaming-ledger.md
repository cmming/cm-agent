# 控制台运行流式输出进度账本

## 任务状态

- [x] 定位 v2 运行页、运行接口与 AgentScope 事件链路。
- [x] 增加 Runtime 与 AgentScope 文本增量通道。
- [x] 新增受保护 SSE 运行接口与控制台实时渲染。
- [x] 更新测试、README、发布说明和本组文档。
- [x] 使用 JDK 21 完成模块编译和 Adapter、控制台测试。
- [x] 完成 `RunControllerTest` 流式 MockMvc 验证。

## 实际验证结果

- `mvn -pl cm-agent-server -am -DskipTests compile`：通过（JDK 21）。
- `mvn -pl cm-agent-console test`：通过，12 个测试。
- `mvn -pl cm-agent-agentscope-adapter -am test`：通过，Adapter 模块 53 个测试，含新增文本增量合同测试。
- `mvn -pl cm-agent-server -am -Dtest=RunControllerTest#streamRunSendsOutputDeltaAndCompletedResult+streamRunFailureReturnsCorrelatedSanitizedErrorEvent -Dsurefire.failIfNoSpecifiedTests=false test`：通过，覆盖 SSE 启动、增量、完成及脱敏错误事件与 `errorId` 日志关联。
- `mvn -pl cm-agent-server -am -Dtest=RunControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`：未通过；其中 24 个测试及新增流式测试通过，2 个既有模型配置失败断言期望“请求参数不合法”，当前受控响应为“模型配置不可用”。本次未改动该失败分支或模型配置逻辑。

## 遗留问题

- 不涉及数据库迁移、Docker 或 Testcontainers；无需 Rocky Linux 容器验证。

## 提交信息

未提交。

## 全量核定记录（2026-11-13）

- 核定结论：归档状态待核实。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：流式定向测试通过，但完整 RunControllerTest 两项失败未收口；其是否移出原范围缺少明确依据。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：尚不能确认本包验收缺口是否已被正式收口或移出；保留原结论，后续先明确责任范围，不补造完成、取消或移交。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。

## Git 补证核定（2026-11-13）

- 当前核定结论：已归档。本记录是对前文“归档状态待核实”的后续补证，不改写原测试或任务表。
- 范围与依据：655c33b 的两项回归失败均是模型不可用错误消息旧断言；b25b7dc 的同一 RunControllerTest diff 逐项改成“模型配置不可用”，且该提交中的会话账本记录完整非容器回归通过。直接修复与回归记录形成闭环，不是仅依据后续相关功能或合并标题。
- Git 证据：`655c33bab5`、`b25b7dc445`；均已核对为当前 HEAD 的祖先。本轮只读历史对象，不重新运行这些测试。
- 保留限制：归档只针对上述责任范围；待续组仍需原验收收口。本轮不推断当前部署状态，也不以同名后继功能自动代替原验证。
- 后继入口与详情：[R1 Git 补证表](2026-11-13-docs-full-review-ledger.md#git-audit-2026-08-19-console-run-streaming)。没有补造取消或移交记录，不新增提交、推送或部署授权。
