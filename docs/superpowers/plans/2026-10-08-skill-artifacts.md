# 技能沙箱文件产物实施计划

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
