# Superpowers 四文档治理实施计划

对应设计：[Superpowers 四文档治理设计](../specs/2026-08-07-superpowers-documentation-governance-design.md)。

## 目标

将每个新需求或仓库改动必须生成四类过程文档的约束写入根目录 `AGENTS.md`，并用本次任务验证该约束可以执行。

## 任务

- [x] 检查 `docs/superpowers` 现有目录及文档职责。
- [x] 在 `AGENTS.md` 文档规则中增加适用范围、命名方式和四类文档最低要求。
- [x] 明确同一任务连续修改与新独立任务的文档处理方式。
- [x] 明确纯问答和只读检查的例外。
- [x] 为本次规则变更新增同主题的 specs、plans、implementation、progress 文档。
- [x] 执行格式和差异检查。

## 涉及文件

- `AGENTS.md`
- `docs/superpowers/specs/2026-08-07-superpowers-documentation-governance-design.md`
- `docs/superpowers/plans/2026-08-07-superpowers-documentation-governance.md`
- `docs/superpowers/implementation/2026-08-07-superpowers-documentation-governance-implementation-design.md`
- `docs/superpowers/progress/2026-08-07-superpowers-documentation-governance-ledger.md`

## 验证方式

- 检查四个文件均存在。
- 检查四个文件日期和主题一致。
- 检查 `AGENTS.md` 同时覆盖生成、更新、例外和生产文档边界。
- 执行 `git diff --check`。
