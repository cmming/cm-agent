# 技能版本发布、依赖预检与指定版本试运行进度账本

## 1. 状态与文档索引

- 日期与主题：2026-09-22，`skill-release-validation`。
- 当前阶段：T1～T10 已提交；T11 的本地回归和文档已完成，远程双库与完整浏览器闭环结果待补充。
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
| T11 | 双库、端到端回归和正式文档收口 | 文档与本地回归完成；远程双库和完整浏览器闭环待补充 |

## 3. 本阶段验证

| 检查 | 结果 |
| --- | --- |
| Java 21 Reactor 打包 | `mvn -q -pl cm-agent-server -am -DskipTests package` 通过 |
| core/adapter/console 回归 | `mvn -q -pl cm-agent-core,cm-agent-agentscope-adapter,cm-agent-console -am test` 通过 |
| 控制台与接口回归 | `ConsoleResourceTest`、`AgentSkillControllerTest`、`SkillControllerTest` 通过 |
| Node 回归 | `node --test .../skills.test.cjs .../console-core.test.cjs` 通过，共 75 项 |
| 静态语法 | `node --check` 覆盖 `skills.js`、`app.js`，通过 |
| 浏览器 | 登录页与资源版本已通过 Playwright 快照检查；完整闭环未通过：18080 隔离服务因单模块 Maven 解析本地旧 core 产物而无法启动，8080 为用户 IntelliJ 实例，未干预 |
| Rocky PostgreSQL 16 / MySQL 8.4 | 未执行：`ssh rocky` 的 Docker 23.0.6 可用，但仅有 Maven 3.6.3、Java 17，且不存在 `/workspace/cm-agent`；不满足规定的 Maven 3.9.9 / Java 21 容器和同提交工作区前置条件 |

## 4. 风险与后续

- TEST Run 真实调用模型和工具，可能产生外部副作用；发布前须由具备 `skill:write` 与 `agent:run` 的管理员确认。
- 依赖映射变化会令旧试运行失去发布资格；服务端发布时会重新校验，不依赖浏览器状态。
- 部署升级需先执行 PostgreSQL/MySQL V13 Flyway 迁移；不要回滚到不识别 format 2 快照的旧服务。
- 后续应在干净的完整 reactor 启动环境补跑浏览器闭环，并在 Rocky 准备 Maven 3.9.9 / Java 21 容器和同步本分支提交后，分别完成 PostgreSQL 16 与 MySQL 8.4 专项测试。

## 5. 保护范围

- 本任务仅在 `codex/skill-release-validation` 工作树提交显式路径；未覆盖用户已有配置或用户正在运行的 IntelliJ 服务。
- 未写入真实 JWT、数据库密码、模型 API Key 或生产 JDBC URL。
