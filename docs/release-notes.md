# 发布说明

## 未发布：会话运行失败可见反馈

- 修复会话聊天将 FAILED 终态提示为“回答已完成”的问题；发送、轮询和审批恢复统一按真实业务状态显示结果，只有 SUCCEEDED 显示成功。
- 失败没有 assistant 正文时，消息区仍展示失败反馈与运行编号；刷新后保留失败标记，可按需读取服务端安全原因，读取失败可重试查询，不自动重发消息或工具。已有 errorCode/errorId 时沿用，未提供时不伪造。
- v2 全入口同步共享脚本版本；部署后刷新已打开页面。仅修改控制台，无 API、数据库或配置变更；模拟浏览器与实际登录验证边界见[账本](superpowers/progress/2026-10-26-chat-failure-feedback-ledger.md)。
- R1：后端模型 HTTP 失败不再统一替换成“Agent 运行失败”，现在返回并保存 HTTP 状态及结构化错误说明的脱敏、限长摘要，支持即时问答与“查看失败原因”读取相同内容。失败的即时结果及历史 Run 增加派生 errorCode/errorId；不新增数据库列。Server 屏蔽第三方传输 logger 中携带原始响应的 WARN，保留适配器安全诊断。此项需重启后端生效，旧记录无法恢复上游原文；R0 的“仅前端改动”边界至此调整。

## 未发布：Agent 模型名称跟随绑定配置

- 修复模型配置修改模型名称后，Agent 列表与详情仍展示创建时旧名称的问题；读取时按当前租户和配置 ID 解析最新名称，已有 Agent 无需重新保存。
- Server 准备运行时同步使用当前绑定配置的模型名称，避免页面与实际调用不一致；不回写历史 Agent 记录、不增加数据库迁移，保留嵌入式 SDK 显式指定模型名称的合同。需重新构建并重启 Server 后刷新页面，验证范围见[账本](superpowers/progress/2026-10-15-agent-model-name-sync-ledger.md)。

## 未发布：v2 全页面样式统一优化

- 统一登录与八个业务页的导航图标、标题、间距、控件、反馈和响应式规则；保留前轮Agent/模型页与1920宽屏适配。工具长地址与技能状态可读，运行引导更紧凑，聊天输入保持可见，审计新增局部滚动提示与键盘焦点。
- 新增同源workbench.css与SVG图标，九个入口共同加载；需要完整部署静态资源并刷新已打开页面。未改变API、权限、租户、审计、工具授权、技能发布或运行状态协议。实际测试和边界见[账本](superpowers/progress/2026-10-15-v2-console-polish-ledger.md)。

## 未发布：1920×1080 宽屏适配

- 模型配置页与Agent页新增1680px起的宽屏布局，内容上限1600px、列表320px；模型详情和表单并列，1920×1080首屏可完成配置，较小尺寸沿用原布局。
- 全部v2入口更新CSS版本2.3.4；仅前端样式变化，无API、数据库或凭据处理变更。当前验证见两页原工作包R1账本。

## 未发布：模型配置页样式优化

- 优化v2模型配置列表、详情、服务地址、模型发现及凭据说明分组，适配桌面、中屏与手机；编辑/删除移至标题区，既有业务回调不变。
- 修复同页表单锚点被跨页导航重载的问题，保留未保存输入；所有v2入口同步静态资源版本，更新后刷新页面。验证与边界见[账本](superpowers/progress/2026-10-15-model-config-page-polish-ledger.md)。

## 未发布：Agent 管理页样式优化

- 优化v2 Agent列表长名称、详情层级、提示词、技能绑定与表单布局，支持桌面、中屏和手机；编辑与删除移至详情标题旁，既有业务回调不变。
- 所有v2入口同步共享资源版本，保留v1、API、权限、租户、审计及数据库。静态资源更新后刷新已打开页面；证据与边界见[工作账本](superpowers/progress/2026-10-15-agent-page-polish-ledger.md)。

## 未发布：Harness 相关改动整体提交

- 2026-10-14按用户要求纳入受控Harness实现、V21、只读上下文、对应测试、实验源码、部署脚本及阶段工作包；AgentScope保持2.0.2、默认react，未推送或部署生产。
- 各阶段条目保留形成时的状态，包括当时“未提交”、V20/旧Run及待验收记录；最新采用范围见[模块说明](harness-module.md)和[接续账本](superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md)，不把历史快照当作当前排空证明。

## 未发布：Windows 本机 Harness 运行

- 用户改为本机运行；Windows JDK21/mysql开发profile、真实Harness及回环8080启动，数据库经独立SSH转发连接Rocky MySQL。沿用既有管理员和密钥，关闭不可达的可选Studio；Java/POM/YAML/迁移及默认react未修改。
- 健康、登录及现有DeepSeek无工具真实Run通过；前两次失败及审计保留，3个临时Agent停用。原库经正常V21启动门禁切为harness，173Run/22TEST全终态、检查点/待审批0；一项GLM配置上游余额不足，未证明全部模型或技能容器可用。
- 新增scripts/start-local-harness.ps1前台复现与CheckOnly，更新同路径R1工作包及运维说明。当前本机运行不等于Rocky生产部署完成；原生产任务移出当前范围、七Secret及真实恢复历史阻塞保留，未提交/推送。详见[接续账本R1](superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md)。

## 未发布：Rocky 生产装配准备

- 用户选择Rocky生产目标；新增scripts/rocky-harness非敏感配置、前置检查、启动脚本及说明。仅production、显式MySQL驱动、真实AgentScope，默认react，禁用bootstrap/dev fallback/fake/Studio，首次回环18086，不自动开放流量。
- 同一已验收候选包与5个旧CMA1密文已暂存/home私有目录，避开空间紧张的根分区；密文备份23096字节、两端摘要一致，尚未认证解密或验证独立密钥恢复。非rootJDK21/只读包/Docker组访问实测通过，Maven入口显式改为java，不依赖root缓存初始化。
- 前置检查缺七个生产Secret时拒绝，实际负例及排空SQL通过；未创建生产容器、应用账号、JWT或主密钥，未切换模式。不是生产启动或正式发布完成；源码仍未提交/推送，真实Secret供给与恢复/入口验收见[接续账本](superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md)。

## 未发布：已确认中断测试 Run 的受控收口

- 用户确认Rocky MySQL中8条历史RUNNING为中断测试；新增V21私有备份并实际副本验证审计阶段故障回滚、重复NOOP及排空后的模式往返。
- 使用现有Run/Audit仓储、精确ID/归属/原状态、模式/Run行锁与同一短事务，原库8条收口为FAILED，新增8条终态及8条修复审计；不重放模型/工具，原始输入输出消息、旧审计、其他Run与业务表不变。数据库已全部排空，模式仍react。
- 原库第一次操作因本机IDE的10个连接被拒绝而零写入；通过Spring Admin正常关闭上下文、释放入口和数据库连接后完成修复。IDE JVM未完全退出，未强杀或自动重启，未按mysql/bootstrap配置冒充生产部署；Harness启用仍等待应用目标及配置。无Java生产代码、依赖、YAML或迁移脚本修改，未提交、未推送，见[接续账本](superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md)。

## 未发布：Rocky MySQL 备份还原与 V21 迁移接续

- 用户明确正式数据集为Rocky MySQL `cm_agent`；私有V20逻辑备份已实际隔离还原，全表数据匹配。副本验证V21迁移及8条非终态阻止Harness切换，模式react保留。
- 原库使用既有Flyway正常补齐V21，全部业务表数据迁移前后相同；170条Run/22条TEST保留，8条历史RUNNING未强制终结。未修改迁移脚本、Java生产代码、依赖、YAML或默认react。
- 数据库还原证明不包含密文产物卷与独立密钥；旧Run安全收口、全部部署实例及正式配置仍待确认，未启动应用或启用Harness。未提交、未推送，证据和继续条件见[接续账本](superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md)。

## 未发布：时钟校准与 Harness 受控启用评估

- 2026-10-12恢复Rocky原chronyd离线时间源，取得新鲜NTP样本后校准系统与UTC RTC；系统显示已同步，waitsync校正量约1毫秒，chronyd active/enabled。没有变更时间源配置、重启虚拟机或停止无关容器。
- 补充运维时间校准和只读排空核查步骤，同步T6已完成事实。当前候选MySQL仍有8条RUNNING且迁移V20，PostgreSQL仅V1；正式目标、入口、全部实例和生产备份恢复尚未核实，因此暂不启用Harness。
- 无代码、依赖、接口、迁移或默认react改动，无业务记录修复或生产部署；未提交、未推送。当前结果和继续条件见[评估账本](superpowers/progress/2026-10-12-harness-controlled-enablement-ledger.md)。

## 未发布：T6 真实验收与旧包回退

- 关闭T5剩余Rocky验收，新增显式启用的真实Provider与实际二进制合同，不改变业务运行默认值。ReAct/Harness、DashScope原生及其OpenAI兼容接口8项真实协议合同通过；双策略/装配72项、控制台JS46项、双库迁移/模式3项与实际旧包回退2项通过。
- Harness中文控制台验证续聊、消息与审批SSE、HIGH审批恢复、实际停止、两协议Skill TEST与DOCX下载。TEST不修改正式发布或绑定；HTTP测试目标被Rocky解析为本地地址，安全策略按HTTP_TARGET_REJECTED拒绝。
- 新旧包显式repackage后在临时PostgreSQL16/MySQL8.4执行策略往返与Git基线6752bf8旧包回退，运行历史及V21保留。修正README中不支持审批/取消的过时说明，补充端点与打包/排空边界。
- 修复完整拒绝审批后表单残留“运行已暂停”的提示，按服务端终态显示拒绝/停止/失败/成功或新审批等待，保留主体/会话复核与一次写入门控；v2会话页使用app.js资源版本2.0.32，5项新回归与实体包浏览器复测通过。
- 默认仍react，AgentScope2.0.2、MCP与数据库结构未改变。未提交、未推送、未部署生产；证明范围及Rocky时钟偏差见[T6账本](superpowers/progress/2026-10-12-harness-t6-ledger.md)。

## 未发布：T5 只读工作区上下文

- 新增默认关闭的`cm-agent.workspace-context`，以管理员tenant/Agent映射读取显式私有文本，限制路径、编码、数量与字节；首次Run冻结并加密至现有检查点，审批恢复不重读源文件。
- 新增SDK可选参考快照和默认关闭的能力声明，保留旧构造器；两种策略把资料作为用户消息JSON数据，不授予工具权限或开放原生文件系统。HTTP/SSE有安全中文原因、稳定错误码与原Run编号。
- ASK保留原快照，终态/失败/停止在成功与文件发布前清理，严格失败不回退重跑；新增本地Windows、实际Harness/协议、身份/预算/注入/错误合同，两库/Linux验收已完成。
- 全模块打包、本地合同与Rocky双库/Linux安全目录验收通过；两库各29项、0失败/错误、Windows专属1项跳过。不替代真实Provider/浏览器/回退。依赖、迁移和默认react不变；未提交、未推送、未部署。当前证据见[T5账本](superpowers/progress/2026-10-12-harness-t5-ledger.md)。

## 未发布：T4 Harness 技能沙箱与产物合同

- 修复用户停止在途脚本时被误判为致命沙箱超时：只有同一可信 Run 已进入 `CANCELLING`、线程实际中断且关闭未附加失败时记录严格取消审计，待资源退出后交付 `CANCELLED`；普通超时、审计/状态读取及清理故障继续失败。
- 新增实际 Spring Harness、回环模型、固定 Python Docker、JDBC、AES-GCM 文件与 Web 下载集成合同，覆盖固定版本审批恢复、撤销、依赖授权、预算、防重、候选 TEST、成功发布、下载归属、密文损坏和停止清理。实测结果见 [T4 账本](superpowers/progress/2026-10-11-harness-t4-ledger.md)。
- 无新增接口、配置键、依赖或迁移；AgentScope 保持 2.0.2、默认 react，Skill/沙箱/文件开关仍由部署者显式启用。本轮不替代真实 Provider、浏览器、强杀进程或生产多实例验收，未提交、未推送、未部署。

## 未发布：T3 Harness 审批恢复、状态与停止合同

- 首次 ASK 的审批创建失败、决定通知失败和独立运行恢复前置失败进入统一补偿；关闭活动 Run 并清理同一发起主体的加密检查点，JDBC 使用共享运行短锁及短事务，严格清理失败保留原原因为 suppressed。
- 审批恢复先直接读取原检查点，避免 AgentScope 2.0.2 读取异常回退空状态掩盖数据库或密文损坏；ASK 后资源关闭失败也删除状态。默认槽只读探测返回空，匿名写入与删除继续拒绝。
- 以生产 Harness、回环模型协议桩、真实治理网关和加密 Store 验证多轮/重复/部分批准/撤权、对象重建、过期竞态与停止实际工具退出；JDBC 同时验证 PostgreSQL 16 和 MySQL 8.4。命令、结果与边界见 [T3 账本](superpowers/progress/2026-10-10-harness-t3-ledger.md)。
- HTTP/SSE、数据库结构、依赖和默认 react 不变；独立 Run/TEST 的非交互 HIGH 拒绝合同保持。单实例停止句柄、提交决定后崩溃无自动重放、真实外部 Provider/浏览器验收等限制仍需遵循现有运维说明。本轮未提交或部署。

## 未发布：T2 受控 Harness 执行策略与模式装配

- 新增生产隔离对象及 Harness 执行器，复用 ReAct 治理循环、审批/停止/固定 Skill 契约；私有工作区、拒绝型宿主能力与最终工具白名单关闭默认 `wait_async_results` 和原生宿主能力。
- 新增 `cm-agent.agentscope.execution-mode`，默认 `react`；可显式选择 `harness`，未知值启动失败，自定义 Runtime 仍优先。Adapter 的 Harness 依赖为 optional，Server 显式装配，全部 AgentScope 保持 2.0.2。
- 新增 PostgreSQL/MySQL V21 系统模式表及中文原生注释；模式改变须排空非终态 Run、TEST 和所有检查点，短事务行锁串行切换。相同模式重启可恢复审批，旧模式实例在凭据解析前拒绝，不自动重跑工具。
- 不改现有 HTTP/SSE、前端和 MCP 版本。旧二进制须全部停用；本轮代码交付不替代 T3–T6 的真实模型及完整生产验收。当前命令和证据见[交付账本](superpowers/progress/2026-10-10-harness-t2-ledger.md)。

## 未发布：无审批入口的高风险工具拒绝修复

- 开启 `cm-agent.agentscope.permission-enabled` 时，有持久化会话的 HIGH 工具继续按次审批；无会话 Run/TEST 的 HIGH 调用显式拒绝，避免 `DONT_ASK` 下显式 ASK 仍暂停且无人恢复。
- 原生拒绝发生在工具桥接器之前，适配器补充未授权工具记录并把 Run 映射为 `DENIED`；返回固定中文原因，清理检查点，拒绝日志关联 Run 编号，不记录原始参数或凭据。
- 该轮准入修复保持 AgentScope 2.0.2，生产当时仍使用 ReAct；Harness 最终白名单、空消息查询和私有工作区在独立实验验证。该轮没有新增配置键或数据库迁移，默认 `permission-enabled=false` 保持兼容；后续生产策略接入见上方 T2 条目。该轮证据见[实施账本](superpowers/progress/2026-10-10-harness-entry-hardening-ledger.md)。

## 未发布：会话聊天页面均衡优化

- v2 聊天采用独立消息滚动区与紧凑输入区，手机通过“会话与 Agent”展开历史列表；支持已加载标题过滤、时间分组、完整标题查看、正文与代码复制。
- 向上阅读暂停跟随，点击“回到最新”恢复；输入支持 Enter 发送、Shift+Enter 换行及中文组合输入保护。未确认输入只在当前页面内存保留；网络/5xx 结果未知先查询核对，不自动重发写请求。
- 空审批历史收为一行，历史读取失败自动展开具体错误；保留待审批、只读权限、停止等待与发送锁。其他 v2 入口仅同步共享资源版本，v1 与后台接口、数据库、配置不变；已打开页面需刷新一次。
- 自动化与模拟浏览器验收结果、真实输入法/模型验收边界见[实施账本](superpowers/progress/2026-10-10-chat-page-ux-delivery-ledger.md)。本次变更纳入本地提交，未部署。

## 未发布：会话聊天停止本轮

- v2 聊天新增停止及查询状态入口，`CANCELLING` 保持发送锁；实际退出后 `CANCELLED` 保留脱敏正文和中断提示，可继续新一轮，不续跑原回答。刷新通过权威查询找回原 Run，SSE 断开不等于停止。
- 原发起主体与 `agent:run` 授权后才能停止；等待审批时取消 PENDING、递增版本并清理检查点，旧批准不能恢复工具。批准后的原 Run 恢复 `RUNNING`，已接受决定保留只读历史。
- 独立停止信号阻止后续业务工具、技能读取和沙箱准入；已进入调用必须实际退出后才结束，严格基础设施失败不得降级为正常取消。已发生的外部副作用不回滚。
- 无配置键或 schema 迁移；需要整体更新服务端及控制台。首版仅支持单实例活跃句柄，无跨实例接管、重启自动补偿或独立 TEST 停止入口。新行为取代历史版本“不支持手动取消”的限制；验证与部署边界见[交付账本](superpowers/progress/2026-10-10-chat-stop-delivery-ledger.md)。

## 未发布：Windows 技能沙箱引导参数修复

- 服务端固定 Python 引导程序通过 Base64 包裹后交给 Docker CLI，修复 Windows Java 命令参数中的双引号被破坏、文件产物引导代码在输出 `CMA1` 前触发语法错误的问题。
- 覆盖 Windows → SSH 账号密码 → Rocky Docker 的非空 stdin 与 DOCX 文件帧回传；技能脚本和输入仍经 stdin，权限、隔离参数、输出预算、超时与清理策略保持原约束。
- 部署更新后的 Server 并重启后生效，无技能 ZIP、配置键或数据库迁移。实际验证和用户现场复验边界见[修复账本](superpowers/progress/2026-10-09-windows-sandbox-bootstrap-ledger.md)。

## 未发布：问答文件生成检查点容量修复

- 新增 PostgreSQL/MySQL V20：MySQL 的 `runtime_checkpoints.encrypted_payload` 扩为 `LONGTEXT`，PostgreSQL 保留 `TEXT` 并同步中文注释，修复大运行状态在检查点保存阶段触发字段长度超限的问题。
- 保留完整 AES/GCM 密文、旧数据及租户边界，不截断状态或关闭保存；JDBC 部署必须按流程应用 V20 后重试。文件下载仍需运行成功与既有授权，不因扩容提前放行。
- 双库升级、状态往返及 TXT 交付的实际结果见[修复账本](superpowers/progress/2026-10-09-checkpoint-payload-capacity-ledger.md)；真实模型原请求的复验与部署状态单独记录。

## 未发布：技能容量配置同步

- 纳入手工调整的容量值：ZIP 默认 4 MiB/硬上限 16 MiB，文件数默认 256/硬上限 512，单资源默认 256 KiB/硬上限 1 MiB，单 Run 累计读取及沙箱准备预算 16 MiB。
- 解压总大小 4 MiB、Run 快照准备 8 MiB、累计读取尝试 32 次保持；导入、准备和累计读取预算仍分别检查。同步默认/硬边界测试、沙箱累计预算回归及配置表；不增加配置键或数据库迁移。

## 未发布：数据库诊断日志修正

- 数据库失败日志新增 SQLState、厂商错误编号、原始 JDBC 异常类型与固定中文原因，保留脱敏后的原因链、附加异常及 JDBC 后续异常。
- 长度超限、唯一/非空/外键约束等按编号分类；SQL、参数、重复值、失败行与凭据不进入日志。未知原因保持固定脱敏说明，异常图设有循环与体积限制。
- 不改变前端错误响应、配置或数据库结构。验证证据见[诊断修正账本](superpowers/progress/2026-10-08-jdbc-diagnostic-ledger.md)。

## 未发布：技能长描述导入修复

- 新增 PostgreSQL/MySQL V18，将 `skill_versions.description` 从 500 扩为 1024 个字符，对齐 ZIP 解析器的描述约束，修复部分原版文档技能解析通过后版本写入失败的问题。
- 保留既有版本、摘要、资源和候选/发布指针；没有修改历史迁移或截断技能描述。JDBC 发布需按流程应用新迁移，修改资源大小配置不能替代数据库升级。
- 验证与生效边界见[修复账本](superpowers/progress/2026-10-08-skill-import-description-ledger.md)。

## 未发布：控制台技能版本升级

- v2 技能详情新增“上传新版本”，复用已有 multipart 双指针候选接口。ZIP 内技能名称保持一致，新内容形成递增候选版本；相同摘要复用，上传不自动试运行或发布。
- 区分列表栏“导入新技能”和详情内升级；显示真实版本号、中文历史状态、候选与正式版本。新增权限复核、重复/迟到保护、冲突与断线恢复提示，保留沙箱端点视图。
- 修复从其他页面进入技能页缺少沙箱页签、返回技能页复用旧组件导致内容与刷新不同；统一入口依赖，导航时释放旧实例，清空未提交凭据并拒绝迟到响应更新新页面。
- 无接口、数据库或部署配置变更；更新控制台静态资源后生效。验证及浏览器原生文件上传限制见[本轮账本](superpowers/progress/2026-10-08-skills-version-console-ledger.md)。

## 未发布：远程 Docker SSH 账号密码认证

- SSH新增可选`sshAuthType=PASSWORD`，旧省略仍为`KEY`；主机用户名/密码与严格known_hosts配套，不支持Registry或daemon HTTP Basic认证。
- 密码通过JSch 2.28.7内存会话到固定Docker Unix socket，不进入子进程参数、环境或生成文件；不回退私钥、本地或重试旧密码。保留既有私钥/TLS传输。
- 控制台增加认证选择与掩码只写输入，类型切换需完整新材料及凭据写权限；凭据省略仅同类型保留，轮换失效探测，在途连接仍固定。
- PostgreSQL/MySQL新增V17认证类型及中文注释，V16旧端点升级默认KEY。混合版本实例不能管理PASSWORD端点，应统一升级后启用；主密钥、租户、审计与既有隔离限额保持。
- 实际验证与平台证据见[账号密码认证账本](superpowers/progress/2026-10-01-remote-docker-password-auth-ledger.md)。本轮未部署。

## 未发布：R1 租户沙箱端点与远程 Docker

- 新增无 Spring/JDBC 的后端 SPI、统一限额注册器和固定连接生命周期。Docker 支持显式 LOCAL、SSH 严格主机身份验证与双向 TLS；当前调用执行/清理固定原主机，不自动重放或回退。
- 新增本租户端点 API、独立读/写/删除/探测/凭据权限与严格审计。默认选择要求当前配置版本探测通过；轮换或更新后重新探测，停用/删除在交付阶段复核。
- SSH/TLS 材料使用独立部署主密钥 AES/GCM 加密，绑定租户、端点及凭据版本；响应不回显明文/密文。V16 两库新增端点及默认表、中文原生注释、tenant 组合外键和 CAS/租户锁。
- v2 技能页增加端点列表—详情—操作视图，支持保存→测试→默认、轮换、删除、失败保留普通表单与错误编号。原技能导入、发布、Run/TEST 治理与容器隔离保持兼容。
- 显式连接不读取全局 Docker/SSH 配置。旧远程 DOCKER_HOST 须迁移为 SSH/TLS；缺失沙箱主密钥拒绝管理写入。部署前需授予管理角色新增权限、准备可信镜像和目标允许列表；没有跨主密钥自动重加密、跨实例执行接管或非 Docker 后端。实际验收见[本轮账本](superpowers/progress/2026-09-30-skill-sandbox-ledger.md)。

## 未发布：技能 Python 容器沙箱

- 新增默认关闭的 `cm-agent.skills.sandbox` 策略和受治理 `run_skill_script` 工具；仅执行当前 Run 固定版本中已登记的 Python 脚本，支持同版本文本资源与有界 stdin。开启后导入白名单自动补入 `.py`，能力接口新增执行开关、语言与限额字段。
- 执行前后校验可信租户、主体、运行状态、快照与撤销权限，复用读取预算与准备记录；同一调用标识拒绝重复执行。准备记录表示资源交付沙箱，执行终态通过 `SKILL_EXECUTE` 审计与 Run 状态确认。容器在短数据库事务之外运行，严格审计失败不会继续执行或交付成功输出。
- 容器默认禁网、非 root、只读根文件系统、无宿主挂载，限制 CPU、内存、PID、时间和输出；失败包含稳定错误码和 `errorId`，不返回 Python traceback。Linux 部署需要 Docker CLI、可用 daemon 和预拉取的可信 Python 镜像；不回退宿主解释器。共享宿主内核，处理不可信代码时应采用强化 runtime 或独立执行主机。
- 资源准备使用独立 `SANDBOX_PREPARED` 状态，不计作成功模型读取或放行 TEST 发布门禁。V15 分别维护 PostgreSQL/MySQL 的状态字段中文原生注释，不改变表结构；旧服务无法解析该新状态，写入沙箱准备记录后应使用同版本实例，回退旧版本前需评估兼容性。

## 未发布：A 批次审批主动过期

- 增加会话与独立 TEST 过期扫描，默认开启、间隔 30 秒、批次 50；V14 添加扫描索引，不升级依赖。系统主体不借用用户权限，不调用 Runtime 或外部工具。
- 统一过期条件竞争和 JDBC 短事务：EXPIRED、等待 Run DENIED、检查点清理、关联 TEST FAILED 与严格审计同成同败。memory 复用技能工作单元但不提供跨仓储回滚。已批准或已恢复运行不被扫描误收口；失败候选有脱敏诊断并在后续遍历重试。
- TEST 控制台提交失败后先锁定旧入口，通过只读查询恢复权威结果；新增刷新试运行状态，终态不能继续决定，保留 v1/v2 兼容。
- 修复发布/回滚路径参数对编译元数据的依赖、新 TEST 沿用旧发布按钮，以及 MySQL 秒级时间舍入造成快速 Run 完成失败；均由本轮端到端或双库验收发现并补回归。
- 跨实例执行租约、已批准未知结果的自动恢复、外部副作用恰好一次仍未提供。实际验证与未执行项以[本轮账本](superpowers/progress/2026-09-30-approval-expiry-a-ledger.md)为准。

## 发版状态（截至 2026-09-28）

父 POM 版本仍为 `0.1.0-SNAPSHOT`，本文件仍是待发布草案，尚未标记正式版本。基于代码提交 `8fb2c15` 并补齐三处测试上下文后，Rocky Linux 上使用 Maven 3.9.9、JDK 21 和 Testcontainers 执行 `mvn -q test` 通过：80 个 Surefire 测试报告共 497 项，失败 0、错误 0、跳过 0。首次运行暴露的 9 个夹具错误已由 `AgentScopeRuntimeConfigurationTest`、`ApplicationProfileConfigurationTest` 和 `ConsoleSmokeTest` 的测试配置修正；该结果不替代真实模型、浏览器及发布流程验收。

本轮运行验证了 PostgreSQL 16 与 MySQL 8.4，并成功执行 V1–V13 迁移；Flyway 对 MySQL 8.4 输出高于已测试支持版本 8.1 的提示。依赖树另确认 MCP 示例解析为 AgentScope Core/Redis 扩展 2.0.2 与 MCP SDK 0.17.0；Testcontainers BOM 声明 2.0.5，但实际解析版本为 1.21.0，本轮测试通过的是 1.21.0。发正式版本前仍需确定版本号，并完成真实模型、浏览器和发布流程的验收；Testcontainers BOM 与实际解析版本差异也应由维护者确认预期。

## 未发布：Skill 候选发布、依赖预检与 TEST Run

- 技能版本改为候选与正式发布双指针；上传不会改变正式 Run，发布与回滚均追加不可变事实。新绑定默认 `FOLLOW_PUBLISHED`，可用乐观锁修订切换为固定曾发布版本；已有 Run 继续使用自己的版本和依赖快照。
- 新增工具依赖声明、租户内逻辑映射、结构/Agent/发布预检、`POST /api/skills/{skillId}/trials` 指定版本试运行，以及发布和回滚接口。`TEST` Run 不创建会话或消息，但沿用真实模型、工具授权、风险审批和审计，因此可能产生外部副作用；只有目标技能实际读取且映射修订未漂移的试运行能满足发布门禁。
- V13 为 PostgreSQL/MySQL 增加发布、依赖、预检、试运行与快照所需结构及中文注释。v2 技能详情新增候选、依赖映射修复、结构预检、试运行、版本历史和回滚；Agent 详情可选择跟随正式发布或固定历史版本。控制台只展示服务端脱敏结果、错误码与 errorId，不自动授权工具或自动发布。
- 修复 TEST Run 高风险工具审批恢复：控制台可按 Run 读取并逐项处理待审批请求；审批创建失败或过期时服务会关闭 Run、删除加密检查点并记录审计。审批恢复前重新复核必需依赖工具的启用与授权状态，且关闭 `cm-agent.skills.enabled` 时不能再创建试运行。
- 修复技能发布工作区的预检状态展示字段不匹配，并显式指定试运行控制器 UUID 路径参数名称，避免运行时因编译参数元数据缺失而返回 400。test profile 浏览器复验确认预检通过、试运行进入 Fake Runtime 并以 `NOT_TRIGGERED` 保持发布门禁关闭；不代表真实模型或正式发布验收。
- 技能管理页的指定版本试运行将“目标 Agent ID”改为 Agent 下拉选择，只展示当前租户中已启用且具有有效 ID 的 Agent，并显示名称与模型；页面按 `agent:read`、`agent:run` 权限及加载结果给出可操作提示。仍使用既有 Agent 列表与试运行接口，服务端继续负责最终权限和状态校验，不改变 API、数据库或授权语义。
- 试运行接口在原有 `runId`、门禁状态与审批信息之外新增脱敏 `preview`，包含 Run 状态、最终输出/错误、时间和最小化工具调用摘要；开始试运行、审批恢复后的响应及有权读取的试运行详情均返回该预览。v2 技能页安全展示状态、输出、错误和工具调用摘要，恢复页面时可重新加载结果；原始工具输入、租户/主体标识及工具调用 ID 不进入预览，不改变 TEST Run 的真实执行与发布门禁语义。

## 未发布：OpenCode Go 消息协议兼容

- 修复 AgentScope 2.0.2 默认发送消息级 `name` 导致 OpenCode Go 返回 HTTP 400 的问题。仅在
  `OPENAI_COMPATIBLE` 基础地址精确匹配 `opencode.ai` 的 `/zen/go/v1`（可带末尾 `/`）时省略该字段；
  工具名称、参数、正文、原始消息和既有运行会话头保持不变。其他网关与 OpenCode Zen 不受影响。
  无依赖升级、配置项、API 或数据库变更，部署后需重启服务。

## 未发布：Skill 受治理加载

- 新增文本型 Skill：ZIP 接受 `SKILL.md` 与配置开放的文本资源，默认停用；定义、不可变版本、资源、Agent 绑定、Run 快照和读取记录在 memory/JDBC 合同中保持 tenant 隔离。新 Run 固定版本，停用、解绑或访问纪元变化会阻止旧 Run 继续读取或审批恢复。
- 新增技能管理和绑定 API，以及控制台“技能管理”页面。页面支持 ZIP 导入、详情、启停和固定版本资源预览；Agent 详情支持绑定已启用技能，运行详情只显示读取元数据与错误编号，不展示正文。`skill:read`、`skill:write` 与既有 `agent:*` 权限由服务端共同校验，绑定不授予业务工具权限。
- AgentScope 2.0.2 原生 SkillBox 使用只读内存映射与受治理读取桥接。模型只能按 Run 快照读取登记路径，读取前后均核验预算和撤销状态；原生加载器、审计或持久化基础设施失败会中止运行，不能伪装成普通工具文本。
- 新增 PostgreSQL/MySQL 方言 V12 技能表与历史 Run 空快照回填。部署前先备份并完成 Flyway 迁移；生产以 `cm-agent.skills.enabled=false` 起步，完成权限、容量和模型上下文验证后再显式开启。完整配置和排障步骤见 `docs/configuration.md` 与 `docs/operations.md`。

## 0.1.0-SNAPSHOT：阶段3真实 AgentScope Runtime

本快照在阶段2生产持久化与安全收口基础上，接入 AgentScope Java 2.0.2 真实 Runtime。第一阶段底座和阶段2的 JDBC/Flyway、安全、多租户、权限与严格审计边界继续保持。

- 新增消息一等公民的持久化会话：Core 提供 Conversation、ConversationMessage 和有序内容块合同，文本、工具调用与工具结果以脱敏快照保存。新增 `/api/agents/{agentId}/conversations` 会话/消息分页、同步发送和 SSE 发送接口；续聊使用最近 40 条、最多 60,000 字符的完整消息窗口，原 Run API 与 `AgentRunResult` 保持兼容。
- 新增 PostgreSQL/MySQL 方言 V10，扩展 `conversations.updated_at` 与消息 sequence、发送方、内容块 JSON、关联 runId，补齐中文数据库注释、租户索引、会话内 sequence 唯一约束和 Run 外键。memory 模式同步提供仅用于本地和测试的会话仓储。
- AgentScope Adapter 从最终 `Msg` 映射安全的 TEXT/TOOL_USE/TOOL_RESULT 块，会话使用 conversationId 作为 sessionId；会话 SSE 使用 `started`、`message-started`、`delta`、`completed/error` 生命周期事件。控制台运行页新增会话选择、新建会话、历史回放和连续发送。

### 本次变更

- 修复 OpenCode Go 的真实运行兼容性：当 `OPENAI_COMPATIBLE` 基础地址主机精确为 `opencode.ai` 时，模型传输层自动携带供应商必需的 `x-opencode-session`，值绑定服务端已创建的 `runId`，同 Run 重试保持一致；不新增可由客户端提交的自定义 Header 配置，其他兼容网关不受影响。同时将 AgentScope 2.0.0 在可信 `RuntimeContext` 绑定前的无用户默认状态槽读取视为未命中，继续拒绝所有无可信身份的检查点写入、删除和非空非法身份访问，消除误导性运行告警而不放宽租户边界。

- 模型配置 v2 页面新增显式“获取模型列表”：服务端新增草稿和已保存配置两条发现接口，统一返回去重、排序且受上限约束的模型 ID，页面支持候选选择和手工输入回退。发现请求仅由服务端发起，已保存密钥只可用于原配置地址；请求禁止重定向，默认仅放行 OpenAI 与 DashScope 官方主机，并受 5 秒、128 KiB、200 项限制。新增 `MODEL_DISCOVERY_*` 错误码、审计事件和同一 `errorId` 的诊断日志，不新增数据库迁移或目录缓存。

- 补齐会话审批历史回显：新增 `GET /api/agents/{agentId}/conversations/{conversationId}/approvals/history`，按决定时间/审批 ID 游标分页，要求 `agent:read` 并隔离 tenant、Agent、会话。前端把待审批和只读历史分开；同 Run 多轮决定按消息关联，未匹配当前消息窗口的记录在历史区展示，支持重新进入回显、加载更早和失败重试。历史不提供批准按钮，也不触发工具执行或自动过期处理。复用既有 V11，无新增迁移或配置；跨重启保存要求 JDBC。Node 历史回归及 Rocky PostgreSQL/MySQL 专项测试通过，真实浏览器及进程重启验收仍待完成。

- 优化聊天页人工确认：增加仅本次授权和参数核对说明、逐项决定汇总及结果明确的提交按钮；批量按钮明确只改变选择，单项不显示批量操作。提交、接受、恢复和未知结果分别提示，终态详情折叠；内存草稿绑定审批版本/调用快照，退出或快照变化失效。新增只查询的“刷新审批状态”入口，保留错误编号、防重复提交及纯文本参数展示。仅前端变化，不调整权限、API 或数据库；真实浏览器视觉和焦点验收待完成。

- 新增 AgentScope Permission System 在线审批：桥接工具统一继承 `ToolBase`，`cm-agent.agentscope.permission-enabled=true` 时 HIGH 工具按具体 `toolId + toolCallId + 输入哈希` 在治理网关前进入 ASK，Run 状态变为 `WAITING_APPROVAL`；聊天 SSE 发送 `approval-required`，审批接口按全部明细原子接收允许/拒绝决定，并使用原发起主体从加密 AgentState 恢复。审批者必须是运行发起人且同时拥有 `agent:run` 和新增 `agent:approve`；恢复执行仍重新校验 tenant、ToolGrant 和工具启用状态，同名替换工具不能继承旧批准。每轮 ASK 前已执行的工具记录会保存，恢复预检失败尝试终结 Run、清理检查点并审计；接口与日志使用相同错误编号，受控拒绝和内部故障分别记录脱敏 WARN/ERROR。控制台支持刷新恢复、纯文本参数、多工具决定、旧会话响应隔离和异常后的权威状态查询。新增 PostgreSQL/MySQL V11；功能默认关闭，有效期默认 15 分钟、最大 24 小时。主动过期扫描、崩溃自动接管和完整浏览器端到端验收尚未交付，详见运维说明。

- 会话聊天页新增类似 AgentScope Studio 的可折叠“执行过程”：实时展示模型实际产生的完整思考块以及工具调用准备、执行和终态；工具桥接器以实际进入治理网关的调用为唯一来源，即使 AgentScope 最终消息未保留工具块，历史消息和 SSE `progress` 仍会关联展示入参、返回值（或受控错误）及服务端耗时。所有展示载荷均由服务端脱敏、限长后才会持久化或发送到浏览器，绝不传递原始参数、原始结果或凭据；思考块只用于可观察性展示，不会作为后续轮次提示词重新注入模型。原有 Runtime 实现通过默认重载保持兼容，不新增数据库迁移；已写入 `THINKING` 内容块后，回滚到不认识该枚举值的旧版本前应先确认历史消息兼容策略。

- v2 控制台新增独立“会话聊天”页：可按 Agent 创建、切换和继续持久化会话，历史消息、受控工具摘要和模型回答以聊天形式呈现；发送通过既有会话 SSE 实时追加文本，完成后以服务端持久化消息为准刷新。该页面复用现有认证、租户隔离、`agent:read`/`agent:run` 权限、审计和错误编号，不新增 API、数据库迁移或消息编辑/删除能力；原运行记录页继续用于运行调试、历史和工具调用回看。

- 新增可选的 AgentScope Studio 开发期调试集成：`cm-agent.agentscope.studio.enabled=true` 时，服务通过 AgentScope 2.0.2 的进程级系统 Hook 将 Agent 消息转发至 Studio。Studio 地址、项目和服务实例 Run 名称均可配置；严格生产 profile 会拒绝启用，且不改变 CM Agent 的 Run、审计、权限或租户隔离语义。

- v2 运行记录页改为流式输出：新增 `POST /api/agents/{agentId}/runs/stream` 同源 SSE 接口，依次发送 `started`、已脱敏的 `delta`、`completed` 或包含稳定错误码和 `errorId` 的 `error` 事件。AgentScope Runtime 转发最终回答的文本块增量，不发送思考过程、工具参数或工具原始输出；连接断开不取消后端运行，运行记录、工具调用和审计仍按既有终态流程落库。原 `POST /api/agents/{agentId}/runs` 保持不变，以兼容现有 API 调用方。
- Agent 管理新增 `PUT /api/agents/{id}` 与 `DELETE /api/agents/{id}`：创建和编辑请求使用当前租户的 `modelConfigId`，服务端仅接受已启用配置并从配置读取实际模型名称；删除需要新增的 `agent:delete` 权限，会自动移除无历史 Agent 的工具授权并写入严格审计。已有会话或运行历史的 Agent 返回明确 `409 Conflict`，不可级联删除历史，应通过编辑接口停用。v2 控制台新增模型配置下拉选择、编辑、删除确认和冲突提示；v1 旧请求的 `modelName` 仅在当前租户唯一匹配一个已启用配置时暂时兼容。
- 新增租户级模型配置管理 API 与 v2 控制台页面：`GET/POST /api/model-configs` 和 `GET/PUT/DELETE /api/model-configs/{id}` 分别使用 `model:read`、`model:write`、`model:delete` 权限，创建、更新、删除写入严格审计；删除仍被 Agent 引用的配置返回明确 `409 Conflict`，启动初始化器维护的系统默认配置不可删除但可停用或更新。创建请求必须写入 API Key，更新可轮换 API Key；服务以 AES/GCM 加密后写入数据库，所有响应均不回显密钥。新增 PostgreSQL/MySQL 方言 Flyway V9，仅更新密文字段的中文数据库注释。
- 新增 Flyway V8 数据库注释迁移，为现有 18 张业务表和 135 个字段补齐中文原生注释；PostgreSQL 与 MySQL 使用同版本方言脚本，由统一 Flyway 配置按 JDBC 元数据选择，迁移测试逐表、逐字段阻止后续注释遗漏。
- 新增示例模块 `cm-agent-examples/dashscope-mcp-agent`：演示 AgentScope Java 智能体使用内置 `McpClientBuilder` 以 Streamable HTTP 协议连接外部 MCP 服务（示例地址 `http://localhost:8088/api/mcp`）、注册其时间查询等工具，并由阿里云百炼 DashScope `qwen3.7-plus` 模型驱动 `ReActAgent` 自动决策调用。示例通过独立 `main` 方法运行，不依赖 CM Agent Server，也不经过其租户隔离、权限与审计链路；模型 API Key 从 `DASHSCOPE_API_KEY` 环境变量读取，本项不改变生产 API、数据库 Schema 或现有工具治理语义。该模块单独锁定 `io.modelcontextprotocol.sdk:mcp-core`/`mcp-json-jackson2` 为 `0.17.0`（与 `agentscope-core:2.0.2` 当前构建中的 MCP 客户端依赖一致），避免与父 POM 为 `cm-agent-server` 自身 MCP Streamable HTTP Server 管理的 `2.0.0` 版本发生二进制不兼容（`McpSchema.Tool#inputSchema()` 返回类型不同导致的 `NoSuchMethodError`）；不修改父 POM 的 `mcp.version`，不影响 `cm-agent-server` 现有 MCP 端点。
- 新增面向开发者的 LOCAL 与 HTTP 工具开发指南；完善可运行的 LOCAL `echo`/`add` 多工具示例，并新增通过公开 REST API 创建和调试 HTTP 工具的客户端示例。本项不改变生产 API、数据库 Schema 或现有工具治理语义。
- 新增动态 HTTP 工具：支持 GET/POST、嵌套 JSON Schema、本地引用、JSON Pointer 参数映射、缺失/null 默认值、PATH/QUERY/HEADER/BODY 目标及 `secret/...` Header 引用；创建与配置保存保持原子性和租户内工具名称唯一。
- 动态 HTTP 工具统一使用扁平 `parameters` 定义：由 `id + parentId` 表达对象和数组关系，顶层 `requestLocation` 直接声明 PATH、QUERY、HEADER、BODY 或 BODY_ROOT，服务端自动生成输入 Schema；不再接收或执行 `inputSchema + parameterMappings`、`sourcePointer`、`targetPointer` 或 `nodeRole`，并通过 `BODY_ROOT` 支持 `[{"p1":"v1"}]` 一类根数组请求。
- HTTP 工具允许提交空 `parameters: []`，用于没有 PATH、QUERY、HEADER 或 BODY 输入的接口；服务端为其生成禁止额外字段的空对象 Schema，调用输入固定为 `{}`。
- 新增受治理 HTTP 执行器，默认关闭并要求主机白名单；执行时校验 SSRF 风险地址、同源重定向、总超时和响应上限，输出经过结构化脱敏。部署仍需以 egress 防火墙、受控 DNS 或代理防御 DNS TOCTOU。
- 新增默认关闭的 MCP 2.0 Streamable HTTP 服务端点。启用时必须配置 Origin/Host 白名单，端点保持 JWT 认证、`tool:mcp:invoke` 授权、多租户目录隔离和严格 MCP 调用审计；每个请求无状态构建并在完成后关闭 transport/server。
- 新增 MCP 发布/取消发布与 HTTP/LOCAL 单工具调试。调试需要 `tool:debug`，HIGH 风险工具要求完全匹配的名称确认；发布管理需要 `tool:grant`。取消发布、禁用或配置漂移会在下次 MCP 调用立即生效。
- 工具调试失败响应不再统一隐藏为“工具调试失败”：控制台显示经过脱敏的具体原因、稳定错误码和错误编号；服务端使用同一错误编号记录租户、工具、状态及脱敏诊断信息，未分类异常只在后台保留脱敏堆栈。
- REST 失败响应统一新增 `errorId`，并通过 `X-Request-Id` 回传调用关联编号；控制台在 `5xx` 响应含有结构化消息时展示消息、错误码和错误编号。Agent 运行、受治理工具调用及 MCP 工具调用失败也统一写入脱敏诊断日志，便于按运行、工具调用和租户上下文定位。
- 新增工具编辑、删除与 Agent 解除关联：编辑和解除关联需要 `tool:grant`，删除需要 `tool:delete`；仍被 Agent 引用的工具返回明确的 `409 Conflict` 且无副作用，必须先在 Agent 详情确认解除关联。已有调用历史的工具返回另一条明确的 `409 Conflict`，保留工具定义、运行历史、调用记录和审计链路，控制台不会将其误判为可解除的关联冲突。
- 工具编辑保持工具 ID、租户、类型和创建人不变，LOCAL 工具不可改名。HTTP 工具编辑必须提交有效 HTTP 配置，其他类型拒绝 HTTP 配置；已发布 LOCAL 工具可保持或取消原 MCP 发布状态，未发布 LOCAL 工具仍通过独立发布操作管理。
- 工具更新接口直接返回本次写命令提交的定义、HTTP 配置与发布状态快照，不再在成功审计后重新查询，因此同一工具的并发更新不会互相污染响应，也不会因随后删除而把已成功更新误报为 `500`。
- 成功删除采用安全软删除：管理面立即不可见并释放原名称，但保留工具定义墓碑作为 `tool_calls` 外键锚点。删除前已开始的调用即使在解除关联和删除后才持久化，仍会完整进入运行历史；已有调用历史的删除 `409` 语义保持不变。
- 非生产 MySQL 固定目录的内置 LOCAL 示例删除后可按固定租户、固定 ID、原名称和类型受控原位恢复，安装与审计保持同一事务；普通工具保存不能复活墓碑，PostgreSQL、MySQL 与 memory 的主键语义保持一致。
- Agent 工具关联的内存更新采用原子变更，JDBC 更新在同一事务内锁定 Agent 行；授权、撤销和审计共享事务边界，避免同一 Agent 的不同工具在多实例并发下发生丢失更新。工具更新与删除统一锁定工具行，更新命中零行时返回明确的不存在响应。
- 轻量控制台升级为面向使用者的可操作管理控制台，采用独立登录页、左侧导航、能力总览和分模块管理布局。
- 控制台新增版本化多页面入口：根路径默认跳转到 `/console/v2/login.html`，v2 将登录、总览、Agent、模型配置、Tool、运行和审计拆为七个独立 HTML 页面；原始 `index.html` 不删除，并继续通过 `/console/v1/` 提供。v2 使用仅作用于 `/api` 的 `HttpOnly`、`SameSite=Strict` 会话 Cookie 恢复刷新认证，并在当前文档内加载独立 HTML 以兼容嵌入式浏览器；JWT 只短暂保留在当前页面内存，不写入浏览器存储或 URL。修复了 `body[data-page]` 被误绑定为导航按钮、点击登录时未认证概览请求与登录请求竞争并回跳的问题；退出接口会立即清除 Cookie，用户名和密码不落地。
- 控制台覆盖当前用户、Agent 列表/详情/创建、Tool 列表/创建/编辑/删除/授权与解除关联、Agent 执行、运行历史/详情/工具调用和审计游标分页；健康检查与 OpenAPI 作为辅助入口。
- HTTP Tool 注册与编辑表单改为树形参数编辑器，支持在 OBJECT/ARRAY 节点内直接添加子参数并按层级缩进展示；页面根据 `parentId` 还原树，提交时自动转为扁平参数数组。表单同时提供类型、请求位置、默认值、示例值及包含 PATH、QUERY、BODY_ROOT 根数组的完整示例，并已移除旧版 Schema 与映射入口。
- v1 控制台继续使用页面内存令牌；v2 使用前端不可读取的会话 Cookie 恢复刷新认证，并只在当前文档内存中保留登录令牌。两个版本复用统一 `401` 失效处理和纯文本 DOM 渲染，不使用 `localStorage` 或 `sessionStorage` 持久化 JWT、用户名或密码；补充窄屏响应式布局和键盘焦点样式。
- 控制台仍不提供手动取消、消息编辑/删除、会话归档、自动摘要、无人值守暂停、长期审批规则或独立审批中心。
- `agentscope.version` 升级到 `2.0.0`，接入 OpenAI Compatible 与 DashScope Provider，提供同步单轮 ReAct 运行。
- 通过 `tenantId + modelConfigId` 调用 `ModelCredentialProvider` 获取模型凭据；默认实现从数据库读取 AES/GCM 密文并在运行时解密，`model_configs` 不保存明文 API Key。
- 生产 profile 使用 `fake-runtime-enabled=false` 与 `agentscope-enabled=true`；fake runtime 继续仅服务本地和测试。
- 工具每次调用重新授权并记录严格审计，endpoint 元数据不自动执行；模型、工具 timeout 和 Provider 故障按固定结果语义收口。
- 非生产 `mysql` profile 为固定 bootstrap 示例租户 `00000000-0000-0000-0000-000000000001` 新增固定 `echo`、`add` LOCAL 示例目录与控制台显式安装入口；其他 tenant 的目录为空且安装返回 `404`。启动只在当前 JVM 注册固定 Java 执行器，示例租户中具有 `tool:grant` 权限的主体操作后才写入 MySQL；工具摘要新增仅表示注册快照的 `runtimeReady`，实际调用仍重新执行治理校验。该入口不支持动态代码，正式业务 LOCAL 工具仍须在同一 Server JVM 中注册 `ToolExecutor`；不新增数据库 Schema 或 Flyway 迁移，`prod`、`production`、`supabase` 不启用此能力。

- Run、ToolCall、Audit 接入 JDBC Repository，并保持每次读写的 tenant 隔离。
- 通过 Flyway 新增 `V2__add_runtime_query_indexes.sql` 和 `V3__add_tool_calls_created_at_index.sql`，为运行、工具调用和审计查询增加租户范围索引。
- Run 启动与完成采用两段式持久化：启动阶段记录 `RUNNING` 与启动审计，完成阶段更新结果、写入 ToolCall 并记录完成/失败审计。
- Run 和 Audit 查询支持有界 cursor 分页；Run 详情返回同 tenant 的 ToolCall。
- 审计写入失败保持严格语义，API 返回 `503 Service Unavailable`；错误、输入、输出和日志经过敏感信息脱敏。
- 收口 JWT secret、profile、bootstrap admin、生产 JDBC 和错误响应边界；`production`、`prod`、`supabase` 必须使用 JDBC，禁用 bootstrap admin 和开发 JWT fallback。
- 公共 `application.yml` 不再默认选择 `local`；部署应通过 `spring.profiles.active`，`CM_AGENT_PROFILE` 仅作为兼容选择器。

### 数据库迁移影响

- 不修改已经发布的 `V1__init_schema.sql`。
- 新增 PostgreSQL/MySQL 方言版 `V8__add_schema_comments.sql`，只写入表和字段注释，不增加或删除业务列；MySQL 受语法限制会以 `MODIFY COLUMN` 完整重述既有字段定义，部署前必须备份并通过迁移测试核对结构约束。
- V2、V3 迁移只增加 `runs`、`tool_calls`、`audit_events` 的查询索引；V1 已建立对应表和基础租户约束。
- 新增 `V5__soft_delete_tool_definitions.sql`，为 `tool_definitions` 增加 `deleted_at`、`deleted_name` 和租户删除状态索引。旧迁移不变；墓碑行保留原名称副本，活动名称改为内部唯一值以释放租户内名称约束。
- 新增 V6 扁平 HTTP 参数定义列，并通过 `V7__remove_legacy_http_parameter_mapping.sql` 删除 HTTP 配置表中的旧 Schema 与 JSON Pointer 映射列；历史 HTTP 映射数据不再兼容。
- 新增 PostgreSQL/MySQL 方言 V11，创建 `tool_approval_requests`、`tool_approval_items` 和 `runtime_checkpoints`，包含租户复合外键、乐观锁版本、审批查询索引、AES/GCM 密文状态槽及完整中文表/字段注释。回滚到旧版本前必须先收口所有 `WAITING_APPROVAL` Run。
- JDBC 应用启动时由 Flyway 执行迁移。升级前应备份数据库、核对 `flyway_schema_history`，并准备迁移失败处理与恢复预案。
- 生产可将迁移账号与运行账号分离；连接信息、密码和 JWT secret 只从受控外部 YAML 或 secret manager 注入。

### 兼容性与安全注意事项

- `memory` 仍只用于开发和测试，重启会丢失 Run、ToolCall 和 Audit，不适用于生产。
- 现有 API 的认证、权限、租户过滤和审计约束继续生效；新增的 cursor 由服务端生成，调用方不应自行构造。
- 审计写入失败不再被忽略，会导致请求返回 `503`；部署和告警系统应将其视为依赖不可用。
- 生产 profile 不允许 bootstrap admin、开发 JWT fallback 或可用的固定凭据。文档和配置示例仅使用占位符。
- 真实 Runtime 当前支持兼容单轮 SSE、持久化会话 SSE 与在线 HIGH 工具审批；不承诺手动取消、会话归档、自动摘要、写请求幂等重放、无人值守暂停或长期审批规则。
- AgentScope 2.0.2 工具层的通用取消信号不能证明外部副作用已停止；有副作用的工具必须使用 `runId`、`toolCallId` 或业务键保证幂等。
- 模型 API Key 通过受权限保护的模型配置接口加密写入数据库；加密主密钥只能使用受控环境变量或 Secret Manager 注入，密钥不得进入 Git、日志、审计或 API 响应。

### 未包含范围

以下内容不属于本次阶段3发布：

- 消息编辑/删除、会话归档、自动摘要、写请求幂等重放、手动取消、无人值守暂停、长期审批规则和独立审批中心。
- 阶段4 metrics、集中式日志与追踪、备份恢复自动化、容量治理和应用自动归档。
- 阶段5 CI/CD 交付流水线、发布自动化、稳定性工程和正式版本承诺。

详细边界见[中文路线图](roadmap.md)。

## 未发布：技能沙箱文件产物 R0

- 固定包内 Python 可将普通附件写入 `/workspace/output`，通过有界二进制协议在同一 LOCAL/SSH KEY/SSH PASSWORD/TLS 连接退出前回传；原禁网、非 root、无宿主挂载及 stdout 预算保留。
- 新增独立文件配置、AES/GCM 私有卷存储、JDBC V19 元数据与跨实例配额/租约。调用只保留 PENDING，Run 成功持久化和严格审计后发布；失败补偿与过期清理不提前返还容量。
- 新增正式/会话/TEST 列表和统一鉴权下载，复核创建者及权限，完整解密认证后以附件交付。v2 三个结果区域增加文件卡片，复用当前会话 fetch/Blob 保存与导航清理。
- 首版不支持原 docx.zip 的 Node.js 动态脚本、预览、分享、Range 或 S3；真实模型检查点长度问题 X1 不在本次修复范围。实际验收及未执行项见 [工作包账本](superpowers/progress/2026-10-08-skill-artifacts-ledger.md)。本轮未提交、未部署。

## 2026-10-27 工作区参考目录选择（未发布）

- 增加管理员登记的只读目录 catalog、会话首发前选择及版本更新、Run 固定元数据；DEFAULT/SELECTED/NONE 区分旧调用、选定目录和明确不用资料。
- v2 聊天增加目录摘要、筛选与选择弹层、失效提示、已发消息锁定和新会话入口；Run 详情可查看本轮选择。
- PostgreSQL/MySQL 新增 V22 元数据迁移，正文继续沿用加密检查点，审批恢复校验原目录身份与快照；Harness/Skill 权限和宿主文件边界不变。
- 发布门禁与实际验证见本次实施账本，未执行的真实 Provider 或重启验收不能视为通过。配置及排空回退见 configuration.md、operations.md。

## 2026-10-28 本机资料导入存储基线（R1 T9，未发布）

- 新增IMPORTED协议及不可变导入清单、DRAFT/READY/REVOKED/EXPIRED、memory/JDBC原子配额、上传/共享读取/GC租约和崩溃补偿清理。
- 新增独立AES-256/GCM私有输入卷，复核相对路径、文本、长度和摘要；不接受本机绝对路径，正文不进入普通元数据表。V23无损扩展V22来源约束，新增两库同语义表和中文原生注释。
- 本阶段未开放上传API、原生文件夹窗口或IMPORTED运行绑定；提前提交新来源会明确拒绝，不能回退DEFAULT。T10–T14及完整R1发布门禁仍待完成。实际命令与边界见[当前R1账本](superpowers/progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)。
## 2026-10-28 本机资料导入API与所有者隔离（R1 T10，未发布）

- 新增capabilities、明确确认后的manifest创建、服务端fileId原始正文上传、complete、GET及撤销；增加requestId与GET by-request核对未知创建结果，拒绝重复创建/覆盖，新增双库V24，历史V22/V23不改。
- 原始正文有数量/字节/总等待预算和真实线程硬界，不使用multipart临时落盘；完成前复核真实密文，审计失败回滚或撤销补偿。外层事务中的拒绝在事务退出后完成严格审计与同号诊断，两库保留拒绝证据。
- IMPORTED派生会话、Run、消息、审批和技能记录按原主体隔离，列表在游标/LIMIT前过滤；原三态保持旧权限，私有资源对外复用旧路由通用404。自定义ConversationRepository/RunRepository需实现listVisible，否则明确拒绝。
- 默认关闭上传。T10只完成API和读取隔离，READY的bindable/runtimeAvailable仍为false；T11运行冻结/恢复、T12原生选择和T13/T14整体门禁仍待做，未提交、未部署。实际命令、跳过项及边界见[当前R1账本](superpowers/progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)。

## 2026-10-28 工作区目录单选修复（R1 T12/T13局部，未发布）

- 修复v2会话“可选目录”未设置真实radio分组，导致默认项和登记目录同时选中且不能互相取消的问题；显式设置同名分组，选择“不使用工作区”后保存NONE，取消不写入。
- 共享app.js版本更新为2.0.38，九个v2入口同步；JS177项、Java21控制台资源16项及真实HTML的DOM前后对照通过。当前8080服务已提供新脚本，现场浏览器因Work设置关闭本地浏览器访问未验；不据此宣布R1整体发布通过。未提交。

## 2026-10-28 Windows登记资料可信系统权限（R2，未发布）

- DEFAULT/SELECTED参考资料允许所有者、固定SID的LocalSystem与本机内置Administrators；继承权限可保留，不按显示名信任同名账户。其他主体的ALLOW继续拒绝，不开放任意白名单或跳过开关。
- 校验覆盖登记根、选中的中间目录和文件，审批恢复/ASK及交付前重新检查；保留原正文快照、路径/链接/预算、owner/RBAC和严格错误诊断。源文件删除不重建正文，仅对仍存在路径及存活祖先查权限。
- Server新增JNA5.17.0核心依赖，用只读Win32 API取得SID并显式释放资源；不新增Shell/helper，不修改用户ACL，不影响POSIX或IMPORTED/Skill/Harness私有存储策略，无schema/配置键变更。
- Windows真实NTFS、两策略回环合同与Rocky兼容结果分别见[工作包账本](superpowers/progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)。需包含新依赖并重启Server；不将R2通过等同R1全部原生窗口/真实Provider发布门禁完成。未提交、未部署。
