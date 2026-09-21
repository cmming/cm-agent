# Skill 支持进度账本

## 状态与文档索引

- 主题日期：2026-09-20；本次更新：2026-09-21。
- 当前阶段：用户已选择 A，由主代理顺序执行；Task 1～Task 7 已完成，Task 8 待开始。
- 四份同主题文档随任务持续更新和提交；当前分支仅在本地，未推送。
- 关联：[设计规格](../specs/2026-09-20-skill-support-design.md)、[实施计划](../plans/2026-09-20-skill-support.md)、[实现说明](../implementation/2026-09-20-skill-support-implementation-design.md)。

## 需求与决策记录

| 顺序 | 已完成事项 | 结果 |
| --- | --- | --- |
| 1 | 按 brainstorming 核对代码和逐项讨论 | 确定指令与资源型 Skill、控制台管理、模型自动按需读取 |
| 2 | 确认接入方向 | CM Agent 负责治理，AgentScope 负责原生提示与加载 |
| 3 | 编写初版规格 | 本地提交 `326c86a`，提交说明为“docs: 补充 Skill 支持设计规格” |
| 4 | 用户要求纳入前端 | 增加完整页面、绑定、请求、错误与浏览器验收范围 |
| 5 | 完善前端书面规格 | 本地提交 `d68599c`，提交说明为“docs: 细化 Skill 前端交付范围与验收” |
| 6 | 用户确认书面设计并要求继续 | 进入 writing-plans；不将确认规划理解为立即实现 |
| 7 | 编写并自查计划 | 11 个任务，补齐接口合同、红绿步骤、验证环境、前端独立交付和范围映射 |
| 8 | 补齐同主题四文档 | 实现说明准确记录规划阶段，未将拟实现功能标为已完成 |
| 9 | 用户选择执行方式 A | 使用 `superpowers:executing-plans`，先创建隔离工作树，再按 T1～T11 顺序执行 |

## 实施任务状态

| 任务 | 交付内容 | 依赖 | 当前状态 |
| --- | --- | --- | --- |
| T1 | 领域、错误码和运行契约 | 无 | 已完成；提交说明为“feat: 定义技能领域与运行契约” |
| T2 | 无落盘技能包校验与配置 | T1 | 已完成；提交说明为“feat: 增加受限技能包解析与配置” |
| T3 | Repository 合同和 memory 工作单元 | T1 | 已完成；提交说明为“feat: 增加技能仓储与内存工作单元” |
| T4 | V12 双数据库迁移、JDBC 合同 | T3 | 已完成；提交说明为“feat: 持久化技能版本与运行读取记录” |
| T5 | 管理 API、权限和严格审计事务 | T2～T4 | 已完成；提交 `a4c426e`，边界补强提交 `181468c` |
| T6 | Run 固定快照、读时治理和审批恢复 | T3～T5 | 已完成；提交 `ee70305` |
| T7 | 原生技能加载及致命故障门控 | T1、T6 | 已完成；已创建本地提交 |
| T8 | 前端 multipart 与会话隔离 | T5 契约 | 未开始 |
| T9 | 技能工作区和导航生命周期 | T5、T8 | 未开始 |
| T10 | Agent 绑定、聊天错误和运行读取详情 | T6、T8、T9 | 未开始 |
| T11 | 前后端联调、全量回归和正式说明 | T1～T10 | 未开始 |

## 本阶段实际核对

| 检查 | 实际执行内容 | 结果及限制 |
| --- | --- | --- |
| 工作区 | `git status --short`、`git log -3 --oneline` | 识别并保留已有配置与未跟踪目录；确认两个本地设计提交 |
| 代码结构 | CodeGraph 查询后核对相关源码、POM、README、前端及测试文件 | 找到当前运行、审批、错误出口、持久化和控制台接入位置；不等同于功能验证 |
| 原生接口 | 源码及本地依赖的 `javap` 核对 | 确认 SkillBox/Toolkit 等接口存在；未声称新的适配链已运行成功 |
| 计划自查 | 规格覆盖、占位符扫描、类型及方法签名一致性、五项失败模式 | 已补充聊天错误传播、历史版本展示、软指针和归档限制；各项映射至实施任务 |
| 文档检查 | PowerShell 校验四份文档路径、相对链接、同日期同主题、代码围栏、T1～T11 连续编号及占位符；`git diff --check` 和未跟踪文档行尾空白检查 | 全部通过；Git 仅提示规格文件后续会按仓库规则转换为 CRLF。业务文件不在本次修改范围，现有配置差异保留 |

## 未执行验证

| 验证 | 状态 | 原因与执行位置 |
| --- | --- | --- |
| 新增 Java 单元/接口测试 | 未执行 | 尚无 Skill 实现与测试；按 T1～T7、T11 在 Java 21 环境执行 |
| 新增前端 Node 测试 | 未执行 | 尚无技能页面和组件；按 T8～T10 执行 |
| JDBC/Flyway/Testcontainers 双库验证 | 已通过 | Rocky Linux Docker 23.0.6；指定 Maven/JDK 21 容器；PostgreSQL 16 与 MySQL 8.4 共 4 项测试通过 |
| 浏览器完整闭环与响应式验证 | 未执行 | 尚无 Skill 页面；历史截图不计入验收证据 |
| 全量构建和回归 | 未执行 | 未改业务代码；安排在 T11 对实际实现执行 |

Task 1 实际验证：首次目标测试在测试编译阶段因 Skill 类型、错误码和 `skills()` 缺失而失败，符合红灯预期；实现后目标命令通过 18 项测试，随后 `mvn -q -pl cm-agent-core -am test` 退出码为 0。PowerShell 下 Maven 的逗号和点号属性需要将完整 `-D...` 参数加引号，测试选择和值未改变。

Task 2 实际验证：首次目标测试因解析器、限制类型和配置属性缺失而红灯；实现基础行为后新增嵌套 YAML null 用例，确认旧实现错误拒绝后改为递归冻结并转绿。最终目标命令通过 13 项测试；依赖树确认直接解析 Commons Compress 1.27.1、SnakeYAML 2.4；`git diff --check` 通过。

Task 3 实际验证：首次目标测试因 `InMemorySkillStore` 缺失而在编译期红灯；实现六个仓储视图和统一工作单元后，目标测试通过 9 项用例。单独加载 `ServerRepositoryConfigurationTest` 首次暴露底层存储 Bean 位于错误配置边界，调整后与目标测试合并通过。扩大执行 `mvn -q -pl cm-agent-server -am test` 时，既有 persistence Testcontainers 测试因本机无可用 Docker 中止；按仓库规则不在本机运行 JDBC 集成验证，T4/T11 将在 `ssh rocky` 指定容器中执行。

Task 4 实际验证：红灯提交 `d26b9081ed2b0e269498e8ef3324dc54054f9a3e` 在 Rocky 指定容器中因六个 JDBC Repository 类缺失而于测试编译阶段失败，属于预期功能缺失。初版实现提交 `b3a7513955fc16f74b76c9fed3634465b1098fce` 的双库测试 4 项通过；补充 V11→V12 既有 Run 空快照回填、模型调用唯一键和并发绑定上限后，最终精确提交 `38c9c38f67a5a9017111eea538bc73515b3351a5` 再次运行 `MigrationTest,JdbcSkillRepositoriesTest`，结果为 4 项通过、0 失败、0 错误。环境为 Rocky Linux 9.3、Docker 23.0.6、`maven:3.9.9-eclipse-temurin-21`、PostgreSQL 16-alpine 与 MySQL 8.4。

Task 5 实际验证：MockMvc 红灯首先以技能路由不存在返回 404；实现管理服务和接口后，补充功能关闭仍可读取历史和停用、同租户同名拒绝、非 ZIP 媒体类型、跨租户隐藏、版本冲突、重复绑定及删除 Agent 清理绑定。最终本地执行 `SkillControllerTest,AgentSkillControllerTest,ApiExceptionHandlerTest,AuthControllerTest,AgentControllerTest` 共 28 项测试，0 失败、0 错误。Rocky Linux Docker 23.0.6 使用精确提交 `181468cf9ccf3e258c0f735c0e9ff96122449e12`，在 `maven:3.9.9-eclipse-temurin-21` 与 PostgreSQL 16-alpine 上执行 `SkillManagementJdbcPersistenceTest` 1 项通过，审计失败响应为稳定错误且写入全部回滚。首次远程依赖下载曾遇到 Maven Central DNS 临时解析失败，重试完成缓存后通过，不属于代码或测试失败。

Task 6 实际验证：红灯命令因 `SkillRuntimeService` 和 `GovernedSkillAccessService` 缺失而在测试编译阶段失败。实现后本地执行 `SkillRuntimeServiceTest,GovernedSkillAccessServiceTest,ToolApprovalServiceTest,RunControllerTest,ConversationControllerTest`，四个存在的测试类共 44 项通过、0 失败、0 错误；不存在独立 `ConversationControllerTest`，参数只允许其空选择。远程独立工作副本的 HEAD 为 `ee703054231e6cbc8580d59762cf270f699dbfdf`，Rocky Linux 9.3 Docker 23.0.6 在 `maven:3.9.9-eclipse-temurin-21` 中分别启动 PostgreSQL 16-alpine 与 MySQL 8.4，`SkillRuntimeJdbcPersistenceTest` 各 1 项通过，验证 Run 快照在更新当前版本后仍恢复版本 1。

Task 7 实际验证：初始 `AgentScopeSkillSessionTest` 因 `AgentScopeSkillRepository` 缺失而失败，符合红灯预期。实现后，Temurin 21 下执行 `mvn -q -pl cm-agent-agentscope-adapter -am "-Dtest=AgentScopeSkillSessionTest,AgentScopeSkillContractTest,AgentScopeRunGateTest,AgentScopeRuntimeContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 退出码为 0。测试覆盖只读内存仓储、无 originDir、原生内部 skillId 到固定版本映射、目录不含正文、未知资源安全拒绝、名称冲突、无技能回归及 fatal 失败门控。随后 `mvn -q -pl cm-agent-server -am -DskipTests compile` 退出码为 0。未改 JDBC/Flyway，本任务不需 Rocky/Testcontainers 验证。

## 提交与保护范围

- `326c86a`：初版设计规格，已本地提交、未推送。
- `d68599c`：前端范围与验收，已本地提交、未推送。
- Task 4 前的规格、计划、实现说明和账本已随前序任务提交；Task 5 代码提交为 `a4c426e`，边界补强提交为 `181468c`；Task 6 代码提交为 `ee70305`；Task 7 代码与同步文档已创建本地提交，均未推送。
- 未修改用户已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、`.codex/`、`.impeccable/critique/`、`.workbuddy/`。
- Skill 整体功能尚未完成，因此暂不发布正式说明；T11 在前后端闭环完成后统一更新。

## 下一步与主要边界

用户已选择主代理顺序实施。隔离工作树中的 T7 AgentScope 原生技能加载与故障门控已完成，下一步实现 T8 multipart 请求封装；不派生逐任务实现子代理，最终执行一次独立整体验证和评审。

执行阶段重点守住版本和撤销边界、严格审计提交后再交付正文、框架吞错防护、前端会话与迟到响应隔离。所有预期验收结果都仍属于待验证事项，不据此宣称 Skill 已可用。
