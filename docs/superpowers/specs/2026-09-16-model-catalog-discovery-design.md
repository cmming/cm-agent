# 模型配置远程模型目录发现设计

## 背景

当前 v2 模型配置页面要求使用者手工填写 `modelName`。服务端已保存 Provider、基础地址和经 AES/GCM 加密的 API Key，但没有把供应商提供的模型目录转化为可选择的名称。这会导致配置名称拼写错误、供应商模型升级后仍依赖人工维护，以及不熟悉模型标识的使用者难以完成配置。

`OPENAI_COMPATIBLE` 运行时使用 OpenAI 风格的 `baseUrl` 与模型名；OpenAI 标准提供 `GET /v1/models`。`DASHSCOPE_NATIVE` 使用 DashScope 原生 `baseUrl`，DashScope 提供 `GET /api/v1/models`。两类配置的 `baseUrl` 均已包含供应商 API 前缀，因此第一版统一在其后附加 `/models`，解析通用的 `data[].id`。但“OpenAI Compatible”并不承诺所有自建或第三方网关都实现模型列表，所以自动发现不能替代自由输入。

## 目标

- 在创建或编辑模型配置时，允许用户显式从供应商接口获取模型名称并选择。
- 浏览器只调用 CM Agent API，绝不直接向供应商发送 API Key。
- 保留手工输入模型名称，作为不支持发现接口、临时网络异常或自定义网关的兼容路径。
- 让模型发现遵循租户隔离、权限、出站访问控制、凭据不回显、脱敏日志和可关联错误编号的既有边界。

## 范围

- v2 `model-configs.html` 与共享控制台脚本的模型名称交互。
- 模型目录发现的 Controller、应用服务、Provider 协议适配、出站 HTTP 安全策略和错误转换。
- 模型配置 Web 测试、控制台脚本/资源测试，以及本需求的设计文档。

## 非目标

- 不修改 `model_configs` 表、`ModelConfig` 领域 record 或 Agent 的模型绑定协议。
- 不把完整供应商响应、API Key、Authorization 或模型目录持久化到数据库。
- 不在表单字段变更时自动发起网络请求，不提供批量发现或模型能力/价格管理。
- 不宣称所有 OpenAI Compatible 服务一定支持 `/models`。

## 交互方案

模型名称改为“可搜索选择 + 手工输入”的组合控件：

1. 使用者选择 Provider，填写服务基础地址和 API Key。
2. 点击“获取模型列表”；按钮在 Provider、地址或创建时 API Key 不完整时禁用。
3. 页面调用 CM Agent 的发现接口，成功后按模型 ID 去重、稳定排序，并在可搜索列表中展示。
4. 选择一项会回填 `modelName`；使用者也可继续直接输入任意有效名称。
5. 发现失败只展示脱敏原因、错误码和 `errorId`，不清空当前模型名，提示可手工填写并继续保存。

编辑已有配置时，页面展示已保存的模型名但不回显 API Key。若 Provider 与基础地址均未修改，用户可选择“使用已保存凭据获取”；任一项改变后，页面必须要求重新输入 API Key，不能将旧密钥发送到新地址。

## API 方案

新增两个仅返回受控模型名称的接口：

- `POST /api/model-configs/discover-models`：用于创建前或编辑过程中尚未保存的草稿。请求包含 `providerType`、`baseUrl` 和本次临时 `apiKey`，其中 API Key 仅在调用链内存中使用。
- `POST /api/model-configs/{id}/discover-models`：用于未变更 Provider 与基础地址的已保存配置。服务端在租户边界内读取密文，并只在当前出站调用期间解密。

两个接口均要求 `model:write`，因为该操作会使用或接收写权限才能接触的模型凭据。响应统一为模型名称数组，例如 `items: ["qwen-plus"]`；不透传原始 JSON、供应商拥有者字段、密钥或响应头。

Provider 适配规则：

- `DASHSCOPE_NATIVE` 与 `OPENAI_COMPATIBLE`：构建 `GET {baseUrl}/models`，解析 `data[].id`。第一版只读取一次响应，不自动分页；供应商的下一页或非该结构目录不会被推测处理。
- 收到 `404`、`405` 时返回 `MODEL_DISCOVERY_UNSUPPORTED`；无法识别的结构返回 `MODEL_DISCOVERY_RESPONSE_INVALID`，均保留手工填写路径。

## 安全与可观测性

模型发现是一次携带密钥的服务端出站请求，必须独立于普通业务 HTTP 调用治理：

- 复用或抽取可验证的 URL 规则：仅 HTTP(S)、无用户信息、无片段；对目标主机实施模型供应商专用白名单，并拒绝本机、环回、链路本地、私网及解析后落入受限地址的目标。
- 禁止重定向；设置有限的连接和读取超时、响应大小与最终模型数上限，防止阻塞、内存耗尽和供应商枚举放大。第一版不自动分页。
- 已保存凭据仅可发送到原配置的规范化 Provider 与基础地址；草稿凭据仅可发送用户本次提交的值，不会写库或写日志。
- 仅对显式按钮点击发起请求；第一版不做服务端持久化缓存。前端可保留本页内存结果并提供“刷新”。
- 使用稳定错误码：`MODEL_DISCOVERY_AUTH_FAILED`、`MODEL_DISCOVERY_TIMEOUT`、`MODEL_DISCOVERY_TARGET_REJECTED`、`MODEL_DISCOVERY_UNSUPPORTED`、`MODEL_DISCOVERY_RESPONSE_INVALID`、`MODEL_DISCOVERY_UPSTREAM_ERROR`。所有未预期失败生成 `errorId`，前端和结构化日志使用同一编号。
- 日志只记录可信 tenant、principal、资源 ID、稳定错误码和 `errorId`；不得记录 API Key、Authorization、完整 URL、供应商原始响应或堆栈到前端。显式发现记录 `MODEL_CONFIG_MODEL_DISCOVERY` 审计事件及结果摘要。

## 验收标准

1. 用户可从两个已支持 Provider 的模型目录中选择名称，也可在任意情况下手工填写。
2. 浏览器网络面板不出现对供应商域名的请求，API Key 不出现在响应、页面、审计或日志中。
3. 已保存凭据不会因修改 Provider 或基础地址而发送到新目标。
4. 越权、跨租户、受限目标、鉴权失败、超时、非兼容目录和异常响应都返回正确 HTTP 状态、稳定错误码与脱敏信息；未预期失败带 `errorId`。
5. 出站请求超时、体积、重定向和条目数量均受上限约束；控制台加载、选择、手工回退和刷新都可测试。

## 参考资料

- [Spring Boot 3.5 HTTP 客户端配置](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/autoconfigure/http/client/HttpClientProperties.html)：连接与读取超时的配置边界。
- [OpenAI Models API](https://platform.openai.com/docs/api-reference/models/object?lang=curl)：标准 `GET /v1/models` 和 `data[].id` 返回模式。
- [阿里云 Model Studio：查询模型列表](https://help.aliyun.com/zh/model-studio/list-models)：DashScope `GET /api/v1/models` 与分页约束。

相关计划见 [实施计划](../plans/2026-09-16-model-catalog-discovery.md)，实际状态见 [进度账本](../progress/2026-09-16-model-catalog-discovery-ledger.md)。
