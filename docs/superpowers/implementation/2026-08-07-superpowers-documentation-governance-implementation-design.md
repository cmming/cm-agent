# Superpowers 四文档治理实现说明

对应设计：[Superpowers 四文档治理设计](../specs/2026-08-07-superpowers-documentation-governance-design.md)。  
对应计划：[Superpowers 四文档治理实施计划](../plans/2026-08-07-superpowers-documentation-governance.md)。

## 1. 实际实现

根目录 `AGENTS.md` 的“文档规则”新增强制约束：每个涉及新需求或仓库内容改动的独立任务，完成前必须生成 specs、plans、implementation、progress 四份主题一致的中文文档。

## 2. 命名约定

四份文档共享 `<YYYY-MM-DD>-<topic>`：

- specs 使用 `-design.md` 后缀。
- plans 直接使用 `.md` 后缀。
- implementation 使用 `-implementation-design.md` 后缀。
- progress 使用 `-ledger.md` 后缀。

## 3. 更新规则

- 同一独立任务的连续修改同步更新已有四份文档。
- 新的独立需求或改动任务新建一组四份文档。
- 纯问答、只读检查且没有仓库改动时不生成。

## 4. 边界

四文档用于研发过程追踪，不替代 README、配置、部署、安全、API 和发布说明。涉及生产行为变化时，仍按原规则同步维护生产文档。

## 5. 方案差异

最终实现除强制生成四份文档外，还补充了同一任务更新规则和只读任务例外，避免一次任务重复生成文档，也避免纯解释任务产生无意义文件。
