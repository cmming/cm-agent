# 枚举常量与对象属性注释规则设计

## 背景

用户要求：枚举类型和对象属性也需要注释，并将该要求追加到 `AGENTS.md` 的“代码注释与 JavaDoc 规范”。此前仓库注释规则侧重类/方法级触发清单，枚举常量与 record 组件的注释义务未明文，导致 `cm-agent-core` 部分枚举常量无可读说明、部分 record 组件缺少逐字段语义说明。

## 目标

1. 在 `AGENTS.md` 触发清单中新增条款：枚举每个常量必须逐个 JavaDoc 说明业务含义；领域/API 对象每个属性（含 record 组件）必须通过 record 类级 JavaDoc 的 `{@code @param}` 标签逐一说明语义，新增/重命名/删除属性时同步维护。
2. 按新规则治理 `cm-agent-core`：10 个枚举的 33 个常量逐个补 JavaDoc；全部 record 的每个组件在类级 JavaDoc 补 `@param` 说明。

## 范围

- `AGENTS.md`：代码注释与 JavaDoc 规范一节新增一条触发清单条款。
- `cm-agent-core` 主源码：全部枚举与 record 注释。
- 按 AGENTS.md 文档规则生成同日期、同 topic（`enum-field-comments`）四份文档。

## 非目标

- 不修改任何可执行代码与行为。
- 不扩展到 `cm-agent-api` 等其他模块（本次范围以用户指定的 core 为准；规则本身全仓生效）。
- 不要求为普通常量（如 `DEFAULT_TITLE`、局部 Pattern）添加注释——规则限“枚举常量”与“对象属性”。

## 方案

- 枚举常量采用单行 JavaDoc `/** ... */`，内容为业务含义 + 必要的约束说明（密度随语义复杂度）。
- record 组件在类级 JavaDoc 中以 `{@code @param}` 逐组件说明；含义在紧凑构造器已有 `@param` 时不重复堆砌，类级说明负责“字段语义”，构造器说明负责“校验行为”。
- 字段为可空、有取值范围、有脱敏要求、有跨租户含义时在说明中显式写出。
- 领域行为特殊字段（如 `RunRecord.finishedAt` 的 RUNNING 互斥、`MessageContentBlock` 分类型字段组合）在组件说明中点出互斥关系。

## 验收标准

- `AGENTS.md` 触发清单包含新条款，且与其他条款格式一致、中文表述。
- `cm-agent-core` 10 个枚举的每个常量均有 JavaDoc；每个 record 的每个组件均有对应类级 `@param`。
- 注释全部中文、无低价值复述；不出现错字、半角逗号等笔误。
- `mvn -pl cm-agent-core test` 通过（仅注释变更）。

