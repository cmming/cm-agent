# 模型配置远程模型目录发现实现说明

## 实际实现

本次在不修改 `model_configs` 表和 Agent 模型绑定协议的前提下，完成了模型名称目录发现能力。

- `ModelCatalogDiscoveryProperties` 以 `cm-agent.model-catalog-discovery.*` 提供独立的 HTTP 开关、主机白名单、5 秒超时、128 KiB 响应上限和 200 项目录上限。它通过组合生成 URL 策略配置，不继承 HTTP 工具配置，避免 Spring 将两个配置对象误识别为同一个注入点。
- `ModelCatalogDiscoveryService` 通过 JDK `HttpClient` 发起不跟随重定向的 `GET {baseUrl}/models`，解析 `data[].id`，去重、排序、截断后返回字符串列表。`OPENAI_COMPATIBLE` 与 `DASHSCOPE_NATIVE` 均使用已含 API 前缀的 `baseUrl`；第一版不自动分页、缓存或持久化目录。
- 草稿路径只在本次调用内使用 `apiKey`；已保存路径以可信 `tenantId + modelConfigId` 读取配置与凭据，客户端不能提供目标地址。凭据读取失败、请求失败和解析失败均写入 `MODEL_CONFIG_MODEL_DISCOVERY` 失败审计。
- `ModelConfigController` 新增 `POST /api/model-configs/discover-models` 和 `POST /api/model-configs/{id}/discover-models`，均要求 `model:write`。成功响应为 `{ "items": ["model-a"] }`。
- `ApiExceptionHandler` 将发现服务的受控异常转换为稳定 `MODEL_DISCOVERY_*` 错误码、脱敏中文原因和同一 `errorId`；后台诊断日志使用该 `errorId` 关联。
- v2 模型配置页面增加“获取模型列表”按钮、状态文本和 `datalist`。创建或修改 Provider、地址时必须输入本次 API Key；未修改已保存配置时可使用服务端已保存凭据。候选列表不会自动覆盖当前模型名称，手工输入始终可用。

## 与设计的差异

设计阶段曾将 DashScope 分页列为候选能力。当前实现明确只消费一次 `data[].id` 响应，不自动追加页参数或推测供应商分页字段；这是为了避免把不同供应商的游标语义与凭据携带请求混入首版安全边界。其余调用链、安全约束和交互方案与设计一致。

## 验证与限制

服务层 4 项覆盖目录排序去重、已保存凭据目标绑定、缺少凭据提供者、供应商失败映射及 API Key 不泄露；Controller 7 项覆盖草稿请求、`model:write` 拒绝和错误响应。Node 控制台 69 项与控制台 Maven 测试通过，资源测试覆盖新元素、脚本版本与发现入口引用。本地 test profile 健康检查为 `UP`，页面资源含获取按钮、候选 datalist 和状态提示；未认证浏览器按预期跳转登录页。

本功能没有数据库迁移，不需要为本次逻辑改动执行 JDBC/Testcontainers 专项迁移验证。浏览器烟测和更大范围构建的最终结果记录在[进度账本](../progress/2026-09-16-model-catalog-discovery-ledger.md)。
