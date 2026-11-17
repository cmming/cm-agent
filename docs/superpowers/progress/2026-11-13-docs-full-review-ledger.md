# docs 全量整理：进度账本

日期：2026-11-13；主题：`docs-full-review`；修订：R1；模式：`mode=interactive`。

## 授权与基线

用户选择全面处理 docs 并明确包含历史文档。开始时 docs 有 543 份 Markdown、115 个工作包；HEAD e7da291，master 领先 origin/master 一个提交，暂存区为空。临时摘要清单包含 1548 个文件。

## 任务状态

| 编号 | 状态 | 证据 |
|---|---|---|
| T0 | 完成 | 起始摘要与全部文件清单已保存到系统临时目录 |
| T1 | 完成 | 全部文件已读取并解析，职责和历史范围逐组复核 |
| T2 | 完成 | 更正工具指南旧字段和客户端通道/默认值说明，保留独立安全门禁 |
| T3 | 完成 | 原 115 组逐一核定，101 个原账本追加记录，已有归档事件维持 |
| T4 | 完成 | 116 主题完整导航及 549 文件覆盖快照写入 |
| T5 | 完成 | 引用、目录、覆盖、原文前缀与范围保护检查通过，结果见下方 |

## 工作包归档记录

核定日期：2026-11-13；归档结论：当前。责任范围为 docs 全量复核与历史归档核定；本轮不自动归档自身。后继移交：无。

## 已证实问题及处理

1. 工具开发指南常见错误仍引用旧 `targetName` 和 BODY 映射；当前扁平参数合同使用 `name`，GET 同时拒绝 BODY/BODY_ROOT，已定点修正。
2. 配置说明把通用客户端 audience 写成仅 HTTPS；当前 `LocalClientProperties.validate` 接受规范 HTTP/HTTPS。已说明传输风险，并明确 `ClientProjectProperties` 对非回环浏览器 HTTP 的拒绝仍保留，不混淆两个通道。
3. 基础配置表把所有默认值解释成属性对象初始值，但 Starter 的 fake runtime 初始为 true、Server 公共配置回退为 false；已区分两者，不修改配置值。
4. 原生产持久化账本中的 Docker Desktop 补验建议保留为当时记录，在本次核定中明确现行验证须使用 Rocky/JDK21 容器。
5. 设备项目交付 R6/R7 的 HTTP 描述补充协议层次说明；通用设备通道的实测不作为浏览器来源策略放宽的证据。

## 逐组核定快照

本表保留 2026-11-13 的 R0 验证快照；R1 Git 补证后的分类见文末，不回改本表形成时的结论。归档和待续以对应核定来源为准，不复制原任务状态或测试数字。原 115 组为已归档 69、待续 30、归档状态待核实 16；加本包当前 1 组，共 116。13 个无账本主题保持原集合；相关主题和后来代码不能自动替原验收收口。

| 主题 | 核定快照 | 范围与依据 |
|---|---|---|
| <a id="audit-2026-11-13-documentation-governance"></a>2026-11-13-documentation-governance [原账本](2026-11-13-documentation-governance-ledger.md#全量核定记录2026-11-13) | 已归档 | 已确认的 16 份治理规则与验证全部记录完成，本轮另作责任结束核定，不修改原当前结论的形成时语义。 |
| <a id="audit-2026-11-13-doc-organization-followup"></a>2026-11-13-doc-organization-followup [原账本](2026-11-13-doc-organization-followup-ledger.md#归档记录2026-11-13) | 已归档 | 原账本已有用户确认的归档事件，维持原结论与 14 个外链核验限制。 |
| <a id="audit-2026-11-13-doc-navigation-cleanup"></a>2026-11-13-doc-navigation-cleanup [原账本](2026-11-13-doc-navigation-cleanup-ledger.md#全量核定记录2026-11-13) | 已归档 | T0–T21 文档导航、事实刷新与职责迁移已完成；本包文档责任结束，不替各业务包关闭门禁。 |
| <a id="audit-2026-11-12-sites-v2-design-sync"></a>2026-11-12-sites-v2-design-sync [原账本](2026-11-12-sites-v2-design-sync-ledger.md#全量核定记录2026-11-13) | 已归档 | T0–T3 静态设计预览同步与文档交付已结束；移动端与真实后端另行验收，不外推 Sites 当前状态。 |
| <a id="audit-2026-11-09-client-project-workspace-delivery"></a>2026-11-09-client-project-workspace-delivery [原账本](2026-11-09-client-project-workspace-delivery-ledger.md#全量核定记录2026-11-13) | 待续 | R9 的 T25 原生窗口期限仍待真实新进程验收，T9/T17 故障矩阵未整体收口。 |
| <a id="audit-2026-11-09-client-project-workspace"></a>2026-11-09-client-project-workspace [原账本](2026-11-09-client-project-workspace-ledger.md#全量核定记录2026-11-13) | 已归档 | 本包明确只交付 R2 需求文档，业务 T0–T18 待做不属于本轮分析实施；关联交付包仍单独待续。 |
| <a id="audit-2026-11-05-shared-runtime"></a>2026-11-05-shared-runtime [原账本](2026-11-05-shared-runtime-ledger.md#全量核定记录2026-11-13) | 待续 | 遗留的 Maven 3.9+ Linux 矩阵未完成，不能用既有主机 Maven 成功替代。 |
| <a id="audit-2026-11-05-projects-page"></a>2026-11-05-projects-page [原账本](2026-11-05-projects-page-ledger.md#全量核定记录2026-11-13) | 待续 | 真实认证远端项目完整操作与修复后局域网浏览器复测未验收。 |
| <a id="audit-2026-11-05-local-client-page"></a>2026-11-05-local-client-page [原账本](2026-11-05-local-client-page-ledger.md#全量核定记录2026-11-13) | 已归档 | T0–T5 输入 Schema/解析修复与实际设备回报有记录，本包范围完成。 |
| <a id="audit-2026-11-04-project-workspace-layout"></a>2026-11-04-project-workspace-layout [原账本](2026-11-04-project-workspace-layout-ledger.md#全量核定记录2026-11-13) | 待续 | 原项目页 T3 真实验收仍进行中；合并来源的已完成任务不覆盖它。 |
| <a id="audit-2026-11-03-projects-page"></a>2026-11-03-projects-page [原账本](2026-11-03-projects-page-ledger.md#全量核定记录2026-11-13) | 已归档 | 项目页本轮 T0–T12 范围完成；其他平台、真实读屏及后续设备项目验收不被外推。 |
| <a id="audit-2026-11-03-local-client-page"></a>2026-11-03-local-client-page [原账本](2026-11-03-local-client-page-ledger.md#全量核定记录2026-11-13) | 待续 | T7/T8/T9/T17 的真实设备、Linux/macOS、远程 TLS 等门禁仍明确待续。 |
| <a id="audit-2026-10-27-workspace-directory-selection-implementation"></a>2026-10-27-workspace-directory-selection-implementation [原账本](2026-10-27-workspace-directory-selection-implementation-ledger.md#全量核定记录2026-11-13) | 待续 | T11–T14 整体验收未完成，R3 文档迁移完成不能替代业务门禁。 |
| <a id="audit-2026-10-27-workspace-directory-selection"></a>2026-10-27-workspace-directory-selection [原账本](2026-10-27-workspace-directory-selection-ledger.md#全量核定记录2026-11-13) | 已归档 | 本包限定仅交付需求分析文档；实际实施由独立实施包记录，后者 T11–T14 未完成不伪写通过。 |
| <a id="audit-2026-10-26-chat-failure-feedback"></a>2026-10-26-chat-failure-feedback [原账本](2026-10-26-chat-failure-feedback-ledger.md#全量核定记录2026-11-13) | 已归档 | 前端反馈及 R1 模型错误摘要的约定定向验证已记录，既有设计元数据漂移属于原范围外限制。 |
| <a id="audit-2026-10-15-v2-console-polish"></a>2026-10-15-v2-console-polish [原账本](2026-10-15-v2-console-polish-ledger.md#全量核定记录2026-11-13) | 已归档 | 九页统一样式 T0–T6 与提交前定向复核完成，仅保留形成时浏览器范围。 |
| <a id="audit-2026-10-15-model-config-page-polish"></a>2026-10-15-model-config-page-polish [原账本](2026-10-15-model-config-page-polish-ledger.md#全量核定记录2026-11-13) | 已归档 | 模型配置页 R1 宽屏与原 R0 目标验证完成，不外推真实模型或其他页面。 |
| <a id="audit-2026-10-15-agent-page-polish"></a>2026-10-15-agent-page-polish [原账本](2026-10-15-agent-page-polish-ledger.md#全量核定记录2026-11-13) | 已归档 | Agent 页 R1 宽屏与原 R0 目标验证完成，不外推全部角色及业务状态。 |
| <a id="audit-2026-10-15-agent-model-name-sync"></a>2026-10-15-agent-model-name-sync [原账本](2026-10-15-agent-model-name-sync-ledger.md#全量核定记录2026-11-13) | 待续 | 原账本明确修复后在线页面及真实供应商调用未验收，未见同范围收口。 |
| <a id="audit-2026-10-12-harness-t6"></a>2026-10-12-harness-t6 [原账本](2026-10-12-harness-t6-ledger.md#全量核定记录2026-11-13) | 已归档 | 本次 T6 固定协议、浏览器与回退验证已记录完成；T7/T8 生产启用需独立范围。 |
| <a id="audit-2026-10-12-harness-t5"></a>2026-10-12-harness-t5 [原账本](2026-10-12-harness-t5-ledger.md#全量核定记录2026-11-13) | 已归档 | T5 的双库及 Linux 句柄验证已补齐；真实 Provider、浏览器和 T6 不被其替代。 |
| <a id="audit-2026-10-12-harness-drain-readiness"></a>2026-10-12-harness-drain-readiness [原账本](2026-10-12-harness-drain-readiness-ledger.md#全量核定记录2026-11-13) | 已归档 | 当前采用 G11–G14 已完成；G5/G9/G10 有明确移出范围记录，未宣称 Rocky 生产已部署。 |
| <a id="audit-2026-10-12-harness-controlled-enablement"></a>2026-10-12-harness-controlled-enablement [原账本](2026-10-12-harness-controlled-enablement-ledger.md#全量核定记录2026-11-13) | 已归档 | 原范围是时钟校准与准入评估，账本明确两者完成且实际生产准入不通过。 |
| <a id="audit-2026-10-11-harness-t4"></a>2026-10-11-harness-t4 [原账本](2026-10-11-harness-t4-ledger.md#全量核定记录2026-11-13) | 已归档 | T4 固定 Skill/沙箱/产物治理与受控集成验证已有完成证据，真实外部服务不外推。 |
| <a id="audit-2026-10-10-harness-t3"></a>2026-10-10-harness-t3 [原账本](2026-10-10-harness-t3-ledger.md#全量核定记录2026-11-13) | 已归档 | T3 生产审批/状态/停止及双库合同已完成；历史时钟及跨主机 TTL 限制保留。 |
| <a id="audit-2026-10-10-harness-t2"></a>2026-10-10-harness-t2 [原账本](2026-10-10-harness-t2-ledger.md#全量核定记录2026-11-13) | 已归档 | T2 工厂、隔离与模式装配范围完成；T3–T6 是独立后续阶段，不宣称由本包通过。 |
| <a id="audit-2026-10-10-harness-entry-hardening"></a>2026-10-10-harness-entry-hardening [原账本](2026-10-10-harness-entry-hardening-ledger.md#全量核定记录2026-11-13) | 已归档 | 显式拒绝及安全入口加固范围完成；原完整安全准入和后续工厂阶段不由本包替代。 |
| <a id="audit-2026-10-10-harness-architecture"></a>2026-10-10-harness-architecture [原账本](2026-10-10-harness-architecture-ledger.md#全量核定记录2026-11-13) | 已归档 | 本包仅架构分析和依赖草案，T0/T9 文档范围完成，不把 T1–T8 未来业务任务记为已实现。 |
| <a id="audit-2026-10-10-harness-202-compatibility"></a>2026-10-10-harness-202-compatibility [原账本](2026-10-10-harness-202-compatibility-ledger.md#全量核定记录2026-11-13) | 待续 | T1 安全门禁尚未全部满足，G1/G2 记录缺陷特征；不能用特征测试转绿代替修复验收。 |
| <a id="audit-2026-10-10-chat-stop-delivery"></a>2026-10-10-chat-stop-delivery [原账本](2026-10-10-chat-stop-delivery-ledger.md#全量核定记录2026-11-13) | 已归档 | 停止交付 T0–T6 与提交前复核已记录完成，保留外部副作用不能回滚的限制。 |
| <a id="audit-2026-10-10-chat-stop"></a>2026-10-10-chat-stop [原账本](2026-10-10-chat-stop-ledger.md#全量核定记录2026-11-13) | 已归档 | 只交付停止需求分析与用户选择，实施由独立交付包记录，不回填本分析的未来业务任务。 |
| <a id="audit-2026-10-10-chat-page-ux-delivery"></a>2026-10-10-chat-page-ux-delivery [原账本](2026-10-10-chat-page-ux-delivery-ledger.md#全量核定记录2026-11-13) | 待续 | T4/T6 部分与 T7 真实 IME、缩放及实际服务烟测仍待验收。 |
| <a id="audit-2026-10-10-chat-page-ux"></a>2026-10-10-chat-page-ux [原账本](2026-10-10-chat-page-ux-ledger.md#全量核定记录2026-11-13) | 已归档 | 聊天交互需求分析及 D1/D2 文档验收完成；独立交付包的 T7 仍待续。 |
| <a id="audit-2026-10-09-windows-sandbox-bootstrap"></a>2026-10-09-windows-sandbox-bootstrap [原账本](2026-10-09-windows-sandbox-bootstrap-ledger.md#全量核定记录2026-11-13) | 待续 | T4 用户现场 TEST/下载闭环仍待部署后复验。 |
| <a id="audit-2026-10-09-server-lombok-support"></a>2026-10-09-server-lombok-support [原账本](2026-10-09-server-lombok-support-ledger.md#全量核定记录2026-11-13) | 已归档 | Lombok 接入本包 T0–T3 编译/配置范围完成；共享提交尾注的现场 Skill 验收不属于 Lombok 结论。 |
| <a id="audit-2026-10-09-docx-stdlib-skill"></a>2026-10-09-docx-stdlib-skill [原账本](2026-10-09-docx-stdlib-skill-ledger.md#全量核定记录2026-11-13) | 待续 | T8 部署使用阶段真实候选/TEST/下载仍待做。 |
| <a id="audit-2026-10-09-checkpoint-payload-capacity"></a>2026-10-09-checkpoint-payload-capacity [原账本](2026-10-09-checkpoint-payload-capacity-ledger.md#全量核定记录2026-11-13) | 已归档 | 检查点容量迁移、双库与文件交付定向验收完成；保留形成时提交记录。 |
| <a id="audit-2026-10-08-skills-version-console"></a>2026-10-08-skills-version-console [原账本](2026-10-08-skills-version-console-ledger.md#全量核定记录2026-11-13) | 待续 | T5 原生浏览器 ZIP 选择上传未完成；数据库原包导入不替代该验收。 |
| <a id="audit-2026-10-08-skill-sandbox-config-docs"></a>2026-10-08-skill-sandbox-config-docs [原账本](2026-10-08-skill-sandbox-config-docs-ledger.md#全量核定记录2026-11-13) | 已归档 | T0–T2 配置文档与属性/profile 对齐完成；不表示本轮复验容器执行。 |
| <a id="audit-2026-10-08-skill-import-description"></a>2026-10-08-skill-import-description [原账本](2026-10-08-skill-import-description-ledger.md#全量核定记录2026-11-13) | 已归档 | 长描述存储扩容与定向验证、后续职责迁移已完成；浏览器上传阻塞属于独立 UI 范围。 |
| <a id="audit-2026-10-08-skill-capacity"></a>2026-10-08-skill-capacity [原账本](2026-10-08-skill-capacity-ledger.md#全量核定记录2026-11-13) | 已归档 | 容量配置、解析与双库接口专项完成；不把浏览器 ZIP 未验或真实脚本未执行外推为通过。 |
| <a id="audit-2026-10-08-skill-artifacts"></a>2026-10-08-skill-artifacts [原账本](2026-10-08-skill-artifacts-ledger.md#全量核定记录2026-11-13) | 待续 | T7 真实模型验收与 X1/X3 未关闭边界仍保留。 |
| <a id="audit-2026-10-08-jdbc-diagnostic"></a>2026-10-08-jdbc-diagnostic [原账本](2026-10-08-jdbc-diagnostic-ledger.md#全量核定记录2026-11-13) | 已归档 | 日志边界、受控接口与双库驱动专项完成；仓库外证据不可用及 UI 上传限制保留。 |
| <a id="audit-2026-10-01-remote-docker-password-auth"></a>2026-10-01-remote-docker-password-auth [原账本](2026-10-01-remote-docker-password-auth-ledger.md#全量核定记录2026-11-13) | 已归档 | 当前 R2 职责迁移与已记录 R1 SSH 密码实现范围完成，旧 R0 待做文本保留为历史。 |
| <a id="audit-2026-09-30-skill-sandbox"></a>2026-09-30-skill-sandbox [原账本](2026-09-30-skill-sandbox-ledger.md#全量核定记录2026-11-13) | 已归档 | 原沙箱 T0–T11 与文档迁移任务已完成，条件跳过及不含生产/强化 runtime 的范围保留。 |
| <a id="audit-2026-09-30-requirement-workpack-skill"></a>2026-09-30-requirement-workpack-skill [原账本](2026-09-30-requirement-workpack-skill-ledger.md#全量核定记录2026-11-13) | 已归档 | 技能生成、迭代与快捷问答文档范围完成，文字模拟不外推为通用业务实测。 |
| <a id="audit-2026-09-30-next-iteration-analysis"></a>2026-09-30-next-iteration-analysis [原账本](2026-09-30-next-iteration-analysis-ledger.md#全量核定记录2026-11-13) | 已归档 | 本包交付后续需求分析文档，后续业务未被此分析授权或验收。 |
| <a id="audit-2026-09-30-chat-approval-ui"></a>2026-09-30-chat-approval-ui [原账本](2026-09-30-chat-approval-ui-ledger.md#全量核定记录2026-11-13) | 待续 | 真实登录后的浏览器视觉验收仍待可用会话补做。 |
| <a id="audit-2026-09-30-approval-expiry-a"></a>2026-09-30-approval-expiry-a [原账本](2026-09-30-approval-expiry-a-ledger.md#全量核定记录2026-11-13) | 已归档 | 主动过期 A 批次 T0–T8 与合并复核完成，保留不推送部署的原边界。 |
| <a id="audit-2026-09-28-skill-trial-result-preview"></a>2026-09-28-skill-trial-result-preview [原账本](2026-09-28-skill-trial-result-preview-ledger.md#全量核定记录2026-11-13) | 待续 | 真实模型/工具及非空工具摘要仍列为待另行验收；Fake NOT_TRIGGERED 只证明门禁。 |
| <a id="audit-2026-09-28-skill-trial-agent-dropdown"></a>2026-09-28-skill-trial-agent-dropdown [原账本](2026-09-28-skill-trial-agent-dropdown-ledger.md#全量核定记录2026-11-13) | 已归档 | 目标 Agent 下拉、桌面/窄屏与资源验证已完成，不涉及数据库或真实试运行。 |
| <a id="audit-2026-09-22-skill-release-validation"></a>2026-09-22-skill-release-validation [原账本](2026-09-22-skill-release-validation-ledger.md#全量核定记录2026-11-13) | 待续 | 成功试运行、发布、审批恢复、固定版本和回滚的浏览器端到端尚未完成。 |
| <a id="audit-2026-09-21-skill-management-polish"></a>2026-09-21-skill-management-polish [原账本](2026-09-21-skill-management-polish-ledger.md#全量核定记录2026-11-13) | 已归档 | 技能管理布局任务已交付并有提交记录。 |
| <a id="audit-2026-09-21-opencode-message-name"></a>2026-09-21-opencode-message-name [原账本](2026-09-21-opencode-message-name-ledger.md#全量核定记录2026-11-13) | 已归档 | 消息名称兼容和会话路由修复及目标验证已记录完成。 |
| <a id="audit-2026-09-20-tool-workspace-stages"></a>2026-09-20-tool-workspace-stages [原账本](2026-09-20-tool-workspace-stages-ledger.md#全量核定记录2026-11-13) | 已归档 | 工具工作区分阶段交互与后续连续修复验证已记录完成。 |
| <a id="audit-2026-09-20-skill-support"></a>2026-09-20-skill-support [原账本](2026-09-20-skill-support-ledger.md#全量核定记录2026-11-13) | 归档状态待核实 | 实现任务已记录完成，但发布前真实浏览器/Provider Stub 证据责任边界不够明确，不能推断已经收口。 |
| <a id="audit-2026-09-20-impeccable-product-init"></a>2026-09-20-impeccable-product-init [原账本](2026-09-20-impeccable-product-init-ledger.md#全量核定记录2026-11-13) | 已归档 | 本包仅产品上下文和技能初始化文档；Live 可用性是独立后续范围。 |
| <a id="audit-2026-09-20-impeccable-live-setup"></a>2026-09-20-impeccable-live-setup [原账本](2026-09-20-impeccable-live-setup-ledger.md#全量核定记录2026-11-13) | 待续 | Live 属临时会话注入，用户退出后的清理没有完成证据。 |
| <a id="audit-2026-09-20-impeccable-design-documentation"></a>2026-09-20-impeccable-design-documentation [原账本](2026-09-20-impeccable-design-documentation-ledger.md#全量核定记录2026-11-13) | 已归档 | 设计规范归档文档及布局核对完成；当时资源错误不在设计文档范围内。 |
| <a id="audit-2026-09-20-first-agent-onboarding"></a>2026-09-20-first-agent-onboarding [原账本](2026-09-20-first-agent-onboarding-ledger.md#全量核定记录2026-11-13) | 已归档 | 首个 Agent 引导和静态响应式验收已有完成记录。 |
| <a id="audit-2026-09-18-opencode-go-runtime-session"></a>2026-09-18-opencode-go-runtime-session [原账本](2026-09-18-opencode-go-runtime-session-ledger.md#全量核定记录2026-11-13) | 已归档 | 运行会话路由及状态槽告警目标修复与验证已记录完成。 |
| <a id="audit-2026-09-16-model-catalog-discovery"></a>2026-09-16-model-catalog-discovery [原账本](2026-09-16-model-catalog-discovery-ledger.md#全量核定记录2026-11-13) | 已归档 | 模型目录发现的实现、安全/权限与目标验证已记录完成。 |
| <a id="audit-2026-09-16-console-visual-interaction-polish"></a>2026-09-16-console-visual-interaction-polish [原账本](2026-09-16-console-visual-interaction-polish-ledger.md#全量核定记录2026-11-13) | 已归档 | 视觉交互调整及真实业务页面目标验证已记录完成。 |
| <a id="audit-2026-09-16-console-prototype-alignment"></a>2026-09-16-console-prototype-alignment [原账本](2026-09-16-console-prototype-alignment-ledger.md#全量核定记录2026-11-13) | 已归档 | 静态原型对齐与资源结构验证完成，未外推数据库或真实模型。 |
| <a id="audit-2026-09-03-tool-approval-ui-ux"></a>2026-09-03-tool-approval-ui-ux [原账本](2026-09-03-tool-approval-ui-ux-ledger.md#全量核定记录2026-11-13) | 待续 | 重启后真实 HIGH 操作、混合决定及断线/键盘验收仍待续；不能用另一历史主题自动替代。 |
| <a id="audit-2026-09-03-tool-approval-history"></a>2026-09-03-tool-approval-history [原账本](2026-09-03-tool-approval-history-ledger.md#全量核定记录2026-11-13) | 待续 | 真实页面端到端和服务进程重启未验收，Repository 重建不替代。 |
| <a id="audit-2026-09-02-agentscope-permission-system-integration"></a>2026-09-02-agentscope-permission-system-integration [原账本](2026-09-02-agentscope-permission-system-integration-ledger.md#全量核定记录2026-11-13) | 待续 | 专门 JDBC 租户/并发用例与浏览器、重启/撤销演练仍明确未完成。 |
| <a id="audit-2026-09-01-mandatory-comment-generation"></a>2026-09-01-mandatory-comment-generation [原账本](2026-09-01-mandatory-comment-generation-ledger.md#全量核定记录2026-11-13) | 已归档 | 强制注释规则文本与范围复核完成，原账本无遗留。 |
| <a id="audit-2026-09-01-log-coverage-gaps"></a>2026-09-01-log-coverage-gaps [原账本](2026-09-01-log-coverage-gaps-ledger.md#全量核定记录2026-11-13) | 待续 | 原账本要求同版本 Rocky 双库集成补验，尚无本包收口；全量失败与本轮目标通过分别保留。 |
| <a id="audit-2026-09-01-enum-field-comments"></a>2026-09-01-enum-field-comments [原账本](2026-09-01-enum-field-comments-ledger.md#全量核定记录2026-11-13) | 已归档 | 枚举及 core record 注释约定范围已完成，其他模块历史治理明确不在范围。 |
| <a id="audit-2026-09-01-core-module-readme"></a>2026-09-01-core-module-readme [原账本](2026-09-01-core-module-readme-ledger.md#全量核定记录2026-11-13) | 已归档 | core README 源码核对和文档交付完成，原账本无遗留。 |
| <a id="audit-2026-09-01-core-code-comments"></a>2026-09-01-core-code-comments [原账本](2026-09-01-core-code-comments-ledger.md#全量核定记录2026-11-13) | 已归档 | core 注释、编译与模块测试完成；用户配置与其他任务保留。 |
| <a id="audit-2026-09-01-console-chat-execution-trace"></a>2026-09-01-console-chat-execution-trace [原账本](2026-09-01-console-chat-execution-trace-ledger.md#全量核定记录2026-11-13) | 已归档 | 思考/工具轨迹及后续载荷展示目标验证完成；全量受环境影响的历史失败单独保留。 |
| <a id="audit-2026-09-01-adapter-comments-cleanup"></a>2026-09-01-adapter-comments-cleanup [原账本](2026-09-01-adapter-comments-cleanup-ledger.md#全量核定记录2026-11-13) | 已归档 | adapter 注释整改与模块联编测试完成，无关主工作区变更保留。 |
| <a id="audit-2026-08-31-message-first-conversations"></a>2026-08-31-message-first-conversations [原账本](2026-08-31-message-first-conversations-ledger.md#全量核定记录2026-11-13) | 已归档 | 消息一等公民的约定实现与远程双库验证完成，未交付能力是明确范围外。 |
| <a id="audit-2026-08-31-console-session-chat-page"></a>2026-08-31-console-session-chat-page [原账本](2026-08-31-console-session-chat-page-ledger.md#全量核定记录2026-11-13) | 归档状态待核实 | 账本写无遗留，同时 ConsoleSmokeTest 未执行；是否为原约定必需门禁未明确收口。 |
| <a id="audit-2026-08-28-agentscope-studio-integration"></a>2026-08-28-agentscope-studio-integration [原账本](2026-08-28-agentscope-studio-integration-ledger.md#全量核定记录2026-11-13) | 已归档 | Studio 条件装配、安全保护及目标配置验证完成，开发者自行启动外部 Studio 属使用条件。 |
| <a id="audit-2026-08-20-console-tool-call-payload-ui"></a>2026-08-20-console-tool-call-payload-ui [原账本](2026-08-20-console-tool-call-payload-ui-ledger.md#全量核定记录2026-11-13) | 已归档 | 工具载荷 UI 布局及模块验证完成。 |
| <a id="audit-2026-08-20-console-runs-ui"></a>2026-08-20-console-runs-ui [原账本](2026-08-20-console-runs-ui-ledger.md#全量核定记录2026-11-13) | 已归档 | 运行页 UI 及基础流式展示目标验证完成。 |
| <a id="audit-2026-08-20-console-run-markdown"></a>2026-08-20-console-run-markdown [原账本](2026-08-20-console-run-markdown-ledger.md#全量核定记录2026-11-13) | 已归档 | 受限 Markdown 渲染及目标验证完成。 |
| <a id="audit-2026-08-20-agentscope-adapter-comments"></a>2026-08-20-agentscope-adapter-comments [原账本](2026-08-20-agentscope-adapter-comments-ledger.md#全量核定记录2026-11-13) | 已归档 | adapter 目标 JavaDoc 与模块验证完成，上游 core 聚合 JavaDoc 既有错误明确在本包外。 |
| <a id="audit-2026-08-19-console-run-streaming"></a>2026-08-19-console-run-streaming [原账本](2026-08-19-console-run-streaming-ledger.md#全量核定记录2026-11-13) | 归档状态待核实 | 流式定向测试通过，但完整 RunControllerTest 两项失败未收口；其是否移出原范围缺少明确依据。 |
| <a id="audit-2026-08-18-model-config-management"></a>2026-08-18-model-config-management [原账本](2026-08-18-model-config-management-ledger.md#全量核定记录2026-11-13) | 待续 | JdbcModelConfigRepositoryTest 与双库 CRUD/V9 注释验证仍待符合基线的 Rocky 环境。 |
| <a id="audit-2026-08-18-database-schema-comments"></a>2026-08-18-database-schema-comments [原账本](2026-08-18-database-schema-comments-ledger.md#全量核定记录2026-11-13) | 已归档 | 双方言数据库注释与 Rocky 迁移验证完成，升级前备份属于使用条件。 |
| <a id="audit-2026-08-18-console-versioned-multipage"></a>2026-08-18-console-versioned-multipage [原账本](2026-08-18-console-versioned-multipage-ledger.md#全量核定记录2026-11-13) | 已归档 | 多页导航、Cookie 恢复与真实页面刷新/退出验证已记录完成。 |
| <a id="audit-2026-08-18-console-v2-layout"></a>2026-08-18-console-v2-layout [原账本](2026-08-18-console-v2-layout-ledger.md#全量核定记录2026-11-13) | 已归档 | v2 布局和快捷操作范围完成。 |
| <a id="audit-2026-08-18-agent-management-model-selection"></a>2026-08-18-agent-management-model-selection [原账本](2026-08-18-agent-management-model-selection-ledger.md#全量核定记录2026-11-13) | 待续 | 原自动化状态排除容器集成测试，完整容器验收未见收口。 |
| <a id="audit-2026-08-17-docker-compose-redis"></a>2026-08-17-docker-compose-redis [原账本](2026-08-17-docker-compose-redis-ledger.md#全量核定记录2026-11-13) | 待续 | 远程构建环境核对仅部分完成，不能视为完整 JDK21/Maven3.9 验证。 |
| <a id="audit-2026-08-17-agentscope-state-store-examples"></a>2026-08-17-agentscope-state-store-examples [原账本](2026-08-17-agentscope-state-store-examples-ledger.md#全量核定记录2026-11-13) | 待续 | 真实模型与 Redis 联调记录为未执行。 |
| <a id="audit-2026-08-14-agentscope-context-agent-state-example"></a>2026-08-14-agentscope-context-agent-state-example [原账本](2026-08-14-agentscope-context-agent-state-example-ledger.md#全量核定记录2026-11-13) | 待续 | 真实模型调用结果仍待有有效凭据环境确认。 |
| <a id="audit-2026-08-13-run-execution-service-run-comments"></a>2026-08-13-run-execution-service-run-comments [原账本](2026-08-13-run-execution-service-run-comments-ledger.md#全量核定记录2026-11-13) | 已归档 | run 方法调用链注释与隔离验证完成。 |
| <a id="audit-2026-08-13-dashscope-mcp-tool-agent-example"></a>2026-08-13-dashscope-mcp-tool-agent-example [原账本](2026-08-13-dashscope-mcp-tool-agent-example-ledger.md#全量核定记录2026-11-13) | 待续 | 使用者环境中 MCP 依赖修复后复测和完整模型回复仍未完成。 |
| <a id="audit-2026-08-10-tool-debug-error-observability"></a>2026-08-10-tool-debug-error-observability [原账本](2026-08-10-tool-debug-error-observability-ledger.md#全量核定记录2026-11-13) | 已归档 | 调试失败原因、编号与日志关联目标验证完成，集中日志与追踪明确在范围外。 |
| <a id="audit-2026-08-10-global-error-diagnostics"></a>2026-08-10-global-error-diagnostics [原账本](2026-08-10-global-error-diagnostics-ledger.md#全量核定记录2026-11-13) | 已归档 | 全局错误编号和诊断目标已完成，集中告警与分布式追踪属于后续范围。 |
| <a id="audit-2026-08-10-error-diagnostics-development-standards"></a>2026-08-10-error-diagnostics-development-standards [原账本](2026-08-10-error-diagnostics-development-standards-ledger.md#全量核定记录2026-11-13) | 已归档 | 错误诊断工程规范文档及格式复核完成，未承诺历史全仓治理。 |
| <a id="audit-2026-08-07-superpowers-documentation-governance"></a>2026-08-07-superpowers-documentation-governance [原账本](2026-08-07-superpowers-documentation-governance-ledger.md#全量核定记录2026-11-13) | 已归档 | 四文档治理规则当时范围完成；新六件套规则保存在独立治理 ADR，不回写旧决定。 |
| <a id="audit-2026-08-06-http-tool-parameter-definition"></a>2026-08-06-http-tool-parameter-definition [原账本](2026-08-06-http-tool-parameter-definition-ledger.md#全量核定记录2026-11-13) | 已归档 | 参数模型、空参数 HTTP、双方言与浏览器验证已记录完成，旧映射历史保留。 |
| <a id="audit-2026-08-03-tool-management-edit-delete"></a>2026-08-03-tool-management-edit-delete [implementation](../implementation/2026-08-03-tool-management-edit-delete-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-07-31-tool-management-edit-delete"></a>2026-07-31-tool-management-edit-delete [specs](../specs/2026-07-31-tool-management-edit-delete-design.md)、[plans](../plans/2026-07-31-tool-management-edit-delete.md)、[implementation](../implementation/2026-07-31-tool-management-edit-delete-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-07-30-mysql-builtin-local-tools"></a>2026-07-30-mysql-builtin-local-tools [specs](../specs/2026-07-30-mysql-builtin-local-tools-design.md)、[plans](../plans/2026-07-30-mysql-builtin-local-tools.md)、[implementation](../implementation/2026-07-30-mysql-builtin-local-tools-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-07-28-tool-development-guide-examples"></a>2026-07-28-tool-development-guide-examples [specs](../specs/2026-07-28-tool-development-guide-examples-design.md)、[plans](../plans/2026-07-28-tool-development-guide-examples.md)、[implementation](../implementation/2026-07-28-tool-development-guide-examples-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-07-21-dynamic-http-mcp-tools"></a>2026-07-21-dynamic-http-mcp-tools [原账本](2026-07-21-dynamic-http-mcp-tools-ledger.md#全量核定记录2026-11-13) | 待续 | 端到端任务记录容器验证待 Rocky，不能按实现已完成归档该验收。 |
| <a id="audit-2026-07-17-console-capabilities"></a>2026-07-17-console-capabilities [specs](../specs/2026-07-17-console-capabilities-design.md)、[plans](../plans/2026-07-17-console-capabilities.md)、[implementation](../implementation/2026-07-17-console-capabilities-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-07-17-commit-file-export"></a>2026-07-17-commit-file-export [specs](../specs/2026-07-17-commit-file-export-design.md)、[plans](../plans/2026-07-17-commit-file-export.md)、[implementation](../implementation/2026-07-17-commit-file-export-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-07-16-phase-3-agentscope-runtime"></a>2026-07-16-phase-3-agentscope-runtime [原账本](2026-07-16-phase-3-agentscope-runtime-ledger.md#全量核定记录2026-11-13) | 已归档 | 阶段三各任务、最终 Rocky 全量测试及打包有完成证据，历史 AgentScope 版本不改写成当前版本。 |
| <a id="audit-2026-07-14-phase-2-production-runtime"></a>2026-07-14-phase-2-production-runtime [原账本](2026-07-14-phase-2-production-runtime-ledger.md#全量核定记录2026-11-13) | 已归档 | 阶段二范围及 8 月 MCP 夹具回归在 Rocky 全量验证完成，不把形成时提交状态改成现状。 |
| <a id="audit-2026-07-13-vm-database-profiles"></a>2026-07-13-vm-database-profiles [specs](../specs/2026-07-13-vm-database-profiles-design.md)、[plans](../plans/2026-07-13-vm-database-profiles.md)、[implementation](../implementation/2026-07-13-vm-database-profiles-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-07-13-server-yaml-variable-overrides"></a>2026-07-13-server-yaml-variable-overrides [specs](../specs/2026-07-13-server-yaml-variable-overrides-design.md)、[plans](../plans/2026-07-13-server-yaml-variable-overrides.md)、[implementation](../implementation/2026-07-13-server-yaml-variable-overrides-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-06-29-supabase-persistence-automation"></a>2026-06-29-supabase-persistence-automation [原账本](2026-06-29-supabase-persistence-automation-ledger.md#全量核定记录2026-11-13) | 待续 | Supabase 开发分支验证被当时套餐阻塞，Task4 未完成；未查询当前套餐，也未在主项目执行 DDL。 |
| <a id="audit-2026-06-29-production-persistence"></a>2026-06-29-production-persistence [原账本](2026-06-29-production-persistence-ledger.md#全量核定记录2026-11-13) | 待续 | 历史 JDBC/Testcontainers 验证因 Docker 不可用未完成；当前必须按 Rocky 规则补验，不继续旧 Docker Desktop 指引。 |
| <a id="audit-2026-06-26-production-persistence"></a>2026-06-26-production-persistence [plans](../plans/2026-06-26-production-persistence.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-06-25-production-persistence"></a>2026-06-25-production-persistence [specs](../specs/2026-06-25-production-persistence-design.md)、[implementation](../implementation/2026-06-25-production-persistence-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-06-22-test-launch-profile"></a>2026-06-22-test-launch-profile [specs](../specs/2026-06-22-test-launch-profile-design.md)、[plans](../plans/2026-06-22-test-launch-profile.md)、[implementation](../implementation/2026-06-22-test-launch-profile-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-06-18-cm-agent-first-slice"></a>2026-06-18-cm-agent-first-slice [plans](../plans/2026-06-18-cm-agent-first-slice.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| <a id="audit-2026-06-18-cm-agent"></a>2026-06-18-cm-agent [specs](../specs/2026-06-18-cm-agent-design.md)、[implementation](../implementation/2026-06-18-cm-agent-implementation-design.md) | 归档状态待核实 | 没有原进度账本；现有设计、计划或实现说明不足以补造完成、取消或移交结论。 |
| docs-full-review | 当前 | 本轮完整复核与归档核定工作包，自身不自动归档 |

## 逐文件覆盖

本节为 R0 全量覆盖快照；R1 只继续核实 16 个主题，不新增或删除文档，分类更新见文末 Git 补证记录。

覆盖起始 543 文件及本包新增六份，共 549 份，包含全部历史文档。逐份读取正文并检查职责、结构、代码围栏和引用；以组为单位判断历史验收范围与状态。历史内容保留形成时语义，不代表重新审查全部业务代码或重新执行历史测试；未出现可证实错误的文件保持原文，不为产生差异而改写。

<details>
<summary>展开全部 549 份文件的职责与核定入口</summary>

| 文件 | 职责 | 本次内容处理与核定入口 |
|---|---|---|
| [adr/0001-documentation-governance.md](../../adr/0001-documentation-governance.md) | 已确认的重要取舍 | 保留已确认决定和适用范围，引用有效 |
| [configuration.md](../../configuration.md) | 当前使用或架构说明 | 更正通用客户端 audience 与默认值说明；独立浏览器门禁保留 |
| [deployment.md](../../deployment.md) | 当前使用或架构说明 | 当前与历史范围区分；运行行为仅对相关冲突作源码核对 |
| [harness-module.md](../../harness-module.md) | 当前使用或架构说明 | 当前与历史范围区分；运行行为仅对相关冲突作源码核对 |
| [operations.md](../../operations.md) | 当前使用或架构说明 | 当前与历史范围区分；运行行为仅对相关冲突作源码核对 |
| [README.md](../../README.md) | 当前导航 | 来源、职责与分类入口核对；不作为运行环境事实 |
| [release-notes.md](../../release-notes.md) | 当前使用或架构说明 | 保留形成时发布与验证记录，不把历史未提交/未发布当现状 |
| [roadmap.md](../../roadmap.md) | 当前使用或架构说明 | 当前与历史范围区分；运行行为仅对相关冲突作源码核对 |
| [skill-sandbox-endpoints.md](../../skill-sandbox-endpoints.md) | 当前使用或架构说明 | 当前与历史范围区分；运行行为仅对相关冲突作源码核对 |
| [superpowers/checklists/2026-09-30-next-iteration-analysis-checklist.md](../checklists/2026-09-30-next-iteration-analysis-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-09-30-next-iteration-analysis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-09-30-requirement-workpack-skill-checklist.md](../checklists/2026-09-30-requirement-workpack-skill-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-09-30-requirement-workpack-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-09-30-skill-sandbox-checklist.md](../checklists/2026-09-30-skill-sandbox-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-09-30-skill-sandbox-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-01-remote-docker-password-auth-checklist.md](../checklists/2026-10-01-remote-docker-password-auth-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-01-remote-docker-password-auth-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-08-jdbc-diagnostic-checklist.md](../checklists/2026-10-08-jdbc-diagnostic-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-08-jdbc-diagnostic-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-08-skill-artifacts-checklist.md](../checklists/2026-10-08-skill-artifacts-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-artifacts-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-08-skill-capacity-checklist.md](../checklists/2026-10-08-skill-capacity-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-08-skill-import-description-checklist.md](../checklists/2026-10-08-skill-import-description-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-import-description-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-08-skill-sandbox-config-docs-checklist.md](../checklists/2026-10-08-skill-sandbox-config-docs-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-sandbox-config-docs-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-08-skills-version-console-checklist.md](../checklists/2026-10-08-skills-version-console-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-08-skills-version-console-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-09-checkpoint-payload-capacity-checklist.md](../checklists/2026-10-09-checkpoint-payload-capacity-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-09-checkpoint-payload-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-09-docx-stdlib-skill-checklist.md](../checklists/2026-10-09-docx-stdlib-skill-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-09-docx-stdlib-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-09-server-lombok-support-checklist.md](../checklists/2026-10-09-server-lombok-support-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-09-server-lombok-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-09-windows-sandbox-bootstrap-checklist.md](../checklists/2026-10-09-windows-sandbox-bootstrap-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-09-windows-sandbox-bootstrap-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-chat-page-ux-checklist.md](../checklists/2026-10-10-chat-page-ux-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-chat-page-ux-delivery-checklist.md](../checklists/2026-10-10-chat-page-ux-delivery-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-chat-stop-checklist.md](../checklists/2026-10-10-chat-stop-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-chat-stop-delivery-checklist.md](../checklists/2026-10-10-chat-stop-delivery-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-harness-202-compatibility-checklist.md](../checklists/2026-10-10-harness-202-compatibility-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-202-compatibility-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-harness-architecture-checklist.md](../checklists/2026-10-10-harness-architecture-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-architecture-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-harness-entry-hardening-checklist.md](../checklists/2026-10-10-harness-entry-hardening-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-entry-hardening-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-harness-t2-checklist.md](../checklists/2026-10-10-harness-t2-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t2-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-10-harness-t3-checklist.md](../checklists/2026-10-10-harness-t3-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t3-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-11-harness-t4-checklist.md](../checklists/2026-10-11-harness-t4-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-11-harness-t4-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-12-harness-controlled-enablement-checklist.md](../checklists/2026-10-12-harness-controlled-enablement-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-controlled-enablement-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-12-harness-drain-readiness-checklist.md](../checklists/2026-10-12-harness-drain-readiness-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-drain-readiness-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-12-harness-t5-checklist.md](../checklists/2026-10-12-harness-t5-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t5-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-12-harness-t6-checklist.md](../checklists/2026-10-12-harness-t6-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t6-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-15-agent-model-name-sync-checklist.md](../checklists/2026-10-15-agent-model-name-sync-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-model-name-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-15-agent-page-polish-checklist.md](../checklists/2026-10-15-agent-page-polish-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-15-model-config-page-polish-checklist.md](../checklists/2026-10-15-model-config-page-polish-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-15-model-config-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-15-v2-console-polish-checklist.md](../checklists/2026-10-15-v2-console-polish-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-15-v2-console-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-26-chat-failure-feedback-checklist.md](../checklists/2026-10-26-chat-failure-feedback-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-26-chat-failure-feedback-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-27-workspace-directory-selection-checklist.md](../checklists/2026-10-27-workspace-directory-selection-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-implementation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-03-local-client-page-checklist.md](../checklists/2026-11-03-local-client-page-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-03-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-03-projects-page-checklist.md](../checklists/2026-11-03-projects-page-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-03-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-04-project-workspace-layout-checklist.md](../checklists/2026-11-04-project-workspace-layout-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-04-project-workspace-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-05-local-client-page-checklist.md](../checklists/2026-11-05-local-client-page-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-05-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-05-projects-page-checklist.md](../checklists/2026-11-05-projects-page-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-05-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-05-shared-runtime-checklist.md](../checklists/2026-11-05-shared-runtime-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-05-shared-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-09-client-project-workspace-checklist.md](../checklists/2026-11-09-client-project-workspace-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-09-client-project-workspace-delivery-checklist.md](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-12-sites-v2-design-sync-checklist.md](../checklists/2026-11-12-sites-v2-design-sync-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-12-sites-v2-design-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-13-doc-navigation-cleanup-checklist.md](../checklists/2026-11-13-doc-navigation-cleanup-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-navigation-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/checklists/2026-11-13-doc-organization-followup-checklist.md](../checklists/2026-11-13-doc-organization-followup-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-organization-followup-ledger.md#归档记录2026-11-13) |
| [superpowers/checklists/2026-11-13-docs-full-review-checklist.md](../checklists/2026-11-13-docs-full-review-checklist.md) | 需求、依赖与验收 | 本次 R0 工作包，按固定职责维护 |
| [superpowers/checklists/2026-11-13-documentation-governance-checklist.md](../checklists/2026-11-13-documentation-governance-checklist.md) | 需求、依赖与验收 | 历史内容按原范围保留；[核定依据](2026-11-13-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-06-18-cm-agent-implementation-design.md](../implementation/2026-06-18-cm-agent-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-06-18-cm-agent) |
| [superpowers/implementation/2026-06-22-test-launch-profile-implementation-design.md](../implementation/2026-06-22-test-launch-profile-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-06-22-test-launch-profile) |
| [superpowers/implementation/2026-06-25-production-persistence-implementation-design.md](../implementation/2026-06-25-production-persistence-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-06-25-production-persistence) |
| [superpowers/implementation/2026-06-29-supabase-persistence-automation-implementation-design.md](../implementation/2026-06-29-supabase-persistence-automation-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-06-29-supabase-persistence-automation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-07-13-server-yaml-variable-overrides-implementation-design.md](../implementation/2026-07-13-server-yaml-variable-overrides-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-07-13-server-yaml-variable-overrides) |
| [superpowers/implementation/2026-07-13-vm-database-profiles-implementation-design.md](../implementation/2026-07-13-vm-database-profiles-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-07-13-vm-database-profiles) |
| [superpowers/implementation/2026-07-14-phase-2-production-runtime-implementation-design.md](../implementation/2026-07-14-phase-2-production-runtime-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-07-14-phase-2-production-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-07-16-phase-3-agentscope-runtime-implementation-design.md](../implementation/2026-07-16-phase-3-agentscope-runtime-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-07-16-phase-3-agentscope-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-07-17-commit-file-export-implementation-design.md](../implementation/2026-07-17-commit-file-export-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-07-17-commit-file-export) |
| [superpowers/implementation/2026-07-17-console-capabilities-implementation-design.md](../implementation/2026-07-17-console-capabilities-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-07-17-console-capabilities) |
| [superpowers/implementation/2026-07-21-dynamic-http-mcp-tools-implementation-design.md](../implementation/2026-07-21-dynamic-http-mcp-tools-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-07-21-dynamic-http-mcp-tools-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-07-28-tool-development-guide-examples-implementation-design.md](../implementation/2026-07-28-tool-development-guide-examples-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-07-28-tool-development-guide-examples) |
| [superpowers/implementation/2026-07-30-mysql-builtin-local-tools-implementation-design.md](../implementation/2026-07-30-mysql-builtin-local-tools-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-07-30-mysql-builtin-local-tools) |
| [superpowers/implementation/2026-07-31-tool-management-edit-delete-implementation-design.md](../implementation/2026-07-31-tool-management-edit-delete-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-07-31-tool-management-edit-delete) |
| [superpowers/implementation/2026-08-03-tool-management-edit-delete-implementation-design.md](../implementation/2026-08-03-tool-management-edit-delete-implementation-design.md) | 实际改动说明 | 无原账本；[依据不足](#audit-2026-08-03-tool-management-edit-delete) |
| [superpowers/implementation/2026-08-06-http-tool-parameter-definition-design.md](../implementation/2026-08-06-http-tool-parameter-definition-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-06-http-tool-parameter-definition-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-07-superpowers-documentation-governance-implementation-design.md](../implementation/2026-08-07-superpowers-documentation-governance-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-07-superpowers-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-10-error-diagnostics-development-standards-implementation-design.md](../implementation/2026-08-10-error-diagnostics-development-standards-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-10-error-diagnostics-development-standards-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-10-global-error-diagnostics-implementation-design.md](../implementation/2026-08-10-global-error-diagnostics-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-10-global-error-diagnostics-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-10-tool-debug-error-observability-implementation-design.md](../implementation/2026-08-10-tool-debug-error-observability-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-10-tool-debug-error-observability-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-13-dashscope-mcp-tool-agent-example-implementation-design.md](../implementation/2026-08-13-dashscope-mcp-tool-agent-example-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-13-dashscope-mcp-tool-agent-example-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-13-run-execution-service-run-comments-implementation-design.md](../implementation/2026-08-13-run-execution-service-run-comments-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-13-run-execution-service-run-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-14-agentscope-context-agent-state-example-implementation-design.md](../implementation/2026-08-14-agentscope-context-agent-state-example-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-14-agentscope-context-agent-state-example-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-17-agentscope-state-store-examples-implementation-design.md](../implementation/2026-08-17-agentscope-state-store-examples-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-17-agentscope-state-store-examples-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-17-docker-compose-redis-implementation-design.md](../implementation/2026-08-17-docker-compose-redis-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-17-docker-compose-redis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-18-agent-management-model-selection-implementation-design.md](../implementation/2026-08-18-agent-management-model-selection-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-18-agent-management-model-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-18-console-v2-layout-implementation-design.md](../implementation/2026-08-18-console-v2-layout-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-18-console-v2-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-18-console-versioned-multipage-implementation-design.md](../implementation/2026-08-18-console-versioned-multipage-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-18-console-versioned-multipage-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-18-database-schema-comments-implementation-design.md](../implementation/2026-08-18-database-schema-comments-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-18-database-schema-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-18-model-config-management-implementation-design.md](../implementation/2026-08-18-model-config-management-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-18-model-config-management-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-19-console-run-streaming-implementation-design.md](../implementation/2026-08-19-console-run-streaming-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-19-console-run-streaming-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-20-agentscope-adapter-comments-implementation-design.md](../implementation/2026-08-20-agentscope-adapter-comments-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-20-agentscope-adapter-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-20-console-run-markdown-implementation-design.md](../implementation/2026-08-20-console-run-markdown-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-20-console-run-markdown-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-20-console-runs-ui-implementation-design.md](../implementation/2026-08-20-console-runs-ui-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-20-console-runs-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-20-console-tool-call-payload-ui-implementation-design.md](../implementation/2026-08-20-console-tool-call-payload-ui-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-20-console-tool-call-payload-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-28-agentscope-studio-integration-implementation-design.md](../implementation/2026-08-28-agentscope-studio-integration-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-28-agentscope-studio-integration-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-31-console-session-chat-page-implementation-design.md](../implementation/2026-08-31-console-session-chat-page-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-31-console-session-chat-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-08-31-message-first-conversations-implementation-design.md](../implementation/2026-08-31-message-first-conversations-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-08-31-message-first-conversations-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-01-adapter-comments-cleanup-implementation-design.md](../implementation/2026-09-01-adapter-comments-cleanup-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-01-adapter-comments-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-01-console-chat-execution-trace-implementation-design.md](../implementation/2026-09-01-console-chat-execution-trace-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-01-console-chat-execution-trace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-01-core-code-comments-implementation-design.md](../implementation/2026-09-01-core-code-comments-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-01-core-code-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-01-core-module-readme-implementation-design.md](../implementation/2026-09-01-core-module-readme-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-01-core-module-readme-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-01-enum-field-comments-implementation-design.md](../implementation/2026-09-01-enum-field-comments-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-01-enum-field-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-01-log-coverage-gaps-implementation-design.md](../implementation/2026-09-01-log-coverage-gaps-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-01-log-coverage-gaps-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-01-mandatory-comment-generation-implementation-design.md](../implementation/2026-09-01-mandatory-comment-generation-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-01-mandatory-comment-generation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-02-agentscope-permission-system-integration-implementation-design.md](../implementation/2026-09-02-agentscope-permission-system-integration-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-02-agentscope-permission-system-integration-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-03-tool-approval-history-implementation-design.md](../implementation/2026-09-03-tool-approval-history-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-03-tool-approval-history-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-03-tool-approval-ui-ux-implementation-design.md](../implementation/2026-09-03-tool-approval-ui-ux-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-03-tool-approval-ui-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-16-console-prototype-alignment-implementation-design.md](../implementation/2026-09-16-console-prototype-alignment-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-16-console-prototype-alignment-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-16-console-visual-interaction-polish-implementation-design.md](../implementation/2026-09-16-console-visual-interaction-polish-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-16-console-visual-interaction-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-16-model-catalog-discovery-implementation-design.md](../implementation/2026-09-16-model-catalog-discovery-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-16-model-catalog-discovery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-18-opencode-go-runtime-session-implementation-design.md](../implementation/2026-09-18-opencode-go-runtime-session-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-18-opencode-go-runtime-session-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-20-first-agent-onboarding-implementation-design.md](../implementation/2026-09-20-first-agent-onboarding-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-20-first-agent-onboarding-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-20-impeccable-design-documentation-implementation-design.md](../implementation/2026-09-20-impeccable-design-documentation-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-design-documentation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-20-impeccable-live-setup-implementation-design.md](../implementation/2026-09-20-impeccable-live-setup-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-live-setup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-20-impeccable-product-init-implementation-design.md](../implementation/2026-09-20-impeccable-product-init-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-product-init-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-20-skill-support-implementation-design.md](../implementation/2026-09-20-skill-support-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-20-skill-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-20-tool-workspace-stages-implementation-design.md](../implementation/2026-09-20-tool-workspace-stages-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-20-tool-workspace-stages-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-21-opencode-message-name-implementation-design.md](../implementation/2026-09-21-opencode-message-name-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-21-opencode-message-name-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-21-skill-management-polish-implementation-design.md](../implementation/2026-09-21-skill-management-polish-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-21-skill-management-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-22-skill-release-validation-implementation-design.md](../implementation/2026-09-22-skill-release-validation-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-22-skill-release-validation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-28-skill-trial-agent-dropdown-implementation-design.md](../implementation/2026-09-28-skill-trial-agent-dropdown-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-28-skill-trial-agent-dropdown-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-28-skill-trial-result-preview-implementation-design.md](../implementation/2026-09-28-skill-trial-result-preview-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-28-skill-trial-result-preview-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-30-approval-expiry-a-implementation-design.md](../implementation/2026-09-30-approval-expiry-a-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-30-approval-expiry-a-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-30-chat-approval-ui-implementation-design.md](../implementation/2026-09-30-chat-approval-ui-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-30-chat-approval-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-30-next-iteration-analysis-implementation-design.md](../implementation/2026-09-30-next-iteration-analysis-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-30-next-iteration-analysis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-30-requirement-workpack-skill-implementation-design.md](../implementation/2026-09-30-requirement-workpack-skill-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-30-requirement-workpack-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-09-30-skill-sandbox-implementation-design.md](../implementation/2026-09-30-skill-sandbox-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-09-30-skill-sandbox-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-01-remote-docker-password-auth-implementation-design.md](../implementation/2026-10-01-remote-docker-password-auth-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-01-remote-docker-password-auth-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-08-jdbc-diagnostic-implementation-design.md](../implementation/2026-10-08-jdbc-diagnostic-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-08-jdbc-diagnostic-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-08-skill-artifacts-implementation-design.md](../implementation/2026-10-08-skill-artifacts-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-artifacts-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-08-skill-capacity-implementation-design.md](../implementation/2026-10-08-skill-capacity-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-08-skill-import-description-implementation-design.md](../implementation/2026-10-08-skill-import-description-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-import-description-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-08-skill-sandbox-config-docs-implementation-design.md](../implementation/2026-10-08-skill-sandbox-config-docs-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-sandbox-config-docs-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-08-skills-version-console-implementation-design.md](../implementation/2026-10-08-skills-version-console-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-08-skills-version-console-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-09-checkpoint-payload-capacity-implementation-design.md](../implementation/2026-10-09-checkpoint-payload-capacity-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-09-checkpoint-payload-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-09-docx-stdlib-skill-implementation-design.md](../implementation/2026-10-09-docx-stdlib-skill-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-09-docx-stdlib-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-09-server-lombok-support-implementation-design.md](../implementation/2026-10-09-server-lombok-support-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-09-server-lombok-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-09-windows-sandbox-bootstrap-implementation-design.md](../implementation/2026-10-09-windows-sandbox-bootstrap-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-09-windows-sandbox-bootstrap-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-chat-page-ux-delivery-implementation-design.md](../implementation/2026-10-10-chat-page-ux-delivery-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-chat-page-ux-implementation-design.md](../implementation/2026-10-10-chat-page-ux-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-chat-stop-delivery-implementation-design.md](../implementation/2026-10-10-chat-stop-delivery-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-chat-stop-implementation-design.md](../implementation/2026-10-10-chat-stop-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-harness-202-compatibility-implementation-design.md](../implementation/2026-10-10-harness-202-compatibility-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-202-compatibility-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-harness-architecture-implementation-design.md](../implementation/2026-10-10-harness-architecture-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-architecture-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-harness-entry-hardening-implementation-design.md](../implementation/2026-10-10-harness-entry-hardening-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-entry-hardening-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-harness-t2-implementation-design.md](../implementation/2026-10-10-harness-t2-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t2-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-10-harness-t3-implementation-design.md](../implementation/2026-10-10-harness-t3-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t3-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-11-harness-t4-implementation-design.md](../implementation/2026-10-11-harness-t4-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-11-harness-t4-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-12-harness-controlled-enablement-implementation-design.md](../implementation/2026-10-12-harness-controlled-enablement-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-controlled-enablement-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-12-harness-drain-readiness-implementation-design.md](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-drain-readiness-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-12-harness-t5-implementation-design.md](../implementation/2026-10-12-harness-t5-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t5-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-12-harness-t6-implementation-design.md](../implementation/2026-10-12-harness-t6-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t6-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-15-agent-model-name-sync-implementation-design.md](../implementation/2026-10-15-agent-model-name-sync-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-model-name-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-15-agent-page-polish-implementation-design.md](../implementation/2026-10-15-agent-page-polish-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-15-model-config-page-polish-implementation-design.md](../implementation/2026-10-15-model-config-page-polish-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-15-model-config-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-15-v2-console-polish-implementation-design.md](../implementation/2026-10-15-v2-console-polish-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-15-v2-console-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-26-chat-failure-feedback-implementation-design.md](../implementation/2026-10-26-chat-failure-feedback-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-26-chat-failure-feedback-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-27-workspace-directory-selection-implementation-design.md](../implementation/2026-10-27-workspace-directory-selection-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-implementation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-03-local-client-page-implementation-design.md](../implementation/2026-11-03-local-client-page-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-03-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-03-projects-page-implementation-design.md](../implementation/2026-11-03-projects-page-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-03-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-04-project-workspace-layout-implementation-design.md](../implementation/2026-11-04-project-workspace-layout-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-04-project-workspace-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-05-local-client-page-implementation-design.md](../implementation/2026-11-05-local-client-page-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-05-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-05-projects-page-implementation-design.md](../implementation/2026-11-05-projects-page-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-05-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-05-shared-runtime-implementation-design.md](../implementation/2026-11-05-shared-runtime-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-05-shared-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-09-client-project-workspace-delivery-implementation-design.md](../implementation/2026-11-09-client-project-workspace-delivery-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-09-client-project-workspace-implementation-design.md](../implementation/2026-11-09-client-project-workspace-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-12-sites-v2-design-sync-implementation-design.md](../implementation/2026-11-12-sites-v2-design-sync-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-12-sites-v2-design-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-13-doc-navigation-cleanup-implementation-design.md](../implementation/2026-11-13-doc-navigation-cleanup-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-navigation-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/implementation/2026-11-13-doc-organization-followup-implementation-design.md](../implementation/2026-11-13-doc-organization-followup-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-organization-followup-ledger.md#归档记录2026-11-13) |
| [superpowers/implementation/2026-11-13-docs-full-review-implementation-design.md](../implementation/2026-11-13-docs-full-review-implementation-design.md) | 实际改动说明 | 本次 R0 工作包，按固定职责维护 |
| [superpowers/implementation/2026-11-13-documentation-governance-implementation-design.md](../implementation/2026-11-13-documentation-governance-implementation-design.md) | 实际改动说明 | 历史内容按原范围保留；[核定依据](2026-11-13-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-06-18-cm-agent-first-slice.md](../plans/2026-06-18-cm-agent-first-slice.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-06-18-cm-agent-first-slice) |
| [superpowers/plans/2026-06-22-test-launch-profile.md](../plans/2026-06-22-test-launch-profile.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-06-22-test-launch-profile) |
| [superpowers/plans/2026-06-26-production-persistence.md](../plans/2026-06-26-production-persistence.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-06-26-production-persistence) |
| [superpowers/plans/2026-06-29-supabase-persistence-automation.md](../plans/2026-06-29-supabase-persistence-automation.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-06-29-supabase-persistence-automation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-07-13-server-yaml-variable-overrides.md](../plans/2026-07-13-server-yaml-variable-overrides.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-07-13-server-yaml-variable-overrides) |
| [superpowers/plans/2026-07-13-vm-database-profiles.md](../plans/2026-07-13-vm-database-profiles.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-07-13-vm-database-profiles) |
| [superpowers/plans/2026-07-14-phase-2-production-runtime.md](../plans/2026-07-14-phase-2-production-runtime.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-07-14-phase-2-production-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-07-16-phase-3-agentscope-runtime.md](../plans/2026-07-16-phase-3-agentscope-runtime.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-07-16-phase-3-agentscope-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-07-17-commit-file-export.md](../plans/2026-07-17-commit-file-export.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-07-17-commit-file-export) |
| [superpowers/plans/2026-07-17-console-capabilities.md](../plans/2026-07-17-console-capabilities.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-07-17-console-capabilities) |
| [superpowers/plans/2026-07-21-dynamic-http-mcp-tools.md](../plans/2026-07-21-dynamic-http-mcp-tools.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-07-21-dynamic-http-mcp-tools-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-07-28-tool-development-guide-examples.md](../plans/2026-07-28-tool-development-guide-examples.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-07-28-tool-development-guide-examples) |
| [superpowers/plans/2026-07-30-mysql-builtin-local-tools.md](../plans/2026-07-30-mysql-builtin-local-tools.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-07-30-mysql-builtin-local-tools) |
| [superpowers/plans/2026-07-31-tool-management-edit-delete.md](../plans/2026-07-31-tool-management-edit-delete.md) | 实施方法 | 无原账本；[依据不足](#audit-2026-07-31-tool-management-edit-delete) |
| [superpowers/plans/2026-08-06-http-tool-parameter-definition.md](../plans/2026-08-06-http-tool-parameter-definition.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-06-http-tool-parameter-definition-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-07-superpowers-documentation-governance.md](../plans/2026-08-07-superpowers-documentation-governance.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-07-superpowers-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-10-error-diagnostics-development-standards.md](../plans/2026-08-10-error-diagnostics-development-standards.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-10-error-diagnostics-development-standards-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-10-global-error-diagnostics.md](../plans/2026-08-10-global-error-diagnostics.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-10-global-error-diagnostics-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-10-tool-debug-error-observability.md](../plans/2026-08-10-tool-debug-error-observability.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-10-tool-debug-error-observability-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-13-dashscope-mcp-tool-agent-example.md](../plans/2026-08-13-dashscope-mcp-tool-agent-example.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-13-dashscope-mcp-tool-agent-example-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-13-run-execution-service-run-comments.md](../plans/2026-08-13-run-execution-service-run-comments.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-13-run-execution-service-run-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-14-agentscope-context-agent-state-example.md](../plans/2026-08-14-agentscope-context-agent-state-example.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-14-agentscope-context-agent-state-example-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-17-agentscope-state-store-examples.md](../plans/2026-08-17-agentscope-state-store-examples.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-17-agentscope-state-store-examples-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-17-docker-compose-redis.md](../plans/2026-08-17-docker-compose-redis.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-17-docker-compose-redis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-18-agent-management-model-selection.md](../plans/2026-08-18-agent-management-model-selection.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-18-agent-management-model-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-18-console-v2-layout.md](../plans/2026-08-18-console-v2-layout.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-18-console-v2-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-18-console-versioned-multipage.md](../plans/2026-08-18-console-versioned-multipage.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-18-console-versioned-multipage-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-18-database-schema-comments.md](../plans/2026-08-18-database-schema-comments.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-18-database-schema-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-18-model-config-management.md](../plans/2026-08-18-model-config-management.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-18-model-config-management-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-19-console-run-streaming.md](../plans/2026-08-19-console-run-streaming.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-19-console-run-streaming-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-20-agentscope-adapter-comments.md](../plans/2026-08-20-agentscope-adapter-comments.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-20-agentscope-adapter-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-20-console-run-markdown.md](../plans/2026-08-20-console-run-markdown.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-20-console-run-markdown-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-20-console-runs-ui.md](../plans/2026-08-20-console-runs-ui.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-20-console-runs-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-20-console-tool-call-payload-ui.md](../plans/2026-08-20-console-tool-call-payload-ui.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-20-console-tool-call-payload-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-28-agentscope-studio-integration.md](../plans/2026-08-28-agentscope-studio-integration.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-28-agentscope-studio-integration-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-31-console-session-chat-page.md](../plans/2026-08-31-console-session-chat-page.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-31-console-session-chat-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-08-31-message-first-conversations.md](../plans/2026-08-31-message-first-conversations.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-08-31-message-first-conversations-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-01-adapter-comments-cleanup.md](../plans/2026-09-01-adapter-comments-cleanup.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-01-adapter-comments-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-01-console-chat-execution-trace.md](../plans/2026-09-01-console-chat-execution-trace.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-01-console-chat-execution-trace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-01-core-code-comments.md](../plans/2026-09-01-core-code-comments.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-01-core-code-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-01-core-module-readme.md](../plans/2026-09-01-core-module-readme.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-01-core-module-readme-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-01-enum-field-comments.md](../plans/2026-09-01-enum-field-comments.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-01-enum-field-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-01-log-coverage-gaps.md](../plans/2026-09-01-log-coverage-gaps.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-01-log-coverage-gaps-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-01-mandatory-comment-generation.md](../plans/2026-09-01-mandatory-comment-generation.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-01-mandatory-comment-generation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-02-agentscope-permission-system-integration.md](../plans/2026-09-02-agentscope-permission-system-integration.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-02-agentscope-permission-system-integration-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-03-tool-approval-history.md](../plans/2026-09-03-tool-approval-history.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-03-tool-approval-history-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-03-tool-approval-ui-ux.md](../plans/2026-09-03-tool-approval-ui-ux.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-03-tool-approval-ui-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-16-console-prototype-alignment.md](../plans/2026-09-16-console-prototype-alignment.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-16-console-prototype-alignment-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-16-console-visual-interaction-polish.md](../plans/2026-09-16-console-visual-interaction-polish.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-16-console-visual-interaction-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-16-model-catalog-discovery.md](../plans/2026-09-16-model-catalog-discovery.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-16-model-catalog-discovery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-18-opencode-go-runtime-session.md](../plans/2026-09-18-opencode-go-runtime-session.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-18-opencode-go-runtime-session-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-20-first-agent-onboarding.md](../plans/2026-09-20-first-agent-onboarding.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-20-first-agent-onboarding-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-20-impeccable-design-documentation.md](../plans/2026-09-20-impeccable-design-documentation.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-design-documentation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-20-impeccable-live-setup.md](../plans/2026-09-20-impeccable-live-setup.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-live-setup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-20-impeccable-product-init.md](../plans/2026-09-20-impeccable-product-init.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-product-init-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-20-skill-support.md](../plans/2026-09-20-skill-support.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-20-skill-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-20-tool-workspace-stages.md](../plans/2026-09-20-tool-workspace-stages.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-20-tool-workspace-stages-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-21-opencode-message-name.md](../plans/2026-09-21-opencode-message-name.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-21-opencode-message-name-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-21-skill-management-polish.md](../plans/2026-09-21-skill-management-polish.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-21-skill-management-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-22-skill-release-validation.md](../plans/2026-09-22-skill-release-validation.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-22-skill-release-validation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-28-skill-trial-agent-dropdown.md](../plans/2026-09-28-skill-trial-agent-dropdown.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-28-skill-trial-agent-dropdown-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-28-skill-trial-result-preview.md](../plans/2026-09-28-skill-trial-result-preview.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-28-skill-trial-result-preview-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-30-approval-expiry-a.md](../plans/2026-09-30-approval-expiry-a.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-30-approval-expiry-a-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-30-chat-approval-ui.md](../plans/2026-09-30-chat-approval-ui.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-30-chat-approval-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-30-next-iteration-analysis.md](../plans/2026-09-30-next-iteration-analysis.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-30-next-iteration-analysis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-30-requirement-workpack-skill.md](../plans/2026-09-30-requirement-workpack-skill.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-30-requirement-workpack-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-09-30-skill-sandbox.md](../plans/2026-09-30-skill-sandbox.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-09-30-skill-sandbox-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-01-remote-docker-password-auth.md](../plans/2026-10-01-remote-docker-password-auth.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-01-remote-docker-password-auth-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-08-jdbc-diagnostic.md](../plans/2026-10-08-jdbc-diagnostic.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-08-jdbc-diagnostic-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-08-skill-artifacts.md](../plans/2026-10-08-skill-artifacts.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-artifacts-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-08-skill-capacity.md](../plans/2026-10-08-skill-capacity.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-08-skill-import-description.md](../plans/2026-10-08-skill-import-description.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-import-description-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-08-skill-sandbox-config-docs.md](../plans/2026-10-08-skill-sandbox-config-docs.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-sandbox-config-docs-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-08-skills-version-console.md](../plans/2026-10-08-skills-version-console.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-08-skills-version-console-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-09-checkpoint-payload-capacity.md](../plans/2026-10-09-checkpoint-payload-capacity.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-09-checkpoint-payload-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-09-docx-stdlib-skill.md](../plans/2026-10-09-docx-stdlib-skill.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-09-docx-stdlib-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-09-server-lombok-support.md](../plans/2026-10-09-server-lombok-support.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-09-server-lombok-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-09-windows-sandbox-bootstrap.md](../plans/2026-10-09-windows-sandbox-bootstrap.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-09-windows-sandbox-bootstrap-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-chat-page-ux-delivery.md](../plans/2026-10-10-chat-page-ux-delivery.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-chat-page-ux.md](../plans/2026-10-10-chat-page-ux.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-chat-stop-delivery.md](../plans/2026-10-10-chat-stop-delivery.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-chat-stop.md](../plans/2026-10-10-chat-stop.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-harness-202-compatibility.md](../plans/2026-10-10-harness-202-compatibility.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-202-compatibility-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-harness-architecture.md](../plans/2026-10-10-harness-architecture.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-architecture-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-harness-entry-hardening.md](../plans/2026-10-10-harness-entry-hardening.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-entry-hardening-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-harness-t2.md](../plans/2026-10-10-harness-t2.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t2-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-10-harness-t3.md](../plans/2026-10-10-harness-t3.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t3-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-11-harness-t4.md](../plans/2026-10-11-harness-t4.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-11-harness-t4-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-12-harness-controlled-enablement.md](../plans/2026-10-12-harness-controlled-enablement.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-controlled-enablement-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-12-harness-drain-readiness.md](../plans/2026-10-12-harness-drain-readiness.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-drain-readiness-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-12-harness-t5.md](../plans/2026-10-12-harness-t5.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t5-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-12-harness-t6.md](../plans/2026-10-12-harness-t6.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t6-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-15-agent-model-name-sync.md](../plans/2026-10-15-agent-model-name-sync.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-model-name-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-15-agent-page-polish.md](../plans/2026-10-15-agent-page-polish.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-15-model-config-page-polish.md](../plans/2026-10-15-model-config-page-polish.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-15-model-config-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-15-v2-console-polish.md](../plans/2026-10-15-v2-console-polish.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-15-v2-console-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-26-chat-failure-feedback.md](../plans/2026-10-26-chat-failure-feedback.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-26-chat-failure-feedback-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-27-workspace-directory-selection-implementation.md](../plans/2026-10-27-workspace-directory-selection-implementation.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-implementation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-10-27-workspace-directory-selection.md](../plans/2026-10-27-workspace-directory-selection.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-03-local-client-page.md](../plans/2026-11-03-local-client-page.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-03-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-03-projects-page.md](../plans/2026-11-03-projects-page.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-03-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-04-project-workspace-layout.md](../plans/2026-11-04-project-workspace-layout.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-04-project-workspace-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-05-local-client-page.md](../plans/2026-11-05-local-client-page.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-05-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-05-projects-page.md](../plans/2026-11-05-projects-page.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-05-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-05-shared-runtime.md](../plans/2026-11-05-shared-runtime.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-05-shared-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-09-client-project-workspace-delivery.md](../plans/2026-11-09-client-project-workspace-delivery.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-09-client-project-workspace.md](../plans/2026-11-09-client-project-workspace.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-12-sites-v2-design-sync.md](../plans/2026-11-12-sites-v2-design-sync.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-12-sites-v2-design-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-13-doc-navigation-cleanup.md](../plans/2026-11-13-doc-navigation-cleanup.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-navigation-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/plans/2026-11-13-doc-organization-followup.md](../plans/2026-11-13-doc-organization-followup.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-organization-followup-ledger.md#归档记录2026-11-13) |
| [superpowers/plans/2026-11-13-docs-full-review.md](../plans/2026-11-13-docs-full-review.md) | 实施方法 | 本次 R0 工作包，按固定职责维护 |
| [superpowers/plans/2026-11-13-documentation-governance.md](../plans/2026-11-13-documentation-governance.md) | 实施方法 | 历史内容按原范围保留；[核定依据](2026-11-13-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-06-29-production-persistence-ledger.md](2026-06-29-production-persistence-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-06-29-production-persistence-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-06-29-supabase-persistence-automation-ledger.md](2026-06-29-supabase-persistence-automation-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-06-29-supabase-persistence-automation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-07-14-phase-2-production-runtime-ledger.md](2026-07-14-phase-2-production-runtime-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-07-14-phase-2-production-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-07-16-phase-3-agentscope-runtime-ledger.md](2026-07-16-phase-3-agentscope-runtime-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-07-16-phase-3-agentscope-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-07-21-dynamic-http-mcp-tools-ledger.md](2026-07-21-dynamic-http-mcp-tools-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-07-21-dynamic-http-mcp-tools-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-06-http-tool-parameter-definition-ledger.md](2026-08-06-http-tool-parameter-definition-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-06-http-tool-parameter-definition-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-07-superpowers-documentation-governance-ledger.md](2026-08-07-superpowers-documentation-governance-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-07-superpowers-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-10-error-diagnostics-development-standards-ledger.md](2026-08-10-error-diagnostics-development-standards-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-10-error-diagnostics-development-standards-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-10-global-error-diagnostics-ledger.md](2026-08-10-global-error-diagnostics-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-10-global-error-diagnostics-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-10-tool-debug-error-observability-ledger.md](2026-08-10-tool-debug-error-observability-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-10-tool-debug-error-observability-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-13-dashscope-mcp-tool-agent-example-ledger.md](2026-08-13-dashscope-mcp-tool-agent-example-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-13-dashscope-mcp-tool-agent-example-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-13-run-execution-service-run-comments-ledger.md](2026-08-13-run-execution-service-run-comments-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-13-run-execution-service-run-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-14-agentscope-context-agent-state-example-ledger.md](2026-08-14-agentscope-context-agent-state-example-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-14-agentscope-context-agent-state-example-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-17-agentscope-state-store-examples-ledger.md](2026-08-17-agentscope-state-store-examples-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-17-agentscope-state-store-examples-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-17-docker-compose-redis-ledger.md](2026-08-17-docker-compose-redis-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-17-docker-compose-redis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-18-agent-management-model-selection-ledger.md](2026-08-18-agent-management-model-selection-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-18-agent-management-model-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-18-console-v2-layout-ledger.md](2026-08-18-console-v2-layout-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-18-console-v2-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-18-console-versioned-multipage-ledger.md](2026-08-18-console-versioned-multipage-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-18-console-versioned-multipage-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-18-database-schema-comments-ledger.md](2026-08-18-database-schema-comments-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-18-database-schema-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-18-model-config-management-ledger.md](2026-08-18-model-config-management-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-18-model-config-management-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-19-console-run-streaming-ledger.md](2026-08-19-console-run-streaming-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-19-console-run-streaming-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-20-agentscope-adapter-comments-ledger.md](2026-08-20-agentscope-adapter-comments-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-20-agentscope-adapter-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-20-console-run-markdown-ledger.md](2026-08-20-console-run-markdown-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-20-console-run-markdown-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-20-console-runs-ui-ledger.md](2026-08-20-console-runs-ui-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-20-console-runs-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-20-console-tool-call-payload-ui-ledger.md](2026-08-20-console-tool-call-payload-ui-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-20-console-tool-call-payload-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-28-agentscope-studio-integration-ledger.md](2026-08-28-agentscope-studio-integration-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-28-agentscope-studio-integration-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-31-console-session-chat-page-ledger.md](2026-08-31-console-session-chat-page-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-31-console-session-chat-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-08-31-message-first-conversations-ledger.md](2026-08-31-message-first-conversations-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-08-31-message-first-conversations-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-01-adapter-comments-cleanup-ledger.md](2026-09-01-adapter-comments-cleanup-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-01-adapter-comments-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-01-console-chat-execution-trace-ledger.md](2026-09-01-console-chat-execution-trace-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-01-console-chat-execution-trace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-01-core-code-comments-ledger.md](2026-09-01-core-code-comments-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-01-core-code-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-01-core-module-readme-ledger.md](2026-09-01-core-module-readme-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-01-core-module-readme-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-01-enum-field-comments-ledger.md](2026-09-01-enum-field-comments-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-01-enum-field-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-01-log-coverage-gaps-ledger.md](2026-09-01-log-coverage-gaps-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-01-log-coverage-gaps-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-01-mandatory-comment-generation-ledger.md](2026-09-01-mandatory-comment-generation-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-01-mandatory-comment-generation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-02-agentscope-permission-system-integration-ledger.md](2026-09-02-agentscope-permission-system-integration-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-02-agentscope-permission-system-integration-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-03-tool-approval-history-ledger.md](2026-09-03-tool-approval-history-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-03-tool-approval-history-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-03-tool-approval-ui-ux-ledger.md](2026-09-03-tool-approval-ui-ux-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-03-tool-approval-ui-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-16-console-prototype-alignment-ledger.md](2026-09-16-console-prototype-alignment-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-16-console-prototype-alignment-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-16-console-visual-interaction-polish-ledger.md](2026-09-16-console-visual-interaction-polish-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-16-console-visual-interaction-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-16-model-catalog-discovery-ledger.md](2026-09-16-model-catalog-discovery-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-16-model-catalog-discovery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-18-opencode-go-runtime-session-ledger.md](2026-09-18-opencode-go-runtime-session-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-18-opencode-go-runtime-session-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-20-first-agent-onboarding-ledger.md](2026-09-20-first-agent-onboarding-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-20-first-agent-onboarding-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-20-impeccable-design-documentation-ledger.md](2026-09-20-impeccable-design-documentation-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-20-impeccable-design-documentation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-20-impeccable-live-setup-ledger.md](2026-09-20-impeccable-live-setup-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-20-impeccable-live-setup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-20-impeccable-product-init-ledger.md](2026-09-20-impeccable-product-init-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-20-impeccable-product-init-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-20-skill-support-ledger.md](2026-09-20-skill-support-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-20-skill-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-20-tool-workspace-stages-ledger.md](2026-09-20-tool-workspace-stages-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-20-tool-workspace-stages-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-21-opencode-message-name-ledger.md](2026-09-21-opencode-message-name-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-21-opencode-message-name-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-21-skill-management-polish-ledger.md](2026-09-21-skill-management-polish-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-21-skill-management-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-22-skill-release-validation-ledger.md](2026-09-22-skill-release-validation-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-22-skill-release-validation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-28-skill-trial-agent-dropdown-ledger.md](2026-09-28-skill-trial-agent-dropdown-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-28-skill-trial-agent-dropdown-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-28-skill-trial-result-preview-ledger.md](2026-09-28-skill-trial-result-preview-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-28-skill-trial-result-preview-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-30-approval-expiry-a-ledger.md](2026-09-30-approval-expiry-a-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-30-approval-expiry-a-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-30-chat-approval-ui-ledger.md](2026-09-30-chat-approval-ui-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-30-chat-approval-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-30-next-iteration-analysis-ledger.md](2026-09-30-next-iteration-analysis-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-30-next-iteration-analysis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-30-requirement-workpack-skill-ledger.md](2026-09-30-requirement-workpack-skill-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-30-requirement-workpack-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-09-30-skill-sandbox-ledger.md](2026-09-30-skill-sandbox-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-09-30-skill-sandbox-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-01-remote-docker-password-auth-ledger.md](2026-10-01-remote-docker-password-auth-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-01-remote-docker-password-auth-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-08-jdbc-diagnostic-ledger.md](2026-10-08-jdbc-diagnostic-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-08-jdbc-diagnostic-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-08-skill-artifacts-ledger.md](2026-10-08-skill-artifacts-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-08-skill-artifacts-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-08-skill-capacity-ledger.md](2026-10-08-skill-capacity-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-08-skill-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-08-skill-import-description-ledger.md](2026-10-08-skill-import-description-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-08-skill-import-description-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-08-skill-sandbox-config-docs-ledger.md](2026-10-08-skill-sandbox-config-docs-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-08-skill-sandbox-config-docs-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-08-skills-version-console-ledger.md](2026-10-08-skills-version-console-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-08-skills-version-console-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-09-checkpoint-payload-capacity-ledger.md](2026-10-09-checkpoint-payload-capacity-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-09-checkpoint-payload-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-09-docx-stdlib-skill-ledger.md](2026-10-09-docx-stdlib-skill-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-09-docx-stdlib-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-09-server-lombok-support-ledger.md](2026-10-09-server-lombok-support-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-09-server-lombok-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-09-windows-sandbox-bootstrap-ledger.md](2026-10-09-windows-sandbox-bootstrap-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-09-windows-sandbox-bootstrap-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-chat-page-ux-delivery-ledger.md](2026-10-10-chat-page-ux-delivery-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-chat-page-ux-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-chat-page-ux-ledger.md](2026-10-10-chat-page-ux-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-chat-page-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-chat-stop-delivery-ledger.md](2026-10-10-chat-stop-delivery-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-chat-stop-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-chat-stop-ledger.md](2026-10-10-chat-stop-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-chat-stop-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-harness-202-compatibility-ledger.md](2026-10-10-harness-202-compatibility-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-harness-202-compatibility-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-harness-architecture-ledger.md](2026-10-10-harness-architecture-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-harness-architecture-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-harness-entry-hardening-ledger.md](2026-10-10-harness-entry-hardening-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-harness-entry-hardening-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-harness-t2-ledger.md](2026-10-10-harness-t2-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-harness-t2-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-10-harness-t3-ledger.md](2026-10-10-harness-t3-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-10-harness-t3-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-11-harness-t4-ledger.md](2026-10-11-harness-t4-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-11-harness-t4-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-12-harness-controlled-enablement-ledger.md](2026-10-12-harness-controlled-enablement-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-12-harness-controlled-enablement-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md](2026-10-12-harness-drain-readiness-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-12-harness-drain-readiness-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-12-harness-t5-ledger.md](2026-10-12-harness-t5-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-12-harness-t5-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-12-harness-t6-ledger.md](2026-10-12-harness-t6-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-12-harness-t6-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-15-agent-model-name-sync-ledger.md](2026-10-15-agent-model-name-sync-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-15-agent-model-name-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-15-agent-page-polish-ledger.md](2026-10-15-agent-page-polish-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-15-agent-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-15-model-config-page-polish-ledger.md](2026-10-15-model-config-page-polish-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-15-model-config-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-15-v2-console-polish-ledger.md](2026-10-15-v2-console-polish-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-15-v2-console-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-26-chat-failure-feedback-ledger.md](2026-10-26-chat-failure-feedback-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-26-chat-failure-feedback-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-27-workspace-directory-selection-implementation-ledger.md](2026-10-27-workspace-directory-selection-implementation-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-27-workspace-directory-selection-implementation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-10-27-workspace-directory-selection-ledger.md](2026-10-27-workspace-directory-selection-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-10-27-workspace-directory-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-03-local-client-page-ledger.md](2026-11-03-local-client-page-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-03-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-03-projects-page-ledger.md](2026-11-03-projects-page-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-03-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-04-project-workspace-layout-ledger.md](2026-11-04-project-workspace-layout-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-04-project-workspace-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-05-local-client-page-ledger.md](2026-11-05-local-client-page-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-05-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-05-projects-page-ledger.md](2026-11-05-projects-page-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-05-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-05-shared-runtime-ledger.md](2026-11-05-shared-runtime-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-05-shared-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-09-client-project-workspace-delivery-ledger.md](2026-11-09-client-project-workspace-delivery-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-09-client-project-workspace-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-09-client-project-workspace-ledger.md](2026-11-09-client-project-workspace-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-09-client-project-workspace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-12-sites-v2-design-sync-ledger.md](2026-11-12-sites-v2-design-sync-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-12-sites-v2-design-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-13-doc-navigation-cleanup-ledger.md](2026-11-13-doc-navigation-cleanup-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-13-doc-navigation-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/progress/2026-11-13-doc-organization-followup-ledger.md](2026-11-13-doc-organization-followup-ledger.md) | 状态与证据 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-organization-followup-ledger.md#归档记录2026-11-13) |
| [superpowers/progress/2026-11-13-docs-full-review-ledger.md](2026-11-13-docs-full-review-ledger.md) | 状态与证据 | 本次 R0 工作包，按固定职责维护 |
| [superpowers/progress/2026-11-13-documentation-governance-ledger.md](2026-11-13-documentation-governance-ledger.md) | 状态与证据 | 原文保留并追加带日期核定；[依据](2026-11-13-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-09-30-next-iteration-analysis-prompt.md](../prompts/2026-09-30-next-iteration-analysis-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-09-30-next-iteration-analysis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-09-30-requirement-workpack-skill-prompt.md](../prompts/2026-09-30-requirement-workpack-skill-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-09-30-requirement-workpack-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-09-30-skill-sandbox-prompt.md](../prompts/2026-09-30-skill-sandbox-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-09-30-skill-sandbox-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-01-remote-docker-password-auth-prompt.md](../prompts/2026-10-01-remote-docker-password-auth-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-01-remote-docker-password-auth-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-08-jdbc-diagnostic-prompt.md](../prompts/2026-10-08-jdbc-diagnostic-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-08-jdbc-diagnostic-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-08-skill-artifacts-prompt.md](../prompts/2026-10-08-skill-artifacts-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-artifacts-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-08-skill-capacity-prompt.md](../prompts/2026-10-08-skill-capacity-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-08-skill-import-description-prompt.md](../prompts/2026-10-08-skill-import-description-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-import-description-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-08-skill-sandbox-config-docs-prompt.md](../prompts/2026-10-08-skill-sandbox-config-docs-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-sandbox-config-docs-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-08-skills-version-console-prompt.md](../prompts/2026-10-08-skills-version-console-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-08-skills-version-console-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-09-checkpoint-payload-capacity-prompt.md](../prompts/2026-10-09-checkpoint-payload-capacity-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-09-checkpoint-payload-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-09-docx-stdlib-skill-prompt.md](../prompts/2026-10-09-docx-stdlib-skill-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-09-docx-stdlib-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-09-server-lombok-support-prompt.md](../prompts/2026-10-09-server-lombok-support-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-09-server-lombok-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md](../prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-09-windows-sandbox-bootstrap-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-chat-page-ux-delivery-prompt.md](../prompts/2026-10-10-chat-page-ux-delivery-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-chat-page-ux-prompt.md](../prompts/2026-10-10-chat-page-ux-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-chat-stop-delivery-prompt.md](../prompts/2026-10-10-chat-stop-delivery-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-chat-stop-prompt.md](../prompts/2026-10-10-chat-stop-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-harness-202-compatibility-prompt.md](../prompts/2026-10-10-harness-202-compatibility-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-202-compatibility-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-harness-architecture-prompt.md](../prompts/2026-10-10-harness-architecture-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-architecture-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-harness-entry-hardening-prompt.md](../prompts/2026-10-10-harness-entry-hardening-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-entry-hardening-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-harness-t2-prompt.md](../prompts/2026-10-10-harness-t2-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t2-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-10-harness-t3-prompt.md](../prompts/2026-10-10-harness-t3-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t3-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-11-harness-t4-prompt.md](../prompts/2026-10-11-harness-t4-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-11-harness-t4-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-12-harness-controlled-enablement-prompt.md](../prompts/2026-10-12-harness-controlled-enablement-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-controlled-enablement-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-12-harness-drain-readiness-prompt.md](../prompts/2026-10-12-harness-drain-readiness-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-drain-readiness-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-12-harness-t5-prompt.md](../prompts/2026-10-12-harness-t5-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t5-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-12-harness-t6-prompt.md](../prompts/2026-10-12-harness-t6-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t6-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-15-agent-model-name-sync-prompt.md](../prompts/2026-10-15-agent-model-name-sync-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-model-name-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-15-agent-page-polish-prompt.md](../prompts/2026-10-15-agent-page-polish-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-15-model-config-page-polish-prompt.md](../prompts/2026-10-15-model-config-page-polish-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-15-model-config-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-15-v2-console-polish-prompt.md](../prompts/2026-10-15-v2-console-polish-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-15-v2-console-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-26-chat-failure-feedback-prompt.md](../prompts/2026-10-26-chat-failure-feedback-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-26-chat-failure-feedback-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-implementation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-10-27-workspace-directory-selection-prompt.md](../prompts/2026-10-27-workspace-directory-selection-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-03-local-client-page-prompt.md](../prompts/2026-11-03-local-client-page-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-03-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-03-projects-page-prompt.md](../prompts/2026-11-03-projects-page-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-03-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-04-project-workspace-layout-prompt.md](../prompts/2026-11-04-project-workspace-layout-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-04-project-workspace-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-05-local-client-page-prompt.md](../prompts/2026-11-05-local-client-page-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-05-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-05-projects-page-prompt.md](../prompts/2026-11-05-projects-page-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-05-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-05-shared-runtime-prompt.md](../prompts/2026-11-05-shared-runtime-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-05-shared-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-09-client-project-workspace-delivery-prompt.md](../prompts/2026-11-09-client-project-workspace-delivery-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-09-client-project-workspace-prompt.md](../prompts/2026-11-09-client-project-workspace-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-12-sites-v2-design-sync-prompt.md](../prompts/2026-11-12-sites-v2-design-sync-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-12-sites-v2-design-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-13-doc-navigation-cleanup-prompt.md](../prompts/2026-11-13-doc-navigation-cleanup-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-navigation-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/prompts/2026-11-13-doc-organization-followup-prompt.md](../prompts/2026-11-13-doc-organization-followup-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-organization-followup-ledger.md#归档记录2026-11-13) |
| [superpowers/prompts/2026-11-13-docs-full-review-prompt.md](../prompts/2026-11-13-docs-full-review-prompt.md) | 修订执行输入 | 本次 R0 工作包，按固定职责维护 |
| [superpowers/prompts/2026-11-13-documentation-governance-prompt.md](../prompts/2026-11-13-documentation-governance-prompt.md) | 修订执行输入 | 历史内容按原范围保留；[核定依据](2026-11-13-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/README.md](../README.md) | 当前导航 | 来源、职责与分类入口核对；不作为运行环境事实 |
| [superpowers/specs/2026-06-18-cm-agent-design.md](../specs/2026-06-18-cm-agent-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-06-18-cm-agent) |
| [superpowers/specs/2026-06-22-test-launch-profile-design.md](../specs/2026-06-22-test-launch-profile-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-06-22-test-launch-profile) |
| [superpowers/specs/2026-06-25-production-persistence-design.md](../specs/2026-06-25-production-persistence-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-06-25-production-persistence) |
| [superpowers/specs/2026-06-29-supabase-persistence-automation-design.md](../specs/2026-06-29-supabase-persistence-automation-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-06-29-supabase-persistence-automation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-07-13-server-yaml-variable-overrides-design.md](../specs/2026-07-13-server-yaml-variable-overrides-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-07-13-server-yaml-variable-overrides) |
| [superpowers/specs/2026-07-13-vm-database-profiles-design.md](../specs/2026-07-13-vm-database-profiles-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-07-13-vm-database-profiles) |
| [superpowers/specs/2026-07-14-phase-2-production-runtime-design.md](../specs/2026-07-14-phase-2-production-runtime-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-07-14-phase-2-production-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-07-16-phase-3-agentscope-runtime-design.md](../specs/2026-07-16-phase-3-agentscope-runtime-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-07-16-phase-3-agentscope-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-07-17-commit-file-export-design.md](../specs/2026-07-17-commit-file-export-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-07-17-commit-file-export) |
| [superpowers/specs/2026-07-17-console-capabilities-design.md](../specs/2026-07-17-console-capabilities-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-07-17-console-capabilities) |
| [superpowers/specs/2026-07-21-dynamic-http-mcp-tools-design.md](../specs/2026-07-21-dynamic-http-mcp-tools-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-07-21-dynamic-http-mcp-tools-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-07-28-tool-development-guide-examples-design.md](../specs/2026-07-28-tool-development-guide-examples-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-07-28-tool-development-guide-examples) |
| [superpowers/specs/2026-07-30-mysql-builtin-local-tools-design.md](../specs/2026-07-30-mysql-builtin-local-tools-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-07-30-mysql-builtin-local-tools) |
| [superpowers/specs/2026-07-31-tool-management-edit-delete-design.md](../specs/2026-07-31-tool-management-edit-delete-design.md) | 方案与决定 | 无原账本；[依据不足](#audit-2026-07-31-tool-management-edit-delete) |
| [superpowers/specs/2026-08-06-http-tool-parameter-definition-design.md](../specs/2026-08-06-http-tool-parameter-definition-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-06-http-tool-parameter-definition-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-07-superpowers-documentation-governance-design.md](../specs/2026-08-07-superpowers-documentation-governance-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-07-superpowers-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-10-error-diagnostics-development-standards-design.md](../specs/2026-08-10-error-diagnostics-development-standards-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-10-error-diagnostics-development-standards-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-10-global-error-diagnostics-design.md](../specs/2026-08-10-global-error-diagnostics-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-10-global-error-diagnostics-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-10-tool-debug-error-observability-design.md](../specs/2026-08-10-tool-debug-error-observability-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-10-tool-debug-error-observability-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-13-dashscope-mcp-tool-agent-example-design.md](../specs/2026-08-13-dashscope-mcp-tool-agent-example-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-13-dashscope-mcp-tool-agent-example-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-13-run-execution-service-run-comments-design.md](../specs/2026-08-13-run-execution-service-run-comments-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-13-run-execution-service-run-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-14-agentscope-context-agent-state-example-design.md](../specs/2026-08-14-agentscope-context-agent-state-example-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-14-agentscope-context-agent-state-example-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-17-agentscope-state-store-examples-design.md](../specs/2026-08-17-agentscope-state-store-examples-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-17-agentscope-state-store-examples-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-17-docker-compose-redis-design.md](../specs/2026-08-17-docker-compose-redis-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-17-docker-compose-redis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-18-agent-management-model-selection-design.md](../specs/2026-08-18-agent-management-model-selection-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-18-agent-management-model-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-18-console-v2-layout-design.md](../specs/2026-08-18-console-v2-layout-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-18-console-v2-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-18-console-versioned-multipage-design.md](../specs/2026-08-18-console-versioned-multipage-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-18-console-versioned-multipage-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-18-database-schema-comments-design.md](../specs/2026-08-18-database-schema-comments-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-18-database-schema-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-18-model-config-management-design.md](../specs/2026-08-18-model-config-management-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-18-model-config-management-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-19-console-run-streaming-design.md](../specs/2026-08-19-console-run-streaming-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-19-console-run-streaming-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-20-agentscope-adapter-comments-design.md](../specs/2026-08-20-agentscope-adapter-comments-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-20-agentscope-adapter-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-20-console-run-markdown-design.md](../specs/2026-08-20-console-run-markdown-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-20-console-run-markdown-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-20-console-runs-ui-design.md](../specs/2026-08-20-console-runs-ui-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-20-console-runs-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-20-console-tool-call-payload-ui-design.md](../specs/2026-08-20-console-tool-call-payload-ui-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-20-console-tool-call-payload-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-28-agentscope-studio-integration-design.md](../specs/2026-08-28-agentscope-studio-integration-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-28-agentscope-studio-integration-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-31-console-session-chat-page-design.md](../specs/2026-08-31-console-session-chat-page-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-31-console-session-chat-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-08-31-message-first-conversations-design.md](../specs/2026-08-31-message-first-conversations-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-08-31-message-first-conversations-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-01-adapter-comments-cleanup-design.md](../specs/2026-09-01-adapter-comments-cleanup-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-01-adapter-comments-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-01-console-chat-execution-trace-design.md](../specs/2026-09-01-console-chat-execution-trace-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-01-console-chat-execution-trace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-01-core-code-comments-design.md](../specs/2026-09-01-core-code-comments-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-01-core-code-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-01-core-module-readme-design.md](../specs/2026-09-01-core-module-readme-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-01-core-module-readme-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-01-enum-field-comments-design.md](../specs/2026-09-01-enum-field-comments-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-01-enum-field-comments-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-01-log-coverage-gaps-design.md](../specs/2026-09-01-log-coverage-gaps-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-01-log-coverage-gaps-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-01-mandatory-comment-generation-design.md](../specs/2026-09-01-mandatory-comment-generation-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-01-mandatory-comment-generation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-02-agentscope-permission-system-integration-design.md](../specs/2026-09-02-agentscope-permission-system-integration-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-02-agentscope-permission-system-integration-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-03-tool-approval-history-design.md](../specs/2026-09-03-tool-approval-history-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-03-tool-approval-history-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-03-tool-approval-ui-ux-design.md](../specs/2026-09-03-tool-approval-ui-ux-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-03-tool-approval-ui-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-16-console-prototype-alignment-design.md](../specs/2026-09-16-console-prototype-alignment-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-16-console-prototype-alignment-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-16-console-visual-interaction-polish-design.md](../specs/2026-09-16-console-visual-interaction-polish-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-16-console-visual-interaction-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-16-model-catalog-discovery-design.md](../specs/2026-09-16-model-catalog-discovery-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-16-model-catalog-discovery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-18-opencode-go-runtime-session-design.md](../specs/2026-09-18-opencode-go-runtime-session-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-18-opencode-go-runtime-session-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-20-first-agent-onboarding-design.md](../specs/2026-09-20-first-agent-onboarding-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-20-first-agent-onboarding-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-20-impeccable-design-documentation-design.md](../specs/2026-09-20-impeccable-design-documentation-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-design-documentation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-20-impeccable-live-setup-design.md](../specs/2026-09-20-impeccable-live-setup-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-live-setup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-20-impeccable-product-init-design.md](../specs/2026-09-20-impeccable-product-init-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-20-impeccable-product-init-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-20-skill-support-design.md](../specs/2026-09-20-skill-support-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-20-skill-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-20-tool-workspace-stages-design.md](../specs/2026-09-20-tool-workspace-stages-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-20-tool-workspace-stages-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-21-opencode-message-name-design.md](../specs/2026-09-21-opencode-message-name-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-21-opencode-message-name-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-21-skill-management-polish-design.md](../specs/2026-09-21-skill-management-polish-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-21-skill-management-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-22-skill-release-validation-design.md](../specs/2026-09-22-skill-release-validation-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-22-skill-release-validation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-28-skill-trial-agent-dropdown-design.md](../specs/2026-09-28-skill-trial-agent-dropdown-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-28-skill-trial-agent-dropdown-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-28-skill-trial-result-preview-design.md](../specs/2026-09-28-skill-trial-result-preview-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-28-skill-trial-result-preview-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-30-approval-expiry-a-design.md](../specs/2026-09-30-approval-expiry-a-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-30-approval-expiry-a-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-30-chat-approval-ui-design.md](../specs/2026-09-30-chat-approval-ui-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-30-chat-approval-ui-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-30-next-iteration-analysis-design.md](../specs/2026-09-30-next-iteration-analysis-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-30-next-iteration-analysis-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-30-requirement-workpack-skill-design.md](../specs/2026-09-30-requirement-workpack-skill-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-30-requirement-workpack-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-09-30-skill-sandbox-design.md](../specs/2026-09-30-skill-sandbox-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-09-30-skill-sandbox-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-01-remote-docker-password-auth-design.md](../specs/2026-10-01-remote-docker-password-auth-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-01-remote-docker-password-auth-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-08-jdbc-diagnostic-design.md](../specs/2026-10-08-jdbc-diagnostic-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-08-jdbc-diagnostic-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-08-skill-artifacts-design.md](../specs/2026-10-08-skill-artifacts-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-artifacts-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-08-skill-capacity-design.md](../specs/2026-10-08-skill-capacity-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-08-skill-import-description-design.md](../specs/2026-10-08-skill-import-description-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-import-description-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-08-skill-sandbox-config-docs-design.md](../specs/2026-10-08-skill-sandbox-config-docs-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-08-skill-sandbox-config-docs-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-08-skills-version-console-design.md](../specs/2026-10-08-skills-version-console-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-08-skills-version-console-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-09-checkpoint-payload-capacity-design.md](../specs/2026-10-09-checkpoint-payload-capacity-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-09-checkpoint-payload-capacity-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-09-docx-stdlib-skill-design.md](../specs/2026-10-09-docx-stdlib-skill-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-09-docx-stdlib-skill-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-09-server-lombok-support-design.md](../specs/2026-10-09-server-lombok-support-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-09-server-lombok-support-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-09-windows-sandbox-bootstrap-design.md](../specs/2026-10-09-windows-sandbox-bootstrap-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-09-windows-sandbox-bootstrap-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-chat-page-ux-delivery-design.md](../specs/2026-10-10-chat-page-ux-delivery-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-chat-page-ux-design.md](../specs/2026-10-10-chat-page-ux-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-page-ux-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-chat-stop-delivery-design.md](../specs/2026-10-10-chat-stop-delivery-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-chat-stop-design.md](../specs/2026-10-10-chat-stop-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-chat-stop-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-harness-202-compatibility-design.md](../specs/2026-10-10-harness-202-compatibility-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-202-compatibility-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-harness-architecture-design.md](../specs/2026-10-10-harness-architecture-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-architecture-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-harness-entry-hardening-design.md](../specs/2026-10-10-harness-entry-hardening-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-entry-hardening-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-harness-t2-design.md](../specs/2026-10-10-harness-t2-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t2-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-10-harness-t3-design.md](../specs/2026-10-10-harness-t3-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-10-harness-t3-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-11-harness-t4-design.md](../specs/2026-10-11-harness-t4-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-11-harness-t4-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-12-harness-controlled-enablement-design.md](../specs/2026-10-12-harness-controlled-enablement-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-controlled-enablement-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-12-harness-drain-readiness-design.md](../specs/2026-10-12-harness-drain-readiness-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-drain-readiness-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-12-harness-t5-design.md](../specs/2026-10-12-harness-t5-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t5-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-12-harness-t6-design.md](../specs/2026-10-12-harness-t6-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-12-harness-t6-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-15-agent-model-name-sync-design.md](../specs/2026-10-15-agent-model-name-sync-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-model-name-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-15-agent-page-polish-design.md](../specs/2026-10-15-agent-page-polish-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-15-agent-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-15-model-config-page-polish-design.md](../specs/2026-10-15-model-config-page-polish-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-15-model-config-page-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-15-v2-console-polish-design.md](../specs/2026-10-15-v2-console-polish-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-15-v2-console-polish-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-26-chat-failure-feedback-design.md](../specs/2026-10-26-chat-failure-feedback-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-26-chat-failure-feedback-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-27-workspace-directory-selection-design.md](../specs/2026-10-27-workspace-directory-selection-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-10-27-workspace-directory-selection-implementation-design.md](../specs/2026-10-27-workspace-directory-selection-implementation-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-10-27-workspace-directory-selection-implementation-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-03-local-client-page-design.md](../specs/2026-11-03-local-client-page-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-03-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-03-projects-page-design.md](../specs/2026-11-03-projects-page-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-03-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-04-project-workspace-layout-design.md](../specs/2026-11-04-project-workspace-layout-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-04-project-workspace-layout-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-05-local-client-page-design.md](../specs/2026-11-05-local-client-page-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-05-local-client-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-05-projects-page-design.md](../specs/2026-11-05-projects-page-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-05-projects-page-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-05-shared-runtime-design.md](../specs/2026-11-05-shared-runtime-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-05-shared-runtime-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-09-client-project-workspace-delivery-design.md](../specs/2026-11-09-client-project-workspace-delivery-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-delivery-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-09-client-project-workspace-design.md](../specs/2026-11-09-client-project-workspace-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-09-client-project-workspace-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-12-sites-v2-design-sync-design.md](../specs/2026-11-12-sites-v2-design-sync-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-12-sites-v2-design-sync-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-13-doc-navigation-cleanup-design.md](../specs/2026-11-13-doc-navigation-cleanup-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-navigation-cleanup-ledger.md#全量核定记录2026-11-13) |
| [superpowers/specs/2026-11-13-doc-organization-followup-design.md](../specs/2026-11-13-doc-organization-followup-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-13-doc-organization-followup-ledger.md#归档记录2026-11-13) |
| [superpowers/specs/2026-11-13-docs-full-review-design.md](../specs/2026-11-13-docs-full-review-design.md) | 方案与决定 | 本次 R0 工作包，按固定职责维护 |
| [superpowers/specs/2026-11-13-documentation-governance-design.md](../specs/2026-11-13-documentation-governance-design.md) | 方案与决定 | 历史内容按原范围保留；[核定依据](2026-11-13-documentation-governance-ledger.md#全量核定记录2026-11-13) |
| [superpowers/WORKPACK-STATUS.md](../WORKPACK-STATUS.md) | 当前导航 | 来源、职责与分类入口核对；不作为运行环境事实 |
| [technical-architecture.md](../../technical-architecture.md) | 当前使用或架构说明 | 当前与历史范围区分；运行行为仅对相关冲突作源码核对 |
| [tool-development-guide.md](../../tool-development-guide.md) | 当前使用或架构说明 | 更正常见错误中旧参数字段与 GET 请求体约束 |

</details>

## 验证与限制

- `git status --short --branch`、`git diff --cached --name-status`：以原有未提交修改为基线；HEAD 仍为 e7da291，暂存区保持开始时状态。
- 全部文件覆盖：docs 原 543 份及新增六份工作包，共 549 份 Markdown；逐文件表 549 行、路径唯一且与实际集合相同。全部正文按职责读取和解析，代码围栏无未闭合、无空文件；历史正文以原修订/日期为适用范围，未声称逐项重新验证所有业务行为。
- 全仓库 Markdown 解析复查：567 份文档中解析 4450 个链接，检查 902 处本地标题锚点，文件目标及本地锚点缺失 0；显式引用式链接的未定义候选为 0。包含普通链接、引用式链接、图片与 HTML 引用，排除代码内容，标题规则采用 GitHub 风格。
- 工作包核对：116 个主题与完整目录 116 行匹配；六类 48、四类 54、三类 8、两类 2、单篇 4，13 组无账本保持原集合。原 115 组人工按最新范围/验收及遗留核定为已归档 69、待续 30、待核实 16，本包当前；六份 R0、交互模式及 T0–T5 范围一致。
- 原文保护：101 个原账本只追加本次核定，新增标题前的完整字节 SHA-256 与开始时相同；前一已归档的 doc-organization-followup 账本完全未改。未移动、删除或补造任何旧工作包。
- 工作树范围：修改已有 docs 文件 106 个（101 个账本追加、5 个当前说明或导航定点调整），新增 docs 文件 6 个，删除 0 个；其余 1442 个已记录摘要保持不变，全部 docs 外已记录文件保持原样。HEAD 和暂存区未改变。
- `git -c core.safecrlf=false diff --check`：通过。检查工具与清单位于系统临时目录，不加入仓库。核定映射首轮因缺少三个页面主题说明而中止，补齐明确依据后再写入，未按关键词自动归档。

只做文档检查及必要的源码只读核对，未执行 Maven、业务、数据库、浏览器或模型调用，未重新启动/停止服务，未复验旧环境。CodeGraph 对设备项目属性未返回目标源码，已转向实际拥有该策略的配置类只读核对；未改变 HTTPS 或 profile 门禁。原有 14 个公共外链未能核验的限制保留，本轮未重新请求这些外链，未核实不等于失效。发布行为未变化，未改写发布说明中的历史发布事实。

## 提交与发布

未提交、未推送、未部署。


[清单](../checklists/2026-11-13-docs-full-review-checklist.md) · [提示词](../prompts/2026-11-13-docs-full-review-prompt.md) · [设计](../specs/2026-11-13-docs-full-review-design.md) · [计划](../plans/2026-11-13-docs-full-review.md) · [实现说明](../implementation/2026-11-13-docs-full-review-implementation-design.md)

## R1：16 组 Git 补证核定

用户要求继续核实原 16 个待核实主题，结合 Git 历史和现有文档，能确认的更新分类，其余列明原因。沿用同一日期、主题与六份路径，不新增旧账本或改写 R0 快照。新的归档只针对已证实责任范围，不代表重新运行历史测试或当前已部署。

### R1 任务状态

| 编号 | 状态 | 证据 |
|---|---|---|
| T6 | 完成 | 16 组本地可达 Git、文件历史和现有文档已核对；采纳证据逐个验证为 HEAD 祖先 |
| T7 | 完成 | 4 组归档、4 组待续、8 组仍待核实，逐组依据见下表 |
| T8 | 完成 | 三份原目标账本和一份共同账本只追加补证；目录与六份 R1 同步 |
| T9 | 完成 | 本地链接、锚点、分类计数、修订和范围保护检查通过 |

### R1 证据准则与口径

- 使用本地 `git log --all`、`git show`、重命名摘要及 `git merge-base --is-ancestor`；没有 fetch、网络查询或远端状态假设。主题日期不等于提交日期。
- 非 HEAD 祖先分支只作线索；例如 YAML 分支的 `54a1b8b` 不直接用于证明主线完成，而以包含同一实际进度记录的主线 `0b088b7` 为依据。
- 合并、代码和测试存在不等于测试通过；计划中的 Expected 命令也不是实际执行记录。只有同一缺口的明确修复/回归、明确责任范围及历史记录能改变分类。
- 6 月 25 日设计与 26 日计划由 29 日共同账本的 Spec/Plan 字段直接引用；不再把“无同日独立文件”等同没有任何账本。
- 对纯文档采纳及路径整理，本次核定只结束该文档责任；不声称相关业务验收通过。缺少旧账本的新结论保存在本节并注明来源，不伪造一个当时的进度文件。

### R1 逐组补证表

| 主题与核定 | Git 与文档证据 | 结论、剩余事项或不足 |
|---|---|---|
| <a id="git-audit-2026-09-20-skill-support"></a>2026-09-20-skill-support：**待续** | `ff3e2b24d0` feat: 完成技能前后端闭环与验收说明、`1107aadd7b` docs: 收口技能任务账本提交记录；[specs](../specs/2026-09-20-skill-support-design.md)、[plans](../plans/2026-09-20-skill-support.md)、[implementation](../implementation/2026-09-20-skill-support-implementation-design.md)、[progress](2026-09-20-skill-support-ledger.md) | 原设计第 11 节及计划 Task11 明确要求确定性 Provider Stub 和浏览器十步闭环；ff3e2b2/1107aadd7b 的实际记录明确未重跑该闭环，未发现明确取消或逐项移交。任务表的已完成不能覆盖仍存在的原验收要求。 |
| <a id="git-audit-2026-08-31-console-session-chat-page"></a>2026-08-31-console-session-chat-page：**待续** | `13a9aafaf2` feat(console): 新增会话聊天页并补充会话注释；[specs](../specs/2026-08-31-console-session-chat-page-design.md)、[plans](../plans/2026-08-31-console-session-chat-page.md)、[implementation](../implementation/2026-08-31-console-session-chat-page-implementation-design.md)、[progress](2026-08-31-console-session-chat-page-ledger.md) | 原计划明确列出 ConsoleSmokeTest，原账本明确未执行；13a9aaf 已交付页面和测试断言，但未发现对应版本的该项运行成功记录或范围移除依据。 |
| <a id="git-audit-2026-08-19-console-run-streaming"></a>2026-08-19-console-run-streaming：**已归档** | `655c33bab5` feat(console): 增加运行流式展示与详情渲染、`b25b7dc445` feat: 实现消息一等公民会话模型；[specs](../specs/2026-08-19-console-run-streaming-design.md)、[plans](../plans/2026-08-19-console-run-streaming.md)、[implementation](../implementation/2026-08-19-console-run-streaming-implementation-design.md)、[progress](2026-08-19-console-run-streaming-ledger.md) | 655c33b 的两项回归失败均是模型不可用错误消息旧断言；b25b7dc 的同一 RunControllerTest diff 逐项改成“模型配置不可用”，且该提交中的会话账本记录完整非容器回归通过。直接修复与回归记录形成闭环，不是仅依据后续相关功能或合并标题。 |
| <a id="git-audit-2026-08-03-tool-management-edit-delete"></a>2026-08-03-tool-management-edit-delete：**已归档** | `8e5a1e91e6` feat 修改文件路径；[implementation](../implementation/2026-08-03-tool-management-edit-delete-implementation-design.md) | 本主题只有实现说明文档。8e5a1e9 显示从 specs/implementation 到 implementation 的 100% 路径重命名；据此只确认该历史文档组织事项结束，不声称 7 月工具功能或验收因此完成。 |
| <a id="git-audit-2026-07-31-tool-management-edit-delete"></a>2026-07-31-tool-management-edit-delete：**归档状态待核实** | `fd070cb259` feat: 支持控制台工具编辑删除与解除关联、`1f907333ec` 修复工具更新与删除竞态、`cbb05b18a1` Merge pull request #14 from cmming/codex/tool-management-edit-delete、`780635ebcd` docs: 补齐任务技术实现文档；[specs](../specs/2026-07-31-tool-management-edit-delete-design.md)、[plans](../plans/2026-07-31-tool-management-edit-delete.md)、[implementation](../implementation/2026-07-31-tool-management-edit-delete-implementation-design.md) | 可定位工具编辑删除代码、并发修复和合并 cbb05b1，但计划 31 个步骤仍是预期输入，未找到本包所要求的最终 Node、Web 与 Rocky 持久化运行结果或明确收口；代码和测试被提交不等于测试已执行通过。 |
| <a id="git-audit-2026-07-30-mysql-builtin-local-tools"></a>2026-07-30-mysql-builtin-local-tools：**归档状态待核实** | `dfb0acde9f` Merge branch 'codex/mysql-builtin-local-tools'、`ab2ff9ee25` fix: 修复内置LOCAL示例最终审查问题、`780635ebcd` docs: 补齐任务技术实现文档；[specs](../specs/2026-07-30-mysql-builtin-local-tools-design.md)、[plans](../plans/2026-07-30-mysql-builtin-local-tools.md)、[implementation](../implementation/2026-07-30-mysql-builtin-local-tools-implementation-design.md) | dfb0acd 包含实现及测试，计划要求最终相同 HEAD 的 Rocky Maven 全量复验；现有文档未保存该次提交/环境对应的退出码或收口记录。780635e 仅补技术实现说明，不能代替验收。 |
| <a id="git-audit-2026-07-28-tool-development-guide-examples"></a>2026-07-28-tool-development-guide-examples：**归档状态待核实** | `d80ff64dff` Merge pull request #12 from cmming/codex/tool-development-guide、`782610ad6b` feat: 添加 HTTP 工具客户端示例、`17bcd39fe1` feat: 完善 LOCAL 多工具示例、`780635ebcd` docs: 补齐任务技术实现文档；[specs](../specs/2026-07-28-tool-development-guide-examples-design.md)、[plans](../plans/2026-07-28-tool-development-guide-examples.md)、[implementation](../implementation/2026-07-28-tool-development-guide-examples-implementation-design.md) | d80ff64/782610a/17bcd39 证明示例和测试已交付；计划中的执行器、客户端及最终回归记录未找到。技术说明列验证顺序不等于实际命令结果。 |
| <a id="git-audit-2026-07-17-console-capabilities"></a>2026-07-17-console-capabilities：**归档状态待核实** | `904ed419fb` fix: 收口控制台兼容性与使用说明、`7054b7c871` Merge pull request #10 from cmming/codex/console-capabilities、`780635ebcd` docs: 补齐任务技术实现文档；[specs](../specs/2026-07-17-console-capabilities-design.md)、[plans](../plans/2026-07-17-console-capabilities.md)、[implementation](../implementation/2026-07-17-console-capabilities-implementation-design.md) | 904ed41 与合并 7054b7c 能证明 UI 和兼容修改已落入 HEAD，但原计划要求的 ConsoleSmokeTest/Rocky 与最终操作验证未找到同范围结果；不以合并替代功能验收。 |
| <a id="git-audit-2026-07-17-commit-file-export"></a>2026-07-17-commit-file-export：**归档状态待核实** | `1bfcdb27b9` feat: 支持导出指定提交文件、`99a4a8a60a` docs: 说明提交文件导出脚本、`780635ebcd` docs: 补齐任务技术实现文档；[specs](../specs/2026-07-17-commit-file-export-design.md)、[plans](../plans/2026-07-17-commit-file-export.md)、[implementation](../implementation/2026-07-17-commit-file-export-implementation-design.md) | 1bfcdb2 提交导出脚本和行为测试，99a4a8a 提交说明；未找到测试实际输出/退出码或明确责任结束记录。测试脚本中的成功打印语句不证明历史执行发生。 |
| <a id="git-audit-2026-07-13-vm-database-profiles"></a>2026-07-13-vm-database-profiles：**归档状态待核实** | `97c903d4c6` Merge pull request #5 from cmming/codex/vm-database-profiles、`f1dd7e9574` docs: plan vm database profiles、`780635ebcd` docs: 补齐任务技术实现文档；[specs](../specs/2026-07-13-vm-database-profiles-design.md)、[plans](../plans/2026-07-13-vm-database-profiles.md)、[implementation](../implementation/2026-07-13-vm-database-profiles-implementation-design.md) | 97c903d 含 postgres/mysql profile、混用保护和测试，现有计划末尾仍只有 Expected 结果；未找到对应 ApplicationProfileConfigurationTest/最终验证实际结果。两条非 HEAD 分支文档记录不作为主线完成证据。 |
| <a id="git-audit-2026-07-13-server-yaml-variable-overrides"></a>2026-07-13-server-yaml-variable-overrides：**已归档** | `0b088b7f9c` refactor: 使用 profile 变量组织服务端配置、`f94d9e28f9` Merge pull request #4 from cmming/codex/yaml-profile-config；[specs](../specs/2026-07-13-server-yaml-variable-overrides-design.md)、[plans](../plans/2026-07-13-server-yaml-variable-overrides.md)、[implementation](../implementation/2026-07-13-server-yaml-variable-overrides-implementation-design.md) | 0b088b7 主线提交内的原计划 Task4 Progress ledger 逐项记录 JDBC、JWT、ConsoleSmoke、Auth 及 reactor 实际通过，含 server 61 tests/0 failures、7 模块 SUCCESS。该记录随主线配置实现进入 HEAD，足以确认此历史重构范围收口。 |
| <a id="git-audit-2026-06-26-production-persistence"></a>2026-06-26-production-persistence：**待续** | `37c6b4a856` docs: 添加生产持久化实施计划、`4592be30a0` docs: 记录生产持久化验证阻塞、`d04dfcad47` fix: 避免 JDBC 配置覆盖全局 ObjectMapper、`497514aeca` Merge pull request #2 from cmming/codex/production-persistence；[plans](../plans/2026-06-26-production-persistence.md)；[确切共同账本](2026-06-29-production-persistence-ledger.md#git-补证核定2026-11-13) | 2026-06-29 原账本的 Plan 字段精确指向本计划，Task6 明确 partially verified/docker blocked；4592be3 及 d04dfca 的记录未关闭容器验证。因此找到真实共同账本，已能确认待续，而不是只因缺少同日账本而未知。 |
| <a id="git-audit-2026-06-25-production-persistence"></a>2026-06-25-production-persistence：**待续** | `dca80ae716` docs: 设计生产化持久化切片、`4592be30a0` docs: 记录生产持久化验证阻塞、`497514aeca` Merge pull request #2 from cmming/codex/production-persistence；[specs](../specs/2026-06-25-production-persistence-design.md)、[implementation](../implementation/2026-06-25-production-persistence-implementation-design.md)；[确切共同账本](2026-06-29-production-persistence-ledger.md#git-补证核定2026-11-13) | 2026-06-29 原账本的 Spec 字段精确指向本设计，并列出实现提交及未完成的 Task6 Docker 验证；设计与实施范围的承接明确，仍缺该范围的最终容器收口。 |
| <a id="git-audit-2026-06-22-test-launch-profile"></a>2026-06-22-test-launch-profile：**归档状态待核实** | `6a31015090` feat: 添加测试启动 profile 配置、`e13a610247` fix: 收紧 bootstrap profile 判断并中文化计划、`b76473295c` Merge pull request #1 from cmming/cm-agent-first-slice、`780635ebcd` docs: 补齐任务技术实现文档；[specs](../specs/2026-06-22-test-launch-profile-design.md)、[plans](../plans/2026-06-22-test-launch-profile.md)、[implementation](../implementation/2026-06-22-test-launch-profile-implementation-design.md) | 6a31015/e13a610 和 b764732 证明测试 profile、安全修复及合并；当前计划只有预期最终命令，未找到当时精确范围的执行结果、审查结论或明确验收收口。 |
| <a id="git-audit-2026-06-18-cm-agent-first-slice"></a>2026-06-18-cm-agent-first-slice：**归档状态待核实** | `a99bc87a99` docs: add CM Agent implementation plan、`d3b398e456` docs: 添加中文生产文档和 Starter 示例、`b76473295c` Merge pull request #1 from cmming/cm-agent-first-slice；[plans](../plans/2026-06-18-cm-agent-first-slice.md) | b764732 包含薄纵切及后续 profile 变更，计划覆盖 12 个任务和双库/全量构建；未找到该计划最终 mvn test/package/审查的实际结果。后续阶段完成不能自动证明这一原范围逐项验收。 |
| <a id="git-audit-2026-06-18-cm-agent"></a>2026-06-18-cm-agent：**已归档** | `4ce3bbb4fb` docs: translate design spec to Chinese、`a99bc87a99` docs: add CM Agent implementation plan；[specs](../specs/2026-06-18-cm-agent-design.md)、[implementation](../implementation/2026-06-18-cm-agent-implementation-design.md) | 4ce3bbb 中设计已经明确“已批准进入实施计划”，其后 a99bc87 形成实施计划。仅按该设计文档形成与采纳的责任归档；不把第一阶段全部功能或 first-slice 的未证实验收写成通过。 |

### R1 当前分类汇总

原待核实 16 组中：4 组更新为已归档，4 组更新为待续，8 组继续待核实。全目录共 116 组：已归档 73、待续 34、归档状态待核实 8、当前 1。原 13 组没有同日同主题的独立账本这一文件事实不变；其中 2 组已确认共同账本，3 组按 Git 证据核定文档责任，其余 8 组不足逐项说明。

### R1 验证与限制

- 只读历史命令：`git log --all --date=short --format=... -- <paths>`、`git show`、`git show --summary --find-renames` 及 `git merge-base --is-ancestor`。16 组均已定位文档历史，最终采纳的 34 个不同提交均是当前 HEAD 的祖先；未把非祖先分支的完成描述当主线事实。
- 直接证据核对：b25b7dc 中两处失败断言的修复与同提交非容器回归记录；0b088b7 中 Task4 实际进度与 server 61 tests/7 模块 SUCCESS；8e5a1e9 的 100% 文档重命名；4ce3bbb 的设计已批准状态。它们只支持表中限定范围，不证明整个系统当前通过。
- 共同账本核对：6 月 29 日账本文首精确引用 6 月 25 日设计及 26 日计划，原 Task6 仍保留 Docker 验证阻塞；两组改为待续，未补造历史文件。
- 本地文档检查：567 份 Markdown 中解析 4506 个链接，检查 915 处本地标题锚点，文件目标与本地锚点缺失 0。原 R0 快照和 549 文件覆盖保持原意；R1 新补证覆盖 16 组且结论为 4 已归档、4 待续、8 待核实。
- 修改保护：开始时摘要共 1554 个文件，本轮仅 11 份预定 docs 文件改变，新增/删除 0；其余 1543 个摘要不变。三份原目标账本和一份共同账本的新增标题之前完整字节与开始时相同；HEAD 与暂存区未改变。
- 六份 R1、`mode=interactive` 和 T0–T9 范围一致；完整目录仍是 116 个主题。`git -c core.safecrlf=false diff --check` 通过，检查脚本与临时证据保存在系统临时目录，不加入仓库。

本轮未运行 Maven、浏览器、数据库、历史脚本或模型，没有重做旧验收；只核对本地可达 Git 历史和现有文档，未 fetch 或查询远端 CI。因此未发现的结果只能记为缺少证据，不能断言历史测试从未执行。已记录公共外链限制未重新核验。未提交、未推送、未部署，未改变发布行为或发布说明。
