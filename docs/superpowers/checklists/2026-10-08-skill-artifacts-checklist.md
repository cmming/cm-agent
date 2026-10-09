# 技能沙箱文件产物需求清单

> 当前交付与剩余验收见文末 R0 当前交付状态；前文仅文档/待做描述为历史快照。

日期：2026-10-08｜主题：skill-artifacts｜修订：R0｜工作模式：mode=default

本工作包是独立新增需求，关联原技能沙箱工作包；本轮交付需求分析与六份文档，业务实施尚未执行。普通工程细节按默认模式自主取舍，所列方案是自主假设，不代表用户逐项确认。

[执行提示词](../prompts/2026-10-08-skill-artifacts-prompt.md) · [设计](../specs/2026-10-08-skill-artifacts-design.md) · [实施计划](../plans/2026-10-08-skill-artifacts.md) · [实现说明](../implementation/2026-10-08-skill-artifacts-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-artifacts-ledger.md)

## 需求与当前基线

用户提出：“新增一个需求支持增加文件产物收集、存储和带权限校验的下载功能”。目标是让技能脚本生成的 DOCX、PPTX、PDF 等文件在容器退出后仍可通过受控接口获取，而非仅返回文件路径。

Git 基线：`d0ee3a10dc8ec545e58ab36c0648c0c4b6cabc86`；当前分支 `codex/skills-version-console`；暂存区为空。本轮开始时已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、控制台根 `index.html` 以及 `.codex/`、`.workbuddy/` 修改或未跟踪项，保留原状。

## 当前证据

| 编号 | 当前事实 | 已核实位置 |
|---|---|---|
| E1 | 沙箱只执行固定技能版本的包内 Python 脚本，返回脱敏文本 | [DockerSkillSandbox](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/DockerSkillSandbox.java)、[SkillSandboxBackend](../../../cm-agent-core/src/main/java/com/cmagent/core/runtime/SkillSandboxBackend.java) |
| E2 | 容器使用 `--rm`，无宿主挂载，`/workspace` 与 `/tmp` 为各 16 MiB tmpfs | 同 E1；文件不得等容器退出后才尝试提取 |
| E3 | 执行完成后关闭句柄、复核授权、写严格审计，然后返回文本 | [GovernedSkillAccessService](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/GovernedSkillAccessService.java)、[ManagedSkillSandbox](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/ManagedSkillSandbox.java) |
| E4 | NORMAL/TEST 运行均有可信 tenant、principalId、runId；TEST 只允许创建者查询 | [RunRecord](../../../cm-agent-core/src/main/java/com/cmagent/core/domain/RunRecord.java)、[SkillTrialService](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillTrialService.java)、[SkillTrialController](../../../cm-agent-server/src/main/java/com/cmagent/server/web/SkillTrialController.java) |
| E5 | 正式运行查询已有 `agent:read`；产物权限不可仅复用租户内通用历史可见性 | [RunController](../../../cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java)、[PermissionEvaluator](../../../cm-agent-core/src/main/java/com/cmagent/core/security/PermissionEvaluator.java) |
| E6 | 当前生产源码未找到沙箱文件产物存储和下载 API；现有最新方言迁移为 V18 | 本轮 `rg` 与迁移文件清单复核，具体搜索见账本 |
| E7 | 用户日志提示检查点写入发生 MySQL 22001/1406；`encrypted_payload` 仍为 TEXT，高概率是载荷容量问题，具体列与真实载荷大小未确认 | [检查点写入](../../../cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRuntimeCheckpointRepository.java)、[原迁移](../../../cm-agent-persistence/src/main/resources/db/migration/mysql/V11__add_tool_approvals.sql) |

CodeGraph 已优先查询，但结果未覆盖产物目标符号；随后直接复核上述当前源文件。既有沙箱历史验证不能证明文件回传与下载已经可用。

## 本轮采用范围

- 为 Python 沙箱增加指定输出目录、受限二进制文件回传；覆盖 LOCAL、SSH KEY、SSH PASSWORD、TLS。
- 采用服务端持久卷文件存储与 JDBC 元数据；提供存储扩展接口，首版不交付云对象存储后端。
- 绑定可信租户、主体、Agent、Run、技能版本、调用标识；以严格审计和运行终态控制可见性。
- 提供鉴权列表、下载；控制台技能 TEST、运行详情及聊天回复展示产物与下载入口。
- 提供过期清理、配额、崩溃补偿、下载完整性校验及错误诊断。
- 用专用包内 Python 测试技能生成可解析 DOCX，验证收集至下载链路。

不含：执行模型生成代码、开放 Node.js/Shell、直接适配原 Anthropic docx.zip、升级镜像依赖、文件上传、在线预览、匿名或永久公开链接、S3 实现、跨主体共享、管理员代取。原 ZIP 导入、模型读取、现有运行权限不因此扩大。检查点容量故障单独跟踪，详见 X1。

## 稳定任务与验收

| 编号 | 状态 | 目标与文件线索 | 依赖 | 验收条件 | 验证方法 |
|---|---|---|---|---|---|
| T0 | 完成 | 当前证据核对与六份工作包 | 无 | 六份齐全，链接与状态一致，未将业务标为完成 | 本轮文档检查，见账本 |
| T1 | 待做 | 产物领域对象、存储 SPI、配置、沙箱兼容契约；core/runtime、core/domain、server/config | T0 | 旧文本后端保持兼容；独立文件配额与 stdout 配额；能力接口返回真实策略；新增字段与 SPI 有中文 JavaDoc | core 单测、配置绑定与能力 API 测试 |
| T2 | 待做 | Docker 固定引导程序与有界流式收集；DockerSkillSandbox、DockerDaemonConnection、ManagedSkillSandbox | T1 | 退出前回传；只收集指定目录普通文件；拒绝越界、链接、设备、超限和非法帧；原隔离与固定端点策略保留 | 单测与 Rocky 本地/SSH/TLS 容器回归 |
| T3 | 待做 | 产物元数据 Repository、方言迁移、加密文件存储、配额与过期清理；core/repository、persistence、server/runtime/config | T1 | 服务重启可查可下载；PG/MySQL 字段注释齐全；并发配额不穿透；损坏、满盘和遗留临时文件可诊断与收敛 | 双库 Testcontainers、临时存储目录与故障注入 |
| T4 | 待做 | 治理、Run 终态和 TEST 生命周期接入；GovernedSkillAccessService、RunExecutionService、RunPersistenceService、SkillTrialService | T2、T3 | 运行未成功不开放下载；失败、撤销、审计/检查点错误不交付；审批恢复不重复产物；清理失败仍为失败 | 运行编排、审批、异常与幂等回归 |
| T5 | 待做 | 列表/下载 Controller、权限策略、审计和错误映射 | T3、T4 | 401/403/404/410 与稳定错误码对应；跨租户/跨主体不可读取；attachment、安全文件名、禁缓存；流失败有同编号日志 | MockMvc 安全矩阵、完整性与中断测试 |
| T6 | 待做 | 控制台 TEST/运行/聊天产物卡片及鉴权下载；skills.js、assets/app.js、skills/runs/chat.html | T5 | 文件名、类型、大小、有效期清楚；刷新/导航一致；下载失败可重试；登出和换租户撤销未完成请求与 Blob URL | Node、ConsoleResourceTest、真实浏览器桌面/移动测试 |
| T7 | 待做 | 端到端文件与隔离验收、原文本能力回归 | T2～T6 | Python 测试技能生成 DOCX 后下载字节/摘要相同且可解析；本地与 SSH/TLS 通过；失败不留下可下载产物 | Rocky Java 21 + PostgreSQL 16/MySQL 8.4 + Docker，浏览器下载 |
| T8 | 待做 | 更新生产配置/运维/发布说明，同步实施说明与账本 | T7 | 实际配置及备份/清理说明准确；命令、状态与阻塞有证据；未提交写“未提交” | docs 链接、差异、敏感信息自查 |

执行顺序：T1 → T2/T3 → T4 → T5 → T6 → T7 → T8；T2/T3 可独立推进，不强制启动子智能体。

## 验收门禁与现存问题

- 文件生成成功不等于 Run 成功或可下载；存储成功不等于权限与审计验收通过。
- 不能把路径字符串、模型声称“已生成”或 stdout Base64 当作文件交付证据。
- X1：真实模型 DOCX 场景已出现检查点持久化超限。此故障未修复、未复现具体字段；它可能阻断真实模型最终验收，但不阻止专用 Python 沙箱与下载 API 验证。不得为通过本需求关闭状态保存、截断状态或修改历史迁移。
- X2：真实模型、可信镜像依赖及远程连接的隔离测试条件本轮未探测。实施时在项目隔离环境核实，禁止操作用户运行中的服务。
- 本轮仅文档生成，不实施、不提交、不推送、不合并、不部署。

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
