# 技能沙箱文件产物进度账本

> 当前交付与剩余验收见文末 R0 当前交付状态；前文仅文档/待做描述为历史快照。

日期：2026-10-08｜主题：skill-artifacts｜修订：R0｜工作模式：mode=default

本工作包是独立新增需求，关联原技能沙箱工作包；本轮交付需求分析与六份文档，业务实施尚未执行。普通工程细节按默认模式自主取舍，所列方案是自主假设，不代表用户逐项确认。

[清单](../checklists/2026-10-08-skill-artifacts-checklist.md) · [执行提示词](../prompts/2026-10-08-skill-artifacts-prompt.md) · [设计](../specs/2026-10-08-skill-artifacts-design.md) · [实施计划](../plans/2026-10-08-skill-artifacts.md) · [实现说明](../implementation/2026-10-08-skill-artifacts-implementation-design.md)

## 基线与授权范围

- 2026-10-08，HEAD：`d0ee3a10dc8ec545e58ab36c0648c0c4b6cabc86`，分支：`codex/skills-version-console`。
- 用户本轮调用 requirement-workpack 新增文件产物需求；实际执行为分析与文档生成，生成实施提示词不等于执行其中命令。
- 原工作包：2026-09-30-skill-sandbox；本项独立新需求，不覆盖原 R0/R1/R2 历史。
- 原脏文件包含控制台根 index.html、application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/，本轮未改动。初始暂存区为空。
- 修订 R0，mode=default；A1～A7 为本次自主方案，非用户逐项已确认决定。

## 状态

| 编号 | 状态 | 实际证据与未执行验收 |
|---|---|---|
| T0 | 完成 | 已核对源码/规范/基线，并生成六份文件；本轮文档检查结果见下方 |
| T1 | 待做 | 未编写产物契约/配置/能力接口，兼容性和绑定测试未执行 |
| T2 | 待做 | 未修改固定引导程序或输出协议，容器文件回传测试未执行 |
| T3 | 待做 | 无新增产物表/存储/加密/配额/清理，双库与重启验证未执行 |
| T4 | 待做 | Run/TEST 发布和补偿未接入，审批/故障回归未执行 |
| T5 | 待做 | 无产物列表/下载 API，权限与流完整性验证未执行 |
| T6 | 待做 | 控制台未展示产物，Node/浏览器下载验证未执行 |
| T7 | 待做 | 固定 Python DOCX 夹具和端到端验收未执行 |
| T8 | 待做 | 无业务发布，生产配置/运维/release-notes 尚不写成现有功能 |

## 本轮实际取证

1. 读取 AGENTS.md、requirement-workpack/SKILL.md 与 references/artifact-contract.md；读取相关 core/adapter/server/persistence/console POM、README、生产配置及沙箱文档。
2. `git log -1 --format='%H %s'`、`git branch --show-current`、`git status --short`、`git diff --cached --stat`：确认上述基线/分支/脏文件，暂存区为空。
3. CodeGraph 优先查询 `SkillTrialController AgentRunResult PermissionEvaluator 文件下载`，返回已有运行/权限符号，没有覆盖产物实现；源文件补核未发现当前产物存储与下载接口。
4. `rg -n 'artifact|Artifact|StreamingResponseBody|Content-Disposition' cm-agent-server/src/main/java cm-agent-core/src/main/java -g '*.java'`：无匹配。此结果用于定位，结合实际 SkillSandboxBackend/DockerSkillSandbox 文本契约确认缺口，不以一次关键词检索证明整个仓库所有可能性。
5. `rg --files cm-agent-persistence/src/main/resources/db/migration`：核对最新方言脚本 V18。读取 RunRecord、SkillTrialController/Service、RunController、PermissionEvaluator 确认可信 principalId 与权限边界。
6. 核对沙箱 --rm、固定 Python、tmpfs、关闭顺序和 String 返回值；本轮未运行沙箱或修改服务。

## 本轮文档验证

实际运行 PowerShell 内嵌 `python -` 检查脚本：六文件存在，R0/date/topic/mode 一致，43 个相对链接全部可解析，清单与账本的 18 项任务状态检查通过，计划包含 T0～T8 共 9 项，执行提示词引用六个真实绝对路径，代码围栏完整，已知凭据模式未命中。脚本退出码 0，问题列表为空。

逐个运行 `git -c core.autocrlf=false diff --no-index --check -- NUL <本组文档绝对路径>`，六份均没有空白错误输出。单次 `-c` 只避免本机 LF/CRLF 转换警告，不改仓库 Git 配置；no-index 的差异退出码 1 是与空文件存在差异，不能直接判作格式失败，检查脚本同时核对输出为空与退出码不超过 1。首次检查把该差异退出码及转换警告误判为问题，调整检查判据后通过；未因此改写用户文件。另运行 `git diff --check`，无输出，退出码 0；该命令单独不足以验证未跟踪文档。

文档生成阶段未运行 Java、Docker、JDBC、Flyway、浏览器与真实模型业务验证：该阶段只生成文档，未修改业务源码，没有相应实现供验收。没有申请/读取/保存真实凭据，没有连接或修改用户服务。

## 外部条件与前置问题

| 编号 | 当前状态 | 影响 | 继续条件 |
|---|---|---|---|
| X1 | 已有日志证明检查点写入字段长度超限，最可能 encrypted_payload；具体字段与大小待复现，未修复 | 可能阻断原真实模型 DOCX 场景；不阻止直接固定脚本及下载接口验证 | 安全复现检查点容量并在独立已授权任务修复，保留真实模型验收缺口 |
| X2 | Rocky Docker/镜像/SSH/TLS 隔离夹具本轮未探测，非已确认环境阻塞 | 未来 T2/T7 集成条件未确认 | 实施时验证 Docker、JDK21、远程源码一致及项目专用连接 |
| X3 | 测试模型凭据及受控持久卷本轮未读取，非已确认环境阻塞 | 真模型验收和生产部署条件未确认 | 使用部署者提供的隔离测试配置，不写真实值到仓库/文档/日志 |

若后续发生实际连接/存储/浏览器错误，追加精确错误和受影响任务；不能把未尝试的环境写作已失败。对 X1 不通过关闭状态保存、截断数据或把文件塞入 AgentState 规避。

## Git 与交付

文档生成阶段实际变更范围仅本组六份文档；业务代码未实施，当时未提交、未推送、未合并、未部署。原用户改动保留。

## 2026-10-08 实施准备与提交记录

用户随后授权实施 T1～T8；已重新读取工作包并核对源码、Git 和环境，尚未开始业务代码修改时，执行被用户中断。T1～T8 继续保持待做，不把环境检查算作业务验收通过。

- 本机 `java -version`、`mvn -v`：当前默认 Java 17.0.13、Maven 3.9.4，尚未切换到项目要求的 JDK 21。
- `ssh rocky` 中只读检查：Docker Server 23.0.6；指定 `maven:3.9.9-eclipse-temurin-21` 临时验证容器报告 Maven 3.9.9、Java 21.0.7。未执行沙箱、数据库或模型业务测试，未操作用户服务。
- 原隔离目录 `/root/cm-agent-diagnostic-20261008` 的 HEAD 为 `5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443`，与当前本地基线不同；没有将该目录作为本需求的同版本验证证据，后续实施仍须同步并核对源码。
- X2 仅完成 Docker 与 Maven/JDK 基础可用性检查；SSH/TLS 项目夹具、镜像依赖、源码一致性与产物回传仍未验证。

用户最新指令“将当前修改提交”授权提交当前工作包。本次提交范围限定为本组六份文档，提交说明为“新增技能沙箱文件产物需求工作包”；提交编号以当前分支的 `git log -1` 为准，避免将提交前编号写成最终编号。没有将任何 T1～T8 标为完成。

提交前重新核对六份文件、43 个相对链接、任务状态、提示词路径、格式和已知敏感信息模式；通过后使用明确文件路径暂存，并检查 `git diff --cached --check` 与暂存文件清单。本次不推送、不合并、不部署；已有 application 配置、根 index.html、.codex/、.workbuddy/ 修改保持原状。

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
### 实际命令与证据（本轮执行，非历史通过）

- 本机先检查 java -version / mvn -v：默认JDK17；显式 JAVA_HOME=F:/java/temurin21/jdk-21.0.11+10 后 Maven3.9.4/JDK21.0.11。快速测试命令：mvn -q -pl cm-agent-server -am -Dtest=SkillArtifact*Test,SkillSandboxCapabilitiesTest,RunPersistenceServiceTest,GovernedSkillAccessServiceTest,ManagedSkillSandboxTest,SkillPropertiesTest,ConsoleResourceTest,SkillTrialControllerTest,SkillTrialServiceTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.agentscope.studio.enabled=false test，退出0。Docker/浏览器专用夹具在本机按环境开关跳过，未在本机运行 JDBC/Docker。
- 最后定向本机回归 SkillArtifactControllerTest,DockerSkillSandboxTest,SkillTrialControllerTest,SkillTrialServiceTest,ConsoleResourceTest 共32项，0失败/错误；控制台修正后另跑14项资源测试，0失败/错误。新增重建集成类本机仅编译、2项按环境跳过。
- node --test cm-agent-console/src/test/js/*.test.cjs：最终116通过、0失败，覆盖会话鉴权Blob实际大小、重复点击、并发上限反馈及完成后继续、迟到租户响应、过期、URL回收和原导航/技能发布回归。
- Rocky 每轮先核对 Docker23.0.6、maven:3.9.9-eclipse-temurin-21 的 Maven3.9.9/JDK21.0.7，独立目录 HEAD=af7b2fac416fc43a545513c0061aa8a4230bbab7；git bundle 基线加明确范围的未提交覆盖，sha256sum -c acceptance-SHA256SUMS 全部通过。未复制用户脏配置或生产凭据；只允许的 application-skill-sandbox.yml 是本需求新增配置。
- Rocky 正式定向回归：docker run --rm --name cm-agent-artifacts-acceptance-validation --network host -e CM_AGENT_TEST_SANDBOX=true -e CM_AGENT_TEST_REMOTE_SANDBOX=true -e CM_AGENT_TEST_SSH_PACKAGE_DIR=/ssh-packages -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -v /root/cm-agent-artifacts-af7b2fa-20261008:/workspace -v /root/.m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -v /usr/bin/docker:/usr/bin/docker:ro -v /root/cm-agent-sandbox-r1-bc57060-20261001/validation-ssh-packages:/ssh-packages:ro -w /workspace maven:3.9.9-eclipse-temurin-21 sh -c "mvn -v && mvn -q -s /root/.m2/settings-docker.xml -pl cm-agent-server -am -Dtest=SkillArtifact*Test,RemoteDockerSandboxIntegrationTest,DockerSkillSandboxIntegrationTest,DockerSkillSandboxTest,SkillTrialControllerTest,SkillTrialServiceTest,JdbcSkillRepositoriesTest,JdbcSkillArtifactRepositoryTest,MigrationTest,ConsoleResourceTest,GovernedSkillAccessServiceTest,RunPersistenceServiceTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx384m -Dcm-agent.agentscope.studio.enabled=false test"：退出0；81项80通过、1项浏览器夹具未启用而跳过。PG16-alpine/MySQL8.4迁移、注释、租户配额并发、租约和最新TEST所有者查询通过；LOCAL、SSH KEY、SSH PASSWORD、TLS均生成并回传DOCX。可信验证容器的socket挂载用于管理夹具，技能容器仍无宿主挂载。
- 追加 SkillArtifactJdbcRestartIntegrationTest：同一Rocky/Maven镜像及socket测试环境，CM_AGENT_TEST_SANDBOX=true，-Dtest=SkillArtifactJdbcRestartIntegrationTest；先核对 restart-SHA256SUMS。2项通过、0失败/错误。实际固定Python → 加密卷 → JDBC Run/审计/发布 → 全部相关对象重新装配 → 当前owner完整下载/摘要与ZIP结构核验。验证范围是应用组件重建，不是生产JVM/Pod切换或生产灾备演练。
- 浏览器在独立Rocky夹具18098运行，通过SSH本机转发18098；8080用户服务保持原状。fixture使用临时测试身份/fake模型元数据，实际Docker生成文档、存储与Run终态/审计仍走真实实现。正式Run详情、会话、TEST都显示文件；已点击下载，TEST刷新后选回技能、跨页导航和back后继续恢复。移动innerWidth/document.scrollWidth均390，没有横向溢出。
- 下载产物921字节，SHA256=552ffbb569808c8632c2793355d0bb14ee1a887aa1bfd24ca9a67465d7e6d6a3，与鉴权列表一致；[Content_Types].xml、_rels/.rels、word/document.xml均解析成功。ZIP时间戳会随夹具变化，其他批次不要求同一摘要。测试资料在 C:/Users/chmi/Documents/Codex/skill-artifacts-20261008/，含 acceptance-summary.json、local-acceptance.log、node-acceptance.log、browser-test-downloaded.docx 及截图，未加入Git。
- Impeccable 普通扩展沿用 PRODUCT.md/DESIGN.md，context已执行一次、detect一次结果[]。独立审查唯一问题为第三次并发点击无反馈，修正并增加测试；复评要求实际画面，专用多文件延迟夹具补证中。documenter代理遭遇账户使用额度限制；主代理按 reference/degraded/documenter.md 明示替代，仅只读对齐，不改旧设计系统。桌面fullPage捕获本批次出现工具异常，采用有效1280×720视口截图；手机390×844全页有效，不冒称桌面1440全页。

### 中途失败与纠正

初次全模块Rocky测试在Server测试编译遇到 SkillUnitOfWork 包引用错误：此前51份报告259项0失败只代表完成的模块，不算全量成功；修正后以本轮定向回归为准。之后一次容器链接拒绝测试遇到 --rm 与显式rm竞争，返回CLEANUP_FAILED；增加同连接有界确认后重新跑四种连接和原隔离回归通过，不改拒绝断言。新增重建测试首编译缺少Jdbc Repository事务构造参数，修正后本机编译和双库回归通过。

没有宣称 mvn全量test 最终全量通过；生产卷权限、备份恢复与真实模型验收没有代入测试结果。Rocky宿主时间与本机不一致，未修改系统时间，测试使用各自实例时间及有界单调计时。后续真实模型验收必须单独解决X1/准备隔离配置。
收尾：三文件延迟下载夹具已实际触发第三次点击等待提示，提示进入可见区域；独立复评 verdict=resolved、remaining=clear、disposition=ship，仅覆盖该项修正。documenter因额度失败采用明示降级只读对齐，保留PRODUCT/DESIGN及sidecar。T1～T6、T8完成；T7固定脚本/下载/双库/四连接完成，真实模型与X1场景验收保留。

### 收尾补证与环境约束

三文件浏览器夹具增加4秒测试专用下载延迟，界面实际显示两项进行中和第三次点击等待提示；提示自动进入可见区域。仅测试类装配该Filter，生产不改变下载延迟。独立复评确认该修正 resolved / ship，未将此项评分表述为全界面批准。此前无反馈的截图不足，补证后才关闭T6。

最后一次浏览器夹具启动在远程 Maven Aether 下载等待中停滞（通过验证容器线程栈确认）。仅停止本项目命名验证容器；-o 首次失败因为 settings-docker.xml 的localRepository指向每次容器内 /usr/share/maven/ref/repository，已有持久缓存在 /root/.m2/repository。改为 mvn -o -Dmaven.repo.local=/root/.m2/repository（仍使用同一settings、JDK/镜像及源码SHA）后成功启动和完成验证；没有更改宿主缓存、网络或用户服务配置。实际日志 validation-browser-feedback-final.log 位于浏览器专用远程目录。

最终116 Node、14 ConsoleResourceTest再次通过；工作包6文件/43相对链接/任务编号/围栏检查无错误，git diff --check无空白错误，暂存区为空。固定测试技能ZIP额外整理在 C:/Users/chmi/Documents/Codex/skill-artifacts-20261008/artifact-docx-test.zip，只有SKILL.md/scripts/main.py；不是原Anthropic包，未冒称真实模型或发布门禁已验收。No changes设计对齐记录为同目录design-alignment.txt，既有按钮token与CSS高度差异仅记录，不修复旧漂移。

### 最终退出与 Git 核对

最后一次真实浏览器操作在文件下载请求进行中退出登录，界面回到登录页；会话销毁与请求取消路径同时保留 Node 回归证据。随后仅向本项目浏览器夹具的 artifact-browser-release 文件写入退出信号，validation-browser-feedback-final.log 对应 Maven 验证进程正常退出，退出码0；关闭本任务 SSH 18098 转发。通过 docker ps 的 cm-agent-artifacts 名称过滤核对，未留下该范围的运行容器。未清理全局 Docker 资源，也未停止或修改用户运行服务。

最终分支仍为 codex/skills-version-console，HEAD=af7b2fac416fc43a545513c0061aa8a4230bbab7；git diff --check 退出0，暂存区为空。本轮未提交、未推送、未合并、未部署。用户已有 application.yml、application-mysql.yml、application-ok.yml、根 index.html、.codex/、.workbuddy/ 保持原状；本需求独立 application-skill-sandbox.yml 配置修改属于已授权范围。T1～T6、T8状态不变，T7仍保留真实模型/检查点闭环未执行的验收缺口。

最终显式排除用户无关文件，对80个本任务变更文件检查完整私钥材料和行尾空白：未发现完整私钥材料；清理 SkillArtifactConfiguration.java 与 SkillArtifactProtocol.java 各一处行尾空白，没有逻辑变化。该格式清理晚于远程源码摘要验证；远程验收对应清理前的已记录快照，不将摘要表述为清理后字节完全相同。

## 2026-10-09 提交交付记录

用户新指令“此次修改了什么，同时将修改的内容提交”授权提交本任务实现及文档，覆盖此前本轮不提交的约束；不授权推送、合并或部署。此前“未提交”记录保留为历史。本次提交说明为“新增技能沙箱文件产物存储与鉴权下载”，最终提交编号以当前分支对应的 git log 记录为准，避免文档自引用提交编号。

提交范围为 skill-artifacts R0 的80个相关变更文件，包含双库V19迁移、测试、控制台及生产说明；保留用户已有配置、根index.html、.codex/和.workbuddy/。后续ManagedSkillSandbox排版与已验证快照相比，忽略空白和注释后逻辑一致；保留当前排版，不覆盖用户编辑。最新前端feedback及browser-feedback摘要核对一致。

提交前复核实际验证报告、显式文件范围、敏感信息模式、注释规范、文档链接及差异格式；本次提交阶段不重复执行已通过的业务测试，既有报告仍对应账本列出的实际快照和命令。T1～T6、T8完成，T7部分完成；真实模型与X1检查点闭环仍未验收。自动安装依赖、按技能选择环境与Node.js执行未实现，近期问答未增加这些能力。
