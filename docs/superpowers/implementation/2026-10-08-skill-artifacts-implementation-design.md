# 技能沙箱文件产物实际实现说明

> 当前交付与剩余验收见文末 R0 当前交付状态；前文仅文档/待做描述为历史快照。

日期：2026-10-08｜主题：skill-artifacts｜修订：R0｜工作模式：mode=default

本工作包是独立新增需求，关联原技能沙箱工作包；本轮交付需求分析与六份文档，业务实施尚未执行。普通工程细节按默认模式自主取舍，所列方案是自主假设，不代表用户逐项确认。

[清单](../checklists/2026-10-08-skill-artifacts-checklist.md) · [执行提示词](../prompts/2026-10-08-skill-artifacts-prompt.md) · [设计](../specs/2026-10-08-skill-artifacts-design.md) · [实施计划](../plans/2026-10-08-skill-artifacts.md) · [进度账本](../progress/2026-10-08-skill-artifacts-ledger.md)

## 本轮实际交付

已完成 T0：基于当前仓库证据识别产物能力缺口，定义默认模式方案并新增六份主题一致的中文工作包。实际变更只包含本组六个 Markdown 文件。

本轮没有新增产物 Java 类型、存储实现、数据库迁移、下载 Controller、控制台组件、配置项或测试技能。清单 T1～T8 均未实施；设计中的接口、属性、领域状态和新类仅为拟议契约。

## 当前真实调用链

包内固定 Python → Docker 私有 tmpfs → stdout 文本 → 输出限额/脱敏 → 容器清理 → 治理授权复核/审计 → 模型工具文本结果。

现有返回契约仍是 String；现有容器文件仍随清理丢失，没有可下载产物记录。DockerSkillSandbox、ManagedSkillSandbox、GovernedSkillAccessService 等源码本轮仅用于阅读，未修改。RunRecord 记录 principalId 可供未来 owner 校验，现有 Run/TEST权限未变化。

## 本轮分析结论

- 文件必须在容器退出前收集，未来实现不能以“停止容器后 docker cp tmpfs”为方案。
- 拟采用独立文件配额、服务端持久卷与 JDBC 元数据，避免文件二进制扩大 AgentState 或数据库 TEXT。
- 新下载权限比正式运行历史查询更严格，默认限定创建者，并在当前会话校验现有权限。
- 产物发布需要 Run 成功、授权和严格审计成功，不能以脚本打印路径代替交付。
- 独立测试技能可验证 DOCX 文件链路；原 Anthropic docx.zip 的 Node.js 动态代码流程不在本范围。
- 用户检查点失败日志是现存风险 X1，具体超限列与载荷大小仍待安全复现，不宣称已修复。

## 与原能力及历史证据的关系

原 [技能沙箱实现说明](2026-09-30-skill-sandbox-implementation-design.md) 记录既有远程 Docker 能力，本工作包引用它作为背景，未改写其完成状态。原导入/读取容量与 JDBC 诊断改动不代表文件生成、回传和下载验收通过。

本轮只运行文档一致性、路径、Git差异和敏感信息检查，没有运行 Java、Docker、JDBC、Flyway、浏览器或真实模型验证。实际结果以账本为准，不将拟议行为写成已有功能。

## 实施后的维护要求

后续明确授权实施时，本文件应追加真实源码/迁移/配置路径、实际状态机与调用链、数据流和方案差异，并与清单/账本对应；保留本次文档阶段记录。新类名、迁移编号与具体限额以实施时的源码和验证确定。

Git 状态（文档生成阶段）：未提交。未推送、未合并、未部署，未修改运行中的服务或用户既有配置。

## 实施准备与本次提交

用户授权实施后，已重新核对工作包、源码与运行环境；业务代码修改开始前被用户中断。默认本机 Maven 使用 JDK 17；Rocky Docker 和指定 Maven/JDK 21 容器可用，但原隔离工作区 HEAD 与本地基线不一致，因此没有开展业务测试，也没有据此改变任务验收状态。

用户最新授权提交当前修改，本次仅提交本组六份文档，提交说明为“新增技能沙箱文件产物需求工作包”，真实提交编号由 Git 记录给出。T1～T8 仍未实施；没有新增文件回传、持久化或下载功能。提交不包含用户已有配置和其他修改，未推送、未合并、未部署。

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
### 已落地源码与数据流

| 职责 | 实际位置 |
|---|---|
| 领域/生命周期/SPI | cm-agent-core 的 SkillArtifact、SkillArtifactStatus、SkillArtifactRepository、SkillArtifactSink、SkillArtifactStorage |
| 部署策略/装配 | server/config/SkillArtifactProperties、SkillArtifactConfiguration、SkillSandboxProperties；application-skill-sandbox.yml |
| 固定目录/协议 | server/runtime/SkillArtifactProtocol；DockerSkillSandbox/ManagedSkillSandbox execute 重载与同连接清理 |
| 加密持久卷 | server/runtime/FileSystemSkillArtifactStorage：tenant/id.gcm、随机IV、tenant/id/size AAD、暂存验证/fsync/原子移动；无宿主挂载 |
| 配额/租约/补偿 | persistence/JdbcSkillArtifactRepository、server/store/InMemorySkillArtifactRepository；mysql/postgresql V19__add_skill_artifacts.sql |
| 治理与发布 | GovernedSkillAccessService 收集后关闭/复核/审计，Collection.complete 只保留PENDING；RunPersistenceService终态事务调用finalizeRun |
| 鉴权与诊断 | SkillArtifactService、SkillArtifactController、ApiErrorCode/ApiExceptionHandler；下载前全量解密认证/摘要/大小/严格审计，单实例4下载有界驻留 |
| 控制台 | v2/assets/skill-artifacts.js/css；skills.js TEST；assets/app.js Run/聊天；assets/console-core.js带会话Blob请求，所有v2入口声明组件依赖 |
| TEST恢复 | SkillTrialRepository/JdbcSkillTrialRepository.findLatest、SkillTrialService.latest、SkillTrialController GET latest；主体/租户/版本复核，204无历史 |
| 夹具/测试 | SkillArtifactProtocol/Storage/Service/Controller/Docker/JdbcRestartIntegrationTest、JdbcSkillArtifactRepositoryTest、SkillArtifactBrowserFixtureTest、Node skill-artifacts.test.cjs；test/resources/skill-artifacts固定Python |

固定包内脚本写 /workspace/output → 引导程序终止残留子进程后扫描普通文件 → 文本/文件/结束二进制帧 → 不可信帧校验 → 跨实例STAGING预算预留 → 流式加密/结构校验/原子发布 → PENDING → 容器清理、授权、严格调用审计 → 模型仍仅获得原文本 → Run成功持久化与严格审计 → READY → 认证owner鉴权列表及附件下载。

失败的调用按run/call补偿，终态失败按Run补偿；等待审批的PENDING保留但不可下载。元数据与对象缺失/损坏均失败关闭；清理需要独占租约，确认实际删除后才归还租户配额。崩溃后的STAGING/PENDING按24小时候选扫描，活动Run的PENDING不提前删除；文件路径由UUID派生，展示名称不能定位宿主对象。DELETED元数据保留过期与幂等历史，Run累计预算不归还。

首版完整解密到最多4MiB受限内存，每实例最多4请求；明文在下载句柄关闭时擦除，不返回到模型。完整认证及STARTED严格审计发生在HTTP附件响应提交前；流中断写同一errorId的失败日志/INTERRUPTED审计。提交后最终审计失败不能撤回已经送出的字节，仍记录同编号失败并释放资源，不伪称成功。多实例必须挂载同一受控卷并使用同一独立部署密钥；文件SPI并不自动实现S3。

配置/迁移与实现差异已在R0续篇说明；实际测试命令及失败修正见账本。浏览器夹具使用固定测试代码与一次性身份，fake模型元数据不满足真实模型或发布门禁，未改写原门禁。
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
