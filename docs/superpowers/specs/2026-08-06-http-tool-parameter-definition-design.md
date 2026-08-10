# HTTP Tool 扁平参数定义需求设计

## 1. 背景

旧版 HTTP Tool 需要分别维护输入 Schema 和参数映射，并通过 `sourcePointer`、`targetPointer` 指定输入字段与 PATH、QUERY、HEADER、BODY 之间的关系。对于字段名称和接口参数名称一致的常见场景，这种方式重复且难以理解，嵌套参数和数组元素的配置也不够直观。

本需求将 HTTP Tool 统一为一套参数定义：前端使用树形界面录入，后端接收扁平 `parameters[]`，通过 `id + parentId` 表达层级，并由服务端自动生成 Tool/MCP 输入 Schema 和 HTTP 请求数据。

## 2. 目标

- HTTP Tool 只维护一份参数定义，不再让用户分别维护 Schema 和映射。
- 没有输入参数的 HTTP 接口可以使用空 `parameters: []`，不需要添加占位参数。
- 参数名称直接作为 PATH、QUERY、HEADER 或 BODY 字段名称。
- 嵌套对象、普通数组、对象数组以及根数组均可使用同一种扁平节点结构表达。
- 前端保持符合用户认知的树形录入体验，传给后端的数据保持扁平。
- 服务端根据参数树生成 JSON Schema，并统一完成校验、默认值应用和请求组装。
- HTTP Tool 数据结构以最新定义为准，不兼容、不读取、不迁移历史映射数据。

## 3. 范围

- HTTP Tool 创建、编辑、查询、执行和 MCP 发布。
- Core 参数领域模型和树结构约束。
- Web API 请求及响应结构。
- HTTP 请求参数编译、校验、默认值和请求组装。
- JDBC 持久化及 Flyway 迁移。
- 控制台树形参数编辑器和扁平数据提交。
- 示例、自动化测试、配置说明、部署说明和工具开发文档。

## 4. 非目标

- 不兼容旧版 `inputSchema + parameterMappings` 配置。
- 不保留 `sourcePointer`、`targetPointer`、`nodeRole` 或嵌套 `arrayItem` 等辅助结构。
- 不自动转换或修复数据库中的历史 HTTP Tool 配置。
- 不改变 HTTP Tool 现有的租户隔离、权限、审计、Secret 引用和 URL 安全策略。
- 不扩展 GET、POST 之外的 HTTP 方法。

## 5. API 数据结构

HTTP 配置只接收以下字段：

```json
{
  "method": "POST",
  "urlTemplate": "https://api.example.com/orders/{orderId}",
  "parameters": [],
  "secretHeaders": {
    "Authorization": "secret/integration/orders-token"
  },
  "timeoutMillis": 3000
}
```

单个参数节点包含：

| 字段 | 说明 |
| --- | --- |
| `id` | Tool 内唯一节点 ID，以字母开头。 |
| `parentId` | 父节点 ID；空值表示顶层参数。 |
| `name` | 参数字段名；匿名数组元素节点为空。 |
| `dataType` | `STRING`、`INTEGER`、`NUMBER`、`BOOLEAN`、`OBJECT` 或 `ARRAY`。 |
| `requestLocation` | 顶层节点的位置；支持 `PATH`、`QUERY`、`HEADER`、`BODY`、`BODY_ROOT`。 |
| `description` | 面向模型和配置人员的参数说明。 |
| `required` | 调用时是否必填。 |
| `defaultValueJson` | 可选的 JSON 默认值。 |
| `exampleValueJson` | 可选的 JSON 示例值。 |
| `enumValues` | 可选的字符串枚举值。 |
| `minLength`、`maxLength` | 字符串长度约束。 |
| `minimum`、`maximum` | 数值范围约束。 |
| `minItems`、`maxItems`、`uniqueItems` | 数组约束。 |

`ToolDefinition.inputSchema` 仍然存在，但由服务端根据 `parameters[]` 自动生成，用于模型、MCP 和运行时校验；客户端不能为 HTTP Tool 单独提交或维护该 Schema。

## 6. 参数树规则

1. `parameters` 必须是数组，可以为空；空数组表示接口没有任何动态输入参数。
2. `parentId` 为空的节点是顶层参数，必须有名称和 `requestLocation`。
3. 嵌套节点通过 `parentId` 定位父节点，不再使用嵌套 JSON 保存子节点。
4. `OBJECT` 可以包含多个有名称的直接子节点，同一父节点下名称必须唯一。
5. `ARRAY` 必须有且只有一个匿名直接子节点，该节点的数据类型就是数组元素类型。
6. 数组元素节点继续通过 `parentId` 指向 `ARRAY`，不需要 `nodeRole` 或 `arrayItem` 属性。
7. 标量节点不能包含子节点。
8. 嵌套节点继承顶层节点的请求位置，不能单独设置 `requestLocation`。
9. 非空参数树不得存在重复 ID、缺失父节点、循环引用、超深层级或超过节点数量限制的情况。

空参数工具生成的 Tool/MCP 输入 Schema 根节点仍为 object，`properties` 为空且 `additionalProperties=false`；调用输入只能是 `{}`。

根数组请求体 `[{"p1":"v1"}]` 使用一个 `BODY_ROOT + ARRAY` 顶层节点、一个匿名 `OBJECT` 数组元素节点和一个名为 `p1` 的字符串子节点表达。

## 7. 请求位置规则

- `PATH`：顶层参数名称必须与 URL `{placeholder}` 一一对应，且必须为必填标量。
- `QUERY`：参数名称直接作为查询参数名，允许标量或标量数组，缺失值不追加。
- `HEADER`：参数名称直接作为请求头名，只允许标量，并拒绝受保护或逐跳请求头。
- `BODY`：一个或多个顶层参数按名称组成 JSON Object 请求体。
- `BODY_ROOT`：将唯一顶层参数的值直接作为整个请求体，用于根对象、根数组或根标量；不能与 `BODY` 混用。
- GET 请求不能包含 `BODY` 或 `BODY_ROOT`。

## 8. 前端交互要求

- 页面以树结构显示参数，按照父子关系缩进。
- 参数列表为空时明确提示“可直接保存”，不能强制用户添加虚假参数。
- 用户从顶层参数或 OBJECT、ARRAY 节点添加子参数，不直接编辑 `parentId`。
- ARRAY 只允许添加一个直接元素节点，该节点名称固定为空。
- 只有顶层节点显示请求位置；嵌套节点展示继承关系。
- 提交时按树的先序遍历生成扁平 `parameters[]`，每个子节点携带 `parentId`。
- 编辑时根据扁平数组恢复树结构。
- 每个输入项提供中文说明、占位示例和完整参数示例。
- 工具页面的 Tool 列表、注册/编辑、授权和调试模块宽度均为 100%。

## 9. 持久化与迁移要求

- `tool_http_configs.parameter_definitions` 保存扁平参数数组 JSON。
- 生成的 Schema 只保存到 `tool_definitions.input_schema`。
- 新增迁移删除 `tool_http_configs.input_schema` 和 `parameter_mappings` 旧列。
- 不转换历史数据；旧 HTTP Tool 需要按最新结构重新注册。
- 迁移应同时适用于 PostgreSQL 16 和 MySQL 8.4，不修改已经发布的旧迁移。

## 10. 安全要求

- 参数 ID 只用于 Tool 内部构树，不能写入外部 HTTP 请求。
- HEADER 参数不能覆盖 Authorization、Cookie、Host、Content-Length 等受保护请求头。
- Secret 只能通过 `secret/...` 引用配置，示例和默认值不得包含真实凭据。
- 保留 URL Host 白名单、私网地址拦截、重定向限制、统一超时、响应大小限制和审计链路。
- 所有读取和写入继续按当前认证主体执行租户隔离。

## 11. 验收标准

- HTTP Tool 创建和编辑接口必须提交数组类型的 `parameters`；缺少或传 `null` 时失败，`[]` 合法。
- 空参数工具生成封闭空对象 Schema，只接受 `{}` 输入，映射结果不包含 PATH、QUERY、动态 HEADER 或 BODY。
- 运行时代码不依赖 `HttpParameterMapping`、`sourcePointer` 或 `targetPointer`。
- PATH、QUERY、HEADER、BODY、BODY_ROOT 混合场景可按字段名称正确组装。
- 嵌套对象、标量数组、对象数组和根数组均有自动化测试。
- Tool/MCP 输入 Schema 与参数树一致，默认值和约束生效。
- 控制台树形录入、扁平提交和编辑回填通过自动化与浏览器验证。
- PostgreSQL/MySQL 迁移验证通过；环境不可用时必须明确记录未执行原因。

## 12. 验收结果

2026-08-07 在 Rocky Linux 9.3、Docker 23.0.6 和 `maven:3.9.9-eclipse-temurin-21` 容器中完成持久化验证：

- 本地临时验证提交与远端检出提交均为 `12c7fb1529247a34b119a54edeafdee607929f0c`。
- `mvn -q -pl cm-agent-persistence -am test` 执行成功，共 109 项测试通过，0 失败、0 错误、0 跳过。
- PostgreSQL 16.14 和 MySQL 8.4 均从空库成功执行 7 个 Flyway 迁移并到达 V7。
- JDBC HTTP Tool 参数定义保存和读取测试通过；迁移测试确认旧映射列已经删除。
- Testcontainers 临时容器以及远端验证目录、bundle 均已清理。

同日完成无参数 HTTP Tool 增量验收：

- 使用临时提交 `7c0abf44f5f1092a6590040a0b6ef05aa30c9bba`，本地与远端提交一致。
- API 和控制台均成功创建 `parameters: []` 的 GET 工具，编辑回填保持 0 个参数。
- 空参数定义生成封闭空对象 Schema，只接受 `{}`，映射结果无 PATH、QUERY、动态 HEADER 和 BODY。
- 本地 Node 36 项、Server 定向 67 项以及 Core、Console 测试通过。
- Rocky Persistence reactor 共 110 项通过，0 失败、0 错误、0 跳过；`JdbcHttpToolConfigRepositoryTest` 7 项通过，其中包含空数组保存和读取。

## 13. 关联文档

- [实施计划](../plans/2026-08-06-http-tool-parameter-definition.md)
- [实现说明](../implementation/2026-08-06-http-tool-parameter-definition-design.md)
- [进度账本](../progress/2026-08-06-http-tool-parameter-definition-ledger.md)
