# CM Agent 文档入口

本页是仓库文档的统一入口。根目录 [README](../README.md) 提供产品概览、快速启动和当前能力摘要；本页按读者目标组织生产说明、架构资料、产品与设计基线以及历史工作包。

## 按任务查找

| 要完成的任务 | 建议阅读顺序 |
|---|---|
| 构建并部署服务 | [部署前置条件](deployment.md#前置条件) → [构建](deployment.md#构建) → [生产配置注入](deployment.md#生产配置注入) → [启动与健康检查](deployment.md#启动与健康检查) |
| 确认配置与认证 | [Profile 选择](configuration.md#profile-选择) → [基础配置](configuration.md#基础配置) → [生产认证边界](configuration.md#生产认证边界)；以目标环境实际生效配置为准 |
| 使用本地项目与本机客户端 | [本地项目配置](configuration.md#本地项目开发工作区) → [本机客户端开关](configuration.md#本机客户端功能开关) → [启用与首次核验](operations.md#启用与首次核验) → [运行管理脚本](operations.md#本机运行管理脚本) |
| 排查运行故障 | [健康检查](operations.md#健康检查) → [日志与告警](operations.md#日志与告警) → [真实运行故障与工具副作用](operations.md#真实运行故障与工具副作用)；按错误编号关联日志 |
| 开发、调试或发布工具 | [选择工具类型](tool-development-guide.md#1-如何选择工具类型) → [LOCAL 工具](tool-development-guide.md#3-创建-local-工具)或 [HTTP 工具](tool-development-guide.md#4-创建-http-工具) → [Agent 授权与 MCP 发布](tool-development-guide.md#5-agent-授权与可选-mcp-发布) |

## 当前事实来源矩阵

下表按事实类型提供文档入口。矩阵最后核验于 2026-11-13；实际行为与预期行为分别核实，不能用单一的“代码优先”或“文档优先”处理所有冲突。

| 事实类型 | 权威入口 | 辅助证据 | 使用规则 |
|---|---|---|---|
| 产品定位、目标用户与产品原则 | [产品上下文](../PRODUCT.md) | [根 README](../README.md) | 变更产品范围先更新产品上下文，再同步用户入口。 |
| 当前可调用能力与限制摘要 | [根 README](../README.md) | [技术路线与实现逻辑](technical-architecture.md)、源码与测试 | 摘要不能覆盖专项文档或当前代码；未发布、默认关闭的能力必须明确标注。 |
| 配置键、默认值、安全门禁 | [配置说明](configuration.md) | 配置类、`application*.yml`、能力接口 | 属性初始值与目标环境生效值分别核对；实现与已确认的安全要求不一致时记录差异。 |
| 构建、首次部署与数据库迁移 | [部署指南](deployment.md) | `cm-agent-persistence/src/main/resources/db/migration/`、Flyway 历史 | 文档说明迁移分类；实际迁移头与已执行状态以仓库脚本和 `flyway_schema_history` 为准。 |
| 启用、切换、排障、恢复与回退 | [运维说明](operations.md) | [Harness 模块说明](harness-module.md)、具体工作包账本 | 运行变更优先阅读专项运维步骤；历史验证不能替代当前环境复核。 |
| 稳定架构、模块职责与关键链路 | [技术路线与实现逻辑](technical-architecture.md) | 模块 README、源码和测试 | 这里解释“为什么”和跨模块关系，不复制完整的接口或迁移清单。 |
| 发布时间线与未发布变化 | [发布说明](release-notes.md) | 对应工作包账本 | 按形成时版本和日期阅读；不能据此推断当前部署状态，也不能仅凭更新时间裁决冲突。 |
| 控制台视觉与交互基线 | [设计系统](../DESIGN.md) | 控制台资源、Node/Java 测试 | 视觉改动以设计系统为基线，权限和运行语义仍由后端合同决定。 |
| 计划与未来承诺 | [中文路线图](roadmap.md) | 对应设计工作包 | 未交付阶段描述未来范围；已完成章节保留阶段背景，当前能力仍以专项说明和实现为准。 |
| 过程证据与历史决定 | [Superpowers 工作包索引](superpowers/README.md) | [工作包状态目录](superpowers/WORKPACK-STATUS.md)、具体主题采用的修订与账本 | 工作包保存形成时事实；任务状态和归档依据只在账本维护，读取旧提示词不构成执行授权。 |
| 术语与重要取舍 | [术语表](../GLOSSARY.md)、[文档治理 ADR](adr/0001-documentation-governance.md) | 用户确认的需求和设计 | 术语表只定义概念，ADR 解释长期取舍；均不保存任务实时状态。 |

### 实际行为与预期行为

- **实际行为**：按对应代码版本、实际生效配置与可重复验证说明系统做了什么。工作树实现不能直接证明目标环境已经部署或生效；未知环境保持未核实。
- **预期行为**：按用户确认的需求、设计和 ADR 说明系统应当做什么。较新的草稿或已有代码不能自动替代已确认决定。
- **冲突处理**：列明双方版本、适用范围和证据，判断实现缺陷、文档过期或需求变更，再同步对应来源；判断依据和未解决差异写入相关账本。实现缺陷不能通过改写需求掩盖，旧设计也不能被当作当前运行事实。

根 README 提供摘要，专项使用说明提供条件和步骤，设计与 ADR 保留采用理由；相互引用，不复制完整状态和验证记录。

## 使用与生产

- [根 README](../README.md)：产品概览、快速开始和当前能力摘要。
- [配置说明](configuration.md)：配置键、默认值、profile、安全边界和功能开关。
- [部署指南](deployment.md)：构建、数据库迁移、部署和健康检查。
- [运维说明](operations.md)：启用、切换、排障、恢复和回退。
- [发布说明](release-notes.md)：按时间记录已交付和未发布变化。
- [技能沙箱端点](skill-sandbox-endpoints.md)：远程 Docker、SSH/TLS 和租户端点管理。
- [工具开发指南](tool-development-guide.md)：LOCAL、HTTP 工具开发、授权、MCP 发布与调试。

当前使用先读[根 README](../README.md)、[配置说明](configuration.md)和[运维说明](operations.md)，并核对对应版本和环境。发布说明及旧工作包中的“当前”“尚未提供”等只表示形成时状态；与当前说明冲突时按上面的两类依据核实，而非仅比较文档日期。

## 架构与开发

- [Harness 模块说明](harness-module.md)：当前 Harness、审批、Skill、沙箱、持久化和部署边界。
- [技术路线与实现逻辑](technical-architecture.md)：核心模块、领域模型、运行链路和当前交付边界。
- [中文路线图](roadmap.md)：阶段 4 和阶段 5 的后续范围，不代表当前已交付能力。
- [Core 模块说明](../cm-agent-core/README.md)：核心领域模型、SPI、依赖原则和扩展方式。
- [AgentScope Adapter 说明](../cm-agent-agentscope-adapter/README.md)：真实运行时适配、工具桥接、流式输出和安全边界。
- [协作与工程规则](../AGENTS.md)：范围、语言、安全、测试、数据库和文档交付约束。

## 产品与设计

- [产品上下文](../PRODUCT.md)：目标用户、产品定位、使用场景、能力边界和产品原则。
- [设计系统](../DESIGN.md)：控制台视觉规范、布局、组件和响应式规则。
- [术语表](../GLOSSARY.md)：本项目采用的文档治理概念。
- [文档治理 ADR](adr/0001-documentation-governance.md)：保留路径、文档分工与权威来源的取舍。

## 工作包与历史

- [Superpowers 工作包索引](superpowers/README.md)：当前长期页面需求组、已合并工作包和目录约定。
- [工作包状态目录](superpowers/WORKPACK-STATUS.md)：截至 2026-11-13 的主题、修订、账本、后续入口及历史 Git 快照；较新主题见[工作包索引](superpowers/README.md#2026-11-13-之后的工作包)，实时状态需查看原账本并核对 Git。
- [docs 全量覆盖与核定](superpowers/progress/2026-11-13-docs-full-review-ledger.md#逐文件覆盖)：当前说明及全部历史文档的逐文件覆盖快照；逐组归档依据仍返回原账本。
- [Requirement Workpack 契约](../.agents/skills/requirement-workpack/references/artifact-contract.md)：六份文档的固定职责和单一状态源。
- [清单](superpowers/checklists/)：需求范围、依赖、任务编号和验收条件。
- [设计](superpowers/specs/)：方案、约束和非目标。
- [计划](superpowers/plans/)：实施顺序、涉及文件和验证方式。
- [提示词](superpowers/prompts/)：可追溯的执行输入。
- [实现说明](superpowers/implementation/)：实际交付和与原方案的差异。
- [进度账本](superpowers/progress/)：验证证据、遗留问题、提交和发布状态。

工作包保存形成时事实；旧提示词不构成新的执行授权。当前任务状态和验收证据查原账本，提交及工作树状态另用 Git 核对。

### 文档职责与归档

六份工作包的职责、单一状态源和归档条件以[产物契约](../.agents/skills/requirement-workpack/references/artifact-contract.md)为准；历史主题保留原路径。2026-11-13 的归档分类从[历史导航](superpowers/WORKPACK-STATUS.md#归档导航)进入，缺少依据的“归档状态待核实”不等于业务未完成。

## 维护规则

- 生产事实变化时更新对应的权威文档，并在根 README 或本入口同步导航。
- 历史工作包保留原日期、主题和路径；需要纠错时追加修订或增加后继入口。
- 删除或合并历史文件前先检查本地 Markdown 引用，避免制造失效链接。
- 新增独立任务按[AGENTS.md](../AGENTS.md)和[工作包契约](../.agents/skills/requirement-workpack/references/artifact-contract.md)维护同日期、同主题的六份中文工作包；状态和证据只在账本维护。
