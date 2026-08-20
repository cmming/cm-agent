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
