# 关键位置日志输出补齐进度账本（2026-09-01-log-coverage-gaps）

对应文档：`specs/2026-09-01-log-coverage-gaps-design.md`、`plans/2026-09-01-log-coverage-gaps.md`、`implementation/2026-09-01-log-coverage-gaps-implementation-design.md`。

## 任务状态

| 任务 | 状态 | 说明 |
|---|---|---|
| 任务 1：server 安全与 MCP 失败边界日志 | 已完成 | JWT catch WARN + MCP 目录 3 条 ERROR 诊断日志 |
| 任务 2：server 编排与生命周期日志 | 已完成 | 补偿失败 WARN、Run 生命周期 INFO、登录 INFO/WARN；顺带修复过期 `@param` 标签 |
| 任务 3：启动期状态日志 | 已完成 | memory 模式/Flyway 迁移/方言/默认数据/FakeAgentRuntime 提示 |
| 任务 4：adapter 凭据失败日志 | 已完成 | run 与 runStructured 两处 WARN |
| 任务 5：starter 依赖修正 | 已完成 | pom.xml 显式添加 slf4j-api |
| 任务 6：文档 | 已完成 | 本组四份文档 |

## 实际验证结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| 环境 | 临时设置 `JAVA_HOME=F:\java21` 后执行 `java -version` / `mvn -v` | JDK 21.0.11（Microsoft LTS）+ Maven 3.9.4，符合项目要求；系统默认 JDK 17 不满足本项目要求 |
| 全模块编译与打包 | `mvn -q -pl cm-agent-server -am install -DskipTests` | 通过；同时验证 starter、persistence、adapter 与 server 的编译和打包 |
| 定向 server 测试 | `mvn -pl cm-agent-server "-Dtest=JwtSecurityConfigurationTest,McpPublishedToolCatalogTest,ManagementCommandServiceTest,RunPersistenceServiceTest,AuthControllerTest" test` | 通过，74 个测试全部成功 |
| adapter 测试 | `mvn -pl cm-agent-agentscope-adapter test` | 通过，54 个测试全部成功 |
| 全量 server 测试 | `mvn -pl cm-agent-server -am test` | 未通过：persistence 模块 12 个 Testcontainers 测试因本机无 Docker 环境报错，server 模块未执行 |
| server 全测试 | `mvn -pl cm-agent-server test` | 未通过：`ApplicationProfileConfigurationTest` 有 27 个断言受工作区既有 `application.yml`/`application-mysql.yml` 改动影响，均先触发“production/prod/supabase profile 禁止启用 HTTP 明文协议”；另有 5 个 JDBC/控制台集成测试因无 Docker 报错 |

## 测试结果记录

- 全量测试已实际尝试，但本机 Docker 不可用，导致持久化迁移与 JDBC 集成测试无法执行。仓库要求使用 `ssh rocky` 的容器环境复核；已确认 Rocky 主机 Docker 可用，但其两个可用工作区的 Git 提交均与本地 `615309d57ad4fa9e88f87dd35353d70ede6d80d8` 不一致，不能用过期代码验证本次未提交修改。
- `ApplicationProfileConfigurationTest` 的失败来自任务开始前已有的运行配置改动，不属于本任务的日志改动；定向日志影响测试均已通过。

## 遗留问题

1. Logback pattern 未显示 MDC `errorId`：`RequestCorrelationFilter` 写入的 MDC 值目前只被诊断日志器显式携带，普通 `log.info/warn` 行没有 errorId。需后续单独任务添加 logback-spring 配置（本次任务范围不含日志框架配置）。
2. 4xx 业务失败（401 登录拒绝已补 WARN，404/409 等普通拒绝）仍无日志：属设计选择（参数校验不滥用日志），如需调整应作为独立需求评审。
3. 跨服务编号透传（MCP 下游、示例客户端）尚未实现，规范预留条款。
4. 需要在与当前本地提交一致的 Rocky 容器工作区补跑 PostgreSQL 16、MySQL 8.4 的 Flyway/JDBC 集成测试。

## 提交信息

- 未提交。
