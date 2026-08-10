# HTTP Tool 扁平参数定义实现设计

对应需求：[HTTP Tool 扁平参数定义需求设计](../specs/2026-08-06-http-tool-parameter-definition-design.md)。  
对应计划：[HTTP Tool 扁平参数定义实施计划](../plans/2026-08-06-http-tool-parameter-definition.md)。

## 1. 背景与目标

现有 HTTP Tool 要求使用者同时维护 `inputSchema` 和 `parameterMappings`。两份配置通过 `sourcePointer`、`targetPointer` 关联，表达能力完整，但普通接口存在明显重复：字段名称、Schema 路径和 HTTP 参数名称相同时，仍需手写多段 JSON。

本次改造引入扁平 `parameters` 定义，以 `id + parentId + name + dataType + requestLocation` 描述输入结构和 HTTP 位置，由服务端生成标准 JSON Schema 并构造请求。目标如下：

- 顶层字段通过 `requestLocation` 声明 `PATH`、`QUERY`、`HEADER`、`BODY` 或 `BODY_ROOT`；字段名称即 HTTP 参数名称。
- 嵌套结构仅通过 `parentId` 表达，不暴露 `sourcePointer`、`targetPointer`、`nodeRole`、`itemNodeId` 或内联 `arrayItem`。
- `ARRAY` 必须有且只有一个匿名直接子节点；该节点的数据类型就是数组元素类型。
- `BODY_ROOT` 将一个命名 Tool 输入字段的值作为整个 HTTP 请求体，支持根数组，同时保持 Tool 输入根节点为 JSON Object。
- 参数定义自动生成 JSON Schema；系统不再读取、执行或迁移旧参数映射，历史 HTTP Tool 需要按最新结构重新注册。

## 2. 领域模型

新增 `HttpParameterDataType`：

```text
STRING、INTEGER、NUMBER、BOOLEAN、OBJECT、ARRAY
```

新增 `HttpParameterDefinition`，核心字段为：

```text
id、parentId、name、dataType、requestLocation、description、required
```

并提供默认值、示例、字符串/数字/数组约束和字符串枚举。约束落入生成的 JSON Schema，避免降低现有输入校验能力。

关系规则：

1. `parentId=null` 表示顶层 Tool 输入字段，必须有名称和 `requestLocation`。
2. `OBJECT` 的直接子节点必须有名称；同一父节点下名称唯一。
3. `ARRAY` 必须有且只有一个直接子节点，该子节点名称必须为空。
4. 标量类型不能有子节点。
5. 嵌套节点不能再次声明 `requestLocation`，继承所属顶层字段的位置。
6. 禁止缺失父节点、循环引用、超深层级和过多节点。

## 3. HTTP 绑定规则

新参数定义不再持久化用户编写的映射：

- `PATH`：顶层名称必须与 URL `{placeholder}` 完整一一对应，字段必须必填且为标量。
- `QUERY`：顶层名称直接作为查询参数名，允许标量或标量数组；缺失值省略。
- `HEADER`：顶层名称直接作为请求头名，只允许标量，并继续拒绝敏感或逐跳请求头。
- `BODY`：一个或多个顶层字段按原名称组成 JSON Object 请求体。
- `BODY_ROOT`：唯一顶层字段的值直接作为请求体，不能与 `BODY` 同时出现；GET 禁止使用。

默认值先写入 Tool 输入副本，再执行生成 Schema 校验。嵌套对象和对象数组中的默认值按参数树递归应用。

## 4. API 契约

`httpConfig` 只包含 `method`、`urlTemplate`、`parameters`、`secretHeaders` 和 `timeoutMillis`。`parameters` 必须是非 null 数组，但允许使用空数组表达无参数接口；客户端不提交 `inputSchema` 或 `parameterMappings`。服务端根据参数定义生成 `ToolDefinition.inputSchema`，用于模型、MCP 和诊断；HTTP 配置响应不再重复返回 Schema。

空参数工具生成 `type=object`、空 `properties`、`additionalProperties=false` 的 Schema，只接受 `{}` 作为调用输入；映射结果不包含 PATH、QUERY、动态 HEADER 或 BODY。系统不读取、转换或执行旧版 JSON Pointer 映射。只提供旧字段且没有 `parameters` 的请求会因缺少参数数组而失败。

## 5. 持久化与迁移

`V6__add_http_parameter_definitions.sql` 新增参数定义列：

```sql
ALTER TABLE tool_http_configs ADD COLUMN parameter_definitions TEXT NULL;
```

`V7__remove_legacy_http_parameter_mapping.sql` 删除旧字段：

```sql
ALTER TABLE tool_http_configs
    DROP COLUMN input_schema,
    DROP COLUMN parameter_mappings;
```

生成后的 Schema 只保存在 `tool_definitions.input_schema`，HTTP 配置表仅持久化 `parameter_definitions`。历史映射数据不迁移。

迁移兼容 PostgreSQL 16 和 MySQL 8.4；不修改 V1–V5。

## 6. 控制台

HTTP Tool 表单采用树形参数编辑器，页面展示结构与后端传输结构明确分离：

- 页面按父子层级缩进展示节点，OBJECT、ARRAY 节点提供“添加子参数”入口。
- 参数列表为空时页面提示当前工具没有输入参数并允许直接保存，提交值为 `parameters: []`。
- 顶部“添加顶层参数”只创建根节点；用户不再通过父参数下拉框拼装层级。
- ARRAY 创建第一个子节点时自动按匿名数组元素处理，名称、位置、必填和默认值不可编辑；已有元素时禁止继续添加直接子节点。
- 嵌套节点不展示父节点选择；请求位置只在顶层编辑，子节点从顶层继承。
- 编辑和示例回填时先根据 `parentId` 还原树；提交时按树的先序遍历重新输出扁平 `parameters[]`，每个子节点继续携带 `parentId`。
- 默认值按 JSON 值解析；前端做基础结构校验，服务端执行最终可信校验。
- Secret 引用仍独立配置。

## 7. 安全与边界

- 参数 ID 只在单个 Tool 内有效，不进入 HTTP 请求。
- HEADER 不能覆盖 Authorization、Cookie、Host、Content-Length 等受保护请求头。
- Secret 仍只能使用 `secret/...` 引用，不能作为参数默认值或示例保存真实凭据。
- URL 占位符、参数数量、树深度、字符串长度和 Schema 编译均执行上限校验。
- 参数名称必须与 PATH 占位符、QUERY 或 HEADER 名称保持一致；接口请求体结构必须与 BODY 参数树保持一致。

## 8. 验证范围

- Core：参数领域对象不变量测试通过。
- Server：参数树、Schema 生成、PATH/QUERY/HEADER/BODY/BODY_ROOT、数组与默认值定向测试通过，共计 149 项服务端相关测试通过。
- Web：创建、更新、响应和旧字段不再生效测试通过。
- Persistence：2026-08-07 在 Rocky Linux 的 Maven 3.9.9/JDK 21 容器中执行 109 项测试，全部通过；覆盖 V6/V7、参数 JSON 保存/读取、租户隔离和旧列删除。
- Console：树形录入、扁平提交、编辑回填和旧入口移除测试通过；Node 共 35 项通过。
- PostgreSQL/MySQL：PostgreSQL 16.14 与 MySQL 8.4 均成功执行 7 个 Flyway 迁移并到达 V7。

验证使用临时提交 `12c7fb1529247a34b119a54edeafdee607929f0c`，远端检出提交与本地一致。MySQL 8.4 验证过程中 Flyway 输出“高于当前已测试支持版本 8.1”的升级建议，但迁移和全部测试均成功。

无参数能力使用临时提交 `7c0abf44f5f1092a6590040a0b6ef05aa30c9bba` 完成增量验证：Node 36 项、Server 定向 67 项、Core 和 Console 测试通过；浏览器成功创建并编辑回填 0 参数工具；Rocky Persistence reactor 110 项全部通过，`JdbcHttpToolConfigRepositoryTest` 7 项全部通过。Testcontainers 和远端 QA 临时资源均已清理。

## 9. 关联文档

- [需求设计](../specs/2026-08-06-http-tool-parameter-definition-design.md)
- [实施计划](../plans/2026-08-06-http-tool-parameter-definition.md)
- [进度账本](../progress/2026-08-06-http-tool-parameter-definition-ledger.md)
