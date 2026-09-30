# A 批次进度与验收账本

关联：[设计](../specs/2026-09-30-approval-expiry-a-design.md)、[计划](../plans/2026-09-30-approval-expiry-a.md)、[实现](../implementation/2026-09-30-approval-expiry-a-implementation-design.md)。

| 任务 | 状态 | 证据 |
| --- | --- | --- |
| T0 | 完成 | HEAD b7c7280；已读取 AGENTS、POM、README、configuration、operations、清单；独立工作树 F:/java/cm-agent-approval-expiry-a |
| T1 | 完成 | ApprovalExpiryPage、双仓储实现、V14索引；ApprovalExpiryRepositoryTest 3例通过 |
| T2 | 进行中 | 统一CAS与等待Run更新已落地；双库回滚待验证 |
| T3 | 进行中 | 配置/调度落地；ApprovalExpiryScannerTest 5例通过，故障继续/重试/脱敏 |
| T4 | 进行中 | 4个控制台文件修正，Node 83/83；桌面/移动浏览器待验证 |
| T5 | 进行中 | JDK21.0.11/Maven3.9.4；server非容器63报告560例通过；新增迟到结果测试待新版回归 |
| T6 | 进行中 | ssh rocky Docker23.0.6；Maven3.9.9/JDK21.0.7；远端HEAD eb38920一致，PG专项运行中 |
| T7 | 待做 | 受控 Runtime 与浏览器待验证 |
| T8 | 进行中 | 四份本轮文档已新建；未提交 |

原工作树的 application*.yml、.codex/、.workbuddy/ 和历史分析文档保持不动。不推送、不合并、不部署。

## 实际命令与中间结果

- 设置 JAVA_HOME=F:/java/temurin21/jdk-21.0.11+10 后确认 java -version/mvn -v 为 Java21；未修改全局环境。
- `mvn -q -pl cm-agent-server -am -DskipTests package` 通过。
- `mvn -q -pl cm-agent-core,cm-agent-agentscope-adapter,cm-agent-console -am test` 通过，日志 fast-regression.log（忽略生成物）。
- server 非容器测试：从 server/src/test/java 的 `*Test.java` 排除包含 org.testcontainers/@Testcontainers 的文件，构造显式 -Dtest 列表，`mvn -q -pl cm-agent-server -am -Dtest=<列表> -Dsurefire.failIfNoSpecifiedTests=false test` 通过；63份 server 报告共560项，零失败/错误/跳过。
- 初次扩大选择时使用 `*Test,!*Jdbc*Test` 错误包含 persistence/MigrationTest，Docker探测失败，未启动本机容器；已改为源码核实后的显式测试列表。新增日志断言初次编译遗漏 ToolOutputSanitizer 的 ObjectMapper 参数，修正后通过。
- `node --test cm-agent-console/src/test/js/skills.test.cjs cm-agent-console/src/test/js/console-core.test.cjs`：83项通过。
- 隔离验证提交 eb38920e5b7198ffe72b8e959ba1d5e055a6f252；只含明确列出的31个本轮文件，不含原工作树用户配置。远端 `/tmp/cm-agent-approval-a-eb38920` Git HEAD一致。后续fixture/文档未提交，最终交付尚未提交到用户原分支。
