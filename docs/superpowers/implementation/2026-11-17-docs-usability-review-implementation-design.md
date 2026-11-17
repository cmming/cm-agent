# docs 可用性复核实现说明

日期：2026-11-17；主题：`docs-usability-review`；修订：R2（2026-11-17）；模式：`mode=default`。

T0 依据当前文件、配置和历史复核分析文档的入口及配置说明。T1 在 `docs/README.md` 增加任务入口，在配置、运维与工具开发指南增加跳转；配置指南将 DashScope 地址说明移入模型配置章节。T2 在配置指南区分提交基线与带日期的工作树配置快照，并修订部署指南对默认关闭的表述。T3 增加本组六份文档，关联此前 `2026-11-13-docs-full-review` 的历史复核。

没有拆分或移动历史文档；没有调整配置、代码、接口或数据库。快照是文档说明而非实例运行状态。未发现需要偏离设计的事项。

## R1 实现说明

T4 核对源码、配置和历史验收；T5 对文档做以下修正；T6 维护本组工作包和链接入口。

- `configuration.md`、`operations.md`、`roadmap.md` 和根 `README.md`：区分 T9/T10 的历史状态与后续 IMPORTED 条件性绑定和运行接线，保留尚未完成的整体验收限制。
- `deployment.md`：说明模型凭据主密钥也保护运行检查点和项目相关状态，备份和恢复需考虑受影响数据；不混同各功能独立密钥。
- 配置、运维、架构、工具开发和根 README：区分属性初始值、提交基线和工作树配置覆盖；补充 bootstrap 登录和外部 JWT 的实际验证边界。
- 运维说明记录产物协议日志的已知实现差异，不把文档要求当作现有实现保证；本次未修改代码。
- `superpowers/WORKPACK-STATUS.md`、`superpowers/README.md`、`docs/README.md`：明确 2026-11-13 历史快照和后续主题的关系。发布说明保留 T9/T10 原始记录，追加后继入口。

与 R0 设计的差异：R1 为避免根入口与专项文档矛盾，定点更新根 README；新增安全实现差异说明，但不扩展到代码修复。

## R2 实现说明

T7 按读者职责盘点重复：配置与工具开发指南同时描述 HTTP 参数，配置与部署说明都有 MCP 示例，Harness 与运维都描述同一次本机启动，架构与路线图重复枚举能力，文档入口与工作包契约重复职责说明。

T8 以配置说明维护部署条件、工具开发指南维护 HTTP 参数合同，MCP 示例集中于配置说明；原配置说明中独有的数组元素、缺失或 null 默认值和 GET 禁止 BODY_ROOT 条件补到开发指南。部署页保留白名单和认证门禁。Harness 的历史启动摘要保留候选包摘要与适用限制，详细步骤链接运维页；模型地址规则链接配置页。架构和路线图分别保留架构边界及阶段/未验收条件，文档入口的职责说明链接工作包契约。

T9 保留原章节标题及历史工作包、发布记录和按不同用途建立的状态导航；六份工作包沿用原路径。未删除整个文档模块，未修改源码或配置。

[清单](../checklists/2026-11-17-docs-usability-review-checklist.md) · [提示词](../prompts/2026-11-17-docs-usability-review-prompt.md) · [设计](../specs/2026-11-17-docs-usability-review-design.md) · [计划](../plans/2026-11-17-docs-usability-review.md) · [实现说明](../implementation/2026-11-17-docs-usability-review-implementation-design.md) · [账本](../progress/2026-11-17-docs-usability-review-ledger.md)
