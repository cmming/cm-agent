# MySQL Profile 内置 LOCAL 工具设计

## 1. 背景

当前工具治理页面允许创建 `LOCAL` 类型的工具定义，也允许对 `LOCAL` 工具调用调试接口。但是页面创建动作只把工具元数据写入 `ToolDefinitionRepository`，不会生成或注册 Java `ToolExecutor`。因此，直接在页面创建的 `LOCAL` 工具虽然会出现在列表和调试下拉框中，执行时仍会被 `GovernedToolExecutionService` 判定为“工具不可用”。

项目已有 `starter-local-tool` 独立示例，其中包含 `echo` 和 `add` 执行器，但该示例运行在独立进程中。另一个 JVM 中的 `ToolRegistry` 不会自动注册到 CM Agent Server，因此不能直接供 Server 控制台调用。

本设计在 MySQL 调试 profile 下提供一组受限、固定、经过审核的内置 LOCAL 示例，使管理员可以从工具治理页面显式安装并立即调试；正式业务 LOCAL 工具仍必须由开发者实现并通过 Java 代码注册。

## 2. 目标

- 在 `mysql` 且非生产类 profile 下提供固定的 `echo`、`add` LOCAL 示例目录。
- 管理员从工具治理页面显式选择并安装示例，不在应用启动时自动写入 MySQL。
- 安装动作同时满足租户隔离、权限校验、严格审计和 JDBC 事务要求。
- 示例安装后立即可通过现有工具调试接口调用，服务重启后仍可调用。
- 页面明确展示 LOCAL 工具的运行时执行器是否已就绪，避免把只有元数据的工具展示为可直接调试。
- 保持现有 HTTP 工具、MCP 发布、Agent 授权和正式业务 LOCAL 工具注册机制不变。

## 3. 非目标

- 不允许从页面上传、编写、编译或解释 Java、脚本或其他可执行代码。
- 不根据工具名称为任意业务 LOCAL 工具自动绑定执行器。
- 不为正式业务 LOCAL 工具提供动态插件安装机制。
- 不新增工具删除、通用启用/停用或编辑能力。
- 不新增数据库表、字段或 Flyway 迁移。
- 不在 `prod`、`production` 或 `supabase` profile 下开放内置示例安装能力。

## 4. 方案比较

### 4.1 受限内置目录

Server 预先包含固定的 `echo`、`add` 定义和执行器。启动时只向进程内 `ToolRegistry` 注册执行器；管理员点击页面按钮后，服务端才把对应定义写入 MySQL。

优点：

- 页面添加路径真实经过管理 API、权限、租户和审计链路。
- 不存在动态执行任意代码的入口。
- 固定 ID 和固定租户使执行器注册在重启后可稳定恢复。
- 启动时不隐式修改数据库。

缺点：

- 只适合有限的开发调试示例，不能代替正式业务工具接入。

### 4.2 启动时自动写入 MySQL

MySQL profile 启动后自动创建两个工具定义并注册执行器。

优点是实现和使用简单；缺点是应用启动会产生隐式数据库写入，无法体现页面显式添加流程，重复启动、冲突和审计语义也更复杂，因此不采用。

### 4.3 页面动态创建任意 LOCAL 执行器

页面同时提交元数据和可执行实现。

该方案会引入代码分发、编译、隔离和远程代码执行风险，明显超出当前工具治理边界，因此不采用。

## 5. 总体架构

本次新增四个相互独立的职责单元：

1. 内置定义与执行器：提供固定的工具标识、输入 Schema、示例输入和 Java 执行逻辑。
2. MySQL profile 注册配置：在允许的 profile 下把固定定义与执行器注册到当前 Server JVM 的 `ToolRegistry`，但不写数据库。
3. 安装服务与 API：查询安装状态，并在管理员显式请求时把固定定义写入当前租户的 Repository，同时记录严格审计。
4. 控制台交互：展示内置目录、安装状态、LOCAL 运行时就绪状态，并复用现有调试表单调用工具。

```text
MySQL Server 启动
  → 固定 echo/add 定义与执行器
  → ToolRegistry.register
  → 不写数据库

管理员打开工具治理页面
  → GET /api/tools/local-examples
  → 展示 echo/add 及安装状态

管理员点击“添加示例工具”
  → POST /api/tools/local-examples/{key}
  → 校验 profile、权限、tenant、固定定义和冲突
  → JDBC 事务内保存 ToolDefinition 并记录审计
  → 刷新工具列表
  → 自动选中工具并填入示例输入

管理员点击“调用/调试”
  → POST /api/tools/{id}/debug
  → 现有权限、风险确认、治理执行、脱敏和审计链路
  → ToolRegistry 中的固定执行器
  → 返回受控调试结果
```

## 6. Profile 与租户边界

内置示例服务和执行器注册配置只在以下条件同时满足时生效：

- 激活 `mysql` profile。
- 未激活 `prod`。
- 未激活 `production`。
- 未激活 `supabase`。

示例只服务当前 MySQL 调试环境中的 bootstrap 租户：

```text
tenantId = 00000000-0000-0000-0000-000000000001
```

`echo` 和 `add` 使用固定且互不冲突的工具 ID。实现优先复用现有 `starter-local-tool` 示例约定：

```text
echo = 00000000-0000-0000-0000-000000000101
add  = 00000000-0000-0000-0000-000000000102
```

外部 JWT 中的其他租户不能安装或看到该目录的已安装状态。固定 ID 只用于绑定受审核执行器，不能作为跨租户查询依据。

## 7. 内置工具契约

### 7.1 echo

- 名称：`echo`
- 类型：`LOCAL`
- 风险等级：`LOW`
- 描述：回显非空消息
- 示例输入：

```json
{"message":"你好，CM Agent"}
```

- 输入 Schema：

```json
{
  "type": "object",
  "properties": {
    "message": {
      "type": "string",
      "minLength": 1
    }
  },
  "required": ["message"],
  "additionalProperties": false
}
```

- 成功输出：

```json
{"message":"你好，CM Agent"}
```

### 7.2 add

- 名称：`add`
- 类型：`LOCAL`
- 风险等级：`LOW`
- 描述：对两个数字执行精确加法
- 示例输入：

```json
{"left":0.1,"right":0.2}
```

- 输入 Schema：

```json
{
  "type": "object",
  "properties": {
    "left": {
      "type": "number"
    },
    "right": {
      "type": "number"
    }
  },
  "required": ["left", "right"],
  "additionalProperties": false
}
```

- 使用 `BigDecimal` 计算，成功输出：

```json
{"sum":0.3}
```

两个执行器都必须自行校验输入并返回受控中文错误，不输出堆栈、原始异常或完整敏感输入。

## 8. API 设计

### 8.1 查询内置目录

```http
GET /api/tools/local-examples
Authorization: Bearer <access-token>
```

权限：`tool:read`。

启用条件满足且主体属于示例租户时，响应包含：

```json
[
  {
    "key": "echo",
    "toolId": "00000000-0000-0000-0000-000000000101",
    "name": "echo",
    "description": "回显非空消息",
    "inputSchema": {
      "type": "object"
    },
    "sampleInput": {
      "message": "你好，CM Agent"
    },
    "installed": false,
    "runtimeReady": true
  }
]
```

非 MySQL 或生产类 profile 返回空目录，不暴露安装入口。其他租户同样返回空目录，避免跨租户状态泄露。

### 8.2 安装内置工具

```http
POST /api/tools/local-examples/{key}
Authorization: Bearer <access-token>
```

权限：`tool:grant`。

行为：

- 只接受 `echo` 或 `add`。
- 只使用认证主体中的 tenant，不接受客户端 tenant。
- 保存固定 ID、名称、类型、Schema、风险等级和启用状态。
- `createdBy`、`updatedBy` 使用当前认证主体。
- 在同一 JDBC 事务内保存定义并记录成功审计。
- 安装成功返回现有工具摘要结构，并包含运行时就绪状态。

幂等与冲突：

- 固定 ID 下已经存在完全匹配且启用的定义时，返回现有定义，不重复写入。
- 固定 ID 已被其他定义占用时返回 `409 Conflict`。
- 当前租户已有同名但不同 ID 的工具时返回 `409 Conflict`。
- 已有定义被手工修改、停用或发生类型漂移时返回 `409 Conflict`，不自动覆盖或降低安全约束。
- 未知 `key` 返回 `404 Not Found`。
- 非允许 profile 或非示例租户调用安装接口时返回 `404 Not Found`。

审计事件使用独立且稳定的事件类型，例如 `LOCAL_EXAMPLE_INSTALL`，资源类型为 `TOOL`，资源 ID 为固定工具 ID。审计写入失败时事务回滚并由现有全局错误链路返回 `503`。

## 9. 运行时就绪状态

工具摘要新增向后兼容字段 `runtimeReady`：

- `HTTP`：工具已启用、HTTP 配置存在、配置 tenant/toolId 与定义一致，且定义 endpoint 与配置 `urlTemplate` 一致时为 `true`。
- `LOCAL`：`ToolRegistry.snapshot(toolId)` 存在，且注册定义与持久化定义的 tenant、ID、名称一致时为 `true`。
- 其他类型或配置漂移时为 `false`。
- 已停用工具始终为 `false`。

该字段只表示调试前的当前快照，不替代执行时治理检查。真正调用时仍由 `GovernedToolExecutionService` 重新校验状态和快照，避免检查后撤销、禁用或注册漂移造成绕过。

页面对 `runtimeReady=false` 的 LOCAL 工具展示“未注册执行器”，不放入可调用工具下拉框。正式业务 LOCAL 工具完成 Java 注册并重启后，刷新页面即可显示为就绪。

## 10. 控制台设计

工具治理页面保留现有通用 Tool 注册表单，并增加明确说明：

> 普通 LOCAL 工具表单只保存治理元数据；执行器必须由 Server 中的 Java 代码注册。

页面新增“内置 LOCAL 示例”区域：

- 展示 `echo` 和 `add`。
- 展示说明、示例输入、安装状态和运行时状态。
- 未安装时显示“添加示例工具”按钮。
- 已安装时禁用重复添加，并显示“已安装、运行时已就绪”。
- 目录为空时隐藏整个区域，不在生产类 profile 展示开发入口。

安装成功后：

1. 刷新内置目录和工具列表。
2. 自动选中新安装工具。
3. 自动把 `sampleInput` 格式化后写入调试 JSON 编辑区。
4. 将用户视线或键盘焦点引导到调试区域。
5. 用户点击“调用/调试”后，复用现有 `debugTool()` 请求。

工具卡片对 `HTTP` 或 `runtimeReady=true` 的 `LOCAL` 工具提供“调用/调试”按钮。点击按钮会选中对应工具并打开或定位到调试区域；不会绕过现有 HIGH 风险名称确认。

所有动态内容继续通过 `textContent` 或安全 DOM API 渲染，不使用 `innerHTML` 拼接服务端数据。

## 11. 正式业务 LOCAL 工具边界

正式业务工具仍遵循现有接入流程：

1. 通过治理 API 创建或保存受租户约束的 `ToolDefinition`。
2. 开发者实现 `ToolExecutor`。
3. Server 扩展代码按 tenant 和 toolId 读取原定义。
4. 在同一 Server JVM 中调用 `ToolRegistry.register(definition, executor)`。
5. 页面刷新后通过 `runtimeReady` 确认注册状态。
6. 调试、Agent 运行和 MCP 调用继续走现有治理链路。

内置示例实现不能根据名称、描述或客户端字段为其他工具绑定执行器，也不能成为通用动态 LOCAL 工具工厂。

## 12. 错误处理

- 页面目录加载失败：内置区域显示受控错误，不影响普通工具列表和 HTTP 工具使用。
- 安装权限不足：返回 `403` 并记录拒绝审计。
- MySQL 唯一键或固定 ID 冲突：转换为可读的 `409` 中文错误。
- 审计失败：不吞掉异常，不把安装结果伪装成成功。
- 执行器输入错误：返回受控工具失败结果，由调试响应统一脱敏。
- 工具定义存在但注册快照漂移：页面显示未就绪，实际调用仍返回固定受控失败。

错误响应、日志和审计不得包含 JWT、数据库密码、模型 API Key、完整 JDBC URL、堆栈或未经脱敏的工具输入。

## 13. 测试策略

### 13.1 单元测试

- `echo` 成功、空消息、非对象和非法 JSON。
- `add` 精确小数、缺少参数、非数字和非法 JSON。
- 固定目录只包含两个受审核 key。
- 运行时就绪状态覆盖注册成功、未注册、tenant 漂移、ID 漂移、名称漂移和停用。
- 安装服务覆盖首次安装、重复安装、固定 ID 冲突、同名冲突、漂移和未知 key。

### 13.2 Web 与安全测试

- `GET /api/tools/local-examples` 的认证、`tool:read` 权限和租户隔离。
- `POST /api/tools/local-examples/{key}` 的认证、`tool:grant` 权限、拒绝审计和成功审计。
- 非允许 profile 不提供安装能力。
- 安装后调用现有 `/api/tools/{id}/debug` 能得到预期结果。
- HIGH 风险确认、输出脱敏和现有 HTTP 调试行为不回退。

### 13.3 控制台测试

- 内置目录渲染、空目录隐藏和错误提示。
- 安装请求路径、按钮提交状态和重复点击保护。
- 安装成功后工具选择与示例 JSON 自动填充。
- `runtimeReady=false` 的 LOCAL 工具不进入可调试列表。
- 工具卡片“调用/调试”操作不会绕过 HIGH 风险确认。
- 保留当前 HTTP 输入 Schema 默认值和前端校验相关回归。

### 13.4 MySQL/JDBC 集成验证

按仓库约定通过 `ssh rocky` 在 Rocky Linux 容器环境执行：

- 确认远程 Git 提交与待验证本地提交一致。
- 确认 Docker 可用，容器内 Maven 使用 JDK 21。
- 使用 `maven:3.9.9-eclipse-temurin-21`。
- 验证首次安装持久化、重复安装、事务审计回滚和服务重启后的调试。
- 不执行全局容器、卷或镜像清理。

## 14. 文档与兼容性

需要同步更新：

- `README.md`：说明 MySQL 调试 profile 的内置示例入口。
- `docs/tool-development-guide.md`：区分页面安装内置示例与正式 Java LOCAL 工具。
- `docs/release-notes.md`：记录页面安装、运行时就绪状态和 profile 边界。

API 只新增端点和响应字段，不删除或重命名现有字段。数据库 Schema 不变。现有 HTTP 工具和正式 LOCAL 注册接口保持兼容。

实现时必须保留工作区中已有的控制台 HTTP Schema 修复以及其他用户未提交修改，不进行无关格式化或配置清理。

## 15. 验收标准

- 在允许的 MySQL 调试 profile 中，管理员能从工具治理页面分别安装 `echo` 和 `add`。
- 安装动作只在用户点击后发生，服务启动不会自动新增数据库记录。
- 页面安装成功后自动填入示例输入，并能通过现有调试入口得到预期输出。
- 服务重启后，已安装示例仍显示运行时已就绪并可调试。
- 重复安装不重复写入；冲突不会覆盖用户数据。
- 普通、未注册执行器的 LOCAL 工具明确显示未就绪，不能从页面直接调用。
- 非 MySQL 或生产类 profile 不展示内置目录，也不能调用安装接口。
- 正式业务 LOCAL 工具仍必须通过 Java `ToolExecutor` 注册。
- 权限拒绝、租户隔离、严格审计和输出脱敏测试通过。
- 不新增数据库迁移，不写入任何真实 secret 或生产凭据。
