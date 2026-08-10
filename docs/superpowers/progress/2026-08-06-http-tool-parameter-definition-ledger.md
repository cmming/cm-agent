# HTTP Tool 扁平参数定义进度账本

对应需求：[HTTP Tool 扁平参数定义需求设计](../specs/2026-08-06-http-tool-parameter-definition-design.md)。  
对应计划：[HTTP Tool 扁平参数定义实施计划](../plans/2026-08-06-http-tool-parameter-definition.md)。  
对应实现：[HTTP Tool 扁平参数定义实现说明](../implementation/2026-08-06-http-tool-parameter-definition-design.md)。

| 工作项 | 状态 | 实际结果 | 提交 |
| --- | --- | --- | --- |
| Core 参数模型 | 已完成 | 新增数据类型和扁平参数节点，以 `id + parentId` 表达对象、数组和数组元素 | 未提交 |
| Schema 编译与请求映射 | 已完成 | 服务端自动生成 Schema，并支持 PATH、QUERY、HEADER、BODY、BODY_ROOT | 未提交 |
| 历史映射移除 | 已完成 | 删除旧映射领域类型及运行时逻辑，HTTP API 只接受 `parameters` | 未提交 |
| JDBC 与迁移 | 已完成 | V6 新增参数定义列，V7 删除 `input_schema` 和 `parameter_mappings` 旧列 | 未提交 |
| 控制台参数编辑器 | 已完成 | 树形录入、扁平提交、编辑回填和完整示例已实现，各工具模块宽度为 100% | 未提交 |
| 文档与示例 | 已完成 | 配置、部署、工具开发、发布说明及 HTTP 客户端示例已更新 | 未提交 |
| 本地自动化验证 | 已完成 | Core/Console 测试通过；服务端定向 149 项通过；Node 35 项通过；示例测试与跳过测试打包通过 | 未提交 |
| 浏览器验证 | 已完成 | 6 个参数节点注册成功，编辑树正确回填，旧字段不存在，主要模块宽度与父容器一致 | 未提交 |
| PostgreSQL/MySQL 集成验证 | 已完成 | 2026-08-07 在 Rocky Linux 9.3、Docker 23.0.6、Maven 3.9.9/JDK 21.0.7 容器中执行 109 项测试，全部通过；PostgreSQL 16.14 与 MySQL 8.4 均从空库迁移到 V7 | 未提交；临时验证提交 `12c7fb1` |
| 空参数 HTTP Tool | 已完成 | `parameters: []` 创建、空 Schema、无参数映射、JDBC `[]` 往返、控制台直接保存和编辑回填均通过；本地 Node 36 项、Server 定向 67 项以及 Core/Console 测试通过；Rocky Persistence reactor 110 项通过 | 未提交；临时验证提交 `7c0abf44` |

## 验证证据

- 本地和远端检出的完整提交号均为 `12c7fb1529247a34b119a54edeafdee607929f0c`。
- 实际命令：`mvn -q -pl cm-agent-persistence -am test`。
- 测试汇总：109 项通过，0 失败、0 错误、0 跳过。
- `JdbcHttpToolConfigRepositoryTest` 6 项通过，`MigrationTest` 2 项通过。
- Flyway 在 PostgreSQL 16.14 和 MySQL 8.4 上均验证并执行 V1–V7，最终版本为 V7。
- Testcontainers 临时容器自动清理；远端 `/tmp/cm-agent-http-v7-12c7fb15` 和对应 bundle 已在路径核验后删除，不可恢复。
- 无参数增量验证的本地和远端提交均为 `7c0abf44f5f1092a6590040a0b6ef05aa30c9bba`。
- 无参数增量本地验证：Node 36 项、Server 定向 67 项以及 Core、Console 测试全部通过；浏览器创建成功，编辑时保持 0 个参数。
- 无参数增量 Rocky 验证：Persistence reactor 110 项通过，0 失败、0 错误、0 跳过；`JdbcHttpToolConfigRepositoryTest` 7 项通过。
- 增量 Testcontainers 容器自动清理；远端 `/tmp/cm-agent-empty-parameters-7c0abf44` 和对应 bundle 已在路径核验后删除，不可恢复。

## 遗留问题与注意事项

- 本需求明确不兼容历史 HTTP Tool 数据，升级已有数据库前需要备份，并按最新参数结构重新注册旧工具。
- Flyway 对 MySQL 8.4 输出版本升级建议，提示当前依赖声明的最新已测试版本为 MySQL 8.1；本次 MySQL 8.4 迁移和全部相关测试实际通过。
