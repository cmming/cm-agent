# 枚举常量与对象属性注释规则进度账本

对应设计：[specs/2026-09-01-enum-field-comments-design.md](../specs/2026-09-01-enum-field-comments-design.md)
对应计划：[plans/2026-09-01-enum-field-comments.md](../plans/2026-09-01-enum-field-comments.md)
实现说明：[implementation/2026-09-01-enum-field-comments-implementation-design.md](../implementation/2026-09-01-enum-field-comments-implementation-design.md)

## 任务状态

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| 任务 1：更新 AGENTS.md | 已完成 | 触发清单新增“枚举常量 + 对象属性注释”条款，明确同步维护义务 |
| 任务 2：枚举常量注释 | 已完成 | 10 个枚举 33 个常量全部补 JavaDoc；附带的字段/方法 @return 缺口一并补全 |
| 任务 3：record 组件注释 | 已完成 | 23 个 record 约 168 个组件全部有类级 @param 标签 |
| 任务 4：验证与文档 | 已完成 | 见实际验证结果 |

## 实际验证结果

- 笔误正则扫描（消消息/租 tenant/迳行/契約/行尾半角逗号）：首次扫描命中 1 处（ToolInvocationRequest），修正后复扫零命中。
- `mvn -q -pl cm-agent-core test`（JAVA_HOME=F:\java21）：EXIT=0，16 个测试类全部通过。
- 抽查确认：AGENTS.md 新条款已落地为实际文字；`RunRecord`、`HttpParameterDefinition`、`MessageContentBlock` 等 record 的组件标签完整。
- 注释自查：命中新触发清单的位置均已落地；无低价值复述；编辑引入的错字已即时修复。

## 遗留问题

- 规则仅在本仓库 AGENTS.md 生效；其他仓库或后续新模块按同一 AGENTS.md 口径执行。
- `cm-agent-api`、`cm-agent-server` 等其他模块的既有枚举/record 未在本任务范围内治理，如需同等覆盖应另立任务。

## 提交信息

未提交。

