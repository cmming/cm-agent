# docs 全量整理：计划

日期：2026-11-13；主题：`docs-full-review`；修订：R1；模式：`mode=interactive`。

1. T0：记录 HEAD、暂存区、文件路径与摘要，固定 docs 起始覆盖清单。
2. T1：读取全部文档并分类，解析 Markdown 链接和锚点，检查空文件、代码块及当前修订。历史角色按原集合识别，不套用新文档职责改写旧正文。
3. T2：核对当前配置、工具指南、运维与模块说明中的冲突；只依据当前 owning source 或明确历史记录修正。
4. T3：逐组核对账本和验收范围；分为当前、待续、已归档、归档状态待核实，记录具体未关闭事项或归档依据。
5. T4：原账本只追加带日期核定；完整目录加入本包和分类导航，本包账本提供逐组与逐文件覆盖快照。
6. T5：检查本轮差异、链接、锚点、文档集合、覆盖数量和状态来源，验证 docs 外摘要及 Git HEAD/暂存区；验证结果只写账本。

涉及目录：docs 根目录、docs/adr、docs/superpowers 的索引与六类目录。实际定点修正以可核实问题为准，不为使每份文件都有差异而改动历史正文。不提交、推送、部署或操作业务服务。

## R1 补证顺序

1. T6：固定当前 HEAD、暂存区与文件摘要；对原 16 组运行只读 `git log --all -- <paths>`、`git show` 和 `git merge-base --is-ancestor`，核对现有文档的任务与实际结果。
2. T7：对直接修复及同版本验证、实际历史进度、纯文档采纳与明确剩余事项分别判断；预期命令及脚本成功提示不作执行证明。
3. T8：原有三组目标账本及找到的共同账本追加新核定；其他缺少原账本的主题在本包留本次证据，不新增旧账本。目录更新分类，本包保存 R0 原快照与 R1 新证据。
4. T9：复查本地链接与锚点、16 组覆盖和分类计数、六份修订、原文前缀、Git HEAD 与暂存区及范围外摘要。


[清单](../checklists/2026-11-13-docs-full-review-checklist.md) · [提示词](../prompts/2026-11-13-docs-full-review-prompt.md) · [设计](../specs/2026-11-13-docs-full-review-design.md) · [实现说明](../implementation/2026-11-13-docs-full-review-implementation-design.md) · [账本](../progress/2026-11-13-docs-full-review-ledger.md)
