# 文档组织整理：实施计划

日期：2026-11-13；主题：`doc-organization-followup`；修订：R1；模式：`mode=default`。

1. T0：检查 Git、既有工作包与生产文档，按当前代码核对回环和非回环条件。
2. T1：同步 `README.md`、`docs/configuration.md`、`docs/operations.md` 的安全语义，修复配置表连续性。
3. T2：检查 `docs/operations.md` 的项目和设备项目证据归属，将阶段性状态明确为历史事实。
4. T3：将 `docs/technical-architecture.md` 重复的迁移分组指向 `docs/deployment.md`，保留部署所需的注意事项。
5. T4：更新 `docs/superpowers/README.md`、`WORKPACK-STATUS.md` 和 `docs/README.md`，增加关联入口并将 Git 栏标为历史快照。
6. T5：维护六份工作包，检查相对链接、表格、修订编号、任务范围、`git diff --check` 与工作树。只修改文档，不安排业务或数据库测试。

## R1 复核顺序

T0–T5 作为原实施范围保留，不因再次读取本计划自动重做。T6 逐项对照 README、配置、路线图与当前相关代码；T7 核对容器验证规范及历史环境边界；T8 用临时安装的 Markdown 解析器检查仓库链接、标题锚点与外链可达性；T9 比较开始时文件摘要、目录统计和六份修订，执行空白检查并把结果写入账本。外链错误按 HTTP 状态或网络限制分类；不访问本机服务链接。

执行状态与证据见[账本](../progress/2026-11-13-doc-organization-followup-ledger.md)；验收以[清单](../checklists/2026-11-13-doc-organization-followup-checklist.md)为准。

[设计](../specs/2026-11-13-doc-organization-followup-design.md) · [提示词](../prompts/2026-11-13-doc-organization-followup-prompt.md) · [实现说明](../implementation/2026-11-13-doc-organization-followup-implementation-design.md)
