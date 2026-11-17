# 模型配置管理进度账本

## 状态

| 任务 | 状态 | 结果 |
| --- | --- | --- |
| 领域与 Repository 契约 | 已完成 | 增加安全地址不变量和完整 CRUD 契约 |
| 加密凭据持久化 | 已完成 | AES/GCM 密文写入、轮换、memory/JDBC 读取和 V9 数据库注释迁移 |
| 真实运行时 | 已完成 | 移除 YAML 凭据绑定，默认按租户从数据库密文解析模型凭据 |
| REST、权限与审计 | 已完成 | 创建必填 API Key、更新可轮换、响应/审计不回显，保留五类接口和三类成功审计 |
| v2 控制台页面 | 已完成 | 密码框创建、编辑留空保留、填写即轮换，详情不回显 |
| 测试与生产文档 | 已完成（远程受限） | 已通过定向 Web/运行时、控制台、编译和打包复验；远程数据库验证因版本前置条件不满足未执行 |

## 已执行验证

- JDK 21.0.11、Maven 3.9.4 环境确认通过。
- `cm-agent-core` 全模块 64 个测试通过，包含 `ModelConfigTest` 2 个新增测试。
- `ConsoleResourceTest`：12 个测试通过。
- `ModelConfigControllerTest`、`AgentScopeRuntimeConfigurationTest` 与 `DatabaseModelCredentialProviderTest`：使用测试 profile 关闭真实 AgentScope runtime 后通过，覆盖密文写入、轮换、无 YAML 凭据启动和跨租户拒绝。
- `node --test cm-agent-console/src/test/js/console-core.test.cjs`：38 个测试通过。
- `mvn -q -pl cm-agent-console test`：通过。
- `mvn -q -pl cm-agent-persistence -am -DskipTests test-compile`：通过。
- `mvn -q -DskipTests package`：通过。
- Node 控制台核心脚本测试：38 个测试通过。
- v2 模型配置页浏览器检查通过：1280px 视口下侧栏与两列内容区尺寸正常、无横向溢出、无脚本警告或错误。
- `mvn -q -DskipTests package`：通过。
- 首次扩大服务端测试仍受用户现有配置在严格 profile 中启用 HTTP 明文协议影响，相关 profile 安全断言按设计失败；未修改这些用户配置文件。

## 未执行验证

- Rocky Linux 的 Docker 23.0.6 可用，但当前 SSH 主机默认 Maven 3.6.3/JDK 17，且 `/data/cm-agent` 不存在 Git 工作区，无法确认远端提交与本地待验证内容一致，也无法满足 Maven 3.9+/JDK 21 前置条件，因此未执行 `JdbcModelConfigRepositoryTest`。本机未替代运行 Testcontainers，符合仓库约束。

## 遗留问题

未实现明文存储和接口回显：这与仓库既定的密钥安全边界冲突，已改为加密存储与只写入轮换。数据库 CRUD 与 V9 注释仍需在具备相同 Git 提交的 Rocky 工作区中补跑 PostgreSQL/MySQL 集成验证。

## 提交信息

未提交。

相关文档：[设计](../specs/2026-08-18-model-config-management-design.md)、[计划](../plans/2026-08-18-model-config-management.md)、[实现说明](../implementation/2026-08-18-model-config-management-implementation-design.md)。

## 全量核定记录（2026-11-13）

- 核定结论：待续。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：JdbcModelConfigRepositoryTest 与双库 CRUD/V9 注释验证仍待符合基线的 Rocky 环境。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：上述剩余事项继续由原任务/验收范围跟踪；本次不执行它们，不依据后来相关主题自动宣布原验收通过，也不补造取消或移交。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
