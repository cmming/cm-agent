# AgentScope Permission System 接入进度账本

## 关联文档

- [设计说明](../specs/2026-09-02-agentscope-permission-system-integration-design.md)
- [实施计划](../plans/2026-09-02-agentscope-permission-system-integration.md)
- [实现说明](../implementation/2026-09-02-agentscope-permission-system-integration-implementation-design.md)

## 任务状态

| 任务 | 状态 | 实际结果 |
| --- | --- | --- |
| 官方机制与现有调用链调研 | 已完成 | 确认 AgentScope 2.0.2 的 ALLOW/DENY/ASK、DEFAULT/DONT_ASK、ToolBase 权限入口及 ConfirmResult 恢复合同；保留 CM Agent RBAC、ToolGrant、租户和审计边界 |
| Core 领域与 Runtime 合同 | 已完成 | 新增 WAITING_APPROVAL、审批/检查点领域类型、Repository SPI、Runtime 待审批与恢复合同 |
| ToolBase 权限桥接 | 已完成 | 全部 CM Agent 桥接工具进入 Permission Engine；开关开启时在线会话 HIGH ASK、无会话 Run DONT_ASK、LOW/MEDIUM ALLOW，开关关闭保持兼容 |
| ASK 捕获与 AgentState 恢复 | 代码与目标测试完成 | ASK 不再误报完成；使用可信 tenant/主体/runId 状态槽保存和恢复，决定必须完整匹配原 toolCallId 集合、工具 ID 与输入哈希；真实重启/跨实例演练待完成 |
| memory/JDBC 持久化 | 已完成 | 新增审批与检查点 Repository、PostgreSQL/MySQL V11、租户外键、乐观锁、索引和中文数据库注释 |
| Server 审批编排与 API | 代码与目标测试完成 | 新增待审批查询、权威详情、决定恢复 SSE；同时要求 agent:run 与 agent:approve、自批限制、有效 PENDING 发送锁、过期提交收口和审批审计 |
| 控制台审批交互 | 代码与合同测试完成 | 聊天页支持刷新恢复、多 item 逐项决定、全部允许/拒绝、纯文本摘要、发送锁、错误状态和窄屏布局；浏览器端到端未验收 |
| 生产与过程文档 | 已完成 | README、配置、部署、运维、路线图、技术架构、发布说明及本主题四份文档同步更新 |
| 无人值守暂停与多实例自动接管 | 未纳入第一版 | 当前没有后台审批租约/补偿任务；无人值守入口和独立审批工作台继续 fail-closed 或不开放 |
| 长期预授权规则治理 | 未纳入第一版 | 不持久化 suggested rules，不开放 BYPASS、EXPLORE 或 ACCEPT_EDITS |

## 已完成验证

### 环境与实际命令

- 本机 `java -version`、`mvn -v` 已确认使用 `F:\java21` 的 JDK 21.0.11，Maven 3.9.4。默认 Java 为 17，因此执行 Maven 前必须在当前进程设置 `JAVA_HOME=F:\java21` 并把其 `bin` 放到 PATH 前部，未修改系统全局环境。
- Rocky 已确认 Docker 23.0.6，验证镜像为 `maven:3.9.9-eclipse-temurin-21`，容器 Maven 3.9.9 / JDK 21.0.7。远程临时工作区 `/tmp/cm-agent-permission-20260902` 与本机 Git 基线均为 `c2907cb2632cd95b330b255786308933a207cacd`，未提交改动同步后验证。

| 实际命令 | 结果 |
| --- | --- |
| `mvn -q -pl cm-agent-core,cm-agent-agentscope-adapter,cm-agent-console -am test` | Core 75、Adapter 65、Console 12 项通过，失败/错误/跳过均为 0 |
| `mvn -q -pl cm-agent-server -am "-Dtest=RunPersistenceServiceTest,ToolApprovalServiceTest,ToolApprovalControllerTest,RepositoryAgentStateStoreTest,InMemoryToolApprovalRepositoryTest,AgentScopeRuntimeConfigurationTest,ApiExceptionHandlerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.studio.enabled=false" test` | 2026-09-03 最终回归 8 类共 42 项通过，失败/错误/跳过均为 0；仅在测试进程禁用 Studio，未改用户配置 |
| `mvn -q -pl cm-agent-server -am "-DskipTests" package` | 最终打包成功，退出码 0 |
| `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js` | 语法检查通过 |
| `node --test cm-agent-console/src/test/js/console-core.test.cjs` | 45 项通过，无失败或跳过 |
| `mvn -pl cm-agent-agentscope-adapter dependency:tree "-Dincludes=io.agentscope:*"` | Core、OpenAI、DashScope 三个 AgentScope 工件均解析为 2.0.2 |
| Rocky 容器内 `mvn -q -pl cm-agent-persistence -am test` | 持久化套件 51 项通过，无失败/错误/跳过；覆盖 PostgreSQL 16 与 MySQL 8.4 |
| `git -c core.safecrlf=false diff --check` | 无差异格式错误 |

Rocky 首轮发现 V11 外键错误引用 `agents`/`tools`，已修正为实际 `agent_definitions`/`tool_definitions`；随后更新迁移测试中的最新版本为 V11，补齐三张表、索引、外键与逐表逐字段原生注释断言。最终 51 项通过报告已复核。两份 V11 文件与远程验证副本的 SHA-256 完全一致：PostgreSQL 为 `474f798af5a9347012f9ea065a3b8274dc61f10727d0d2a067a7613632772af6`，MySQL 为 `52e3217ebe510cfd5ede1bc7abee65fc6d3e90b4a9c24918de3986f39f1bbb6a`。远程结果针对持久化代码及迁移，不宣称其覆盖随后补充的 Server/控制台代码。

### 本轮新增边界验证

- Adapter 本地模型协议桩：HIGH 暂停前网关零调用、批准后一次调用、重复恢复拒绝、输入哈希变化及同名工具 ID 替换拒绝。
- Service/memory：跨租户/Agent/会话不可见、全量明细与版本校验、批准与拒绝竞争仅一个成功、过期清理不调用 Runtime、恢复预检失败终结 Run 并清理/审计。
- 持久化编排：ASK 前已执行工具调用保存并脱敏，连续等待不能丢记录。
- Web：缺少任一权限拒绝且审计、400/409/410 业务码、决定接受后的 SSE 顺序、响应与日志同一 errorId、未预期故障的脱敏堆栈；流内权限撤销使用 WARN，API Key 标记和内部 URL 不出现在响应及日志。
- 前端：单项/多项/混合决定载荷、缺项/重复/外来项拒绝、只读/终态不可提交、中文跨分片 SSE、409/410 错误编号及会话代次/权威查询静态合同。

### 未完成的验证

- 未获得全仓库 `mvn -q test` 全部通过的结果。此前本地全量运行受到工作区既有 profile/运行配置覆盖及本机 Docker 不可用影响；未为使全量通过而覆盖用户配置或降低安全默认值。数据库验证按仓库要求在 Rocky 完成，本次本机执行明确列出的目标套件。
- 新 `JdbcToolApprovalRepository`、`JdbcRuntimeCheckpointRepository` 已编译，V11 在双库验证通过；尚无针对这些新仓储的专门租户/并发/事务回滚集成用例，不能将现有 51 项当作其全部行为已验证。
- 未执行真实浏览器 DOM、键盘/焦点、窄屏端到端测试，也未执行真实服务进程重启、多实例接管或完整授权撤销恢复演练；单元测试与协议桩不能替代这些验收。

## 已知限制与风险

- `permission-enabled` 默认关闭；启用前必须完成 V11 并为所有实例配置相同的受控 AES 主密钥。
- PENDING 查询排除过期请求，提交过期请求时才标记 EXPIRED、收口 Run 和删除检查点；没有主动定时扫描，列表为空不代表已完成清理。
- 原子决定与 Runtime 恢复不在同一事务中；进程在两者之间崩溃时当前不会自动接管恢复。该限制不会绕过审批或重复接受决定，但可能留下需要运维收口的 WAITING_APPROVAL Run。
- 检查点写入、Run 等待转换和审批创建并非一个跨阶段事务；审计/创建失败也可能留下等待 Run。外部工具仍需幂等机制，审批不能保证外部副作用恰好一次。
- 有效 PENDING 发送锁不是跨客户端的会话执行租约，审批接受后恢复期间的跨客户端串行化尚未实现；待审批列表上限 50，无分页或超限诊断。
- 检查点未保存独立密钥版本；主密钥轮换前必须先收口待审批运行。
- 前端已做资源合同、SSE 解析与静态安全测试，尚未引入浏览器级 DOM/无障碍端到端测试。
- 工作区已有 `application.yml`、`application-mysql.yml`、`application-ok.yml` 和 `.workbuddy/` 修改不属于本任务，本次未覆盖或清理。

## 下一步

按实施计划先补齐新 JDBC 仓储专门测试，再做真实浏览器审批链路和重启/授权撤销演练。主动过期扫描、执行租约/崩溃补偿与独立密钥版本属于后续实现，不应在未完成时开启相关配置或宣称已支持。

## 提交信息

本主题与人工确认 UI、审批历史回显一起纳入本账本所在提交，提交说明为 `feat: 完善工具人工审批及会话历史回显`。仅提交功能代码、测试和相关文档；本地应用配置及 `.workbuddy/` 不纳入，不推送远端。具体提交号通过本文件的 Git 历史查询。
