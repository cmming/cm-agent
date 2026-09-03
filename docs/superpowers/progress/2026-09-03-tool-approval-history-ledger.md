# 会话审批历史进度账本

关联：[设计](../specs/2026-09-03-tool-approval-history-design.md)、[计划](../plans/2026-09-03-tool-approval-history.md)、[实现说明](../implementation/2026-09-03-tool-approval-history-implementation-design.md)。

## 状态

- 领域与 memory/JDBC 历史查询：完成。
- 历史 API、授权隔离、错误诊断：完成；定向测试通过。
- 前端回显、分页和错误恢复：完成；69 项 Node 回归通过。
- 双数据库分页与持久化回读：Rocky 定向测试通过。
- 生产文档、完整验收记录：完成；README、配置、架构、运维、发布说明及原 UI 任务状态同步。
- 提交：本主题随本账本所在提交一并提交，提交说明为 `feat: 完善工具人工审批及会话历史回显`。用户已有配置及 `.workbuddy/` 不纳入，未推送远端；具体提交号通过本文件的 Git 历史查询。

## 已执行验证

- 本地 Java 21.0.11、Maven 3.9.4；构建前设置当前进程 `JAVA_HOME=F:\java21`，未改系统环境。
- `mvn -q -pl cm-agent-server -am "-Dtest=ToolApprovalHistoryPageRequestTest,ToolApprovalServiceTest,InMemoryToolApprovalRepositoryTest,ToolApprovalControllerTest,ApiExceptionHandlerTest,ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test`：39 项通过（领域 1、服务 7、内存仓储 3、接口 9、统一错误处理 6、控制台资源 13），失败/错误/跳过均为 0。
- `node --test cm-agent-console/src/test/js/console-core.test.cjs`：69 项通过，失败/跳过均为 0；本任务新增 9 项，包括真实页面加载/权威查询编排、模拟 DOM 的只读渲染与消息关联。app.js 与 console-core.js 均通过 `node --check`。
- `mvn -q -pl cm-agent-server -am "-DskipTests" package`：退出码 0。
- Rocky Docker 容器 `maven:3.9.9-eclipse-temurin-21`：`mvn -q -pl cm-agent-persistence -am -Dtest=JdbcToolApprovalHistoryTest -Dsurefire.failIfNoSpecifiedTests=false test`，PostgreSQL 16、MySQL 8.4 两项通过。
- 本地/远程 Git 基线均为 `c2907cb2632cd95b330b255786308933a207cacd`；补齐测试容器生命周期注释后重新同步并复测通过，最终 api/core/persistence 源归档双端 SHA256：`3f58410c2490c11d991586c4d1be36f4fd6b33eebfbb6d0b2257f2192eae77ee`。远程目录 `/tmp/cm-agent-permission-20260902`，不包含用户服务配置。
- `mvn -pl cm-agent-persistence dependency:tree "-Dincludes=org.testcontainers:*"`：通过。实际解析 Testcontainers 1.21.0，与远程测试日志一致；父 POM 声明属性 2.0.5 未成为当前模块实际版本。本任务未升级依赖，后续治理以解析结果为准。
- `git -c core.safecrlf=false diff --check`：通过。已核对本任务新增 Java 分页、授权、日志、并发和测试容器的中文注释；四份同主题文档齐全。

## 验证边界与遗留

尚未执行浏览器真实页面端到端、真实模型/工具调用，以及真实服务进程重启验收。双数据库测试通过重建 Repository 实例确认数据库回读，不将其表述为服务端重启测试。未扩大到自动过期清理。

本次未重复执行整个仓库全量测试，采用上述受影响模块的定向验证；未自动启动或替换用户正在运行的服务。部署新版后需刷新页面，再使用同一会话完成多轮确认并回看历史。审批仓储的自定义实现需新增 `listHistory` 方法；使用项目自带 memory/JDBC 实现无需额外配置或本任务新增迁移。
