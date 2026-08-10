# AGENTS.md

## 适用范围
- 本文件适用于整个仓库；如子目录未来出现更近的 `AGENTS.md`，以更近文件为准。
- 开始任务前先阅读相关模块的 `pom.xml`、配置文件、README 或 docs，再改动代码。
- 不要覆盖用户已有修改；发现无关脏文件时保持不动，发现相关脏文件时先理解再继续。

## 语言规范
- Agent 新增或修改的代码注释、JavaDoc、README、docs、设计/计划记录、测试说明、提交说明和其他落地到项目中的文字，必须使用中文；已有英文内容仅在必要时保持兼容，不要无关翻译或批量改写。
- 代码标识符、协议字段、配置键、依赖名称、类库 API 和命令等按项目既有约定保留英文或原始写法；解释这些内容时使用中文。

## 项目定位
- CM Agent 是基于 AgentScope Java 的企业级智能体开源底座。
- 当前阶段覆盖 Java SDK/Core、Spring Boot Starter、独立服务端、轻量控制台、工具治理（含动态 HTTP 工具与 MCP 发布）、多租户、RBAC、JWT、安全审计和 JDBC 持久化基线。
- 代码应优先服务后端长期演进：清晰分层、租户隔离、权限可审计、配置可生产化。

## 技术栈
- Java: POM 要求 Java 21，`maven.compiler.release=21`。构建前用 `java -version` 和 `mvn -v` 确认 Maven 运行在 JDK 21。
- Maven: 多模块项目；当前文档要求 Maven 3.9+，当前环境曾解析到 Maven 3.9.4。项目没有 Maven Wrapper。
- Spring Boot: 3.5.0；使用 Web MVC、Security、Actuator、Validation、Auto Configure。
- API 文档: springdoc OpenAPI 2.8.9。
- JWT: JJWT 0.13.0。
- AgentScope: `agentscope-core` 2.0.0；Provider 扩展由 adapter 模块按需引入，并保持 optional 依赖。
- MCP: `io.modelcontextprotocol.sdk:mcp-core`/`mcp-json-jackson2` 2.0.0；用于将已发布工具以 MCP Streamable HTTP 方式对外提供，Server 端实现见 `cm-agent-server` 的 `com.cmagent.server.mcp` 包。
- 数据库: 默认 memory，可通过 `cm-agent.persistence.mode=jdbc` 使用 JDBC/Flyway；目标为 PostgreSQL/Supabase PostgreSQL 和 MySQL。
- 本地数据库: `docker-compose.yml` 提供 PostgreSQL 16-alpine 和 MySQL 8.4。
- 测试: Spring Boot Test、JUnit Jupiter 5.12.2、AssertJ 3.27.3、Mockito 5.17.0、MockMvc、Spring Security Test、Testcontainers。Testcontainers 通过父 `pom.xml` 的 `testcontainers-bom` 管理，当前为 2.0.5；升级前必须用 `mvn -pl <module> dependency:tree` 复核。

## 目录结构约定
- `cm-agent-api`: 对外共享 API 类型，如分页、租户上下文、主体引用、错误码。
- `cm-agent-core`: 领域模型、运行时接口、工具接口、安全策略和 Repository 接口。
- `cm-agent-persistence`: JDBC Repository 实现、Flyway 迁移和数据库集成测试。
- `cm-agent-spring-boot-starter`: Starter 自动配置、`cm-agent` 配置属性和默认 Bean。
- `cm-agent-server`: Spring Boot 应用入口、Web Controller、安全配置、运行配置、审计、memory store、MCP Streamable HTTP 端点（`com.cmagent.server.mcp`）。
- `cm-agent-console`: 轻量控制台模块，由 server 引入。
- `cm-agent-agentscope-adapter`: AgentScope 运行时适配层。
- `cm-agent-examples`: 示例工程，当前包含 `starter-local-tool` 和 `http-tool-client`（动态 HTTP 工具创建与调用示例客户端）。
- 包名保持在 `com.cmagent` 下；新增代码放入最贴近职责的现有包，不为少量代码新建宽泛包。

## Controller 规则
- REST Controller 放在 `cm-agent-server/src/main/java/com/cmagent/server/web`。
- Controller 只处理 HTTP 路由、请求校验、认证主体解析、权限入口、响应状态和审计触发。
- 请求体优先使用 `record`，配合 `jakarta.validation` 注解和 `@Valid`。
- 不在 Controller 中写 SQL、不直接操作 DataSource、不保存密钥、不绕过 Repository 接口。
- 涉及权限的接口必须使用 `PermissionEvaluator` 或对应授权策略；拒绝访问时记录审计。
- 多租户接口必须从认证主体或明确上下文获取 tenant，不能信任客户端传入的 tenant 覆盖当前主体。

## Service 规则
- 只有当流程跨多个 Repository、运行时、权限策略或外部适配器时才新增 Service。
- Service 放在与职责匹配的模块和包下，例如 server 运行编排、security、audit 或 core 领域服务。
- 使用构造器注入；避免静态可变状态和隐藏全局单例。
- Service 中可以编排业务，但不得吞掉安全异常、审计失败或持久化错误。
- 复杂逻辑优先下沉到 core 中可单元测试的类，server 只做 Spring 集成。

## Repository 规则
- Repository 接口放在 `cm-agent-core/src/main/java/com/cmagent/core/repository`。
- JDBC 实现放在 `cm-agent-persistence`，命名保持 `Jdbc*Repository`。
- memory 实现仅用于本地、测试或第一阶段纵切，放在 server store 或配置类中，不作为生产持久化方案。
- JDBC 访问优先使用 Spring `JdbcClient`、命名参数和显式 mapper。
- 每个查询和写入都要保持 tenant 条件；跨租户读取必须用测试覆盖。
- Repository 不依赖 Controller、Web DTO 或 Spring Security 类型。

## Entity / Domain 规则
- 当前项目没有 JPA Entity，也没有 Spring Data Repository；不要默认引入 JPA/Hibernate。
- 领域对象主要是 Java `record`，放在 `cm-agent-core/src/main/java/com/cmagent/core/domain`。
- `record` 构造器中维护领域不变量，例如范围、非空集合、防御性 `List.copyOf`。
- 如果确需引入持久化 Entity，必须先说明迁移策略、模块边界和对现有 JDBC/Flyway 的影响。
- Domain 不应依赖 Spring Web、Spring Security、JDBC 或数据库行结构。

## DTO 规则
- API 请求/响应 DTO 优先用 `record`；只在多个 Controller 或模块共享时放入 `cm-agent-api`。
- DTO 字段名应稳定、语义明确，避免直接暴露数据库列名或内部实现细节。
- 不得在 DTO、日志、审计响应或 OpenAPI 示例中返回 JWT secret、数据库密码、模型 API Key、完整 JDBC URL 等敏感信息。
- 错误响应应使用明确 HTTP 状态和可读中文消息；不要泄露堆栈、密钥或底层 SQL。

## 错误诊断、日志与前端提示规范
- 目标是让使用者能够从前端获知可行动的错误原因，让维护人员能够使用同一错误编号快速定位后台日志；禁止把不同失败原因全部收口成无法区分的“操作失败”或“服务异常”。
- 每个跨层或异步操作在进入可失败执行阶段前应建立可关联标识。优先复用已有 `requestId`、`traceId`、`runId` 或 `toolCallId`；需要单独生成时使用不可预测的随机值，并在对外错误响应中统一命名为 `errorId`。
- 前端可见错误至少包含可读中文原因；对需要后台协助定位的失败还应包含稳定 `errorCode` 和 `errorId`。HTTP 状态、错误码和错误原因必须与实际失败类型一致，不能成功状态与失败文案相互矛盾。
- 可预期业务失败应返回经过脱敏的具体原因和建议动作，例如参数无效、资源不可用、上游超时或权限不足；未知内部异常只返回安全通用说明和 `errorId`，不得把异常类名、堆栈、SQL、文件路径、内部 URL 或依赖实现细节暴露给前端。
- 捕获异常并转换为失败结果、降级结果或统一异常响应时，必须在最接近最终处理边界的位置记录日志；不得只 `catch` 后返回通用错误，也不得仅记录没有上下文的 `exception.getMessage()`。异常继续向上抛出且上层已有统一日志边界时，避免在每一层重复打印同一堆栈。
- 后台失败日志必须包含可检索的键值上下文：`errorId`、操作名称、稳定 `errorCode`、可信 tenant、资源类型与资源 ID；按场景补充 `principalId`、`runId`、`toolCallId`、外部 HTTP 状态、耗时和异常类型。上下文只能来自认证主体、服务端状态或已校验对象，不能直接信任客户端提交的 tenant 或资源归属。
- 日志级别应体现影响：导致当前操作失败的未预期异常、依赖不可用和无法自动恢复的执行失败使用 `ERROR`；可恢复重试、受控降级、外部拒绝或需要关注但未造成系统错误的情况使用 `WARN`；正常状态变化使用 `INFO`。参数校验和普通权限拒绝不应滥用 `ERROR`，但安全拒绝仍须按审计规则记录。
- 未预期异常的后台日志应保留可诊断堆栈，并在写入前脱敏异常消息及附加上下文；已由执行器返回稳定失败码的受控失败可以只记录结构化原因。原始请求体、模型输入输出、Authorization/Cookie、JWT、Secret、密码、API Key、完整 JDBC URL 和未经清理的外部响应不得进入日志。
- 应用日志和安全审计职责不同：日志用于技术定位，审计用于记录主体、动作、资源和结果。不能用详细错误日志替代严格审计，也不能把包含堆栈或敏感诊断信息的日志原文复制到审计事件。
- 前后端必须使用同一错误编号：页面展示的 `errorId` 应能直接检索到对应失败日志。跨服务调用无法沿用编号时，日志需同时记录上游与本地关联标识，响应仍只暴露当前系统承诺的 `errorId`。
- 新增或修改失败分支时必须补测试，至少覆盖：前端/接口显示正确脱敏原因；`errorCode` 和 HTTP 状态正确；响应 `errorId` 与日志一致；未预期异常存在诊断日志；JWT、Secret、内部 URL、堆栈等不会出现在前端或受控失败日志中。
- 评审时重点拒绝以下实现：所有异常返回同一文案、无日志直接吞异常、日志缺少关联标识、前后端错误编号不一致、同一异常重复打印多次、为方便排查而记录原始输入或敏感信息。

## 数据库变更规则
- Flyway 迁移位于 `cm-agent-persistence/src/main/resources/db/migration`。
- 已存在的迁移视为发布历史，不要修改 `V1__init_schema.sql` 等既有版本；新增变更使用新的 `Vn__description.sql`。
- SQL 必须同时考虑 PostgreSQL 16 和 MySQL 8.4，避免只在单一数据库可用的语法。
- 变更 schema 时同步更新 JDBC Repository、迁移测试、部署/配置文档。
- 新表默认包含 tenant 隔离、必要索引、创建/更新时间、外键或明确软引用说明。
- Supabase 使用 PostgreSQL JDBC/Flyway 链路，不需要引入 Supabase Java SDK。
- 不要把生产密码、JWT secret、模型 Key 或真实 JDBC URL 写入迁移、配置、测试断言或文档。

## 测试规则
- core 领域规则、权限策略、工具注册、runtime 行为写快速单元测试。
- Web/API 变更使用 `@SpringBootTest`、`@AutoConfigureMockMvc`、`@ActiveProfiles("test")` 和 MockMvc 覆盖认证、权限、校验、审计。
- JDBC Repository 和 Flyway 迁移使用 Testcontainers；数据库镜像优先保持 PostgreSQL `16-alpine`、MySQL `8.4`。
- 涉及多租户、权限拒绝、bootstrap admin、profile、JWT、secret fallback 的改动必须补测试。
- 涉及错误处理、异常转换、重试、降级或前端失败提示的改动，必须按“错误诊断、日志与前端提示规范”补充错误码、错误编号、日志关联和脱敏断言。
- 测试数据可以使用本地测试凭据，但不得新增可用于生产的默认密码或真实 secret。

## 容器与集成验证环境
- 涉及 Docker、Docker Compose、Testcontainers、JDBC Repository、Flyway 迁移或数据库集成测试时，必须使用 `ssh rocky` 连接 Rocky Linux 虚拟机，并在虚拟机的容器环境中执行，采用的容器镜像为：maven:3.9.9-eclipse-temurin-21；本机 Docker Desktop 不是此类验证的执行环境。
- 远程验证前必须在 `ssh rocky` 会话中确认 Docker 可用、Maven 使用 JDK 21，并确认远程工作区的 Git 提交与待验证的本地提交一致。
- 远程容器操作仅限当前项目所需的 Compose 服务和 Testcontainers；禁止执行全局清理、删除无关容器、卷或镜像等破坏性操作。
- 远程配置、日志和命令输出不得回传或记录 JWT secret、数据库密码、模型 API Key、完整生产 JDBC URL 等敏感信息。
- 因 SSH、远程 Docker、远程仓库版本或容器镜像不可用而无法完成验证时，必须在最终输出中说明确切原因和未执行的测试。

## 构建和验证命令
- 环境检查: `java -version`，`mvn -v`，确认 Maven 使用 JDK 21。
- 全量测试: `mvn -q test`。
- 快速打包: `mvn -q "-DskipTests" package`。
- 单模块测试: `mvn -pl cm-agent-core -am test`。
- 服务端测试: `mvn -pl cm-agent-server -am test`。
- 持久化测试: `mvn -pl cm-agent-persistence -am test`，需要 Docker/Testcontainers。
- 本地数据库: `docker compose up -d mysql postgres`。
- 本地 test profile 启动: `$env:CM_AGENT_PROFILE='test'; mvn -pl cm-agent-server -am spring-boot:run`。
- 依赖核对: `mvn -pl <module> dependency:tree`，版本异常时以实际解析结果为准。

## 文档规则
- 生产文档默认使用中文；英文只能作为补充，不能替代中文说明。
- 配置、部署、安全、数据库、运行 profile、控制台入口变化时，同步更新 `README.md` 或 `docs/*.md`。
- Secret 示例使用占位符，不写真实值；生产禁用项要直接写明。
- `docs/superpowers` 用于设计、计划和过程记录；不要把它作为唯一的生产说明。
- 每个涉及新需求或仓库内容改动的独立任务，完成前必须在 `docs/superpowers` 下生成一组主题一致的四份中文文档，不能只生成其中一部分：
  - `specs/<YYYY-MM-DD>-<topic>-design.md`：记录背景、目标、范围、非目标、方案、约束和验收标准。
  - `plans/<YYYY-MM-DD>-<topic>.md`：记录可执行任务拆分、涉及文件、实现顺序和验证方式。
  - `implementation/<YYYY-MM-DD>-<topic>-implementation-design.md`：记录最终实际实现、关键代码位置、数据或调用链变化及与原方案的差异。
  - `progress/<YYYY-MM-DD>-<topic>-ledger.md`：记录各任务状态、实际验证结果、遗留问题和提交信息；未提交时明确写“未提交”。
- 同一任务的四份文档必须使用相同日期和 `<topic>`，内容相互引用并保持一致；不能用计划文档代替实现说明，也不能用最终回复代替进度账本。
- 同一独立任务的连续修改应同步更新该任务已有的四份文档；新的独立需求或新的改动任务应新建一组四份文档。纯问答、只读检查且未改变仓库内容的任务不要求生成。
- 发布相关行为变化应更新 `docs/release-notes.md` 或在最终输出中说明未更新原因。

## 禁止事项
- 禁止提交真实 JWT secret、数据库密码、模型 API Key、完整生产 JDBC URL 或可用生产凭据。
- 禁止在 `prod`、`production`、`supabase` profile 下启用 bootstrap admin、dev JWT fallback 或 memory 持久化。
- 禁止绕过权限检查、审计记录、多租户过滤和工具授权策略。
- 禁止为了方便测试而降低生产安全默认值。
- 禁止在 Controller 中直接写数据库访问逻辑。
- 禁止无设计说明地引入 JPA、MyBatis、新数据库框架或替换当前 Flyway/JDBC 基线。
- 禁止修改已发布 Flyway 迁移来“修复”历史；必须新增迁移。
- 禁止无关格式化、批量重命名、依赖升级或跨模块重构。
- 禁止把 `target/`、日志、临时 effective POM、IDE 文件等生成物加入版本控制。

## 每次任务完成后的输出格式
完成任务后按以下顺序输出，简洁但要有证据：

1. 变更摘要：说明改了哪些文件和行为。
2. 验证命令与结果：列出实际运行的命令和结果；未运行的测试要说明原因。
3. 影响范围：说明涉及的模块、接口、数据库、配置或文档。
4. 风险与注意事项：说明兼容性、安全、迁移、运行环境限制。
5. 后续建议：只列与本次任务直接相关的下一步。
