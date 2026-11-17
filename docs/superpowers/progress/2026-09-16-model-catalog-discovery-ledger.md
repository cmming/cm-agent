# 模型配置远程模型目录发现进度账本

## 状态

| 任务 | 状态 | 结果 |
| --- | --- | --- |
| Context7 与供应商接口调研 | 已完成 | 已确认 Spring Boot 出站 HTTP 超时配置思路，并核对 OpenAI 与 DashScope 的模型列表接口模式 |
| 方案头脑风暴 | 已完成 | 明确采用服务端代发、显式拉取、下拉选择和手工输入兜底 |
| 后端实现 | 已完成 | 新增独立目录发现配置、受控服务、两条 Controller 入口、稳定错误码、审计及诊断日志 |
| 前端实现 | 已完成 | v2 表单新增获取按钮、状态、候选 datalist、已保存凭据与草稿凭据的安全分流及手工回退 |
| 自动化测试 | 已完成 | Java 21 下服务层 4 项、Controller 7 项定向测试通过；Node 控制台 69 项与控制台 Maven 测试通过 |
| 浏览器验证 | 已完成 | 本地 test profile 健康检查为 UP；模型配置页面资源包含新控件，未认证浏览器按预期跳转登录页 |
| 生产文档与发布说明 | 已完成 | 已更新 README、配置、运维与发布说明，并更新本组设计、计划和实现文档 |

## 实际边界

- 模型目录必须由后端请求，浏览器不携带 API Key 访问供应商。
- 默认目录白名单仅包含 `api.openai.com`、`dashscope.aliyuncs.com` 与 `dashscope-intl.aliyuncs.com`；私有或第三方网关需由部署者显式加入。
- 已保存配置路径只读取当前 tenant 内配置的 Provider、地址与密文；页面修改 Provider 或地址后必须改用草稿 API Key。
- 请求不跟随重定向，默认超时 5 秒、响应上限 128 KiB、返回上限 200 个名称；第一版不分页、不缓存、不写入数据库。
- `404`、`405` 表示不支持目录发现，页面保留手工输入；其他受控失败使用 `MODEL_DISCOVERY_*`、脱敏中文信息和 `errorId`。

## 实际验证

- 使用 Java 21 执行 `mvn -q -pl cm-agent-server -am "-Dtest=ModelCatalogDiscoveryServiceTest,ModelConfigControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，服务层 4 项和 Controller 7 项通过。
- 执行 `node --test cm-agent-console/src/test/js/console-core.test.cjs`，69 项通过；执行 `mvn -q -pl cm-agent-console -am test` 通过。
- 使用 test profile 启动本地服务，`GET /actuator/health` 返回 `UP`，页面资源包含 `discoverModelNamesBtn`、`modelCatalogOptions` 和 `modelCatalogStatus`；浏览器未登录时跳转登录页，符合会话保护预期。
- 已执行 `mvn -q -pl cm-agent-server -am test`；本机没有可用 Docker，13 个既有 Testcontainers 持久化测试无法启动，未发现本次模型目录发现代码的断言失败。
- 本次无 schema、JDBC Repository 或 Flyway 变更，因此未执行 Rocky Docker/Testcontainers 专项迁移验证。
- 未执行真实供应商目录请求，避免在验证中使用或传输任何生产 API Key；实际供应商连通性需由部署环境以测试凭据和已审核白名单另行验收。

## 提交信息

未提交。

关联文档：[设计](../specs/2026-09-16-model-catalog-discovery-design.md)、[计划](../plans/2026-09-16-model-catalog-discovery.md)、[实现说明](../implementation/2026-09-16-model-catalog-discovery-implementation-design.md)。

## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：模型目录发现的实现、安全/权限与目标验证已记录完成。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
