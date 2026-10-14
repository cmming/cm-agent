# Harness 架构引入执行提示词

日期：2026-10-10；主题：harness-architecture；修订：R1；修订日期：2026-10-10；模式：mode=default。

实施提示词未执行。R1已按用户要求生成当前依赖报告与隔离POM草案，未修改正式代码/POM；问答入口不自动授予业务实施、提交或部署权限。R0分析记录保留，差异见设计/账本R1-01–R1-04。

~~~text
仓库：F:/java/cm-agent。需求：采用 AgentScope Java Harness，保留平台治理并渐进接入，不将认证/租户/RBAC/会话/审批/Skill/固定Python沙箱/加密产物整体重写。
当前模式mode=default，普通工程取舍自主依据证据并记录假设；用户切换模式按最新指令。当前修订R1，修订日期2026-10-10，分析基线6752bf88c173dfd8fd31c58339328da9ac2fe825。用户明确要求“以当前项目为准，也可以生成项目依赖”：可解析/下载依赖，生成忽略目录内报告与隔离POM，不把它当正式接入授权。执行前重新核对HEAD/暂存区/工作树。

先读AGENTS、子目录规范、相关POM/README/配置/运维。仓库有.codegraph时先用CodeGraph定位，缺失区间再复查源码；保护无关脏文件，不打印或存储凭据。
读取六份需求产物：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-10-harness-architecture-checklist.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-10-harness-architecture-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-10-harness-architecture.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-10-harness-architecture-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-10-harness-architecture-ledger.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-10-harness-architecture-prompt.md

先依据用户最新请求判断授权：仅问答/分析时回答，不执行下面实施步骤；修订需求时复用原六路径并递增R1/R2，保留任务/证据/决定历史；明确实施时创建独立实施工作包引用本包，按用户实际授权范围推进。不因为提示词存在就修改代码、提交、推送或部署。

清单状态：T0保留R0分析完成；T9完成R1当前effective POM/依赖树、候选12个POM与差异报告；T1兼容运行、T2执行器/装配、T3审批/停止/状态、T4 Skill/沙箱/产物、T5只读工作区、T6真实验收/回退待做。T7/T8仍为首批外候选，不执行。
T9完成不等于T1运行验收。后续实施T1→T2→T3→T4→T5→T6；用户只授权某批次不扩展。关闭全部新增能力只是技术验证，T5才验收首项增益。不重复历史完成项，项目基线变化则复核依赖。

T1：优先当前项目配套Harness/Core/Provider/Studio2.0.2。T9证明候选解析仅增加Harness2.0.2，项目已有版本不变；Jackson最终2.19.0、SQLite3.49.1.0为BOM覆盖，二进制/运行仍待验收。当前Maven仓库F:/mnt/f/java/maven-repository，旧默认.m2下2.0.0只作历史线索，线上新Session Log API也不作2.0.2版本契约。根据实际2.0.2源码/编译/模型Stub合同继续，不升级全工程或更改MCP边界。
T2：通过adapter执行策略接口新增受控Harness；保留ReAct默认、函数式旧契约、自定义Runtime和嵌入式兼容。核查取消能力具体类型判断。拟增服务端execution-mode=react|harness，未知值拒绝；当前不存在该键。core不依赖Harness，adapter不引入Spring/JDBC。
T3：可信tenantId:principalId、sessionId=runId；CM Agent拥有会话历史，原Store拥有加密审批状态。ASK/ConfirmResult、二次授权、多轮/过期/重复/拒绝/停止与清理，严格失败和迟到结果按现有合同。无会话Run与TEST不扩权。不启用第二套历史自动回灌，也不关闭审批必需状态。
T4：固定Skill快照/发布/撤销/预算/TEST、防重、依赖不授予工具；构建后所有工具与别名仍经过ToolInvocationGateway/SkillAccessGateway。run_skill_code不开放，run_skill_script固定Python/有界输入不替换Shell；加密产物归属/状态/下载授权保持。
T5：首项部署管理的只读知识上下文，Server可信tenant/Agent映射取私有资源，按Run冻结版本/摘要/预算。无管理页上传、无任意路径。应用仓库AGENTS.md/本机MEMORY.md/用户主目录/配置/环境文件不得开放。验证实际输入、跨租户、路径/符号链接逃逸、注入、中途改写、预算/清理和开销。API与新文件位置以T1证据为准。
T6：双路径与双Provider、真实模型、中文浏览器、远程数据库/沙箱、排空切换/回退及生产说明。更新根/adapter README（旧HITL/取消描述需纠正）、configuration/operations/release-notes。未完成真实验收不宣布生产可用，自动删除ReAct不在首批。

审查Harness默认文件/Shell/Web、MCP tools.json、目录/动态Skill、子Agent、记忆工具/Hook、压缩、工具结果落盘、trace/transcript与后台线程，逐项关闭或受控替换。不认为自定义Toolkit排除了默认工具；实际2.0.2 javap未见disableWebTools，不能套在线新版API。2.0.2的fromAgent Toolkit.copy/门控/Hook/Store/delegate/关闭顺序需源码与运行证明，旧版本字节码不替代新行为。disableSessionPersistence名称不证明保留审批，禁止未经测试Builder配方。
首阶段停机排空RUNNING/CANCELLING/WAITING_APPROVAL、TEST及检查点后切模式；同数据集不混策略，不能凭全局配置恢复未标记旧Run。按Agent在线灰度需另行持久化模式/版本/格式标记与方言迁移。副作用后失败禁止自动换ReAct重跑，回退仅新Run，并验证旧二进制/状态/依赖。
错误：可行动中文原因、HTTP/errorCode/errorId与日志一致；严格审计/持久化错误传播，凭据/内部URL/SQL/堆栈/原始载荷不入前端或受控日志。新增Java公开契约/领域属性/生命周期/安全/异常按AGENTS同次落地中文JavaDoc/注释及测试。

验证先java -version/mvn -v确认JDK21。adapter用模型Stub合同，server API用test profile MockMvc；必要时运行聊天/停止/技能JS测试与浏览器。Docker/Testcontainers/JDBC/Flyway全部ssh rocky，maven:3.9.9-eclipse-temurin-21，核对Docker/JDK21和远端待测提交一致，PostgreSQL16/MySQL8.4；禁止本机DockerDesktop和全局清理。server/持久化全量含容器时亦遵循。
阻塞记录确切错误/任务/未执行验收并继续独立工作；默认拒绝或状态兼容不成立就停止进生产，不降低安全、不伪造通过。源码、线上资料、本地API、历史测试和本次运行分别标注。
交付同步独立实施工作包并引用本包当前R1，同时保留R0历史；依赖解析不标业务完成。检查六文件/链接/任务/模式/敏感信息、正式POM/原脏文件保护和git diff --check；target下报告、effective POM、日志与草案不提交。未提交写“未提交”。最终按仓库顺序报告变更/实际验证/影响/风险/相关下一步与提示词问答入口，不扩大提交/推送/部署授权。
~~~

## 关联产物

- [清单](../checklists/2026-10-10-harness-architecture-checklist.md)
- [设计](../specs/2026-10-10-harness-architecture-design.md)
- [计划](../plans/2026-10-10-harness-architecture.md)
- [实现说明](../implementation/2026-10-10-harness-architecture-implementation-design.md)
- [账本](../progress/2026-10-10-harness-architecture-ledger.md)
