# Agent 模型名称同步账本

日期：2026-10-15；修订：R0；工作模式：mode=default。

Git 基线：`eac79c26f5d8a856b53c4cdeee0c807ecd191509`。保留任务开始前已存在的控制台、配置与文档修改；初始交付时未提交；本轮提交记录见账本。

| 编号 | 状态 | 目标与验收 | 依赖与验证 |
|---|---|---|---|
| T0 | 完成 | 查明列表、详情和运行中旧模型名称的来源 | CodeGraph、当前源码、8080 浏览器只读复现 |
| T1 | 完成 | 同租户列表与详情按绑定配置 ID 读取当前模型名称；下一次准备运行采用同一名称；不回写历史 | T0；查询服务、Controller、运行编排、不可变定义副本 |
| T2 | 完成 | 验证配置改名、只读权限、多租户、配置停用/缺失、实际运行请求及嵌入式兼容 | T1；两组 Java 21 定向测试共 53 项通过；运行请求由测试 Runtime 捕获，未调用真实供应商 |
| T3 | 完成 | 六份文档与生产说明、发布说明一致，记录实际验证和环境边界 | T2；链接、差异、状态自查通过；纳入本轮提交 |

## 本轮实际验证

环境：默认 Java 为 17，已仅在验证进程内切换 `JAVA_HOME=F:/java21`；`java -version` 为 21.0.11，`mvn -v` 为 Maven 3.9.4 / JDK 21。

第一组命令（退出码 0）：

```powershell
mvn -q -pl cm-agent-server -am "-Dtest=AgentControllerTest,RunControllerTest,ModelConfigControllerTest,AgentDefinitionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

当前报告：AgentControllerTest 3、ModelConfigControllerTest 7、RunControllerTest 33、AgentDefinitionTest 1，共 44 项，失败、错误和跳过均 0。日志保存在系统临时目录 `cm-agent-model-name-tests.log`，不入库。

第二组补充验证（退出码 0）：

```powershell
mvn -q -pl cm-agent-server -am "-Dtest=AgentDefinitionQueryServiceTest,AgentScopeModelFactoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

报告：AgentDefinitionQueryServiceTest 3、AgentScopeModelFactoryTest 6，共 9 项，失败、错误和跳过均 0。两组共 53 项通过，报告位于各模块 `target/surefire-reports/`，不入库；嵌入式模型工厂的显式名称覆盖和空名称回退测试保持通过。

浏览器：只读访问 8080 已登录 Agent 页，确认同一 Agent“模型配置”为 `glm-5.3-flash`，“模型”为 `claude-haiku-5-5`；未修改真实配置或发起真实 Run。

全工作树 `git diff --check` 发现任务开始前已修改的 `application-mysql.yml:19` 文件末尾空行，保留未处理；本次目标路径的差异检查通过。六份文件的相互引用、模式和修订号检查通过。

## 未执行项与状态

- 8080 服务未重启，修复后的在线页面和真实供应商调用未验收；需要以新构建重新启动后刷新检查。不能以接口回归替代真实模型验证。
- 未执行 JDBC/Testcontainers/双库迁移测试：本次没有 SQL、Repository 实现或 schema 变化，定向测试采用 test profile 的 memory store，未操作本机或远程 Docker。
- 未执行全量测试；当前验证范围限定于查询、配置管理、运行 API、核心定义和模型工厂合同。
- 代码与文档纳入本轮提交，未推送、未部署；无真实数据修正、凭据处理或配置覆盖。

## 提交接续

2026-10-15 用户授权将本次修复提交。提交标题：`修复 Agent 模型名称跟随绑定配置`；提交范围为本次 Java 实现、测试、README 与发布说明的对应段落，以及六份工作包。其他控制台、YAML 和工作区原有修改保留。提交编号由 Git 生成，可通过 `git log -1 --format="%H %s" -- cm-agent-server/src/main/java/com/cmagent/server/service/AgentDefinitionQueryService.java` 查询。提交未重新运行测试；沿用本轮已通过的 53 项定向测试，代码无后续变化。

## 关联文档

- [清单](../checklists/2026-10-15-agent-model-name-sync-checklist.md)
- [提示词](../prompts/2026-10-15-agent-model-name-sync-prompt.md)
- [设计](../specs/2026-10-15-agent-model-name-sync-design.md)
- [计划](../plans/2026-10-15-agent-model-name-sync.md)
- [实现说明](../implementation/2026-10-15-agent-model-name-sync-implementation-design.md)
