# Superpowers 工作包状态目录

快照日期：2026-11-13。本目录覆盖当时盘点的工作包主题，不包含后续新增主题；后续入口见[工作包索引](README.md#2026-11-13-之后的工作包)。

## 统计口径

- 原始审查基线：112 组。
- 本次盘点：116 组。在原 115 组基础上新增 `2026-11-13-docs-full-review`，其余主题及原文件集合保留。
- 文档集合：六类 48 组、四类 54 组、三类 8 组、两类 2 组、单篇 4 组。
- 按同日同主题命名没有独立账本的原 13 组保持文件集合不变：2 组已确认跨日期共同账本，3 组以本次 Git 补证核定文档责任，8 组继续待核实；不补造旧账本。
- 历史 Git 快照曾记录 8 组有未提交修改或未跟踪文件；该统计不含后续工作包。
- 当前修订只读取文档显式声明；没有声明时显示“未声明”。
- 每个主题的实时状态和验证证据以最新账本为准；本目录只做跨主题索引。
- Git 栏保留 2026-11-13 原工作树快照，不代表实时状态；实际状态使用 `git status` 核对，不要求每次工作树变化都更新本目录。

## 归档导航

R1 对原 16 个待核实主题结合 Git 补证，4 组改为已归档、4 组改为待续，8 组仍缺充分依据。2026-11-13 快照共 116 组：已归档 73、待续 34、归档状态待核实 8、当前 1。

分类是账本核定的导航投影，不另写任务状态或测试结果。[R0 全量快照](progress/2026-11-13-docs-full-review-ledger.md#逐组核定快照)、[R1 Git 补证](progress/2026-11-13-docs-full-review-ledger.md#r116-组-git-补证核定)及[逐文件覆盖](progress/2026-11-13-docs-full-review-ledger.md#逐文件覆盖)分别保存各修订的范围与限制。

### 当前

- [docs 全量整理](progress/2026-11-13-docs-full-review-ledger.md#工作包归档记录)

### 待续

| 主题 | 待续事项或核定依据 |
|---|---|
| [2026-11-09-client-project-workspace-delivery](progress/2026-11-09-client-project-workspace-delivery-ledger.md#全量核定记录2026-11-13) | R9 的 T25 原生窗口期限仍待真实新进程验收，T9/T17 故障矩阵未整体收口。 |
| [2026-11-05-shared-runtime](progress/2026-11-05-shared-runtime-ledger.md#全量核定记录2026-11-13) | 遗留的 Maven 3.9+ Linux 矩阵未完成，不能用既有主机 Maven 成功替代。 |
| [2026-11-05-projects-page](progress/2026-11-05-projects-page-ledger.md#全量核定记录2026-11-13) | 真实认证远端项目完整操作与修复后局域网浏览器复测未验收。 |
| [2026-11-04-project-workspace-layout](progress/2026-11-04-project-workspace-layout-ledger.md#全量核定记录2026-11-13) | 原项目页 T3 真实验收仍进行中；合并来源的已完成任务不覆盖它。 |
| [2026-11-03-local-client-page](progress/2026-11-03-local-client-page-ledger.md#全量核定记录2026-11-13) | T7/T8/T9/T17 的真实设备、Linux/macOS、远程 TLS 等门禁仍明确待续。 |
| [2026-10-27-workspace-directory-selection-implementation](progress/2026-10-27-workspace-directory-selection-implementation-ledger.md#全量核定记录2026-11-13) | T11–T14 整体验收未完成，R3 文档迁移完成不能替代业务门禁。 |
| [2026-10-15-agent-model-name-sync](progress/2026-10-15-agent-model-name-sync-ledger.md#全量核定记录2026-11-13) | 原账本明确修复后在线页面及真实供应商调用未验收，未见同范围收口。 |
| [2026-10-10-harness-202-compatibility](progress/2026-10-10-harness-202-compatibility-ledger.md#全量核定记录2026-11-13) | T1 安全门禁尚未全部满足，G1/G2 记录缺陷特征；不能用特征测试转绿代替修复验收。 |
| [2026-10-10-chat-page-ux-delivery](progress/2026-10-10-chat-page-ux-delivery-ledger.md#全量核定记录2026-11-13) | T4/T6 部分与 T7 真实 IME、缩放及实际服务烟测仍待验收。 |
| [2026-10-09-windows-sandbox-bootstrap](progress/2026-10-09-windows-sandbox-bootstrap-ledger.md#全量核定记录2026-11-13) | T4 用户现场 TEST/下载闭环仍待部署后复验。 |
| [2026-10-09-docx-stdlib-skill](progress/2026-10-09-docx-stdlib-skill-ledger.md#全量核定记录2026-11-13) | T8 部署使用阶段真实候选/TEST/下载仍待做。 |
| [2026-10-08-skills-version-console](progress/2026-10-08-skills-version-console-ledger.md#全量核定记录2026-11-13) | T5 原生浏览器 ZIP 选择上传未完成；数据库原包导入不替代该验收。 |
| [2026-10-08-skill-artifacts](progress/2026-10-08-skill-artifacts-ledger.md#全量核定记录2026-11-13) | T7 真实模型验收与 X1/X3 未关闭边界仍保留。 |
| [2026-09-30-chat-approval-ui](progress/2026-09-30-chat-approval-ui-ledger.md#全量核定记录2026-11-13) | 真实登录后的浏览器视觉验收仍待可用会话补做。 |
| [2026-09-28-skill-trial-result-preview](progress/2026-09-28-skill-trial-result-preview-ledger.md#全量核定记录2026-11-13) | 真实模型/工具及非空工具摘要仍列为待另行验收；Fake NOT_TRIGGERED 只证明门禁。 |
| [2026-09-22-skill-release-validation](progress/2026-09-22-skill-release-validation-ledger.md#全量核定记录2026-11-13) | 成功试运行、发布、审批恢复、固定版本和回滚的浏览器端到端尚未完成。 |
| [2026-09-20-skill-support](progress/2026-09-20-skill-support-ledger.md#git-补证核定2026-11-13) | 原设计第 11 节及计划 Task11 明确要求确定性 Provider Stub 和浏览器十步闭环；ff3e2b2/1107aadd7b 的实际记录明确未重跑该闭环，未发现明确取消或逐项移交。任务表的已完成不能覆盖仍存在的原验收要求。 |
| [2026-09-20-impeccable-live-setup](progress/2026-09-20-impeccable-live-setup-ledger.md#全量核定记录2026-11-13) | Live 属临时会话注入，用户退出后的清理没有完成证据。 |
| [2026-09-03-tool-approval-ui-ux](progress/2026-09-03-tool-approval-ui-ux-ledger.md#全量核定记录2026-11-13) | 重启后真实 HIGH 操作、混合决定及断线/键盘验收仍待续；不能用另一历史主题自动替代。 |
| [2026-09-03-tool-approval-history](progress/2026-09-03-tool-approval-history-ledger.md#全量核定记录2026-11-13) | 真实页面端到端和服务进程重启未验收，Repository 重建不替代。 |
| [2026-09-02-agentscope-permission-system-integration](progress/2026-09-02-agentscope-permission-system-integration-ledger.md#全量核定记录2026-11-13) | 专门 JDBC 租户/并发用例与浏览器、重启/撤销演练仍明确未完成。 |
| [2026-09-01-log-coverage-gaps](progress/2026-09-01-log-coverage-gaps-ledger.md#全量核定记录2026-11-13) | 原账本要求同版本 Rocky 双库集成补验，尚无本包收口；全量失败与本轮目标通过分别保留。 |
| [2026-08-31-console-session-chat-page](progress/2026-08-31-console-session-chat-page-ledger.md#git-补证核定2026-11-13) | 原计划明确列出 ConsoleSmokeTest，原账本明确未执行；13a9aaf 已交付页面和测试断言，但未发现对应版本的该项运行成功记录或范围移除依据。 |
| [2026-08-18-model-config-management](progress/2026-08-18-model-config-management-ledger.md#全量核定记录2026-11-13) | JdbcModelConfigRepositoryTest 与双库 CRUD/V9 注释验证仍待符合基线的 Rocky 环境。 |
| [2026-08-18-agent-management-model-selection](progress/2026-08-18-agent-management-model-selection-ledger.md#全量核定记录2026-11-13) | 原自动化状态排除容器集成测试，完整容器验收未见收口。 |
| [2026-08-17-docker-compose-redis](progress/2026-08-17-docker-compose-redis-ledger.md#全量核定记录2026-11-13) | 远程构建环境核对仅部分完成，不能视为完整 JDK21/Maven3.9 验证。 |
| [2026-08-17-agentscope-state-store-examples](progress/2026-08-17-agentscope-state-store-examples-ledger.md#全量核定记录2026-11-13) | 真实模型与 Redis 联调记录为未执行。 |
| [2026-08-14-agentscope-context-agent-state-example](progress/2026-08-14-agentscope-context-agent-state-example-ledger.md#全量核定记录2026-11-13) | 真实模型调用结果仍待有有效凭据环境确认。 |
| [2026-08-13-dashscope-mcp-tool-agent-example](progress/2026-08-13-dashscope-mcp-tool-agent-example-ledger.md#全量核定记录2026-11-13) | 使用者环境中 MCP 依赖修复后复测和完整模型回复仍未完成。 |
| [2026-07-21-dynamic-http-mcp-tools](progress/2026-07-21-dynamic-http-mcp-tools-ledger.md#全量核定记录2026-11-13) | 端到端任务记录容器验证待 Rocky，不能按实现已完成归档该验收。 |
| [2026-06-29-supabase-persistence-automation](progress/2026-06-29-supabase-persistence-automation-ledger.md#全量核定记录2026-11-13) | Supabase 开发分支验证被当时套餐阻塞，Task4 未完成；未查询当前套餐，也未在主项目执行 DDL。 |
| [2026-06-29-production-persistence](progress/2026-06-29-production-persistence-ledger.md#全量核定记录2026-11-13) | 历史 JDBC/Testcontainers 验证因 Docker 不可用未完成；当前必须按 Rocky 规则补验，不继续旧 Docker Desktop 指引。 |
| [2026-06-26-production-persistence](progress/2026-06-29-production-persistence-ledger.md#git-补证核定2026-11-13) | 2026-06-29 原账本的 Plan 字段精确指向本计划，Task6 明确 partially verified/docker blocked；4592be3 及 d04dfca 的记录未关闭容器验证。因此找到真实共同账本，已能确认待续，而不是只因缺少同日账本而未知。 |
| [2026-06-25-production-persistence](progress/2026-06-29-production-persistence-ledger.md#git-补证核定2026-11-13) | 2026-06-29 原账本的 Spec 字段精确指向本设计，并列出实现提交及未完成的 Task6 Docker 验证；设计与实施范围的承接明确，仍缺该范围的最终容器收口。 |

### 已归档

<details>
<summary>展开 73 组责任已结束的历史工作包</summary>

| 主题 | 责任结束依据摘要 |
|---|---|
| [2026-11-13-documentation-governance](progress/2026-11-13-documentation-governance-ledger.md#全量核定记录2026-11-13) | 已确认的 16 份治理规则与验证全部记录完成，本轮另作责任结束核定，不修改原当前结论的形成时语义。 |
| [2026-11-13-doc-organization-followup](progress/2026-11-13-doc-organization-followup-ledger.md#归档记录2026-11-13) | 原账本已有用户确认的归档事件，维持原结论与 14 个外链核验限制。 |
| [2026-11-13-doc-navigation-cleanup](progress/2026-11-13-doc-navigation-cleanup-ledger.md#全量核定记录2026-11-13) | T0–T21 文档导航、事实刷新与职责迁移已完成；本包文档责任结束，不替各业务包关闭门禁。 |
| [2026-11-12-sites-v2-design-sync](progress/2026-11-12-sites-v2-design-sync-ledger.md#全量核定记录2026-11-13) | T0–T3 静态设计预览同步与文档交付已结束；移动端与真实后端另行验收，不外推 Sites 当前状态。 |
| [2026-11-09-client-project-workspace](progress/2026-11-09-client-project-workspace-ledger.md#全量核定记录2026-11-13) | 本包明确只交付 R2 需求文档，业务 T0–T18 待做不属于本轮分析实施；关联交付包仍单独待续。 |
| [2026-11-05-local-client-page](progress/2026-11-05-local-client-page-ledger.md#全量核定记录2026-11-13) | T0–T5 输入 Schema/解析修复与实际设备回报有记录，本包范围完成。 |
| [2026-11-03-projects-page](progress/2026-11-03-projects-page-ledger.md#全量核定记录2026-11-13) | 项目页本轮 T0–T12 范围完成；其他平台、真实读屏及后续设备项目验收不被外推。 |
| [2026-10-27-workspace-directory-selection](progress/2026-10-27-workspace-directory-selection-ledger.md#全量核定记录2026-11-13) | 本包限定仅交付需求分析文档；实际实施由独立实施包记录，后者 T11–T14 未完成不伪写通过。 |
| [2026-10-26-chat-failure-feedback](progress/2026-10-26-chat-failure-feedback-ledger.md#全量核定记录2026-11-13) | 前端反馈及 R1 模型错误摘要的约定定向验证已记录，既有设计元数据漂移属于原范围外限制。 |
| [2026-10-15-v2-console-polish](progress/2026-10-15-v2-console-polish-ledger.md#全量核定记录2026-11-13) | 九页统一样式 T0–T6 与提交前定向复核完成，仅保留形成时浏览器范围。 |
| [2026-10-15-model-config-page-polish](progress/2026-10-15-model-config-page-polish-ledger.md#全量核定记录2026-11-13) | 模型配置页 R1 宽屏与原 R0 目标验证完成，不外推真实模型或其他页面。 |
| [2026-10-15-agent-page-polish](progress/2026-10-15-agent-page-polish-ledger.md#全量核定记录2026-11-13) | Agent 页 R1 宽屏与原 R0 目标验证完成，不外推全部角色及业务状态。 |
| [2026-10-12-harness-t6](progress/2026-10-12-harness-t6-ledger.md#全量核定记录2026-11-13) | 本次 T6 固定协议、浏览器与回退验证已记录完成；T7/T8 生产启用需独立范围。 |
| [2026-10-12-harness-t5](progress/2026-10-12-harness-t5-ledger.md#全量核定记录2026-11-13) | T5 的双库及 Linux 句柄验证已补齐；真实 Provider、浏览器和 T6 不被其替代。 |
| [2026-10-12-harness-drain-readiness](progress/2026-10-12-harness-drain-readiness-ledger.md#全量核定记录2026-11-13) | 当前采用 G11–G14 已完成；G5/G9/G10 有明确移出范围记录，未宣称 Rocky 生产已部署。 |
| [2026-10-12-harness-controlled-enablement](progress/2026-10-12-harness-controlled-enablement-ledger.md#全量核定记录2026-11-13) | 原范围是时钟校准与准入评估，账本明确两者完成且实际生产准入不通过。 |
| [2026-10-11-harness-t4](progress/2026-10-11-harness-t4-ledger.md#全量核定记录2026-11-13) | T4 固定 Skill/沙箱/产物治理与受控集成验证已有完成证据，真实外部服务不外推。 |
| [2026-10-10-harness-t3](progress/2026-10-10-harness-t3-ledger.md#全量核定记录2026-11-13) | T3 生产审批/状态/停止及双库合同已完成；历史时钟及跨主机 TTL 限制保留。 |
| [2026-10-10-harness-t2](progress/2026-10-10-harness-t2-ledger.md#全量核定记录2026-11-13) | T2 工厂、隔离与模式装配范围完成；T3–T6 是独立后续阶段，不宣称由本包通过。 |
| [2026-10-10-harness-entry-hardening](progress/2026-10-10-harness-entry-hardening-ledger.md#全量核定记录2026-11-13) | 显式拒绝及安全入口加固范围完成；原完整安全准入和后续工厂阶段不由本包替代。 |
| [2026-10-10-harness-architecture](progress/2026-10-10-harness-architecture-ledger.md#全量核定记录2026-11-13) | 本包仅架构分析和依赖草案，T0/T9 文档范围完成，不把 T1–T8 未来业务任务记为已实现。 |
| [2026-10-10-chat-stop-delivery](progress/2026-10-10-chat-stop-delivery-ledger.md#全量核定记录2026-11-13) | 停止交付 T0–T6 与提交前复核已记录完成，保留外部副作用不能回滚的限制。 |
| [2026-10-10-chat-stop](progress/2026-10-10-chat-stop-ledger.md#全量核定记录2026-11-13) | 只交付停止需求分析与用户选择，实施由独立交付包记录，不回填本分析的未来业务任务。 |
| [2026-10-10-chat-page-ux](progress/2026-10-10-chat-page-ux-ledger.md#全量核定记录2026-11-13) | 聊天交互需求分析及 D1/D2 文档验收完成；独立交付包的 T7 仍待续。 |
| [2026-10-09-server-lombok-support](progress/2026-10-09-server-lombok-support-ledger.md#全量核定记录2026-11-13) | Lombok 接入本包 T0–T3 编译/配置范围完成；共享提交尾注的现场 Skill 验收不属于 Lombok 结论。 |
| [2026-10-09-checkpoint-payload-capacity](progress/2026-10-09-checkpoint-payload-capacity-ledger.md#全量核定记录2026-11-13) | 检查点容量迁移、双库与文件交付定向验收完成；保留形成时提交记录。 |
| [2026-10-08-skill-sandbox-config-docs](progress/2026-10-08-skill-sandbox-config-docs-ledger.md#全量核定记录2026-11-13) | T0–T2 配置文档与属性/profile 对齐完成；不表示本轮复验容器执行。 |
| [2026-10-08-skill-import-description](progress/2026-10-08-skill-import-description-ledger.md#全量核定记录2026-11-13) | 长描述存储扩容与定向验证、后续职责迁移已完成；浏览器上传阻塞属于独立 UI 范围。 |
| [2026-10-08-skill-capacity](progress/2026-10-08-skill-capacity-ledger.md#全量核定记录2026-11-13) | 容量配置、解析与双库接口专项完成；不把浏览器 ZIP 未验或真实脚本未执行外推为通过。 |
| [2026-10-08-jdbc-diagnostic](progress/2026-10-08-jdbc-diagnostic-ledger.md#全量核定记录2026-11-13) | 日志边界、受控接口与双库驱动专项完成；仓库外证据不可用及 UI 上传限制保留。 |
| [2026-10-01-remote-docker-password-auth](progress/2026-10-01-remote-docker-password-auth-ledger.md#全量核定记录2026-11-13) | 当前 R2 职责迁移与已记录 R1 SSH 密码实现范围完成，旧 R0 待做文本保留为历史。 |
| [2026-09-30-skill-sandbox](progress/2026-09-30-skill-sandbox-ledger.md#全量核定记录2026-11-13) | 原沙箱 T0–T11 与文档迁移任务已完成，条件跳过及不含生产/强化 runtime 的范围保留。 |
| [2026-09-30-requirement-workpack-skill](progress/2026-09-30-requirement-workpack-skill-ledger.md#全量核定记录2026-11-13) | 技能生成、迭代与快捷问答文档范围完成，文字模拟不外推为通用业务实测。 |
| [2026-09-30-next-iteration-analysis](progress/2026-09-30-next-iteration-analysis-ledger.md#全量核定记录2026-11-13) | 本包交付后续需求分析文档，后续业务未被此分析授权或验收。 |
| [2026-09-30-approval-expiry-a](progress/2026-09-30-approval-expiry-a-ledger.md#全量核定记录2026-11-13) | 主动过期 A 批次 T0–T8 与合并复核完成，保留不推送部署的原边界。 |
| [2026-09-28-skill-trial-agent-dropdown](progress/2026-09-28-skill-trial-agent-dropdown-ledger.md#全量核定记录2026-11-13) | 目标 Agent 下拉、桌面/窄屏与资源验证已完成，不涉及数据库或真实试运行。 |
| [2026-09-21-skill-management-polish](progress/2026-09-21-skill-management-polish-ledger.md#全量核定记录2026-11-13) | 技能管理布局任务已交付并有提交记录。 |
| [2026-09-21-opencode-message-name](progress/2026-09-21-opencode-message-name-ledger.md#全量核定记录2026-11-13) | 消息名称兼容和会话路由修复及目标验证已记录完成。 |
| [2026-09-20-tool-workspace-stages](progress/2026-09-20-tool-workspace-stages-ledger.md#全量核定记录2026-11-13) | 工具工作区分阶段交互与后续连续修复验证已记录完成。 |
| [2026-09-20-impeccable-product-init](progress/2026-09-20-impeccable-product-init-ledger.md#全量核定记录2026-11-13) | 本包仅产品上下文和技能初始化文档；Live 可用性是独立后续范围。 |
| [2026-09-20-impeccable-design-documentation](progress/2026-09-20-impeccable-design-documentation-ledger.md#全量核定记录2026-11-13) | 设计规范归档文档及布局核对完成；当时资源错误不在设计文档范围内。 |
| [2026-09-20-first-agent-onboarding](progress/2026-09-20-first-agent-onboarding-ledger.md#全量核定记录2026-11-13) | 首个 Agent 引导和静态响应式验收已有完成记录。 |
| [2026-09-18-opencode-go-runtime-session](progress/2026-09-18-opencode-go-runtime-session-ledger.md#全量核定记录2026-11-13) | 运行会话路由及状态槽告警目标修复与验证已记录完成。 |
| [2026-09-16-model-catalog-discovery](progress/2026-09-16-model-catalog-discovery-ledger.md#全量核定记录2026-11-13) | 模型目录发现的实现、安全/权限与目标验证已记录完成。 |
| [2026-09-16-console-visual-interaction-polish](progress/2026-09-16-console-visual-interaction-polish-ledger.md#全量核定记录2026-11-13) | 视觉交互调整及真实业务页面目标验证已记录完成。 |
| [2026-09-16-console-prototype-alignment](progress/2026-09-16-console-prototype-alignment-ledger.md#全量核定记录2026-11-13) | 静态原型对齐与资源结构验证完成，未外推数据库或真实模型。 |
| [2026-09-01-mandatory-comment-generation](progress/2026-09-01-mandatory-comment-generation-ledger.md#全量核定记录2026-11-13) | 强制注释规则文本与范围复核完成，原账本无遗留。 |
| [2026-09-01-enum-field-comments](progress/2026-09-01-enum-field-comments-ledger.md#全量核定记录2026-11-13) | 枚举及 core record 注释约定范围已完成，其他模块历史治理明确不在范围。 |
| [2026-09-01-core-module-readme](progress/2026-09-01-core-module-readme-ledger.md#全量核定记录2026-11-13) | core README 源码核对和文档交付完成，原账本无遗留。 |
| [2026-09-01-core-code-comments](progress/2026-09-01-core-code-comments-ledger.md#全量核定记录2026-11-13) | core 注释、编译与模块测试完成；用户配置与其他任务保留。 |
| [2026-09-01-console-chat-execution-trace](progress/2026-09-01-console-chat-execution-trace-ledger.md#全量核定记录2026-11-13) | 思考/工具轨迹及后续载荷展示目标验证完成；全量受环境影响的历史失败单独保留。 |
| [2026-09-01-adapter-comments-cleanup](progress/2026-09-01-adapter-comments-cleanup-ledger.md#全量核定记录2026-11-13) | adapter 注释整改与模块联编测试完成，无关主工作区变更保留。 |
| [2026-08-31-message-first-conversations](progress/2026-08-31-message-first-conversations-ledger.md#全量核定记录2026-11-13) | 消息一等公民的约定实现与远程双库验证完成，未交付能力是明确范围外。 |
| [2026-08-28-agentscope-studio-integration](progress/2026-08-28-agentscope-studio-integration-ledger.md#全量核定记录2026-11-13) | Studio 条件装配、安全保护及目标配置验证完成，开发者自行启动外部 Studio 属使用条件。 |
| [2026-08-20-console-tool-call-payload-ui](progress/2026-08-20-console-tool-call-payload-ui-ledger.md#全量核定记录2026-11-13) | 工具载荷 UI 布局及模块验证完成。 |
| [2026-08-20-console-runs-ui](progress/2026-08-20-console-runs-ui-ledger.md#全量核定记录2026-11-13) | 运行页 UI 及基础流式展示目标验证完成。 |
| [2026-08-20-console-run-markdown](progress/2026-08-20-console-run-markdown-ledger.md#全量核定记录2026-11-13) | 受限 Markdown 渲染及目标验证完成。 |
| [2026-08-20-agentscope-adapter-comments](progress/2026-08-20-agentscope-adapter-comments-ledger.md#全量核定记录2026-11-13) | adapter 目标 JavaDoc 与模块验证完成，上游 core 聚合 JavaDoc 既有错误明确在本包外。 |
| [2026-08-19-console-run-streaming](progress/2026-08-19-console-run-streaming-ledger.md#git-补证核定2026-11-13) | 655c33b 的两项回归失败均是模型不可用错误消息旧断言；b25b7dc 的同一 RunControllerTest diff 逐项改成“模型配置不可用”，且该提交中的会话账本记录完整非容器回归通过。直接修复与回归记录形成闭环，不是仅依据后续相关功能或合并标题。 |
| [2026-08-18-database-schema-comments](progress/2026-08-18-database-schema-comments-ledger.md#全量核定记录2026-11-13) | 双方言数据库注释与 Rocky 迁移验证完成，升级前备份属于使用条件。 |
| [2026-08-18-console-versioned-multipage](progress/2026-08-18-console-versioned-multipage-ledger.md#全量核定记录2026-11-13) | 多页导航、Cookie 恢复与真实页面刷新/退出验证已记录完成。 |
| [2026-08-18-console-v2-layout](progress/2026-08-18-console-v2-layout-ledger.md#全量核定记录2026-11-13) | v2 布局和快捷操作范围完成。 |
| [2026-08-13-run-execution-service-run-comments](progress/2026-08-13-run-execution-service-run-comments-ledger.md#全量核定记录2026-11-13) | run 方法调用链注释与隔离验证完成。 |
| [2026-08-10-tool-debug-error-observability](progress/2026-08-10-tool-debug-error-observability-ledger.md#全量核定记录2026-11-13) | 调试失败原因、编号与日志关联目标验证完成，集中日志与追踪明确在范围外。 |
| [2026-08-10-global-error-diagnostics](progress/2026-08-10-global-error-diagnostics-ledger.md#全量核定记录2026-11-13) | 全局错误编号和诊断目标已完成，集中告警与分布式追踪属于后续范围。 |
| [2026-08-10-error-diagnostics-development-standards](progress/2026-08-10-error-diagnostics-development-standards-ledger.md#全量核定记录2026-11-13) | 错误诊断工程规范文档及格式复核完成，未承诺历史全仓治理。 |
| [2026-08-07-superpowers-documentation-governance](progress/2026-08-07-superpowers-documentation-governance-ledger.md#全量核定记录2026-11-13) | 四文档治理规则当时范围完成；新六件套规则保存在独立治理 ADR，不回写旧决定。 |
| [2026-08-06-http-tool-parameter-definition](progress/2026-08-06-http-tool-parameter-definition-ledger.md#全量核定记录2026-11-13) | 参数模型、空参数 HTTP、双方言与浏览器验证已记录完成，旧映射历史保留。 |
| [2026-08-03-tool-management-edit-delete](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-08-03-tool-management-edit-delete) | 本主题只有实现说明文档。8e5a1e9 显示从 specs/implementation 到 implementation 的 100% 路径重命名；据此只确认该历史文档组织事项结束，不声称 7 月工具功能或验收因此完成。 |
| [2026-07-16-phase-3-agentscope-runtime](progress/2026-07-16-phase-3-agentscope-runtime-ledger.md#全量核定记录2026-11-13) | 阶段三各任务、最终 Rocky 全量测试及打包有完成证据，历史 AgentScope 版本不改写成当前版本。 |
| [2026-07-14-phase-2-production-runtime](progress/2026-07-14-phase-2-production-runtime-ledger.md#全量核定记录2026-11-13) | 阶段二范围及 8 月 MCP 夹具回归在 Rocky 全量验证完成，不把形成时提交状态改成现状。 |
| [2026-07-13-server-yaml-variable-overrides](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-07-13-server-yaml-variable-overrides) | 0b088b7 主线提交内的原计划 Task4 Progress ledger 逐项记录 JDBC、JWT、ConsoleSmoke、Auth 及 reactor 实际通过，含 server 61 tests/0 failures、7 模块 SUCCESS。该记录随主线配置实现进入 HEAD，足以确认此历史重构范围收口。 |
| [2026-06-18-cm-agent](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-06-18-cm-agent) | 4ce3bbb 中设计已经明确“已批准进入实施计划”，其后 a99bc87 形成实施计划。仅按该设计文档形成与采纳的责任归档；不把第一阶段全部功能或 first-slice 的未证实验收写成通过。 |

</details>

### 归档状态待核实

以下 8 组均已查阅可达 Git 提交与现有文档，但尚缺最终运行结果或明确责任结束记录。实现/测试代码或合并存在不等于验收通过；逐项补证细节见链接。

| 主题 | 仍缺少的依据 |
|---|---|
| [2026-07-31-tool-management-edit-delete](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-07-31-tool-management-edit-delete) | 可定位工具编辑删除代码、并发修复和合并 cbb05b1，但计划 31 个步骤仍是预期输入，未找到本包所要求的最终 Node、Web 与 Rocky 持久化运行结果或明确收口；代码和测试被提交不等于测试已执行通过。 |
| [2026-07-30-mysql-builtin-local-tools](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-07-30-mysql-builtin-local-tools) | dfb0acd 包含实现及测试，计划要求最终相同 HEAD 的 Rocky Maven 全量复验；现有文档未保存该次提交/环境对应的退出码或收口记录。780635e 仅补技术实现说明，不能代替验收。 |
| [2026-07-28-tool-development-guide-examples](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-07-28-tool-development-guide-examples) | d80ff64/782610a/17bcd39 证明示例和测试已交付；计划中的执行器、客户端及最终回归记录未找到。技术说明列验证顺序不等于实际命令结果。 |
| [2026-07-17-console-capabilities](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-07-17-console-capabilities) | 904ed41 与合并 7054b7c 能证明 UI 和兼容修改已落入 HEAD，但原计划要求的 ConsoleSmokeTest/Rocky 与最终操作验证未找到同范围结果；不以合并替代功能验收。 |
| [2026-07-17-commit-file-export](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-07-17-commit-file-export) | 1bfcdb2 提交导出脚本和行为测试，99a4a8a 提交说明；未找到测试实际输出/退出码或明确责任结束记录。测试脚本中的成功打印语句不证明历史执行发生。 |
| [2026-07-13-vm-database-profiles](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-07-13-vm-database-profiles) | 97c903d 含 postgres/mysql profile、混用保护和测试，现有计划末尾仍只有 Expected 结果；未找到对应 ApplicationProfileConfigurationTest/最终验证实际结果。两条非 HEAD 分支文档记录不作为主线完成证据。 |
| [2026-06-22-test-launch-profile](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-06-22-test-launch-profile) | 6a31015/e13a610 和 b764732 证明测试 profile、安全修复及合并；当前计划只有预期最终命令，未找到当时精确范围的执行结果、审查结论或明确验收收口。 |
| [2026-06-18-cm-agent-first-slice](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-06-18-cm-agent-first-slice) | b764732 包含薄纵切及后续 profile 变更，计划覆盖 12 个任务和双库/全量构建；未找到该计划最终 mvn test/package/审查的实际结果。后续阶段完成不能自动证明这一原范围逐项验收。 |

无旧账本的本次核定由 R1 账本保留真实 Git 来源，不重建历史状态；找到明确共同账本的主题返回共同账本。核定与恢复先留痕，再同步导航，规则见[产物契约](../../.agents/skills/requirement-workpack/references/artifact-contract.md#工作包归档与恢复)。

## 历史 Git 快照说明

| 状态 | 含义 |
|---|---|
| 已纳入版本控制 | 快照时该组现有文件没有未提交修改或未跟踪项 |
| 未提交修改 N | 快照时已有跟踪文件存在修改、增加、删除或重命名 |
| 未跟踪 N | 快照时该组包含工作树中新文件 |
| 修改 N / 未跟踪 M | 快照时两种状态同时存在 |
| 见 Git | 未记录历史快照，按需查询实际工作树 |

## 后续入口说明

“后续入口”优先链接提示词，其次计划、账本、清单、设计或实现说明。提示词只记录形成时的执行输入，不构成新的执行授权；继续工作前必须先读取最新账本并核对 Git 工作树。

## 2026-11

| 日期 | 主题 | 集合 | 当前修订 | 最新账本 | 历史 Git 快照 | 后续入口 |
|---|---|---|---|---|---|---|
| 2026-11-13 | `docs-full-review` | 六类 | R1 | [账本](progress/2026-11-13-docs-full-review-ledger.md) | 见 Git | [提示词](prompts/2026-11-13-docs-full-review-prompt.md) |
| 2026-11-13 | `documentation-governance` | 六类 | R0 | [账本](progress/2026-11-13-documentation-governance-ledger.md) | 见 Git | [提示词](prompts/2026-11-13-documentation-governance-prompt.md) |
| 2026-11-13 | `doc-organization-followup` | 六类 | R1 | [账本](progress/2026-11-13-doc-organization-followup-ledger.md) | 见 Git | [提示词](prompts/2026-11-13-doc-organization-followup-prompt.md) |
| 2026-11-13 | `doc-navigation-cleanup` | 六类 | R4 | [账本](progress/2026-11-13-doc-navigation-cleanup-ledger.md) | 未跟踪 6 | [提示词](prompts/2026-11-13-doc-navigation-cleanup-prompt.md) |
| 2026-11-12 | `sites-v2-design-sync` | 六类 | 未声明 | [账本](progress/2026-11-12-sites-v2-design-sync-ledger.md) | 未跟踪 6 | [提示词](prompts/2026-11-12-sites-v2-design-sync-prompt.md) |
| 2026-11-09 | `client-project-workspace` | 六类 | R2 | [账本](progress/2026-11-09-client-project-workspace-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-11-09-client-project-workspace-prompt.md) |
| 2026-11-09 | `client-project-workspace-delivery` | 六类 | R9 | [账本](progress/2026-11-09-client-project-workspace-delivery-ledger.md) | 未提交修改 6 | [提示词](prompts/2026-11-09-client-project-workspace-delivery-prompt.md) |
| 2026-11-05 | `local-client-page` | 六类 | R0 | [账本](progress/2026-11-05-local-client-page-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-11-05-local-client-page-prompt.md) |
| 2026-11-05 | `projects-page` | 六类 | R1 | [账本](progress/2026-11-05-projects-page-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-11-05-projects-page-prompt.md) |
| 2026-11-05 | `shared-runtime` | 六类 | R0 | [账本](progress/2026-11-05-shared-runtime-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-11-05-shared-runtime-prompt.md) |
| 2026-11-04 | `project-workspace-layout` | 六类 | R3 | [账本](progress/2026-11-04-project-workspace-layout-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-11-04-project-workspace-layout-prompt.md) |
| 2026-11-03 | `local-client-page` | 六类 | R3 | [账本](progress/2026-11-03-local-client-page-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-11-03-local-client-page-prompt.md) |
| 2026-11-03 | `projects-page` | 六类 | R1 | [账本](progress/2026-11-03-projects-page-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-11-03-projects-page-prompt.md) |

## 2026-10

| 日期 | 主题 | 集合 | 当前修订 | 最新账本 | 历史 Git 快照 | 后续入口 |
|---|---|---|---|---|---|---|
| 2026-10-27 | `workspace-directory-selection` | 六类 | R0 | [账本](progress/2026-10-27-workspace-directory-selection-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-27-workspace-directory-selection-prompt.md) |
| 2026-10-27 | `workspace-directory-selection-implementation` | 六类 | R3 | [账本](progress/2026-10-27-workspace-directory-selection-implementation-ledger.md) | 未提交修改 6 | [提示词](prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md) |
| 2026-10-26 | `chat-failure-feedback` | 六类 | R1 | [账本](progress/2026-10-26-chat-failure-feedback-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-26-chat-failure-feedback-prompt.md) |
| 2026-10-15 | `agent-model-name-sync` | 六类 | R0 | [账本](progress/2026-10-15-agent-model-name-sync-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-15-agent-model-name-sync-prompt.md) |
| 2026-10-15 | `agent-page-polish` | 六类 | R1 | [账本](progress/2026-10-15-agent-page-polish-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-15-agent-page-polish-prompt.md) |
| 2026-10-15 | `model-config-page-polish` | 六类 | R1 | [账本](progress/2026-10-15-model-config-page-polish-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-15-model-config-page-polish-prompt.md) |
| 2026-10-15 | `v2-console-polish` | 六类 | R0 | [账本](progress/2026-10-15-v2-console-polish-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-15-v2-console-polish-prompt.md) |
| 2026-10-12 | `harness-controlled-enablement` | 六类 | R0 | [账本](progress/2026-10-12-harness-controlled-enablement-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-12-harness-controlled-enablement-prompt.md) |
| 2026-10-12 | `harness-drain-readiness` | 六类 | R1 | [账本](progress/2026-10-12-harness-drain-readiness-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-12-harness-drain-readiness-prompt.md) |
| 2026-10-12 | `harness-t5` | 六类 | R0 | [账本](progress/2026-10-12-harness-t5-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-12-harness-t5-prompt.md) |
| 2026-10-12 | `harness-t6` | 六类 | R0 | [账本](progress/2026-10-12-harness-t6-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-12-harness-t6-prompt.md) |
| 2026-10-11 | `harness-t4` | 六类 | R0 | [账本](progress/2026-10-11-harness-t4-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-11-harness-t4-prompt.md) |
| 2026-10-10 | `chat-page-ux` | 六类 | R0 | [账本](progress/2026-10-10-chat-page-ux-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-chat-page-ux-prompt.md) |
| 2026-10-10 | `chat-page-ux-delivery` | 六类 | R0 | [账本](progress/2026-10-10-chat-page-ux-delivery-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-chat-page-ux-delivery-prompt.md) |
| 2026-10-10 | `chat-stop` | 六类 | R0 | [账本](progress/2026-10-10-chat-stop-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-chat-stop-prompt.md) |
| 2026-10-10 | `chat-stop-delivery` | 六类 | R0 | [账本](progress/2026-10-10-chat-stop-delivery-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-chat-stop-delivery-prompt.md) |
| 2026-10-10 | `harness-202-compatibility` | 六类 | R0 | [账本](progress/2026-10-10-harness-202-compatibility-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-harness-202-compatibility-prompt.md) |
| 2026-10-10 | `harness-architecture` | 六类 | R1 | [账本](progress/2026-10-10-harness-architecture-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-harness-architecture-prompt.md) |
| 2026-10-10 | `harness-entry-hardening` | 六类 | R0 | [账本](progress/2026-10-10-harness-entry-hardening-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-harness-entry-hardening-prompt.md) |
| 2026-10-10 | `harness-t2` | 六类 | R0 | [账本](progress/2026-10-10-harness-t2-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-harness-t2-prompt.md) |
| 2026-10-10 | `harness-t3` | 六类 | R0 | [账本](progress/2026-10-10-harness-t3-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-10-harness-t3-prompt.md) |
| 2026-10-09 | `checkpoint-payload-capacity` | 六类 | R0 | [账本](progress/2026-10-09-checkpoint-payload-capacity-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-09-checkpoint-payload-capacity-prompt.md) |
| 2026-10-09 | `docx-stdlib-skill` | 六类 | R1 | [账本](progress/2026-10-09-docx-stdlib-skill-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-09-docx-stdlib-skill-prompt.md) |
| 2026-10-09 | `server-lombok-support` | 六类 | R0 | [账本](progress/2026-10-09-server-lombok-support-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-09-server-lombok-support-prompt.md) |
| 2026-10-09 | `windows-sandbox-bootstrap` | 六类 | R0 | [账本](progress/2026-10-09-windows-sandbox-bootstrap-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md) |
| 2026-10-08 | `jdbc-diagnostic` | 六类 | R0 | [账本](progress/2026-10-08-jdbc-diagnostic-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-08-jdbc-diagnostic-prompt.md) |
| 2026-10-08 | `skill-artifacts` | 六类 | R1 | [账本](progress/2026-10-08-skill-artifacts-ledger.md) | 未提交修改 6 | [提示词](prompts/2026-10-08-skill-artifacts-prompt.md) |
| 2026-10-08 | `skill-capacity` | 六类 | R0 | [账本](progress/2026-10-08-skill-capacity-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-08-skill-capacity-prompt.md) |
| 2026-10-08 | `skill-import-description` | 六类 | R1 | [账本](progress/2026-10-08-skill-import-description-ledger.md) | 未提交修改 6 | [提示词](prompts/2026-10-08-skill-import-description-prompt.md) |
| 2026-10-08 | `skill-sandbox-config-docs` | 六类 | R0 | [账本](progress/2026-10-08-skill-sandbox-config-docs-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-08-skill-sandbox-config-docs-prompt.md) |
| 2026-10-08 | `skills-version-console` | 六类 | R1 | [账本](progress/2026-10-08-skills-version-console-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-10-08-skills-version-console-prompt.md) |
| 2026-10-01 | `remote-docker-password-auth` | 六类 | R2 | [账本](progress/2026-10-01-remote-docker-password-auth-ledger.md) | 未提交修改 6 | [提示词](prompts/2026-10-01-remote-docker-password-auth-prompt.md) |

## 2026-09

| 日期 | 主题 | 集合 | 当前修订 | 最新账本 | 历史 Git 快照 | 后续入口 |
|---|---|---|---|---|---|---|
| 2026-09-30 | `approval-expiry-a` | 四类 | 未声明 | [账本](progress/2026-09-30-approval-expiry-a-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-30-approval-expiry-a.md) |
| 2026-09-30 | `chat-approval-ui` | 四类 | 未声明 | [账本](progress/2026-09-30-chat-approval-ui-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-30-chat-approval-ui.md) |
| 2026-09-30 | `next-iteration-analysis` | 六类 | 未声明 | [账本](progress/2026-09-30-next-iteration-analysis-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-09-30-next-iteration-analysis-prompt.md) |
| 2026-09-30 | `requirement-workpack-skill` | 六类 | R1 | [账本](progress/2026-09-30-requirement-workpack-skill-ledger.md) | 已纳入版本控制 | [提示词](prompts/2026-09-30-requirement-workpack-skill-prompt.md) |
| 2026-09-30 | `skill-sandbox` | 六类 | R2 | [账本](progress/2026-09-30-skill-sandbox-ledger.md) | 未提交修改 6 | [提示词](prompts/2026-09-30-skill-sandbox-prompt.md) |
| 2026-09-28 | `skill-trial-agent-dropdown` | 四类 | 未声明 | [账本](progress/2026-09-28-skill-trial-agent-dropdown-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-28-skill-trial-agent-dropdown.md) |
| 2026-09-28 | `skill-trial-result-preview` | 四类 | 未声明 | [账本](progress/2026-09-28-skill-trial-result-preview-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-28-skill-trial-result-preview.md) |
| 2026-09-22 | `skill-release-validation` | 四类 | 未声明 | [账本](progress/2026-09-22-skill-release-validation-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-22-skill-release-validation.md) |
| 2026-09-21 | `opencode-message-name` | 四类 | 未声明 | [账本](progress/2026-09-21-opencode-message-name-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-21-opencode-message-name.md) |
| 2026-09-21 | `skill-management-polish` | 四类 | 未声明 | [账本](progress/2026-09-21-skill-management-polish-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-21-skill-management-polish.md) |
| 2026-09-20 | `first-agent-onboarding` | 四类 | 未声明 | [账本](progress/2026-09-20-first-agent-onboarding-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-20-first-agent-onboarding.md) |
| 2026-09-20 | `impeccable-design-documentation` | 四类 | 未声明 | [账本](progress/2026-09-20-impeccable-design-documentation-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-20-impeccable-design-documentation.md) |
| 2026-09-20 | `impeccable-live-setup` | 四类 | 未声明 | [账本](progress/2026-09-20-impeccable-live-setup-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-20-impeccable-live-setup.md) |
| 2026-09-20 | `impeccable-product-init` | 四类 | 未声明 | [账本](progress/2026-09-20-impeccable-product-init-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-20-impeccable-product-init.md) |
| 2026-09-20 | `skill-support` | 四类 | 未声明 | [账本](progress/2026-09-20-skill-support-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-20-skill-support.md) |
| 2026-09-20 | `tool-workspace-stages` | 四类 | 未声明 | [账本](progress/2026-09-20-tool-workspace-stages-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-20-tool-workspace-stages.md) |
| 2026-09-18 | `opencode-go-runtime-session` | 四类 | 未声明 | [账本](progress/2026-09-18-opencode-go-runtime-session-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-18-opencode-go-runtime-session.md) |
| 2026-09-16 | `console-prototype-alignment` | 四类 | 未声明 | [账本](progress/2026-09-16-console-prototype-alignment-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-16-console-prototype-alignment.md) |
| 2026-09-16 | `console-visual-interaction-polish` | 四类 | 未声明 | [账本](progress/2026-09-16-console-visual-interaction-polish-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-16-console-visual-interaction-polish.md) |
| 2026-09-16 | `model-catalog-discovery` | 四类 | 未声明 | [账本](progress/2026-09-16-model-catalog-discovery-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-16-model-catalog-discovery.md) |
| 2026-09-03 | `tool-approval-history` | 四类 | 未声明 | [账本](progress/2026-09-03-tool-approval-history-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-03-tool-approval-history.md) |
| 2026-09-03 | `tool-approval-ui-ux` | 四类 | 未声明 | [账本](progress/2026-09-03-tool-approval-ui-ux-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-03-tool-approval-ui-ux.md) |
| 2026-09-02 | `agentscope-permission-system-integration` | 四类 | 未声明 | [账本](progress/2026-09-02-agentscope-permission-system-integration-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-02-agentscope-permission-system-integration.md) |
| 2026-09-01 | `adapter-comments-cleanup` | 四类 | 未声明 | [账本](progress/2026-09-01-adapter-comments-cleanup-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-01-adapter-comments-cleanup.md) |
| 2026-09-01 | `console-chat-execution-trace` | 四类 | 未声明 | [账本](progress/2026-09-01-console-chat-execution-trace-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-01-console-chat-execution-trace.md) |
| 2026-09-01 | `core-code-comments` | 四类 | 未声明 | [账本](progress/2026-09-01-core-code-comments-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-01-core-code-comments.md) |
| 2026-09-01 | `core-module-readme` | 四类 | 未声明 | [账本](progress/2026-09-01-core-module-readme-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-01-core-module-readme.md) |
| 2026-09-01 | `enum-field-comments` | 四类 | 未声明 | [账本](progress/2026-09-01-enum-field-comments-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-01-enum-field-comments.md) |
| 2026-09-01 | `log-coverage-gaps` | 四类 | 未声明 | [账本](progress/2026-09-01-log-coverage-gaps-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-01-log-coverage-gaps.md) |
| 2026-09-01 | `mandatory-comment-generation` | 四类 | 未声明 | [账本](progress/2026-09-01-mandatory-comment-generation-ledger.md) | 已纳入版本控制 | [计划](plans/2026-09-01-mandatory-comment-generation.md) |

## 2026-08

| 日期 | 主题 | 集合 | 当前修订 | 最新账本 | 历史 Git 快照 | 后续入口 |
|---|---|---|---|---|---|---|
| 2026-08-31 | `console-session-chat-page` | 四类 | 未声明 | [账本](progress/2026-08-31-console-session-chat-page-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-31-console-session-chat-page.md) |
| 2026-08-31 | `message-first-conversations` | 四类 | 未声明 | [账本](progress/2026-08-31-message-first-conversations-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-31-message-first-conversations.md) |
| 2026-08-28 | `agentscope-studio-integration` | 四类 | 未声明 | [账本](progress/2026-08-28-agentscope-studio-integration-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-28-agentscope-studio-integration.md) |
| 2026-08-20 | `agentscope-adapter-comments` | 四类 | 未声明 | [账本](progress/2026-08-20-agentscope-adapter-comments-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-20-agentscope-adapter-comments.md) |
| 2026-08-20 | `console-run-markdown` | 四类 | 未声明 | [账本](progress/2026-08-20-console-run-markdown-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-20-console-run-markdown.md) |
| 2026-08-20 | `console-runs-ui` | 四类 | 未声明 | [账本](progress/2026-08-20-console-runs-ui-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-20-console-runs-ui.md) |
| 2026-08-20 | `console-tool-call-payload-ui` | 四类 | 未声明 | [账本](progress/2026-08-20-console-tool-call-payload-ui-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-20-console-tool-call-payload-ui.md) |
| 2026-08-19 | `console-run-streaming` | 四类 | 未声明 | [账本](progress/2026-08-19-console-run-streaming-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-19-console-run-streaming.md) |
| 2026-08-18 | `agent-management-model-selection` | 四类 | 未声明 | [账本](progress/2026-08-18-agent-management-model-selection-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-18-agent-management-model-selection.md) |
| 2026-08-18 | `console-v2-layout` | 四类 | 未声明 | [账本](progress/2026-08-18-console-v2-layout-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-18-console-v2-layout.md) |
| 2026-08-18 | `console-versioned-multipage` | 四类 | 未声明 | [账本](progress/2026-08-18-console-versioned-multipage-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-18-console-versioned-multipage.md) |
| 2026-08-18 | `database-schema-comments` | 四类 | 未声明 | [账本](progress/2026-08-18-database-schema-comments-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-18-database-schema-comments.md) |
| 2026-08-18 | `model-config-management` | 四类 | 未声明 | [账本](progress/2026-08-18-model-config-management-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-18-model-config-management.md) |
| 2026-08-17 | `agentscope-state-store-examples` | 四类 | 未声明 | [账本](progress/2026-08-17-agentscope-state-store-examples-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-17-agentscope-state-store-examples.md) |
| 2026-08-17 | `docker-compose-redis` | 四类 | 未声明 | [账本](progress/2026-08-17-docker-compose-redis-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-17-docker-compose-redis.md) |
| 2026-08-14 | `agentscope-context-agent-state-example` | 四类 | 未声明 | [账本](progress/2026-08-14-agentscope-context-agent-state-example-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-14-agentscope-context-agent-state-example.md) |
| 2026-08-13 | `dashscope-mcp-tool-agent-example` | 四类 | 未声明 | [账本](progress/2026-08-13-dashscope-mcp-tool-agent-example-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-13-dashscope-mcp-tool-agent-example.md) |
| 2026-08-13 | `run-execution-service-run-comments` | 四类 | 未声明 | [账本](progress/2026-08-13-run-execution-service-run-comments-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-13-run-execution-service-run-comments.md) |
| 2026-08-10 | `error-diagnostics-development-standards` | 四类 | 未声明 | [账本](progress/2026-08-10-error-diagnostics-development-standards-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-10-error-diagnostics-development-standards.md) |
| 2026-08-10 | `global-error-diagnostics` | 四类 | 未声明 | [账本](progress/2026-08-10-global-error-diagnostics-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-10-global-error-diagnostics.md) |
| 2026-08-10 | `tool-debug-error-observability` | 四类 | 未声明 | [账本](progress/2026-08-10-tool-debug-error-observability-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-10-tool-debug-error-observability.md) |
| 2026-08-07 | `superpowers-documentation-governance` | 四类 | 未声明 | [账本](progress/2026-08-07-superpowers-documentation-governance-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-07-superpowers-documentation-governance.md) |
| 2026-08-06 | `http-tool-parameter-definition` | 四类 | 未声明 | [账本](progress/2026-08-06-http-tool-parameter-definition-ledger.md) | 已纳入版本控制 | [计划](plans/2026-08-06-http-tool-parameter-definition.md) |
| 2026-08-03 | `tool-management-edit-delete` | 单篇 | 未声明 | [本次核定](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-08-03-tool-management-edit-delete) | 已纳入版本控制 | [实现](implementation/2026-08-03-tool-management-edit-delete-implementation-design.md) |

## 2026-07

| 日期 | 主题 | 集合 | 当前修订 | 最新账本 | 历史 Git 快照 | 后续入口 |
|---|---|---|---|---|---|---|
| 2026-07-31 | `tool-management-edit-delete` | 三类 | 未声明 | — | 已纳入版本控制 | [计划](plans/2026-07-31-tool-management-edit-delete.md) |
| 2026-07-30 | `mysql-builtin-local-tools` | 三类 | 未声明 | — | 已纳入版本控制 | [计划](plans/2026-07-30-mysql-builtin-local-tools.md) |
| 2026-07-28 | `tool-development-guide-examples` | 三类 | 未声明 | — | 已纳入版本控制 | [计划](plans/2026-07-28-tool-development-guide-examples.md) |
| 2026-07-21 | `dynamic-http-mcp-tools` | 四类 | 未声明 | [账本](progress/2026-07-21-dynamic-http-mcp-tools-ledger.md) | 已纳入版本控制 | [计划](plans/2026-07-21-dynamic-http-mcp-tools.md) |
| 2026-07-17 | `commit-file-export` | 三类 | 未声明 | — | 已纳入版本控制 | [计划](plans/2026-07-17-commit-file-export.md) |
| 2026-07-17 | `console-capabilities` | 三类 | 未声明 | — | 已纳入版本控制 | [计划](plans/2026-07-17-console-capabilities.md) |
| 2026-07-16 | `phase-3-agentscope-runtime` | 四类 | 未声明 | [账本](progress/2026-07-16-phase-3-agentscope-runtime-ledger.md) | 已纳入版本控制 | [计划](plans/2026-07-16-phase-3-agentscope-runtime.md) |
| 2026-07-14 | `phase-2-production-runtime` | 四类 | 未声明 | [账本](progress/2026-07-14-phase-2-production-runtime-ledger.md) | 已纳入版本控制 | [计划](plans/2026-07-14-phase-2-production-runtime.md) |
| 2026-07-13 | `server-yaml-variable-overrides` | 三类 | 未声明 | [本次核定](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-07-13-server-yaml-variable-overrides) | 已纳入版本控制 | [计划](plans/2026-07-13-server-yaml-variable-overrides.md) |
| 2026-07-13 | `vm-database-profiles` | 三类 | 未声明 | — | 已纳入版本控制 | [计划](plans/2026-07-13-vm-database-profiles.md) |

## 2026-06

| 日期 | 主题 | 集合 | 当前修订 | 最新账本 | 历史 Git 快照 | 后续入口 |
|---|---|---|---|---|---|---|
| 2026-06-29 | `production-persistence` | 单篇 | 未声明 | [账本](progress/2026-06-29-production-persistence-ledger.md) | 已纳入版本控制 | [账本](progress/2026-06-29-production-persistence-ledger.md) |
| 2026-06-29 | `supabase-persistence-automation` | 四类 | 未声明 | [账本](progress/2026-06-29-supabase-persistence-automation-ledger.md) | 已纳入版本控制 | [计划](plans/2026-06-29-supabase-persistence-automation.md) |
| 2026-06-26 | `production-persistence` | 单篇 | 未声明 | [共同账本](progress/2026-06-29-production-persistence-ledger.md#git-补证核定2026-11-13) | 已纳入版本控制 | [计划](plans/2026-06-26-production-persistence.md) |
| 2026-06-25 | `production-persistence` | 两类 | 未声明 | [共同账本](progress/2026-06-29-production-persistence-ledger.md#git-补证核定2026-11-13) | 已纳入版本控制 | [设计](specs/2026-06-25-production-persistence-design.md) |
| 2026-06-22 | `test-launch-profile` | 三类 | 未声明 | — | 已纳入版本控制 | [计划](plans/2026-06-22-test-launch-profile.md) |
| 2026-06-18 | `cm-agent` | 两类 | 未声明 | [本次核定](progress/2026-11-13-docs-full-review-ledger.md#git-audit-2026-06-18-cm-agent) | 已纳入版本控制 | [设计](specs/2026-06-18-cm-agent-design.md) |
| 2026-06-18 | `cm-agent-first-slice` | 单篇 | 未声明 | — | 已纳入版本控制 | [计划](plans/2026-06-18-cm-agent-first-slice.md) |

## 维护规则

- 新工作包加入后必须更新本目录；同一需求修订继续复用原日期和主题。
- 当前修订变化时更新“当前修订”，不要在目录里复制完整实施状态。
- 归档、恢复或移交先在原账本记录依据，再更新分类导航；仅追加归档事件不改需求修订号。
- 账本仍是状态和证据的唯一入口；没有独立旧账本时不补造文件，已核实的共同账本或本次补证须明确标注来源，未查明的保持为空。
- 不维护实时 Git 状态；查看提交、暂存、未跟踪文件时直接检查工作树。
