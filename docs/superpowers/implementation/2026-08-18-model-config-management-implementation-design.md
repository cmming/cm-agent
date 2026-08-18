# 模型配置管理实现说明

## 最终实现

`ModelConfig` 现在统一规范化非空文本，并校验字段长度和 HTTP(S) 地址安全边界；标识完整性仍由既有运行请求边界校验。`ModelConfigRepository` 增加创建、更新、列表、事务锁定读取、Agent 引用检查和删除契约。

`ModelCredentialCipher` 使用随机 IV 的 AES/GCM 加密 API Key。`JdbcModelConfigRepository` 新增密文保存、轮换和按租户运行时读取契约；普通 CRUD 查询仍不选择该列。`InMemoryPlatformStore` 同样只保留密文，保证本地和测试不会弱化生产安全边界。所有资源操作均带 `tenant_id` 条件，列表使用显示名称和 ID 稳定排序。PostgreSQL/MySQL V9 方言迁移将 `encrypted_api_key` 的原生注释更新为加密密文语义。

`ModelConfigCommandService` 在 JDBC 事务中绑定写操作、密钥加密与审计。创建必须传入 API Key，更新传入新值时轮换密文，省略时保留原值。更新和删除锁定模型配置行；删除前查询同租户 Agent 引用，外键冲突作为并发兜底转换为 `409`。固定系统默认配置因启动初始化器会自动补齐而禁止删除，避免产生“删除后重启又出现”的误导语义。memory 模式在审计失败时恢复原配置、原密文或撤销创建/删除，避免把严格审计失败伪装为业务成功。

`ModelConfigController` 暴露：

- `GET /api/model-configs`
- `GET /api/model-configs/{id}`
- `POST /api/model-configs`
- `PUT /api/model-configs/{id}`
- `DELETE /api/model-configs/{id}`

Controller 只从 JWT 会话取得 tenant，分别校验 `model:read`、`model:write`、`model:delete`。创建请求 record 接受必填的 `apiKey`，更新请求可选接受该字段；响应始终使用不含凭据的 `ModelConfig`。

`AgentScopeRuntimeConfiguration` 不再创建 `ExternalModelCredentialProvider`，改为装配 `DatabaseModelCredentialProvider`。该提供者按租户读取仓储密文，仅在当前调用期间解密；`cm-agent.agentscope.credentials` 已从属性绑定模型中移除。`cm-agent.model-credentials.encryption-key` 只接收 Base64 编码的 256 位 AES 主密钥，不承载模型 API Key。

v2 新增 `/console/v2/model-configs.html`。共享脚本维护模型配置列表、选择、编辑状态和加载代际，支持创建、全量更新、API Key 轮换与确认删除；编辑密钥框永远不回填，所有动态内容使用 DOM 安全渲染。七个 v2 页面统一增加模型配置导航并更新资源版本号为 `2.0.8`。

## 关键调用链

认证 JWT → `ModelConfigController` 解析可信主体 → `PermissionEvaluator` → `ModelConfigCommandService` → `ModelConfigRepository` → memory/JDBC → `AuditAppender`。

## 与设计差异

新增 V9 数据库注释迁移，但未修改既有表结构。由于安全约束，未按“明文存储、接口回显”的需求实现；改为加密存储与只写入轮换。v1 控制台未扩展，符合非目标范围。

设计与计划分别见 [设计](../specs/2026-08-18-model-config-management-design.md) 和 [计划](../plans/2026-08-18-model-config-management.md)。
