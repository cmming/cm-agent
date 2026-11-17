# docs 可用性复核进度账本

日期：2026-11-17；主题：`docs-usability-review`；修订：R2（2026-11-17）；模式：`mode=default`。

## R0 基线与范围

用户要求分析并优化 docs。开始时 HEAD 为 `e7da291`，`master` 本地领先远端一个提交；工作树已有大量未提交和未跟踪的文档、配置及其他文件，本轮保留这些修改。此前全量整理见 [2026-11-13 复核账本](2026-11-13-docs-full-review-ledger.md)。

| 编号 | 状态 | 证据 |
|---|---|---|
| T0 | 完成 | 盘点 docs、已有复核记录及工作树；并行审查导航与配置漂移 |
| T1 | 完成 | 文档入口、配置、运维及工具开发指南的场景导航 |
| T2 | 完成 | 静态对照当前工作树和提交基线配置；配置及部署指南澄清差异 |
| T3 | 完成 | 六份文档相互链接并加入索引；文档链接及空白检查结果见下 |

## R0 验证与限制

- Python 静态检查 12 份本次新增或编辑文档的 234 处普通相对链接和近似 GitHub 标题锚点，结果 0 问题；跳过代码围栏，不覆盖全部 Markdown 语法、外链或渲染器差异。
- `git -c core.safecrlf=false diff --check -- docs/configuration.md docs/deployment.md docs/operations.md docs/tool-development-guide.md docs/superpowers/README.md`：通过；未跟踪文件使用 Python 检查行尾空白。
- 配置检查只读取必要的非敏感键，依据当前代码和提交基线；没有记录或输出凭据。

本轮只做静态文档及配置分析；不启动服务，不验证监听状态、实际部署、数据库或业务。历史测试结果不能当成本轮验证。发布行为未改变，未更新 release-notes。

## R1 全面复核（2026-11-17）

用户要求再次全面检查 docs 的过时、矛盾和遗漏，沿用本工作包并新增 T4–T6；R0 历史结果不冒充 R1 证据。HEAD 仍为 `e7da291`，开始时保留大量既有未提交和未跟踪内容。

| 编号 | 状态 | 证据 |
|---|---|---|
| T4 | 完成 | 并行核对当前使用文档、源码、配置与历史账本；静态检查 555 份 docs Markdown 的链接与索引 |
| T5 | 完成 | 修正导入、配置覆盖、密钥、认证、日志风险及历史状态入口；保留历史发布条目 |
| T6 | 完成 | 全目录与本轮相关文档链接、锚点、空白及六份 R1 元数据检查，范围见下 |


### R1 发现与证据

- 导入资料：`WorkspaceImportService` 的 `runtimeEnabled`/`view` 按开关、存储和仓储能力计算 `runtimeAvailable` 与 `bindable`；`WorkspaceSelectionService` 和 `WorkspaceContextService` 已包含绑定与运行接线。原[实施账本](2026-10-27-workspace-directory-selection-implementation-ledger.md)保留 T11–T14 整体验收未完成的限制。
- 配置与密钥：当前公共配置与提交基线的开关及执行模式不同；`ModelCredentialCipher` 还用于检查点、本地和设备项目相关状态。文档不把文件配置等同运行环境。配置类及 `LocalProjectConfiguration`、`ClientProjectConfiguration` 为依据。
- 认证：`JwtService.parseAndVerify` 使用共享 HMAC 密钥，未显式要求 `iss`、`aud`、`exp` 或 `sub` 的存在；生产所需额外校验须另行保障。`ProfileSafetyValidator` 禁止严格生产 profile 启用 bootstrap。
- **待修复的代码与规范差异**：`cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillArtifactProtocol.java:185` 在文件产物回传协议解析的异常分支直接记录原始异常与堆栈，该行没有关联编号，异常来源可能包含敏感信息。`DockerSkillSandbox` 在启用沙箱和文件产物且存在接收器时可进入该路径。尚未核实实际环境、日志过滤或发生泄露；本任务只记录差异，未修复代码。后续应设计脱敏、关联编号和回归测试。
- 历史状态目录仅覆盖 2026-11-13 的 116 组快照；保留原数量及分类，不据此推断后续工作包状态。历史 T9/T10 发布记录保留原文并增加后继说明。

### R1 验证与限制

- 只读扫描 555 份 `docs` Markdown 的普通相对链接、引用定义及 HTML `a`/`img`：4467 个目标、955 个近似标题或显式 ID 锚点均无缺失；跳过围栏和行内代码，不覆盖所有 Markdown 语法、外链或渲染器差异。此扫描发生在本轮修改之前。
- 修改后用 Python 对根 README、docs 根目录、两份索引及本工作包共 19 份文档进行普通相对链接与近似锚点复查：724 处，问题 0；检查跳过围栏，不覆盖引用式链接和外链。单独的专项检查覆盖本轮变更的部分引用。
- `git -c core.safecrlf=false diff --check -- README.md docs/configuration.md docs/deployment.md docs/operations.md docs/harness-module.md docs/technical-architecture.md docs/tool-development-guide.md docs/roadmap.md docs/release-notes.md docs/superpowers/README.md`：通过。未跟踪文件另用 Python 检查行尾空白。
- 本轮核对生产说明及关键代码，不声称逐条重新验证所有历史工作包事实；未检查外链可达性，未运行 Maven、数据库、服务、浏览器或真实 Provider。前述问题是静态发现，不代表实际部署状态。仅追加历史发布说明的后继入口，未改变发布行为。

## R2 重复内容整理（2026-11-17）

用户要求继续优化并检查重复内容；沿用六份文档与原任务编号。HEAD 仍为 `e7da291`，工作树保留已有未提交修改。

| 编号 | 状态 | 证据 |
|---|---|---|
| T7 | 完成 | 分别检查现行配置/部署/运维、架构/工具、工作包历史与导航；未发现需要删除的整份文档 |
| T8 | 完成 | HTTP 参数、MCP 示例、Harness 启动步骤、能力摘要及工作包职责按用途收敛，具体见实现说明 |
| T9 | 完成 | 复核关键安全条件、参数、历史证据和锚点；检查结果见下 |


### R2 核对与验证

- 对照 `configuration.md` 与 `tool-development-guide.md` 的 HTTP 工具合同，完整请求和参数说明集中到后者；补充数组匿名直接子节点、缺失或 null 默认值、GET 禁止 BODY_ROOT 等原说明中不能丢失的规则。配置页仍保留开关、Secret、SSRF、重定向、超时、脱敏、出口防护及调试权限。
- MCP 的单一完整 YAML 示例保留在配置说明，部署页保留生产白名单和鉴权门禁。Harness 的历史启动步骤指向运维页，候选包摘要和适用限制仍在 Harness 文档；DashScope 地址以模型配置说明为入口。
- 架构及路线图各保留角色所需的摘要、阶段和未完成验收限制；工作包历史记录、日期及其分类导航不做合并或删除。无完全相同的 70 字以上正文段落被检测出，但该机械检查不能覆盖语义重复。
- Python 静态检查根 README、docs 根目录、两份工作包索引及本工作包，共 19 个文件、737 处普通相对链接/近似标题锚点，未发现缺失；跳过代码围栏，未检查引用式链接、外链和全部渲染器。
- `git -c core.safecrlf=false diff --check -- docs/configuration.md docs/deployment.md docs/harness-module.md docs/roadmap.md docs/technical-architecture.md docs/tool-development-guide.md`：通过；未跟踪的文档另以 Python 检查行尾空白。六份 R2 文件的日期、主题、模式和 T7–T9 标记一致。
- 本轮只修改文档，没有运行 Maven、服务、数据库或浏览器测试；未改变发布行为，不更新发布说明。

## 提交状态

本次文档整理纳入文档提交；提交编号及文件范围以 Git 历史为准。未推送、未部署。

[清单](../checklists/2026-11-17-docs-usability-review-checklist.md) · [提示词](../prompts/2026-11-17-docs-usability-review-prompt.md) · [设计](../specs/2026-11-17-docs-usability-review-design.md) · [计划](../plans/2026-11-17-docs-usability-review.md) · [实现说明](../implementation/2026-11-17-docs-usability-review-implementation-design.md) · [账本](../progress/2026-11-17-docs-usability-review-ledger.md)
