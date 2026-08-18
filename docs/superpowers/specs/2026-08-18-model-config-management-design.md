# 模型配置管理设计

## 背景

项目已有 `model_configs` 表、`ModelConfig` 领域对象和按租户单条读取能力，但缺少管理 API 和控制台页面，模型元数据只能通过数据库初始化或受控部署流程维护。

## 目标

- 提供当前租户模型配置的列表、详情、创建、更新和删除接口。
- 在 v2 控制台新增独立模型配置页面。
- 保持 tenant 隔离、权限校验、权限拒绝审计和写操作严格审计。
- 将模型 API Key 安全持久化到数据库，彻底移除模型凭据 YAML 映射依赖。

## 范围

- `ModelConfigRepository` 扩展完整 CRUD、删除引用检查和事务锁定读取。
- memory、PostgreSQL/MySQL JDBC 实现保持一致语义。
- 新增 `/api/model-configs` REST API 和 `model:read`、`model:write`、`model:delete` 权限。
- 新增 v2 列表、详情、创建、编辑、停启用和删除交互。

## 非目标

- 不保存明文 API Key，也不通过接口、页面、日志或审计回显。
- 不改变 Agent 创建时的模型绑定协议。
- 不为 v1 单页控制台新增同等页面。

## 方案

创建请求包含必填的写入式 `apiKey`；更新请求可选携带该字段，省略时保留已有密文。领域对象统一去除首尾空白并校验长度及 HTTP(S) 绝对地址；地址禁止用户信息和片段。服务使用随机 IV 的 AES/GCM 在进入仓储前加密 API Key，JDBC 与 memory 仓储都只保存密文，普通模型查询不选择该列。

`cm-agent.agentscope.credentials` 不再绑定或读取。真实运行时默认 `DatabaseModelCredentialProvider` 根据 `tenantId + modelConfigId` 查询密文，并仅在本次模型调用中解密；无记录、历史占位值、损坏密文和主密钥不匹配均转换为不泄露细节的“模型凭据不可用”。应用通过受控环境变量提供 Base64 编码的 256 位 AES 主密钥，它不是模型 API Key。

写操作由 `ModelConfigCommandService` 编排。JDBC 模式在事务内完成模型配置写入和审计，更新/删除先锁定目标行；memory 模式在审计失败时执行补偿恢复。删除前检查同租户 Agent 引用，并保留数据库外键作为并发兜底。

Controller 从 JWT 会话构造可信 `PrincipalRef`，不接受客户端 tenant。列表/详情使用 `model:read`，创建/更新使用 `model:write`，删除使用 `model:delete`。权限拒绝写入 `ACCESS_DENIED`，成功写操作分别记录 `MODEL_CONFIG_CREATE`、`MODEL_CONFIG_UPDATE`、`MODEL_CONFIG_DELETE`。

控制台独立页面为 `/console/v2/model-configs.html`，动态文本只通过 `textContent`/DOM 节点渲染。创建表单以密码框写入 API Key；编辑时输入框始终为空，留空保留现有密文，填写新值即轮换，页面不回显密钥。

## 约束

- 所有查询、更新、引用检查和删除都必须携带 tenant 条件。
- API 响应、日志、审计和页面详情不得出现 API Key 或密文；请求只允许通过受保护的写操作提交 API Key。
- 被 Agent 引用的配置以及启动初始化器维护的系统默认配置删除必须返回 `409` 且无副作用。
- 继续兼容 PostgreSQL 16 与 MySQL 8.4。

## 验收标准

1. 有权限主体可完成模型配置 CRUD，跨租户读取返回不存在。
2. 无权限访问返回 `403` 并产生拒绝审计。
3. 非法地址返回脱敏的 `400`，不写入配置。
4. 被 Agent 引用的配置删除返回明确 `409`。
5. API 可写入并轮换 API Key，但不暴露明文或密文；运行时从数据库密文读取，配置文件无需模型 API Key。
6. v2 七个页面导航可到达模型配置页，刷新后可恢复认证。

相关计划见 [2026-08-18-model-config-management.md](../plans/2026-08-18-model-config-management.md)。
