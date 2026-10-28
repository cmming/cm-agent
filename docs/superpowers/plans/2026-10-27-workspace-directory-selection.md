# 工作区目录选择实施计划

日期：2026-10-27；主题：workspace-directory-selection；修订：R0；模式：mode=default。

本计划描述未来实施方法，不是执行结果。T0 已完成需求分析与工作包；T1–T7 均待做。设计 A1–A6 为默认假设；本轮仅文档，未修改前后端业务代码。

## 实施步骤

### T0：建立证据与工作包

检查 AGENTS.md、HEAD、暂存/工作树、相关 POM、配置说明及真实资源路径；先用 CodeGraph，结果不完整再核对源码。记录原有配置与发布说明等脏文件。生成并验证本组六份产物，此项已完成，实际结果见账本。

### T1：建立受控目录 catalog

从 `cm-agent-server/src/main/java/com/cmagent/server/config/WorkspaceContextProperties.java` 和 `WorkspaceContextConfiguration.java` 扩展；将路径校验、目录声明身份与可见过滤整理为可测试策略，避免 Controller 自行拼接路径。新类型位置以 core/domain 与 server/runtime/config 的职责为准，类型名为实现时决定项。

先写旧配置/关闭兼容和多目录用例，再实现显式 catalog；保留 `WorkspaceContextFiles` 全部安全限制。不能把原“一组 tenant/Agent 唯一”校验简单删除后依赖 findFirst 选择。新增 ID 与 Agent 显式授权，检测目录重叠和别名；目录内容不进入列表接口。配置可用状态与执行时文件健康分别表达，不能把仅配置合法当成资料已读取。完成条件按清单 T1。

### T2：持久化会话与 Run 选择

建议新增 `cm-agent-core/src/main/java/com/cmagent/core/domain/ConversationWorkspaceBinding.java`、`RunWorkspaceSelection.java` 及对应 Repository 接口；以上为拟新增路径。新增 `server/store` memory 实现和 `cm-agent-persistence/src/main/java/com/cmagent/persistence` JDBC 实现，在 `cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java` 按既有装配方式接入，核对 memory Bean 的实际配置位置再改。

为元数据新增两库同版本迁移，实施时核对 V21 之后是否出现其他版本。表/字段中文注释、tenant/资源唯一索引、创建更新时间和归属策略必须同时落地；更新 `MigrationTest` 及 Repository 合同。老记录无绑定时 DEFAULT，不能批量推断。会话绑定写入与首发需要共享数据库锁或条件更新，事务外才读取文件/调用模型；memory 流程需失败补偿。完成条件按清单 T2。

### T3：API、权限、诊断

现有入口：`cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java`、`RunController.java`、`ApiExceptionHandler.java`。目录查询/绑定可用职责明确的新 Controller/Service，名称为拟新增；Service 编排配置、Repository 与审计，Controller 不直接访问文件或数据库。

按设计接口表实现可选请求体，保留原无请求体创建、旧 SDK 和 v1 请求；同步/流式单轮请求统一选择语义。绑定 mutation 校验主体是创建者；tenant 只取认证上下文。新增错误分支先分配关联标识，使用统一日志边界、脱敏堆栈和严格审计；Test profile MockMvc 覆盖接口合同与未知内部错误。完成条件按清单 T3。

### T4：接入真实运行与恢复

从 `cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java` 首发锁/短事务入口，把选择身份固定到 Run；`RunExecutionService.java` 和 `WorkspaceContextService.java` 用固定身份查配置并复用正文加密快照。审查 `applies`、`prepare`、`mappingDigest`、retain/cleanup 全链，不能只在 prepare 增加参数而遗漏恢复/停止。SELECTED、DEFAULT、NONE 分支必须贯穿。

复用 `AgentRunRequest.workspaceContext`，优先不改变 adapter 公共 API。新增元数据不得进入 prompt 为指令，仍作为受控参考数据；Harness 原生文件/Shell 禁用保持。扩展 `WorkspaceContextServiceTest`、`HarnessWorkspaceContextFlowTest`、`HarnessWorkspaceContextJdbcFlowTest` 和 adapter `AgentScopeWorkspaceContextContractTest`；补同 Run 文件更新、撤销、快照损坏及历史 Run 兼容负例。完成条件按清单 T4。

### T5：前端完整选择流程

真实入口位于 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`、`runs.html`，共享 `META-INF/resources/assets/app.js`、`console-core.js`；样式限定现有 v2 局部层，按需新增 workspace-select 文件，路径是建议而非既有事实。

按现有页面结构增加紧凑选择摘要和弹层；ensureConversation、createChatConversation、发送、切换、会话载入与退出登录共同处理选择状态。先落地可见目录/API，再处理首次发送、刷新恢复、保存失败、请求代次、锁定后新建流程。使用 textContent 和键盘焦点管理，不随意替换既有 form ID。运行详情展示该 Run 保存的选择而非 catalog 当前名称。新增资源需保证 login 和跨页导航加载依赖完整。JS 行为测试验证请求/状态，不只查字符串存在；回归共享 v1 和既有审批/停止/失败逻辑。完成条件按清单 T5。

### T6：自动化与容器验证

实施后先执行 `java -version`、`mvn -v`，确认 JDK 21，再跑针对性的领域、server、adapter、console 测试与新增 JS 测试。Java 变更按触发清单核查实际中文 JavaDoc、record 所有组件、枚举每个常量、事务/安全/异常注释。

可采用的命令如下，实施时补入实际新增测试类，不能用筛选只跑旧测试来宣称新功能通过：

```powershell
java -version
mvn -v
mvn -q -pl cm-agent-core -am test
mvn -q -pl cm-agent-agentscope-adapter -am test
mvn -q -pl cm-agent-console -am test
node --test cm-agent-console/src/test/js/*.test.cjs
node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js
```

Server 选定 MockMvc/运行合同的 targeted Maven 命令按实际测试类列明，使用 test profile；若需临时覆盖 fake/真实 Runtime 开关只通过测试参数，保留原配置脏文件。完整 `mvn -q -pl cm-agent-server -am test` 与持久化测试可能包含容器用例，必须在 Rocky 执行，不能在本机误启动 Testcontainers。

通过 `ssh rocky` 验证 Docker、远程 HEAD 与待验证本地提交，使用 `maven:3.9.9-eclipse-temurin-21` 容器及 PostgreSQL `16-alpine`、MySQL `8.4`。存在未提交补丁时先形成可复核的提交/补丁一致性证据，不能只比 HEAD 就声称代码相同；本提示词不额外授权提交。远程路径/容器名先检查，不写未经核实的清理命令。执行 `mvn -q -pl cm-agent-persistence -am test`、server 全量及 Linux 文件合同。记录真实命令、提交、两库结果和注释检查；环境阻塞留确切原因。完成条件按清单 T6。

### T7：真实流程与文档

准备两个仅含不同无敏感标记的授权目录，验证选择 A/B 导致模型可见参考不同；分别在 ReAct、Harness 执行，区分捕获输入合同和真实 Provider 结果。覆盖 NONE、DEFAULT、停用、审批恢复旧正文、停止、刷新、退出登录、切换用户/Agent/会话和 JDBC 进程重启后恢复。浏览器批量检查桌面与 390px、键盘/焦点、长名称和错误状态；先检查一次，集中修复后确认，记录截图/操作证据。

同步 README、`docs/configuration.md`、`docs/operations.md`、`docs/release-notes.md`，说明服务器路径、卷配置、只读范围、旧调用兼容、撤销、预算、排空及回退。发布说明已有用户改动，实施时仅追加相关内容。回退前排空 SELECTED 活动/待审批 Run，保留数据库备份和元数据；不自动回滚既有迁移。未通过门禁不得宣称可发布。完成条件按清单 T7。

## 关联产物

- [清单](../checklists/2026-10-27-workspace-directory-selection-checklist.md)
- [提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)
- [设计](../specs/2026-10-27-workspace-directory-selection-design.md)
- [实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-design.md)
- [账本](../progress/2026-10-27-workspace-directory-selection-ledger.md)
