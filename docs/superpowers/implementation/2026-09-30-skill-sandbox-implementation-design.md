# 技能沙箱实现说明

日期沿用任务启动日 2026-09-30；验证跨日到 2026-10-01。初始 HEAD 为 `a72f9228eb07242f303d5e033adaf8b1a56e6241`，任务期间 HEAD 前移到 `5c60ad53372f231f004bf7057b96c92ad07cc441`，已重新核对并在远端采用相同 HEAD。用户于 2026-10-01 授权本地提交，本任务代码、测试及六份工作包纳入同一次提交；提交编号以本文件对应的 Git 历史为准，未推送、未部署。

用户已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、`.codex/` 和 `.workbuddy/` 改动保持不动。远程采用 HEAD 配置加本任务明确文件覆盖，不复制这些未提交配置或凭据。CodeGraph 索引无法定位当前 Skill 类，已先调用并回退源码核对。

## 实际交付

| 编号 | 状态 | 目标与文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| T0 | 完成 | 核实 Skill 链路、工作树与环境，生成六份文档 | 无 | 六份产物齐全，已有配置及工作包保留 |
| T1 | 完成 | Core 网关默认执行扩展；SkillSandboxProperties、DockerSkillSandbox | T0 | 默认关闭；固定命令；有界 stdin、输出、并发、超时及唯一容器清理 |
| T2 | 完成 | GovernedSkillAccessService、AgentScopeSkillExecutionBridge 与准备状态 | T1 | 固定快照、租户/主体、运行状态、撤销、持久化预算、重复拒绝、严格审计、发布门禁测试 |
| T3 | 完成 | 能力接口、部署 profile、V15 状态注释、中文状态标签与生产说明 | T2 | 策略与导入白名单一致；不改变表结构；准备不显示为成功 |
| T4 | 完成 | Java 21 回归、Rocky 真实容器与双数据库验证、工作包核查 | T1,T2,T3 | 首轮全量通过；最终专项 215 项、JS 85 项通过，哈希/文档/零残留已核对 |

首版只执行导入技能包内的 Python 3 脚本和同版本文本资源，模型不能提交代码、命令、镜像、runtime、环境变量或宿主路径。Docker 参数固定为禁网、非 root、只读根文件系统、无宿主挂载、无额外 capabilities、no-new-privileges、CPU 0.5 核、内存与交换内存各 128 MiB、PID 32、nofile 64；私有 /workspace 与 /tmp 各 16 MiB。默认执行 15 秒、stdin 与原始合并输出各 32768 字节、单实例最多 2 个容器；只允许收紧。沙箱默认关闭，开启后 .py 自动进入有效导入白名单，既有资源类型不改变。

- Core：SkillAccessGateway 保持函数式读取契约，增加默认关闭的执行方法；ApiErrorCode 增加关闭、非法、重复、限额、超时、不可用和脚本失败分类。
- Adapter：AgentScopeSkillExecutionBridge 仅定位固定资源；AgentScopeSkillSession 保留原生执行关闭，按策略注册独立执行工具；AgentScopeRunGate 的业务调用前后检查技能致命失败。
- Server：SkillSandboxProperties 与 SkillProperties 提供可收紧策略；DockerSkillSandbox 固定参数、私有 tmpfs、受控环境、stdin 数据传输、并发限流、管道处理与退出清理；GovernedSkillAccessService 完成事务内准备、持久化防重、STARTED 审计、事务外执行与最终复核。审计或持久化故障不伪装成功，未知异常消息不进入日志。
- 准备记录：新增 SANDBOX_PREPARED，deliveredBytes 为 0；预算按该记录的固定版本计算，普通读取不能重放准备记录；SkillTrialService 的既有 SUCCEEDED 判断不会计入准备状态。准备、开始与终态执行审计独立于模型读取成功。
- 能力/部署：SkillResponses 与 SkillController 公开启用状态、python 与时间/输入输出限额；application-skill-sandbox.yml 默认仍关闭。README 与发布说明维护生产限制，原有用户配置未改。
- 持久化/控制台：V15 双方言迁移仅更新 status 中文原生注释；JdbcSkillRepositoriesTest 与 MigrationTest 验证新状态往返、tenant 隔离、双库注释和升级。console-core.js 只新增“沙箱资源已准备”中性状态文案。

## 方案差异

资源准备采用独立状态，未沿用 SUCCEEDED，避免虚假的模型交付和 TEST 门禁通过。由此补充 V15 注释迁移和中文标签，没有新增表或列。首版不提供其他语言、联网安装、导出文件、持久工作区或自动接管。

## 文件范围

- `README.md`
- `cm-agent-agentscope-adapter/README.md`
- `cm-agent-api/src/main/java/com/cmagent/api/ApiErrorCode.java`
- `cm-agent-core/src/main/java/com/cmagent/core/runtime/SkillAccessGateway.java`
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRunGate.java`
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillSession.java`
- `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeSkillExecutionBridge.java`
- `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillExecutionBridgeTest.java`
- `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeSkillRuntimeContractTest.java`
- `cm-agent-server/src/main/java/com/cmagent/server/config/SkillProperties.java`
- `cm-agent-server/src/main/java/com/cmagent/server/config/SkillSandboxProperties.java`
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/DockerSkillSandbox.java`
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/GovernedSkillAccessService.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/ApiExceptionHandler.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/SkillController.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java`
- `cm-agent-server/src/main/resources/application-skill-sandbox.yml`
- `cm-agent-server/src/test/java/com/cmagent/server/config/SkillPropertiesTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/DockerSkillSandboxTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/DockerSkillSandboxIntegrationTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/GovernedSkillAccessServiceTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/SkillControllerTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/ApiExceptionHandlerTest.java`
- `docs/release-notes.md`
- `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillLoadStatus.java`
- `cm-agent-core/src/main/java/com/cmagent/core/domain/SkillLoadRecord.java`
- `cm-agent-core/src/test/java/com/cmagent/core/domain/SkillDomainTest.java`
- `cm-agent-persistence/src/main/resources/db/migration/mysql/V15__document_skill_sandbox_preparation.sql`
- `cm-agent-persistence/src/main/resources/db/migration/postgresql/V15__document_skill_sandbox_preparation.sql`
- `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcSkillRepositoriesTest.java`
- `cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`
- `cm-agent-console/src/test/js/console-core.test.cjs`
- `cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillTrialServiceTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/SkillSandboxCapabilitiesTest.java`

六份工作包随实现同步。最终专项复验 215 项、JS 85 项通过；真实容器、双库 V15、哈希、六份文档链接与零残留已核对，详细证据见账本。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)
