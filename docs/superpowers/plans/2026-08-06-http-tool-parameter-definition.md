# HTTP Tool 扁平参数定义实施计划

对应需求：[HTTP Tool 扁平参数定义需求设计](../specs/2026-08-06-http-tool-parameter-definition-design.md)。

## 1. Core 领域模型

- 新增 `HttpParameterDataType` 和 `HttpParameterDefinition`。
- `HttpParameterLocation` 增加 `BODY_ROOT`。
- `HttpToolConfig` 只保留 `parameters`，删除旧构造器和映射字段，并允许不可变空列表表示无参数接口。
- 增加参数对象不变量单元测试。

## 2. 参数编译和运行时

- 在 `cm-agent-server.runtime.http` 新增参数定义编译器。
- 空参数数组生成封闭空对象 Schema，并映射为空请求组成部分。
- 校验参数树、数组匿名元素、位置类型、URL PATH 占位符和复杂度上限。
- 生成 JSON Schema 2020-12 文本。
- `HttpToolInputMapper` 按顶层位置构造请求并支持 `BODY_ROOT`；删除旧参数映射逻辑。
- 增加嵌套对象、对象数组、简单数组、根数组、默认值和非法树测试。

## 3. 管理服务与 Web API

- `HttpToolCreateSpec`、创建和更新流程接入 `parameters`，在事务写入前生成并校验 Schema。
- `ToolController` 增加参数请求/响应 DTO，要求 `parameters` 为非 null 数组并允许空数组。
- 更新创建、编辑、MCP Schema 和调试测试。

## 4. JDBC 与 Flyway

- 新增 V6 可空 `parameter_definitions` 列。
- 更新 `JdbcHttpToolConfigRepository` 的保存、查询和批量查询。
- 增加新配置、空参数数组往返、旧列删除和迁移元数据测试。
- 在 Rocky Linux 容器环境运行 PostgreSQL 16 与 MySQL 8.4 验证。

## 5. 控制台

- 用树形参数编辑器替代新建流程中的 Schema/映射 JSON 输入；通过节点内“添加子参数”建立层级，提交时按先序遍历输出扁平数组。
- 参数树为空时允许直接提交 `parameters: []`，并显示无参数工具提示。
- 支持父节点、数组元素、位置、类型、必填、描述、默认值和删除。
- 编辑时根据扁平 `parameters` 回填参数树，移除旧 JSON 配置入口。
- 更新前端核心函数和资源测试，并执行窄屏视觉检查。

## 6. 文档与示例

- 更新 README、配置说明、工具开发指南、部署说明和发布说明。
- 更新 `http-tool-client` 示例为新参数定义格式。
- 示例只使用占位域名和 Secret 引用。

## 7. 验证顺序

1. [x] JDK 21 环境检查。
2. [x] Core、Console、Server 快速单元测试。
3. [x] JavaScript 测试与语法检查。
4. [x] Rocky Linux 容器中的 Persistence/Testcontainers 测试。
5. [x] 按模块完成与改动相关的测试，并记录未执行全量测试的原因。

## 8. 实际验证结果

- 本地 Core、Console、Server 定向测试、JavaScript 测试、HTTP 示例测试和跳过测试打包均已通过。
- 2026-08-07 使用 Rocky Linux 上的 Maven 3.9.9/JDK 21 容器执行 `mvn -q -pl cm-agent-persistence -am test`，109 项测试全部通过。
- PostgreSQL 16.14 与 MySQL 8.4 均成功执行 V1–V7，HTTP 参数定义 JDBC 往返和旧列删除断言通过。
- 未再次运行整个仓库的 `mvn -q test`；本需求相关的 Core、Server、Console、示例和 Persistence 测试已分别完成。
- 无参数增量验证中，Node 36 项、Server 定向 67 项通过；浏览器完成空参数创建和编辑回填；Rocky Persistence reactor 110 项全部通过，其中 JDBC HTTP 配置测试 7 项通过。

## 9. 关联文档

- [需求设计](../specs/2026-08-06-http-tool-parameter-definition-design.md)
- [实现说明](../implementation/2026-08-06-http-tool-parameter-definition-design.md)
- [进度账本](../progress/2026-08-06-http-tool-parameter-definition-ledger.md)
