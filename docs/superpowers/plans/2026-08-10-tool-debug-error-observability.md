# 工具调试错误可观测性实现计划

## 任务拆分

1. 检查工具执行器、调试服务、控制器和控制台的失败传递链路。
2. 扩展调试响应，确定 `errorCode`、`errorId` 与已有 `toolCallId` 的关系。
3. 在调试服务中返回具体脱敏原因，并补充带错误编号的结构化错误日志。
4. 修改控制台状态和结果详情，显示原因、错误码和错误编号。
5. 补充服务端日志/脱敏测试和控制台格式化测试。
6. 更新开发指南、发布说明及本任务四份过程文档。
7. 使用 JDK 21 运行针对性测试、服务端模块测试和差异检查。

## 涉及文件

- `cm-agent-server/src/main/java/com/cmagent/server/service/ToolDebugResponse.java`
- `cm-agent-server/src/main/java/com/cmagent/server/service/ToolDebugService.java`
- `cm-agent-server/src/test/java/com/cmagent/server/service/ToolDebugServiceTest.java`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- `cm-agent-console/src/test/js/console-core.test.cjs`
- `docs/tool-development-guide.md`
- `docs/release-notes.md`
- `docs/superpowers/{specs,plans,implementation,progress}` 下同主题文档

## 实现顺序

先固定服务端响应和脱敏边界，再修改控制台展示，随后补测试和文档，最后扩大验证范围。这样可以让前端始终以稳定响应字段为依据，不依赖错误文本做流程判断。

## 验证方式

- `node --test cm-agent-console/src/test/js/console-core.test.cjs`
- `mvn -pl cm-agent-server -am "-Dtest=ToolDebugServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `mvn -pl cm-agent-server -am "-Dtest=ToolDebugServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `mvn -pl cm-agent-server -am "-Dtest=ToolControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.enabled=false" "-Dcm-agent.fake-runtime-enabled=true" test`
- `git diff --check`

本任务不修改 JDBC、Flyway 或数据库集成逻辑，因此不需要在 Rocky Linux 容器环境运行数据库验证。控制器测试通过命令行临时覆盖运行时开关，避免修改工作区现有配置。

## 关联文档

- [需求设计](../specs/2026-08-10-tool-debug-error-observability-design.md)
- [实现说明](../implementation/2026-08-10-tool-debug-error-observability-implementation-design.md)
- [进度账本](../progress/2026-08-10-tool-debug-error-observability-ledger.md)
