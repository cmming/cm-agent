# 模型配置管理实施计划

## 任务拆分

1. 扩展 `ModelConfig` 不变量和 `ModelConfigRepository` CRUD 契约。
2. 在 `JdbcModelConfigRepository` 与 `InMemoryPlatformStore` 实现租户隔离读写、引用检查和删除。
3. 新增 `ModelConfigCommandService` 与 `ModelConfigController`，接入权限、事务、审计和冲突响应。
4. 扩充 bootstrap admin 模型配置权限并同步认证测试。
5. 新增 v2 模型配置页面、导航、脚本状态与安全交互。
6. 将 API Key 加密写入模型配置，替换 YAML 凭据提供者并新增 PostgreSQL/MySQL V9 列注释迁移。
7. 补核心、持久化、Web、控制台测试及生产文档。
8. 使用 JDK 21 执行打包、单元/Web 测试；数据库集成测试按仓库约定在 Rocky Linux 容器环境执行。

## 涉及文件

- `cm-agent-core`：模型配置领域对象、Repository 契约和单元测试。
- `cm-agent-persistence`：JDBC Repository、V9 方言迁移与 PostgreSQL/MySQL 集成测试。
- `cm-agent-server`：加密主密钥配置、数据库凭据提供者、memory Repository、命令服务、Controller、权限和 MockMvc 测试。
- `cm-agent-console`：v2 页面、导航、样式、交互脚本与资源测试。
- `README.md`、`docs/configuration.md`、`docs/operations.md`、`docs/release-notes.md`。

## 实现顺序

领域契约 → 加密编解码与两种持久化实现 → 真实运行时提供者 → 服务与 Controller → 页面与交互 → 文档 → 综合验证。

## 验证方式

- `mvn -q -pl cm-agent-core -Dtest=ModelConfigTest test`
- `mvn -q -pl cm-agent-console test`
- `mvn -q -pl cm-agent-server -am -Dtest=ModelConfigControllerTest,AuthControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -q -DskipTests package`
- Rocky Linux 容器内执行 `mvn -q -pl cm-agent-persistence -am -Dtest=JdbcModelConfigRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

设计依据见 [2026-08-18-model-config-management-design.md](../specs/2026-08-18-model-config-management-design.md)。
