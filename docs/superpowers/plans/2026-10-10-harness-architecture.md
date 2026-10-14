# Harness 架构引入实施计划

日期：2026-10-10；主题：harness-architecture；修订：R1；修订日期：2026-10-10；模式：mode=default。

基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`。T0为R0分析交付，T9为R1当前依赖/草案核查完成；T1–T8仍待做。正式代码/POM未改，业务实施需另行授权并建立独立实施包。R1-01–R1-04见设计/账本。

## T0 当前交付

保留R0规范/源码/框架核查与六文档证据，T0不因R1新增依赖要求被改写为新的验收。R1另加T9，核心治理与阶段范围保持。

## R1 T9：当前依赖评估，已完成

已在Microsoft JDK21.0.11/Maven3.9.4下生成Server有效POM、当前完整reactor依赖树，核实当前localRepository为F:/mnt/f/java/maven-repository。直接读取Maven Central Harness2.0.2发布POM，使用当前Maven下载JAR并查2.0.2 API。

已复制当前12个POM到忽略目录的candidate-reactor，根管理Harness版本沿用agentscope.version，adapter加入optional、server显式加入；正式POM未改。候选dependency:tree成功，Server选定去重坐标从182到183，仅加Harness2.0.2，其他版本不变；独立MCP示例依赖解析也成功，客户端仍为0.17.0。

报告与草案位于cm-agent-server/target/harness-dependency-review-20261010；关键摘要与片段已写入设计，生成物可删除/重建，不入Git。当前完成的是依赖模型评估，没有编译或运行Harness，T1不能因此标完。

## A 批次 T1：兼容实验

依赖T0/T9，优先使用当前配套Harness/Core/Provider/Studio2.0.2；不再按旧默认.m2下2.0.0选型。基线变化时重新解析，否则复用T9有效POM/依赖证据，继续运行合同，不机械重跑完成项。Jackson2.19.0和SQLite3.49.1.0是项目最终覆盖版本；依赖可解析不等于二进制兼容。Server MCP2.0.0与示例0.17.0保持当前边界。

在未来授权的临时实验中编译，用本地 OpenAI Compatible Stub 验证 Harness/fromAgent、事件、ASK/ConfirmResult、Store 与生命周期；查默认工具/目录/写入/转录/线程。特别核实 disableSessionPersistence 与审批 State、Toolkit.copy 与桥接器共享门控、delegate 中断/关闭顺序。形成版本兼容矩阵；失败停留实验，不以依赖下载成功代替运行。

文件：根、adapter、server、示例 POM、候选框架源码和实验合同。依赖 T0；完成标准为可重复编译/运行证据。

## B 批次 T2：执行器与装配

拟在 adapter 增加 AgentScopeHarnessExecutor，通过现有策略接口接入；复用 Model、工具/Skill 网关、门控与结果合同，不复制整份执行器或跨模块重构。调整 RuntimeAdapter 工厂/取消能力，保留三参数函数式契约、自定义 Runtime 和嵌入式兼容。

Server RuntimeProperties/Configuration 拟增加 execution-mode=react|harness，默认 react、未知值拒绝。配置是未来设计，当前不存在。核查构建后的实际工具全集与别名，关闭/替换未经设计能力；审批所需 Store 继续存在，不猜开关行为。

实现排空切换门禁：非终态/等待 TEST/检查点未收口不得切换；同数据集不混部署策略。在线灰度需先修订持久化方案。文件：adapter executor/adapter/options/测试、server配置/测试。依赖 T1；完成标准为默认兼容、白名单负向与生命周期合同。

## B 批次 T3：审批、停止与状态

保持 tenantId:principalId 和 sessionId=runId，ConversationPromptComposer 继续负责历史。对照 RunExecutionService、ToolApprovalService、RepositoryAgentStateStore、RunControlRegistry，复用权限、哈希、快照/Grant、过期收口。

覆盖多轮、重复、拒绝、过期/停止/恢复竞争，独立无会话 Run 与 TEST 按既有支持范围保持。目标 Harness 中断接口与当前 ReActAgent.interrupt(RuntimeContext) 不同，须验证 delegate、订阅取消与真实工具退出；迟到结果不接受，其他终态状态清理。

新增失败分支按 AGENTS 补中文原因、HTTP/errorCode/errorId、日志/脱敏、严格审计传播与关闭失败优先级测试。文件：上述 runtime 接点、adapter及对应合同/MockMvc。依赖 T2；JDBC 验证只能在 Rocky。

## B 批次 T4：Skill、沙箱与产物

保留 SkillSession、受治理 Load/ExecutionBridge，检查 Harness 构建是否覆盖或重注册默认加载器；所有路径/别名必须授权。验证固定版本、候选 TEST、依赖、撤销、读取预算、审计失败与重复执行。

保留 ManagedSkillSandbox 的固定 Python，不开放 run_skill_code/通用 Shell；复用 SkillArtifactService 的加密、归属、PENDING/READY 与下载授权，不用 Harness 文件交付替代。文件：上述类和合同测试；依赖 T2/T3；真实 Docker/产物/JDBC重启在Rocky验收。

## C 批次 T5：只读工作区

Server 按可信 tenant/Agent 映射取部署管理员私有参考资料，准备 Run 时限额并固定版本/摘要，设计暂停恢复与清理。不增加 UI 上传或任意路径输入，不开放应用仓库/用户主目录。adapter 接收校验快照，实际 API/新文件位置由 T1 查证后按职责确认。

合成资料证明内容真正进入模型输入；覆盖跨租户、符号链接/路径逃逸、注入、资源中途改写、超限/缺失和清理失败。工作区不能覆盖权限或 Skill 快照；错误给关联诊断，不静默换策略重跑。记录新增 token/耗时/临时存储；原始正文不进普通日志。

依赖 T3/T4；完成标准为可观察增益、预算/隔离/生命周期合同。

## C 批次 T6：真实验收与回退

ReAct/Harness 双路径回归；真实 OpenAI Compatible/DashScope 与中文控制台验收续聊/SSE/审批/停止/TEST/产物下载，凭据仅验证、不记录。数据库/容器全部用Rocky。

演练停止新运行 → 核对非终态/待审批 TEST → 完成或显式取消 → 备份/版本检查 → 切模式 → 新 Run 验证；未知副作用人工核查，不自动重放。回退同样排空并检查旧二进制、依赖与状态格式，不只改配置。

更新根README、adapter README过时HITL/取消描述、configuration/operations/release-notes；记录生产边界和真实验收缺口。T6通过后才评估默认化，自动删除ReAct不在范围。依赖T5。

## 后续候选 T7/T8

T7明确压缩输入历史、摘要所有权、质量/成本与审批安全，禁止双历史注入。T8分别为记忆/子Agent/Plan Mode/持续工作区设计权限继承、预算、取消传播、保留期、并发/执行租约。各项另行授权，不一次开启全部默认能力，不计入首批完成。

## 未来验证命令

以下列出后续常用命令；Java/Maven环境与dependency:tree已由R1执行，业务test/node/SSH尚未执行。不能把依赖目标BUILD SUCCESS当作编译/测试通过：

~~~powershell
java -version
mvn -v
mvn -pl cm-agent-server -am dependency:tree -Dverbose
mvn -pl cm-agent-agentscope-adapter -am test
node --test cm-agent-console/src/test/js/chat-ux.test.cjs cm-agent-console/src/test/js/chat-stop.test.cjs cm-agent-console/src/test/js/skills.test.cjs
ssh rocky
~~~

Maven必须使用JDK21；effective POM输出留临时/忽略路径。server/持久化/全量测试可能包含JDBC/容器，遵守AGENTS：Rocky里 maven:3.9.9-eclipse-temurin-21，核对Docker/JDK21和远端待测提交，PostgreSQL16-alpine/MySQL8.4。禁止本机DockerDesktop、全局清理。浏览器验收不由JS单测替代。

## 关联产物

- [清单](../checklists/2026-10-10-harness-architecture-checklist.md)
- [提示词](../prompts/2026-10-10-harness-architecture-prompt.md)
- [设计](../specs/2026-10-10-harness-architecture-design.md)
- [实现说明](../implementation/2026-10-10-harness-architecture-implementation-design.md)
- [账本](../progress/2026-10-10-harness-architecture-ledger.md)
