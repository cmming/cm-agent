# 错误诊断开发规范实现计划

## 任务拆分

1. 检查现有 `AGENTS.md` 的 DTO、测试、安全和文档规则。
2. 设计错误编号、前端提示、后台日志、脱敏和审计的统一约束。
3. 在 `AGENTS.md` 增加独立规范章节，并在测试规则中建立强制引用。
4. 生成同主题的设计、计划、实现和进度文档。
5. 检查章节、关键约束、文档互链和差异格式。

## 涉及文件

- `AGENTS.md`
- `docs/superpowers/specs/2026-08-10-error-diagnostics-development-standards-design.md`
- `docs/superpowers/plans/2026-08-10-error-diagnostics-development-standards.md`
- `docs/superpowers/implementation/2026-08-10-error-diagnostics-development-standards-implementation-design.md`
- `docs/superpowers/progress/2026-08-10-error-diagnostics-development-standards-ledger.md`

## 实现顺序

先确定可执行规范，再写入根级规则，随后生成过程文档并检查。根规则优先描述必须做什么、禁止做什么和如何验证，过程文档记录设计依据与实际落地情况。

## 验证方式

- 使用文本检索确认错误编号、日志字段、前端提示、脱敏、审计和测试要求均已落地。
- 检查四份文档的日期、主题、路径和相互引用一致。
- 运行 `git diff --check` 检查空白错误。

本任务只修改开发规范和过程文档，不涉及 Java、前端运行代码、数据库或配置，因此不运行构建、自动化测试和 Rocky Linux 容器验证。

## 关联文档

- [需求设计](../specs/2026-08-10-error-diagnostics-development-standards-design.md)
- [实现说明](../implementation/2026-08-10-error-diagnostics-development-standards-implementation-design.md)
- [进度账本](../progress/2026-08-10-error-diagnostics-development-standards-ledger.md)
