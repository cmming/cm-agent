# 技能沙箱文件产物实施计划

> 当前交付与剩余验收见文末 R0 当前交付状态；前文仅文档/待做描述为历史快照。

日期：2026-10-08｜主题：skill-artifacts｜修订：R0｜工作模式：mode=default

本工作包是独立新增需求，关联原技能沙箱工作包；本轮交付需求分析与六份文档，业务实施尚未执行。普通工程细节按默认模式自主取舍，所列方案是自主假设，不代表用户逐项确认。

[清单](../checklists/2026-10-08-skill-artifacts-checklist.md) · [执行提示词](../prompts/2026-10-08-skill-artifacts-prompt.md) · [设计](../specs/2026-10-08-skill-artifacts-design.md) · [实现说明](../implementation/2026-10-08-skill-artifacts-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-artifacts-ledger.md)

本文件描述未来实施方式，任务完成情况以清单和账本为准。所有新增类名、接口路径与配置均为方案线索，不声称已经存在。

## T0 当前证据与文档

已读取仓库 AGENTS.md、技能契约、相关五个模块 POM、README、配置/运维文档、沙箱与 Run/TEST 权限源码。CodeGraph 优先调用未定位产物实现，直接源文件复核未见产物链路。检查原六份沙箱路径与当前 V18，不重复实现旧沙箱能力。本轮新增当前六份文档，验证结果见账本。

## T1 契约与策略

在 core/domain 增加产物元数据、状态等领域对象，在 core/runtime 增加结构化结果、接收器/句柄及兼容默认方法，在 core/repository 定义元数据 Repository；具体命名在实施时保持现有包职责。读取当前 SkillSandboxBackend、SkillAccessGateway、AgentScopeSkillExecutionBridge 后确定接口兼容方式，不让新二进制流进入旧 String 通道。

在 SkillSandboxProperties 或贴近其职责的嵌套属性中绑定 artifacts 配置，补齐中文字段说明、校验和 IDE 配置元数据。独立于技能导入/模型读取/文本输出限额，并经能力 API 返回同一配置快照。对 memory 与 jdbc 装配明确：memory 只用于 local/test，生产元数据必须持久化；artifact 开关默认 false，缺根目录/密钥拒绝开启。

验收：旧扩展实现与文本工具仍可运行；配置缺失、非法路径、数量/字节/超时组合可观测；拟定配额和工具总超时可验证。先写 meaningful 边界测试，不写镜像实现的机械断言。

## T2 Docker 输出协议和收集

改动 DockerSkillSandbox 固定引导程序：创建固定输出目录、设定固定环境变量、运行包内 Python 子进程、限制 stdout/stderr、在脚本退出后且容器退出前发送文件帧。保留现有 --rm、安全资源参数、原输入与限额。禁止依赖退出后 docker cp/host mount。

服务端有界解析协议，安全处理非法头、截断、尾随、计数、路径、类型、实际长度；以流传给 T3 暂存接收器。校验无软/硬链接、特殊文件和遍历预算，抵御残留子进程造成的文件替换/增长；协议中所有关联元数据不可作为可信租户来源。

同步 ManagedSkillSandbox 的限流、timeout、verifyAccess、close 与异常归类，确保同一 daemon 传输，旧后端 default 方法无需支持产物也可工作。新增关闭失败、文件超限、并发、工具超时与原文本输出回归。LOCAL、SSH 私钥/密码、TLS 在 Rocky 专用夹具执行，不读取真实用户认证材料。

## T3 元数据、文件卷、加密和补偿

新增 Jdbc*ArtifactRepository、local/test memory 实现及配置装配；先核对最新迁移编号，新增 MySQL/PostgreSQL 同编号方言脚本，不修改 V11/V18。Schema 至少覆盖设计列、归属、幂等、过期与清理索引；所有表/字段中文原生注释。MigrationTest 与 Repository 测试验证注释和 tenant 隔离。

实现随机对象键、受控根目录、拒绝符号链接根路径逃逸、staging/原子移动、独立 AES/GCM 二进制加密、摘要和生命周期；不把文件字节放数据库或 ModelCredentialCipher 的 Base64 文本格式。部署根目录不得暴露为 Web 资源。

租户/Run 配额预留使用跨实例有效数据库条件更新或等效事务策略，锁时间限于元数据操作；容器与文件 I/O 在事务外。补偿测试覆盖磁盘错误、加密/校验失败、元数据失败、进程崩溃模拟、孤儿暂存、重复调用、过期与下载/清理竞争。多实例扫描需有租约/防重，配额只在实际删除确认后释放。开启文件存储必须使用隔离临时持久目录测试，不检查/删除用户现有文件。

## T4 Run 与 TEST 生命周期

接入 GovernedSkillAccessService、RunExecutionService、RunPersistenceService、SkillTrialService 和审批恢复边界，必要时复核 ConversationService。调用级只生成 PENDING，端点撤销/授权失败/清理失败/严格审计失败立即阻止交付并补偿。

成功 Run 持久化与审计完成后才发布 READY；失败/拒绝/超时/审批过期/检查点失败清理。审批暂停不提前下载，恢复后旧产物按原幂等键保留，新调用共享 Run 总量。并发恢复、同一调用重放和状态发布失败都有回归；无二进制进入 ReActAgent 状态或文本输出。

X1 的真实检查点超限单独复现/记录；该修复不纳入本工作包的代码实施授权。无法走通真实模型流程时，完成可独立验证项并记录缺口，不关闭状态保存或截断用户数据。

## T5 鉴权列表和下载

新增贴近 web 包职责的产物 Controller，服务编排负责 owner/Run 状态/储存生命周期，Repository 不依赖 Web 或 Security DTO。按设计接口提供分页或有界列表与完整下载，参数只是查找标识，tenant/principal 来自当前认证。

NORMAL 校验 owner + agent:read，TEST 还校验 skill:read 和原 TEST owner，会话 Run 校验会话归属。跨 tenant、另一主体、skillId/agentId/runId 不匹配均不泄露产物存在。旧运行详情权限不扩大。失败分支建立稳定 errorCode/errorId、唯一日志边界及审计。

下载前完成解密认证、摘要、大小验证，审计成功后发送 attachment；安全中文文件名、受控 MIME、Content-Length、nosniff、no-store。接口测试覆盖 401、403、404、410、413、503，缺文件/损坏/审计失败无内容交付，日志/响应编号一致、真实凭据和路径不外泄；流中断单独验证日志与释放租约。

## T6 控制台

真实路径线索：console/v2/assets/skills.js、公共 assets/app.js、console/v2/skills.html、runs.html、chat.html。先阅读实际请求和动态导航生命周期，复用当前会话机制，不假定 JWT 保存在某个字段或 Cookie 必然可用。

在 TEST 结果、运行详情和聊天回复增加文件卡片，读取后端列表，调用有权限的下载 API；采用 fetch/Blob 或与真实会话机制等价的可鉴权方案，不把令牌塞到 URL。处理过期/无权限/下载中断及重新下载，防重复点击造成无界内存，不重跑技能。组件销毁/换页/登出/换租户时释放资源并阻止迟到更新。

验证 Node 逻辑、ConsoleResourceTest，真实浏览器完成生成结果查看、下载、刷新、从别页进入、后退、身份切换、390px 移动布局；检查实际下载的文件。只把完成的交互写为通过，浏览器权限/连接阻塞写精确缺口。

## T7 集成与端到端门禁

创建本项目测试夹具技能：包内固定 Python 读取 stdin 的标题/段落字段，在 CM_AGENT_ARTIFACT_DIR 以 Python 标准库 ZIP/XML 生成最小合法 DOCX；避免依赖远程 npm/pip 或真实凭据。不新增任意代码执行，用户数据只作为受限文本。下载后验证必要 OOXML 成员、内容、长度和服务端 SHA-256；同时验证 PDF/PPTX 等类型策略，不把后缀通过当作格式有效。

全量安全矩阵包括：无产物旧文本行为、多产物、同名调用幂等、禁网与无 host mount、远程端点固定、跨 tenant/owner拒绝、非法帧/链接/目录穿越、每一档配额、审计/存储/数据库/状态保存失败、审批暂停恢复、重启保留、过期清理、下载清理竞争。

Docker/JDBC/Flyway 一律 ssh rocky，以 maven:3.9.9-eclipse-temurin-21 容器执行。先确认 Docker、Maven JDK21、远程 Git HEAD 与待测源码一致；未提交时以同 HEAD + 明确文件覆盖 SHA-256 清单确认，不复制脏配置/凭据。使用 PostgreSQL 16-alpine、MySQL 8.4，仅项目 Testcontainers/Compose 服务。

拟运行命令：
- 本地 Java 环境检查：java -version、mvn -v；本地只运行不触发 Docker 的 core/适配器快速测试。
- Rocky 容器内：mvn -pl cm-agent-persistence -am test；mvn -pl cm-agent-server -am test。须按现有测试开关确认容器用例确实运行，零跳过不能由未开启环境变量冒充。
- Console：读取当前 package/scripts 或测试路径后执行现有 Node 测试与 ConsoleResourceTest。
- 真模型隔离 TEST：需要可用测试模型与合适镜像，X1 解决且不改用户运行服务；独立记录，并与固定夹具结果区分。

若运行中代码发生变化，核对 source hashes 后只重跑受影响测试。SSH、镜像、Docker、数据库或浏览器不可用时记录实际错误、任务、未执行验收和继续条件，不执行全局清理。

## T8 生产说明与最终记录

实施完成后更新 README、docs/configuration.md、docs/operations.md、docs/skill-sandbox-endpoints.md、docs/release-notes.md；说明存储卷、独立密钥、限额、保留期、备份、清理、权限、DOCX 测试技能与外部技能兼容限制。业务尚未交付时不先将这些写成现有功能。

同步本组六份文档，记录代码完成与测试完成的不同状态。无额外授权不提交、不推送、不合并、不部署。最终按 AGENTS.md 报告变更、实际命令与结果、影响、风险和直接后续建议。

## R0 实施续篇（2026-10-08）

用户已授权实际实施 T1～T8。以上“仅文档/未实施”描述保留为最初分析阶段历史；当前代码已进入实施与验证，尚未提交，不推送、不合并、不部署。实施基线 HEAD 为 `af7b2fac416fc43a545513c0061aa8a4230bbab7`，分支 `codex/skills-version-console`，暂存区为空；保留用户无关 application 配置、根 index.html、.codex/ 和 .workbuddy/。最新实际状态以本组六份文件的续篇和账本为准，不用旧验证代替新验收。

当前实现覆盖产物领域/SPI、独立配置与能力 API；固定容器引导程序、目录扫描和有界帧；AES/GCM 私有卷、V19 双库元数据、跨实例配额与租约；治理和 Run 发布；正式/会话/TEST 列表与统一下载；v2 三个结果区域及会话 fetch/Blob 组件。此段为验证开始时快照；最新状态见下方 R0 当前交付状态。

工程取舍：增加 STAGING 表示已预留但尚未确认的文件；过期由 expiresAt 推导，接口返回 EXPIRED，不新增数据库 EXPIRED 状态。对象磁盘键由 tenant UUID + artifact UUID 派生，不存展示名称/内部路径；createdAt 与 expiresAt 足以表达当前生命周期，首版未增加原设计建议的 storage_key、published_at、updated_at。新增 Run 64/租户 4096 文件数限制防止小文件消耗元数据；Run 预算包含删除历史，租户容量仅在实际删除确认后归还。模型工具继续只返回脱敏 stdout，控制台直接从可信 Run 列表查询，不向模型注入文件内容或链接。

验证进行中：本地 Java 21 快速回归、Node/控制台资源；Rocky 独立 `/root/cm-agent-artifacts-af7b2fa-20261008`，核对 HEAD 与显式覆盖文件 SHA-256，maven:3.9.9-eclipse-temurin-21（Maven 3.9.9/JDK 21.0.7）、Docker 23.0.6。初步双库迁移/Repository 契约通过，后续预算修正后仍需最终复验。X1 检查点 MySQL TEXT 长度问题不修复、不关闭/截断状态；真实模型验收尚未执行。所有测试状态以实际命令与报告为准。
## R0 当前交付状态（2026-10-08 实施验收）

本节覆盖上方分析/准备阶段的历史状态，不删除 R0 形成过程。实施基线 af7b2fac416fc43a545513c0061aa8a4230bbab7，分支 codex/skills-version-console；未提交、未推送、未合并、未部署。用户运行服务与无关配置未修改。mode=default。

| 编号 | 当前状态 | 本轮实际证据/限制 |
|---|---|---|
| T0 | 完成（历史） | 复核六份原文档、AGENTS、POM、配置和源码，保留分析历史 |
| T1 | 完成 | 领域/状态/存储与接收 SPI；独立嵌套配置、能力接口、旧文本兼容，Java21/能力回归通过 |
| T2 | 完成 | 固定输出目录/环境变量、有界帧、链接/特殊文件/数量/字节/截断拒绝；Rocky 四种连接通过 |
| T3 | 完成（隔离验证） | V19 双库中文注释；跨实例预算、回滚、租约、补偿；双库重建数据源/Repository/存储/Service 后 DOCX 下载与摘要通过；未操作生产卷或做生产恢复演练 |
| T4 | 完成（受控链路） | 调用保留 PENDING；Run 持久化/严格审计后 READY；失败/审批等待不下载，幂等和清理失败回归通过；真实模型检查点场景见 T7/X1 |
| T5 | 完成 | 三种列表/统一鉴权下载；401/403/404/410、密文损坏、同编号唯一脱敏日志、流中断审计回归通过 |
| T6 | 完成 | 三处接入、本人最近 TEST 恢复、Blob 生命周期/并发提示；116 Node、14资源测试通过，浏览器下载/刷新/导航/返回/移动及三文件并发提示通过；独立复评该修正 resolved / ship |
| T7 | 部分完成，真实模型验收保留 | Rocky 定向81项80通过1跳过，另双库重建2项通过；浏览器另行下载合法921B DOCX；真实模型/检查点闭环未执行，不宣称 Anthropic docx.zip 已适配 |
| T8 | 完成（文档） | README、配置、运维、沙箱、release-notes及本组六份文件同步；实际命令/限制见账本；未提交 |

文件功能默认关闭；生产需独立私有持久卷、独立32字节Base64 AES/GCM密钥及 JDBC。默认单文件4MiB、调用8MiB/8个、Run32MiB/64个、租户256MiB/4096个、保留7天。二进制只保存于加密对象，元数据进入 JDBC，模型仍只接收受限脱敏 stdout，不接收文件或链接。

补充修正：GET /api/skills/{skillId}/trials/latest?versionId=... 恢复本人当前版本最近 TEST（无记录204），查询及服务端复核 owner/tenant；只读技能用户可查看本人结果，不扩大试运行/发布权限。Docker --rm 与显式删除竞争时仅在同连接有界复查确认对象不存在后成功，其他清理错误不放宽。

X1：用户日志 MySQL 22001/1406 与检查点 TEXT 容量风险仍存在；只复核原迁移/写入路径，未取得真实载荷复现具体列与大小，不修复/关闭/截断状态。X2：Rocky Docker/双库/四连接隔离条件已满足。X3：没有使用部署者的隔离真实模型配置；真实模型验收待该条件及 X1 独立处理，不能以固定脚本/fake runtime 冒充真模型成功。
收尾：三文件延迟下载夹具已实际触发第三次点击等待提示，提示进入可见区域；独立复评 verdict=resolved、remaining=clear、disposition=ship，仅覆盖该项修正。documenter因额度失败采用明示降级只读对齐，保留PRODUCT/DESIGN及sidecar。T1～T6、T8完成；T7固定脚本/下载/双库/四连接完成，真实模型与X1场景验收保留。

## 2026-10-09 提交交付记录

用户新指令“此次修改了什么，同时将修改的内容提交”授权提交本任务实现及文档，覆盖此前本轮不提交的约束；不授权推送、合并或部署。此前“未提交”记录保留为历史。本次提交说明为“新增技能沙箱文件产物存储与鉴权下载”，最终提交编号以当前分支对应的 git log 记录为准，避免文档自引用提交编号。

提交范围为 skill-artifacts R0 的80个相关变更文件，包含双库V19迁移、测试、控制台及生产说明；保留用户已有配置、根index.html、.codex/和.workbuddy/。后续ManagedSkillSandbox排版与已验证快照相比，忽略空白和注释后逻辑一致；保留当前排版，不覆盖用户编辑。最新前端feedback及browser-feedback摘要核对一致。

提交前复核实际验证报告、显式文件范围、敏感信息模式、注释规范、文档链接及差异格式；本次提交阶段不重复执行已通过的业务测试，既有报告仍对应账本列出的实际快照和命令。T1～T6、T8完成，T7部分完成；真实模型与X1检查点闭环仍未验收。自动安装依赖、按技能选择环境与Node.js执行未实现，近期问答未增加这些能力。

## 2026-10-09 T7 容量及失败补偿补验

用户要求继续完成未完成步骤；本次基线为 codex/skills-version-console / fb601ed3d696f8dce3bd5728163228ffcb14406d。仅追加验收测试和本组六份文档，不重复实现T1～T6，不修改用户配置、运行服务或旧迁移。本次新增修改未提交，不推送、不合并、不部署；fb601ed是上一阶段已提交实现。

新增 [SkillArtifactCheckpointIntegrationTest](../../../cm-agent-server/src/test/java/com/cmagent/server/runtime/SkillArtifactCheckpointIntegrationTest.java)：固定Python在真实Docker生成DOCX并进入PENDING，合成State通过RepositoryAgentStateStore的JSON序列化、现有AES/GCM和JdbcRuntimeCheckpointRepository写入。MySQL8.4在encrypted_payload的CHARACTER_OCTET_LENGTH=65535边界上，对加密后超过边界的50000字符ASCII合成状态，UPDATE与INSERT均确认SQLState=22001、驱动错误码1406及目标字段encrypted_payload。原状态完整恢复、首次插入失败不留记录；实际RunPersistenceService失败收口与审计后，文件仍不可见且下载404，清理后元数据DELETED、密文文件不存在。PostgreSQL16-alpine同样状态完整保存/恢复，Run成功后文件READY且完整下载。

此证据确认当前实现中X1容量风险的具体字段及合成复现，不证明用户原真实模型请求的载荷大小或唯一根因；不修复或扩容X1，不截断/关闭状态保存。测试在检查点异常后显式调用生产失败收口组件，不冒称完整AgentScope执行编排或真实模型端到端成功。

实际验证：本机Maven3.9.4/JDK21.0.11，mvn -q -pl cm-agent-server -am -Dtest=SkillArtifactCheckpointIntegrationTest,RepositoryAgentStateStoreTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.agentscope.studio.enabled=false test，退出0；4项快速测试通过，2项容器测试按开关跳过。本机未执行Docker/JDBC。Rocky独立目录 /root/cm-agent-artifacts-checkpoint-fb601ed-20261009，HEAD与本机基线一致；557个源码/POM/SQL/测试资源SHA-256逐项通过，已提交文件按HEAD字节核验，新增测试按当前实际文件核验，不复制用户工作树脏配置。Docker23.0.6，maven:3.9.9-eclipse-temurin-21确认Maven3.9.9/JDK21.0.7；定向6项全部通过、0失败/错误/跳过。验证容器已正常退出，没有留下本次命名运行容器，不执行全局清理。

当前状态：T1～T6、T8原交付保留；T7追加双库容量/失败补偿证据，仍部分完成。剩余真实模型配置尚未由用户提供，已请求隔离测试配置路径及非生产用途确认，不读取或借用用户运行服务凭据。原提示词明确X1修复不在范围，已向用户请求是否将其作为独立修复纳入；答复前不新增扩容迁移、不默认同意。原Anthropic技能Node.js兼容与自动依赖安装仍不在范围。

## 2026-10-09 补验提交与 master 合并记录

用户最新指令授权将本次补验修改提交并合并到本地master，覆盖本次补验阶段“不提交、不合并”的限制；不授权推送或部署。提交范围仅SkillArtifactCheckpointIntegrationTest及本组六份文档，共7个文件；提交说明为“补充检查点容量与文件产物失败补偿验收”，最终编号以对应git log记录为准，历史“未提交”保留为阶段快照。

合并前master=5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443，是codex/skills-version-console的祖先，采用git merge --ff-only快进；会同时纳入本分支此前已提交的技能控制台/容量/诊断和文件产物变更。用户未提交的application配置、根index.html、.codex/、.workbuddy/不纳入提交，也不暂存或覆盖。合并后以master是否包含本次补验提交以及受保护文件摘要不变作为实际成功条件。

代码与本次已通过Rocky验证的新增测试一致，提交/快进不引入新业务逻辑，不重复执行该6项测试；再次检查文档链接及暂存差异。T7仍为部分完成，隔离真实模型配置与X1独立修复范围确认仍未收到；提交和合并不表示真实模型已验收，不新增X1扩容迁移。
