# 技能版本发布、依赖预检与指定版本试运行进度账本

## 1. 状态与文档索引

- 日期与主题：2026-09-22，`skill-release-validation`。
- 当前阶段：T1～T10 已提交；T11 Rocky 双库验证通过，真实浏览器仅部分通过，试运行提交失败、预检状态显示异常，完整浏览器闭环未完成。
- 设计：[设计规格](../specs/2026-09-22-skill-release-validation-design.md)。
- 计划：[实施计划](../plans/2026-09-22-skill-release-validation.md)。
- 实现：[实现说明](../implementation/2026-09-22-skill-release-validation-implementation-design.md)。

## 2. 实施任务状态

| 任务 | 内容 | 状态与提交 |
| --- | --- | --- |
| T1 | Core 状态、错误与 Repository 契约 | 完成：`ab68d20` |
| T2 | 技能包依赖解析与摘要 | 完成：`9119629` |
| T3 | memory 工作单元与新增 Repository | 完成：`f08f309` |
| T4 | V13 双库迁移和 JDBC 合同 | 完成：`c206b5e` |
| T5 | 候选、版本历史和差异 API | 完成：`20c59ce` |
| T6 | 映射和两层预检 API | 完成：`3356549`、兼容修复 `2f24bc9` |
| T7 | 发布、回滚和绑定策略 | 完成：`3278f50` |
| T8 | TEST Run、临时注入和审批恢复 | 完成：`03746bb` |
| T9 | format 2 快照与 AgentScope 依赖上下文 | 完成：`7495338` |
| T10 | v2 连续技能发布工作区 | 完成：`e657cd3` |
| T11 | 双库、端到端回归和正式文档收口 | Rocky 持久化全量测试通过；浏览器完整流程未通过，见下方结果 |
| T12 | 审查修复与回归补强 | 完成：补齐 TEST Run 审批发现/恢复、审批创建失败补偿、必需依赖复核、功能开关和运行级审批过期清理；提交 `4e8a605` |

## 3. 本阶段验证

| 检查 | 结果 |
| --- | --- |
| Java 21 Reactor 打包 | `mvn -q -pl cm-agent-server -am -DskipTests package` 通过 |
| core/adapter/console 回归 | `mvn -q -pl cm-agent-core,cm-agent-agentscope-adapter,cm-agent-console -am test` 通过 |
| 控制台与接口回归 | `ConsoleResourceTest`、`AgentSkillControllerTest`、`SkillControllerTest` 通过 |
| Node 回归 | `node --test .../skills.test.cjs .../console-core.test.cjs` 通过，共 75 项 |
| 静态语法 | `node --check` 覆盖 `skills.js`、`app.js`，通过 |
| 浏览器 | Playwright 真实浏览器使用隔离端口 18080 和 test profile；登录、创建测试 Agent、上传 ZIP 成功，桌面/390px页面均渲染，移动视口宽 390px、文档宽 390px。结构预检 API 响应 `PASSED`，但页面仍显示“未知”；`POST /api/skills/{id}/trials` 返回 HTTP 400 / `VALIDATION_FAILED`，未进入 Fake Runtime，也未验证发布、审批恢复、固定版本、回滚。test profile 不访问真实模型或外部工具；8080 用户 IntelliJ 实例未触碰。截图在隔离工作树 `output/playwright/skill-release-validation/` |
| Rocky PostgreSQL 16 / MySQL 8.4 | 提交 `4e8a60530e28d4fff2c21cd83f062f32132ccaff` 隔离副本，Maven 3.9.9 / Java 21.0.7 容器执行 `mvn -B -pl cm-agent-persistence -am test`：58 项通过、0 失败、0 错误、0 跳过。Testcontainers 实际运行 PostgreSQL 16.14 与 MySQL 8.4，V13 方言迁移和字段注释校验通过。Flyway 对 MySQL 8.4 发出当前版本支持提示（最新受测版本 8.1），但测试成功 |
| 审查修复定向回归 | Java 21：`mvn -pl cm-agent-server -am '-Dtest=SkillTrialServiceTest,SkillRuntimeServiceTest,ToolApprovalServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过，19 项；`node --check .../skills.js`、`node --test .../skills.test.cjs` 通过 |

## 4. 风险与后续

- TEST Run 真实调用模型和工具，可能产生外部副作用；发布前须由具备 `skill:write` 与 `agent:run` 的管理员确认。
- 依赖映射变化会令旧试运行失去发布资格；服务端发布时会重新校验，不依赖浏览器状态。
- 部署升级需先执行 PostgreSQL/MySQL V13 Flyway 迁移；不要回滚到不识别 format 2 快照的旧服务。
- 浏览器验收仍未完成：应先排查预检结果未刷新及试运行 HTTP 400 的参数校验原因，再以测试运行时覆盖试运行/发布/审批恢复/固定版本/回滚，不接入真实模型或外部副作用。
- Rocky Maven Central 当前可达；双库测试已完成。若改动持久化代码，仍须按仓库要求在 Docker/Maven 容器和 PostgreSQL 16、MySQL 8.4 上重验。

## 5. 保护范围

- 本任务仅在 `codex/skill-release-validation` 工作树提交显式路径；未覆盖用户已有配置或用户正在运行的 IntelliJ 服务。
- 未写入真实 JWT、数据库密码、模型 API Key 或生产 JDBC URL。
