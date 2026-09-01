# 枚举常量与对象属性注释规则实现说明

对应设计：[specs/2026-09-01-enum-field-comments-design.md](../specs/2026-09-01-enum-field-comments-design.md)
对应计划：[plans/2026-09-01-enum-field-comments.md](../plans/2026-09-01-enum-field-comments.md)

## 实际实现

### AGENTS.md 新条款

在“代码注释与 JavaDoc 规范”触发清单首条位置新增：

> 枚举类型的每个常量、领域对象/API 对象的每个属性（含 record 组件与需要长期维护的字段）：枚举常量逐个补充 JavaDoc 说明业务含义；record 组件统一通过该 record JavaDoc 的 `{@code @param}` 标签逐一说明字段语义，新增、重命名或删除属性时必须同步维护对应标签，不允许只给部分属性补注释。

### cm-agent-core 落地

- **枚举（10 个文件、33 个常量）**：全部常量补单行 JavaDoc。
  - 需要优先说明“为什么”的例子：`RunStatus.DENIED`（说明为何授权拒绝是独立终态）、`ToolType.A2A`（说明当前为预留类型）、`HttpToolMethod.GET`（说明禁止 BODY 参数的原因）、`MessageRole.TOOL`（说明 Web API 不允许客户端直接构造）。
  - `HttpParameterDataType` 同步补全 `schemaType` 字段注释与 `schemaType()`/`scalar()` 的 `@return`。
- **record（23 个文件、约 168 个组件）**：全部组件在类级 JavaDoc 保有独立 `{@code @param}` 标签。代表位置：
  - `RunRecord.finishedAt`：说明 RUNNING 必须为 null、终态必须有值的状态机互斥。
  - `HttpParameterDefinition` 17 个组件逐一说明取值约束与生效范围（如 `minLength` 仅 STRING 生效、`exampleValueJson` 仅供控制台填充）。
  - `ToolExecutionRequest` 各组件说明-by-source 的必填/禁止组合（AGENT 必填、DEBUG/MCP 必须为 null、LEGACY 可 null）。
  - `MessageContentBlock` 各组件说明按类型的字段组合规则，与紧凑构造器校验对应。
  - `MessagePageRequest.afterSequence`、`ConversationPageRequest`/`RunPageRequest`/`AuditPageRequest` 游标组件说明首页请求为 null 的语义。

### 实现过程质量事故与修复

- `ToolDefinition` 曾误写不存在的 `toolName` 组件标签，已删除。
- `ConversationMessage` 与 `ConversationRunResult` 曾出现“消消息”错字，已修正。
- `HttpParameterDefinition` 曾把构造器 JavaDoc 替换为常量说明，已恢复构造器注释（ID_PATTERN 单行常量按“简单赋值/常量”规则不加注释）。
- `ToolInvocationRequest` 注释残留一处行尾半角逗号，已通过脚本修正。
- 修正后全量笔误正则扫描零命中。

## 关键代码位置

- 注释新增文件：41 个（枚举 10 + record 23 + AGENTS.md + 4 文档；部分文件在上一任务已完成主体注释，本轮追加组件/常量标签）。
- 最终验证：`mvn -q -pl cm-agent-core test` EXIT=0（JDK 21）。

## 与原方案的差异

- `HttpParameterDefinition.ID_PATTERN` 不为常量补注释：按 AGENTS.md “简单赋值/常量一句话即可、不为覆盖率堆砌”的既有口径处理，避免与构造器 JavaDoc 重复。

## 发布说明

未更新 `docs/release-notes.md`：本任务为注释与开发规范变更，不涉及任何运行行为、API、配置或数据库变化。

