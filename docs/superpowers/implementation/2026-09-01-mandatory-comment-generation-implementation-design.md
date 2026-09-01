# 强制生成既定范围内代码注释实现说明

关联[设计说明](../specs/2026-09-01-mandatory-comment-generation-design.md)与[实施计划](../plans/2026-09-01-mandatory-comment-generation.md)。

## 实际实现

在 `AGENTS.md` 的“代码注释与 JavaDoc 规范”首条规则之后新增一条强制落地约束。该约束要求 Agent 在既有规则判断需要注释或 JavaDoc 时，必须在同一次代码修改中生成对应的中文内容；不能以分析、计划、测试说明、最终回复或待办替代代码注释。

条款同时明确其不改变现有注释适用范围，因此简单代码是否需要注释、重要方法的 JavaDoc 要求和既有质量标准均保持原规则不变。

## 与设计的差异

无。
