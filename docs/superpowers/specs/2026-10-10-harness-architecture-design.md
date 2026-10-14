# Harness 架构引入需求设计

日期：2026-10-10；主题：harness-architecture；修订：R1；修订日期：2026-10-10；模式：mode=default。

R1 以当前项目有效 POM 和实际依赖树为准，允许生成依赖报告、下载候选构件与隔离 POM 草案；不修改正式代码、POM、配置或数据库。Git 基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`。后续业务实施需要用户明确授权，建立独立实施工作包引用本包。

## 需求与推荐

用户希望项目采用 Harness，并判断是直接新增架构还是将既有实现全部改掉。

推荐**保留平台治理，渐进引入 Harness 执行层**：在既有 `AgentRuntime → AgentScopeRuntimeAdapter → AgentScopeExecutor` 边界增加受控执行策略，分阶段迁移适合由 Harness 承担的运行能力。认证、多租户、RBAC、工具授权、审批、审计、会话、Skill 发布和产物治理继续由 CM Agent 负责。

Harness 与 ReAct 不互斥。官方资料说明 Harness 在基础 Agent 能力上组合工作区、记忆、技能、子 Agent、沙箱等能力。接入期保留 ReAct 默认和回退能力；通过验收再评估 Harness 默认化。无需一次重写整个平台，也不能只加入依赖便宣称完成 Harness 架构。

“Harness”本轮按 AgentScope Java Harness 理解，这是依据当前 Java 项目作出的自主假设，不是用户已确认要接入 DeepSeek DSH 或更换语言。首项业务增益暂选部署管理的只读工作区上下文，用户后续指定具体目标时可以修订。

## 本次当前证据

E1–E10 保留 R0 核查记录，E11–E13 是 R1 当前依赖解析证据；没有编译、模型、浏览器或业务测试证明。

| 编号 | 文件/来源 | 事实与边界 |
| --- | --- | --- |
| E1 | 根 pom.xml:28-35；adapter/core/server POM | Java release 21；Core 声明 2.0.2；Server MCP 声明 2.0.0；adapter 框架依赖 optional，core 不直接依赖 AgentScope；相关 POM 未声明 Harness。声明不等于未来最终解析版本。 |
| E2 | cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeExecutor.java、AgentScopeRuntimeAdapter.java:40-111 | 有包内执行策略接口、结构化事件和审批恢复入口；工厂当前构造 ReAct；取消能力依赖具体 ReAct 类型判断，新策略需调整。 |
| E3 | 同包 AgentScopeReActExecutor.java:337-414,488-582 | 每 Run 创建 Toolkit/Model/RuntimeContext/Agent；userId=tenantId:principalId，sessionId=runId；只有 WAITING_APPROVAL 保留状态，其他终态清理。工具只尝试一次，关闭元工具和任务列表。 |
| E4 | cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java:237-239、ConversationPromptComposer.java:21-22,62-100 | 会话历史由 CM Agent 拼接，最多 40 条/60,000 字符；历史仅参考，不能授予权限。 |
| E5 | server/config/AgentScopeRuntimeConfiguration.java、runtime/RepositoryAgentStateStore.java（位于 com/cmagent/server 包） | 状态经 tenant 隔离、AES/GCM 加密与审批 TTL 的检查点仓储保存；不是永久记忆库。 |
| E6 | adapter 的 AgentScopeSkillSession.java；server 的 GovernedSkillAccessService.java | 原生 SkillBox 加固定版本快照、受治理加载/执行桥；关闭任意原生代码执行，按部署能力开放包内 Python 的 run_skill_script。 |
| E7 | server 的 GovernedToolInvocationService.java:69-89、GovernedToolExecutionService.java；core 的 ToolInvocationGateway.java | 业务工具每次按 tenant、ToolGrant 和状态复核，不能直接根据 endpoint 调用；严格审计/持久化失败必须传播。 |
| E8 | README.md:117,168,194,212；docs/operations.md:58-64,78-88 | 已有会话、审批过期、停止本轮和加密文件产物；会话锁不是跨实例租约，崩溃后不自动重放副作用。 |
| E9 | R0 历史线索：本机 .m2 下 io/agentscope/agentscope-harness/2.0.0 的 POM、javap 与 fromAgent 字节码 | Harness POM 引用 Core2.0.0；曾核查迁移与生命周期接口。R1 已确认该目录不是当前 Maven 使用的本地仓库，不作为候选版本依据。 |
| E10 | 本次 Context7 三次查询与官方架构页 | 确认能力组合和默认工具/状态风险；在线架构页已包含 Session Log/EVENT_LOG，不能作为本地 2.0.0 的版本契约。 |
| E11 | 当前 Server effective POM 与完整 reactor dependency:tree | Core/两种 Provider/Studio 实际均为 2.0.2，MCP core/json为2.0.0，Jackson为2.19.0；Maven使用 F:/mnt/f/java/maven-repository，不是默认 .m2。 |
| E12 | Maven Central 发布 POM、当前 Maven 下载的 Harness2.0.2 JAR 与 javap | Harness2.0.2 确实发布，其 POM 引用 Core2.0.2；迁移、Store、权限、streamEvents/RuntimeContext、delegate、中断与close签名可见；不等于生命周期运行通过。 |
| E13 | 保留当前12个POM结构的隔离候选reactor与依赖对照JSON | 只在草案根/adapter/server加Harness2.0.2；Server已选依赖从182项到183项，仅增加Harness，无移除/既有版本变化；没有构建/测试。 |

CodeGraph 已先定位，再补查其省略区间与未展示文件。adapter README 的部分能力边界仍写“不提供 HITL/手动取消”，与当前源码、根 README 不一致；本轮不改，未来 T6 同步纠正，不能按旧说明重复实现。

原有 .gitignore、console index.html、server application*.yml 与未跟踪 .codex/、.workbuddy/ 保持不动。application.yml 是脏文件，所见开关不能证明部署环境启用状态。历史分析只辅助定位，当前核心结论已重新核查。

## 三种方案与代价

| 方案 | 收益 | 改动与风险 | 结论 |
| --- | --- | --- | --- |
| 只加依赖、直接使用默认 Builder | 很快展示 Harness 入口 | 默认文件/Shell/记忆、目录扫描与状态可能绕过治理；缺乏业务收益和兼容证据 | 不采用 |
| 受控接入、分阶段替代运行能力 | 复用平台与数据，可验证、可回退 | 依赖兼容、执行器能力、默认工具限制、状态语义与双路径回归；接入期维护两条执行路径 | 推荐 |
| 全部重写 | 潜在统一框架生命周期 | 会话/审批/权限/Skill/沙箱/持久化/控制台大改；数据迁移、审计重建、API 兼容与回退成本最高 | 当前无足够业务依据，不采用 |

最小接入的成本主要是依赖、执行器、装配与合同测试；生产试点还需审批、停止、Skill、产物、运维和浏览器验收；永久记忆、子 Agent 会继续增加权限继承、预算、并发、持久化与恢复成本。本轮未测量排期，不提供承诺人日。关闭全部新增能力的封装仅算技术实验，不能算业务需求完成。

## 目标边界

~~~mermaid
flowchart TD
  U[现有中文控制台与 API] --> S[CM Agent 会话、运行与审批服务]
  S --> R[AgentRuntime / AgentScopeRuntimeAdapter]
  R --> X[执行策略选择]
  X --> A[现有 ReAct 执行器]
  X --> H[拟新增受控 Harness 执行器]
  A --> G[工具与 Skill 治理网关]
  H --> G
  G --> B[LOCAL / HTTP 工具与固定 Python 沙箱]
  S --> P[会话、审批、审计、检查点与产物仓储]
~~~

图中的 Harness 分支尚未实现。继续注入按租户凭据创建的 Model 实例，不用环境变量模型字符串绕过模型配置管理。core 不泄露 Harness 类型，adapter 保持无 Spring/JDBC，Server 负责基础设施装配。

| 状态 | 首阶段所有者 | 约束 |
| --- | --- | --- |
| 会话消息 | CM Agent Conversation Repository | 沿用历史拼接，不启用另一套自动历史回灌。 |
| 推理/审批状态 | 框架 AgentState，经 RepositoryAgentStateStore | sessionId=runId；原 Run 恢复；保留加密与 TTL；其他终态清理。 |
| 身份、权限、决定 | CM Agent 服务/治理网关 | 来自认证上下文和固定快照；Harness 权限只协助暂停，不授予业务权限。 |
| 只读工作区 | T5 拟新增部署管理资源快照 | tenant/Agent/Run 隔离，冻结版本/摘要/预算；不读源码仓库或用户主目录。 |
| Skill、沙箱、产物 | 现有发布/读取/执行/下载治理 | 不新增第二套 Skill 发布、通用 Shell 或文件交付。 |
| 压缩/长期记忆/子任务 | 后续单独设计 | 不借审批 TTL 表存永久记忆，不承诺跨实例自动接管。 |

### 模式选择与回退

拟新增服务端键 `cm-agent.agentscope.execution-mode=react|harness`，默认 react；当前不存在此配置。首阶段不增加 Agent 模式字段、控制台选择器或数据库迁移。

采用停机排空切换：停止新运行，完成或显式收口所有 RUNNING、CANCELLING、WAITING_APPROVAL 与 TEST，再切模式。启动门禁必须核查非终态和检查点；无法确定历史检查点所属策略时拒绝切换，不能用全局配置猜测恢复方式。同一数据集不允许混部署不同策略的实例，A/B 仅在隔离测试数据集进行。

若需要按 Agent 灰度或不中断切换，另行设计执行模式、框架版本、检查点格式的持久化标记和方言迁移。回退仅作用后续新 Run；Harness 已产生副作用后失败，禁止自动换 ReAct 重跑。框架依赖/状态变化时，还要验证旧二进制回退，不能只回切配置。旧执行器不无限期双份维护，退出条件单独评估。

### 默认能力与资源限制

逐项在目标版本源码和合同测试中核查并关闭/替换：文件/Shell/Web、MCP tools.json、默认目录与动态 Skill、子 Agent、记忆工具/Hook、自动压缩、工具结果落盘、转录/trace、后台线程。不能认为“给自己的 Toolkit”足以排除默认工具；构建后实际工具全集与所有别名必须受控。

R1 已检查实际下载的 Harness2.0.2，javap仍未显示 disableWebTools，不能照抄在线新版 API；若目标构件注册 Web 工具，需用真实开关或受控移除证明不可绕过。disableSessionPersistence 等名称不能当安全配方，必须验证是否影响审批所需 State/Store。fromAgent 的 Toolkit.copy、桥接器门控、Hook、delegate 与关闭关系也需在2.0.2上验证，R0字节码检查不代替新版本行为。无法证明默认拒绝或审批状态兼容，停留在实验，不进入生产。

T5 的首项增益是部署管理员提供的只读参考资料。Server 从受控私有目录按可信 tenant/Agent 映射取资源，在准备 Run 时限额并冻结版本/摘要；不增加上传/编辑管理页面。具体适配 API 在 T1 核实，不虚构当前已有 workspace 配置。

不开放应用仓库 AGENTS.md、本机 MEMORY.md、环境文件、配置、用户主目录或模型指定任意路径。资料只作参考，不能覆盖权限；跨租户、符号链接/路径逃逸、注入、缺失和超限必须安全拒绝。Run 暂停/恢复时仍用原快照；资源错误产生关联诊断，不静默换策略重跑。记录额外 token、耗时、临时存储与清理情况，证明新增能力实际有用。

## 批次与验收

| 批次 | 任务 | 目标与门禁 |
| --- | --- | --- |
| 当前分析 | T0、T9 | T0保留R0分析完成；T9完成R1当前依赖/草案核查；不实施业务。 |
| A 兼容实验 | T1 | 候选版本、实际依赖与生命周期合同通过；不是仅查签名。 |
| B 受控接入 | T2–T4 | 执行器、审批/停止/状态、Skill/沙箱/产物合同保持，仍默认 ReAct。 |
| C 可用试点 | T5–T6 | 只读上下文的可观察增益、真实模型/浏览器/远程验证与回退。 |
| 后续候选 | T7、T8 | 压缩；长期记忆/子 Agent/计划/持续工作区，另行明确目标与授权。 |

首阶段保留现有中文会话、运行、Skill TEST、审批操作；错误沿用 errorCode/errorId。新计划面板、子任务列表和文件编辑器不在首批。

1. ReAct 默认、无 Skill 运行、自定义 Runtime 和嵌入式兼容；core/adapter 模块边界保持。
2. 业务工具与 Skill 的所有路径/别名经过治理；没有默认 Shell/Web/MCP/文件/目录 Skill 旁路。
3. 覆盖 LOW/MEDIUM、HIGH ASK、允许/拒绝、多轮、过期、重复、输入哈希变化、Grant/版本撤销；无会话 Run 和 TEST 按当前支持范围保持，不扩权。
4. 审批恢复沿用原 Run 状态且不重复注入历史，新 Run 不读旧槽；终态/停止/过期清理；崩溃不自动重放副作用。
5. 中断、超时和停止等待真实工具退出，抑制迟到结果；审计/持久化/关闭失败不能伪报成功。
6. Skill 发布快照、依赖不授予工具、预算、防重、固定 Python 以及产物加密/归属/下载边界保持。
7. replyId/blockId、SSE/HTTP 状态、中文原因、errorCode/errorId 和日志一致，凭据/内部地址/堆栈/原始载荷不泄露。
8. 只读资料实际进入输入，同 Run 不受中途改写影响；跨租户/路径/注入/预算合同通过，增量开销与清理可观察。
9. 未排空不能切模式；真实双 Provider、浏览器、Rocky 与回退验证通过后才评估默认化。

## 自主决定与未验证项

| 编号 | 类型 | 内容与影响 |
| --- | --- | --- |
| D1 | 自主建议 | 渐进接入、保留治理与 API；不是用户已确认实施。 |
| D2 | 自主范围 | 首项只读工作区上下文；后续可按用户目标修订。 |
| D3 | 自主安全方案 | 首阶段停机排空切换；不中断灰度需要另行持久化设计。 |
| A1 | 假设 | Harness 指 AgentScope Java，不接入 DSH。 |
| D4 | 用户明确约束 | “以当前项目为准，也可以生成项目依赖”；使用有效POM/实际解析，生成隔离草案，不把旧缓存当版本基准。 |
| U1 | 部分核实 | 候选Harness/Core/Provider/Studio均为2.0.2，当前与草案依赖解析通过；生产适用性、Jackson/MCP二进制及运行行为仍未验证。 |
| U2 | 未验证 | 默认工具、状态机制、隐式写入与资源所有权。 |
| U3 | 未验证 | 工作区实现、运行质量/成本、部署与回退。 |

R0：2026-10-10 新建独立分析包，旧阶段三“不引入 Harness”属历史边界，本轮提出未来扩展，不覆盖旧记录。无代码实施、无旧包修订或回滚。

## R1 当前项目依赖方案

优先候选为 `io.agentscope:agentscope-harness:2.0.2`，沿用当前 `agentscope.version`，不因仓库存在较新版本就升级全工程。版本号一致和依赖解析成功不能证明运行兼容，T1仍待做。

| 依赖 | 当前实际版本 | 候选草案版本 | 说明 |
| --- | --- | --- | --- |
| agentscope-core / openai / dashscope / studio | 2.0.2 | 2.0.2 | 保持当前项目组合。 |
| agentscope-harness | 未引入 | 2.0.2 | 唯一新增的已选Server依赖。 |
| mcp-core / mcp-json-jackson2 | 2.0.0 | 2.0.0 | 不因Harness引入改变；Core传递mcp聚合构件0.17.0也仍存在，相关客户端兼容须测试。 |
| jackson-core / annotations / databind / dataformat-yaml | 2.19.0 | 2.19.0 | 项目BOM管理；Harness发布POM引用yaml2.21.1，最终受项目覆盖，需实际运行验证。 |
| commons-compress | 1.27.1 | 1.27.1 | 已存在。 |
| sqlite-jdbc | 3.49.1.0 | 3.49.1.0 | 已通过Core存在，项目管理覆盖发布POM的3.47.1.0；不能说Harness新增SQLite。 |
| Testcontainers | 1.21.0 | 1.21.0 | 当前最终解析，不能当成根属性2.0.5。 |

“仅增加一项”按Server完整树中未标为omitted的、去重坐标（含scope）比较，不是JAR大小、工具能力或运行风险只增加一项的结论。

草案生成在已忽略的 `cm-agent-server/target/harness-dependency-review-20261010/candidate-reactor`，复制当前12个POM保留reactor与BOM，仅修改三份草案。正式POM未变。设计中的依赖片段如下，尚未应用：

根 dependencyManagement：

~~~xml
<!-- Harness候选沿用当前框架版本，避免只升级运行层造成版本漂移。 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
~~~

adapter dependencies：

~~~xml
<!-- 保持嵌入式依赖边界，由使用者选择是否装配Harness。 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <optional>true</optional>
</dependency>
~~~

server dependencies：

~~~xml
<!-- adapter依赖为optional，独立Server需要显式声明运行依赖。 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
</dependency>
~~~

不在core或Spring Starter加入Harness，不使用systemPath，不引入第二套版本属性。候选解析没有新建数据库、启用框架SQLite状态后端或增加模型/工具能力。

### R1 差异记录

| 编号 | 类别 | 原R0 → 当前R1 | 来源/依据 | 影响 | 采用 |
| --- | --- | --- | --- | --- | --- |
| R1-01 | 澄清 | 旧缓存作为API线索 → 当前项目有效POM、实际仓库与依赖树为准 | 用户本轮要求、E11 | D4、U1、T1 | 已采用 |
| R1-02 | 新增 | 无同版本候选证据 → 优先Harness2.0.2，实际下载/签名/解析可复核 | E12/E13 | 新任务T9完成；T1运行验收仍待做 | 已采用 |
| R1-03 | 调整 | 可能新增yaml/SQLite → 当前已存在，草案只新增Harness坐标 | 依赖对照 | T1聚焦BOM覆盖与二进制/运行，不做无关升级 | 已采用 |
| R1-04 | 澄清 | 仅六文档 → 可生成忽略目录内依赖报告与POM草案，正式代码/POM未修改 | 用户授权范围延续 | 六产物、提示词和账本；无业务实施 | 已采用 |

## 外部参考

- [官方 Harness 架构](https://java.agentscope.io/v2/zh/docs/harness/architecture)：2026-10-10 读取，含较新 Session Log/EVENT_LOG，作能力参考。
- [Harness 配置](https://github.com/agentscope-ai-java/agentscope-java/blob/master/docs/v2/en/docs/harness/configuration.md)：Context7 检索并读取，默认能力需审查，不固定到本地 2.0.0。
- [官方 Context](https://java.agentscope.io/v2/en/docs/building-blocks/context)：Context7 状态资料，版本契约以候选构件为准。
- [Harness2.0.2发布POM](https://repo.maven.apache.org/maven2/io/agentscope/agentscope-harness/2.0.2/agentscope-harness-2.0.2.pom)：R1直接HTTP读取成功，与当前项目Core2.0.2配套；web读取器不可访问不等于构件不存在。

## 关联产物

- [清单](../checklists/2026-10-10-harness-architecture-checklist.md)
- [提示词](../prompts/2026-10-10-harness-architecture-prompt.md)
- [计划](../plans/2026-10-10-harness-architecture.md)
- [实现说明](../implementation/2026-10-10-harness-architecture-implementation-design.md)
- [账本](../progress/2026-10-10-harness-architecture-ledger.md)
