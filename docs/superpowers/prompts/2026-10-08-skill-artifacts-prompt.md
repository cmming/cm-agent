# 技能沙箱文件产物执行提示词

> 当前交付与剩余验收见文末 R0 当前交付状态；前文仅文档/待做描述为历史快照。

日期：2026-10-08｜主题：skill-artifacts｜修订：R0｜工作模式：mode=default

本工作包是独立新增需求，关联原技能沙箱工作包；本轮交付需求分析与六份文档，业务实施尚未执行。普通工程细节按默认模式自主取舍，所列方案是自主假设，不代表用户逐项确认。

[清单](../checklists/2026-10-08-skill-artifacts-checklist.md) · [设计](../specs/2026-10-08-skill-artifacts-design.md) · [实施计划](../plans/2026-10-08-skill-artifacts.md) · [实现说明](../implementation/2026-10-08-skill-artifacts-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-artifacts-ledger.md)

本提示词已经生成，但本轮未执行其中的代码实施命令。它是可供用户后续明确授权实施的输入，不授予提交、推送、合并、部署或修改运行中服务的权限。

```text
在 F:/java/cm-agent 中实施 skill-artifacts 工作包 R0：为技能沙箱增加文件产物收集、持久化及带权限校验的下载，并接入控制台。

先读取：
F:/java/cm-agent/AGENTS.md
F:/java/cm-agent/docs/superpowers/checklists/2026-10-08-skill-artifacts-checklist.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-08-skill-artifacts-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-08-skill-artifacts.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-08-skill-artifacts-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-08-skill-artifacts-ledger.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-08-skill-artifacts-prompt.md
以及关联的原沙箱工作包、POM、README、配置与当前代码。

工作模式 mode=default：普通工程细节自主处理并记录假设，不主动弹普通方案确认；用户后续切换模式时从其新指令。先核对 Git HEAD/分支/暂存区/脏文件、已有实现和实际测试证据。使用 codex/ 分支，保护无关 application*.yml、根 index.html、.codex/、.workbuddy/；不得覆盖用户改动。存在 .codegraph 时先 CodeGraph，缺失或不匹配再源文件复核。

T0 已完成文档分析，复核即可，不重复当作实现；按依赖实际实施 T1～T8并补充必要测试，同步维护本组六份文档。原 LOCAL/SSH KEY/SSH PASSWORD/TLS Docker、固定包内 Python、禁网、非 root、无宿主挂载、限流、授权、读取预算、审计、审批恢复与发布门禁均保留。

实现固定 /workspace/output 与 CM_AGENT_ARTIFACT_DIR；只有包内固定 Python 可以执行。容器退出前用有界二进制协议收集指定目录普通文件，保留 --rm；禁止等待退出后从 tmpfs docker cp，禁止宿主挂载、从模型提交代码/命令/URL/路径。容器传回的帧、名称、长度、内容均不可信，校验数量、类型、实际字节、目录逃逸、软硬链接、特殊文件、协议截断与超时。同一连接负责执行、传输、清理，无回退。

采用服务端持久卷文件存储与 JDBC 元数据，提供存储 SPI，不实现 S3。独立 AES/GCM 二进制加密与独立部署密钥；文件不能进入 DB TEXT、模型上下文、检查点或日志。按设计的独立文件/调用/Run/租户配额实现跨实例预留、暂存原子发布、崩溃补偿、幂等、过期清理及下载/清理租约。新增双库方言迁移，先查下一个未占用版本；不修改旧迁移，每表每字段有原生中文注释。

治理调用只写 PENDING；Run 成功持久化和严格审计成功后再 READY。审批等待不下载；失败、超时、拒绝、撤销、检查点/数据库/审计/清理失败不得交付，执行补偿。产物字段只能从可信 tenant/principal/Run/skill/version/call 构造，不信任模型提供归属，二进制不返回给模型。

增加正式/会话 Run、TEST列表与统一下载 API。下载需当前认证、可信 tenant、Run owner、agent:read；TEST 另需 skill:read 和原 TEST owner，会话检查原 owner 边界。跨租户/主体404、未认证401、缺权限403、已授权过期410；列表只显示可见文件。下载前完成完整解密认证、大小/摘要校验和严格审计，attachment、安全中文名称、nosniff、private/no-store。不得公开静态路径、URL令牌或未经鉴权外链，首版不做预览、Range与共享。记录稳定 errorCode/errorId 和唯一诊断日志，流中断记录同编号与失败/中断审计，不输出内容或密钥。

控制台技能 TEST、运行详情和聊天回复展示文件名、类型、大小、有效期和鉴权下载。复用当前会话请求，fetch/Blob 安全保存，不把令牌放URL，不从模型Markdown提取链接。处理重复点击、过期、下载失败、登出/换租户/导航销毁及Blob URL释放；刷新、跨页导航和后退一致，移动端不溢出。

使用专用固定 Python 测试技能生成合法 DOCX，验证回传、重启存储、鉴权下载及文件结构和摘要；原 Anthropic docx.zip 的 Node.js 动态脚本兼容不在范围。当前 X1 检查点长度超限可能阻断真实模型验收，它不在本工作包的修复授权内，先记录/核实并独立推进可验证项，不关闭状态保存、截断数据或宣称现有 docx.zip 已适配。

Docker、JDBC、Flyway和容器测试只在 ssh rocky 执行，使用 maven:3.9.9-eclipse-temurin-21、PostgreSQL16-alpine、MySQL8.4。运行前核对Docker、Maven JDK21、远程HEAD和当前代码SHA-256；仅项目隔离夹具，不修改用户运行服务，不全局清理，不复制或输出真实凭据。完成本地快速单测、双库/四种连接容器回归、控制台Node/资源测试和真实浏览器下载；测试条件不足则记录实际阻塞与未执行验收，历史通过不得替代新验收。

新增 Java 的领域字段、枚举、SPI、生命周期、安全和异常分支按 AGENTS.md 落地中文注释/JavaDoc。实施后对齐 README、配置/运维/沙箱说明和 release-notes，并更新本组六份原路径的状态与证据。未通过项不能标完成。记录实际命令/结果、相关源码位置、提交状态，未提交写“未提交”。

本轮不提交、不推送、不合并、不部署。最终按 AGENTS.md 简洁报告实际变更、验证结果、T1～T8状态、风险和确切剩余阻塞，持续推进可独立工作直到验收或明确外部阻塞。
```

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
### 当前继续执行范围

再次使用本提示词时，先复核文末当前状态和账本证据，只处理未验收项或新发现回归；不要从上方历史待做表重复实现已经交付的T1～T6。真实模型/检查点X1没有扩容修复授权，先独立处理该任务并准备部署者提供的隔离测试配置，禁止拿生产凭据或用户运行服务代替夹具。现有代码未提交，本提示词仍不授权提交/推送/合并/部署。当前源代码覆盖范围和SHA必须与新验收一致，不以本轮报告替代未来修改后的验证。
收尾：三文件延迟下载夹具已实际触发第三次点击等待提示，提示进入可见区域；独立复评 verdict=resolved、remaining=clear、disposition=ship，仅覆盖该项修正。documenter因额度失败采用明示降级只读对齐，保留PRODUCT/DESIGN及sidecar。T1～T6、T8完成；T7固定脚本/下载/双库/四连接完成，真实模型与X1场景验收保留。

## 2026-10-09 提交交付记录

用户新指令“此次修改了什么，同时将修改的内容提交”授权提交本任务实现及文档，覆盖此前本轮不提交的约束；不授权推送、合并或部署。此前“未提交”记录保留为历史。本次提交说明为“新增技能沙箱文件产物存储与鉴权下载”，最终提交编号以当前分支对应的 git log 记录为准，避免文档自引用提交编号。

提交范围为 skill-artifacts R0 的80个相关变更文件，包含双库V19迁移、测试、控制台及生产说明；保留用户已有配置、根index.html、.codex/和.workbuddy/。后续ManagedSkillSandbox排版与已验证快照相比，忽略空白和注释后逻辑一致；保留当前排版，不覆盖用户编辑。最新前端feedback及browser-feedback摘要核对一致。

提交前复核实际验证报告、显式文件范围、敏感信息模式、注释规范、文档链接及差异格式；本次提交阶段不重复执行已通过的业务测试，既有报告仍对应账本列出的实际快照和命令。T1～T6、T8完成，T7部分完成；真实模型与X1检查点闭环仍未验收。自动安装依赖、按技能选择环境与Node.js执行未实现，近期问答未增加这些能力。
