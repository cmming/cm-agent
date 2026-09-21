# Skill 支持实现说明

## 文档状态

- 主题日期：2026-09-20；本次更新：2026-09-21。
- 当前阶段：Task 1～Task 6 已实现；Task 7～Task 11 尚未开始。
- 本文记录本阶段实际交付及拟接入位置；后续实施时用实际代码、验证结果和方案差异更新，不能将下述拟实现内容作为功能已上线的依据。
- 配套文档：[设计规格](../specs/2026-09-20-skill-support-design.md)、[实施计划](../plans/2026-09-20-skill-support.md)、[进度账本](../progress/2026-09-20-skill-support-ledger.md)。

## 本阶段实际交付

规划阶段整理了现有运行、工具治理、持久化和控制台边界，形成指令与文本资源型 Skill 方案，并将后端、前端、数据库、权限、诊断和回归拆为 T1～T11。用户已确认书面设计，并明确要求前端在同一需求中交付；当前已进入顺序实现阶段。

Task 1 已新增 10 个技能领域类型、4 个运行时契约和 10 个稳定错误码，并让 `AgentRunRequest` 兼容旧构造入口的同时携带不可变技能版本集合。数据库迁移、管理接口、前端页面和配置项仍未实现，也没有启用 Skill。

Task 2 已增加默认关闭的 `cm-agent.skills` 有界配置和无落盘 ZIP 解析器。解析器使用 Commons Compress 1.27.1 的中央目录及内存可寻址通道，SnakeYAML 2.4 使用安全构造器；两项依赖均固定为当前已解析版本，没有升级框架依赖。

Task 3 已增加六个技能 Repository 合同和单一 memory 工作单元。所有写操作必须在工作单元的暂存副本中完成，只有最外层正常返回才发布；嵌套工作单元复用同一副本，异常后清理线程上下文。空技能列表会作为有效 Run 快照持久化，绑定唯一性、分页顺序、停用状态可见性和读取记录顺序均有回归测试。

Task 4 已增加 V12 PostgreSQL/MySQL 双方言迁移、六个 JDBC Repository 和 JDBC 技能工作单元。旧 Run 在迁移窗口回填格式版本 1 的空技能快照；定义、版本、资源、绑定、快照和读取记录均保持 tenant 条件及数据库约束。Agent 硬删除会在同一事务先移除技能绑定，避免遗留悬挂关联。

Task 5 已增加技能管理、版本更新、启停、资源查询及 Agent 绑定 API。权限和租户均来自 JWT 主体，上传先经过有界解析再进入工作单元，领域写入与严格审计共同提交；功能关闭后仍允许读取历史、停用和解绑。Agent 删除流程会先清理技能绑定。

Task 6 已增加 Run 快照创建与恢复、每次技能读取治理和读取历史接口。`RunExecutionService` 在调用 Runtime 前创建或恢复不可变快照，并将历史版本视图传入 `AgentRunRequest`；审批恢复复用同一执行路径，因此不会重新解析当前绑定。`GovernedSkillAccessService` 在短工作单元中完成快照、绑定、访问纪元、版本和预算复核，严格审计及读取记录提交成功后才返回正文。`RunController` 的读取历史接口只返回固定版本、状态、字节数、耗时与错误编号，绝不返回正文；两个 SSE Controller 保留同一技能错误码和错误编号。

设计文档此前分别形成两个本地提交：`326c86a`（设计规格）、`d68599c`（前端交付范围与验收）。四份同主题文档随各任务持续更新和提交，当前分支均未推送。

## 当前代码与拟接入位置

| 现有位置 | 已核对的当前职责 | 计划中的变化 |
| --- | --- | --- |
| `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java` | 运行请求及租户约束 | T1 增加框架无关技能集合，保留旧构造入口 |
| `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java` | 准备运行、执行与失败收口 | T6 固定快照、恢复校验并保留技能错误码和编号 |
| `cm-agent-server/src/main/java/com/cmagent/server/runtime/ToolApprovalService.java` | 审批与恢复 | T6 恢复时复核撤销状态和读取预算 |
| `cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java` | 运行接口及流式错误出口 | T6 增加独立读取记录接口及技能错误映射 |
| `cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java` | 会话接口及流式错误出口 | T6 保持聊天错误与运行错误编号一致 |
| `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java` | 每次执行创建模型、工具与 Agent | T7 装配原生目录提示和受控读取包装器 |
| `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRunGate.java` | 工具调用门控及基础设施故障保留 | T7 保留致命技能错误，阻止框架吞错后继续调用业务工具 |
| `cm-agent-persistence/src/main/resources/db/migration/` | Flyway 历史迁移 | T4 新增 V12 双方言迁移；不修改历史版本 |
| `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js` | 前端公共请求和状态辅助函数 | T8 增加 multipart 支持，保持 JSON/SSE 与会话隔离 |
| `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js` | 页面路由、数据加载和交互装配 | T9/T10 接入技能工作区、Agent 绑定与运行读取记录 |
| `cm-agent-console/src/main/resources/META-INF/resources/console/v2/` | 现有七页中文工作区 | T9 新增技能页并同步全部 v2 导航；T10 增加局部组件 |

以上文件是已核对的主要运行和前端接入点。Task 1～Task 5 已完成领域、解析、仓储、持久化和管理接口，运行时及前端接入点仍按 T6～T10 实施；新增类和测试的完整路径见实施计划各任务。

### Task 1 实际实现

- `SkillDefinition` 以非空当前版本软指针和非负访问纪元表达稳定身份与撤销状态。
- `SkillVersion` 递归冻结 Map/List 元数据，只接受 JSON 等价标量；版本号从 1 开始。
- `SkillResource` 使用 UTF-8 实际字节数校验预算字段；`SkillVersionView` 复核租户、技能、版本和资源路径唯一性。
- `RunSkillSnapshot` 接受空集合但拒绝重复技能和未知格式；`SkillRuntimeBundle` 严格按快照中的固定版本组装，不回退目录当前版本。
- `SkillLoadRecord` 区分成功、失败和拒绝，未成功记录不能包含已交付字节；无法解析的技能和版本允许同时为空。
- `SkillAccessGateway`、`SkillReadRequest`、`SkillReadResult` 和 `SkillAccessException` 保持 Core 无 HTTP、Spring、数据库及 AgentScope 依赖。
- `AgentRunRequest` 新增九参数主入口，并保留原七参数、八参数构造器，默认技能集合为空；构造时再次校验技能租户和名称唯一性。

### Task 2 实际实现

- 上传流最多读取 ZIP 上限加一字节；实际解压字节、普通文件数量、单文件和路径分别计数，不信任中央目录声明值完成预算判断。
- 中央目录拒绝符号链接、加密、不可读压缩方式、重复和大小写冲突路径；路径在任何标准化前拒绝绝对路径、反斜杠、冒号、空段、`.`、`..` 和控制字符。
- 包内只能有一个 `SKILL.md`，允许一层或多层包装目录但拒绝入口目录外文件；资源仅接受设计约定的六类 UTF-8 文本扩展名和非 NUL 正文。
- YAML 拒绝重复键、自定义对象标签、标量或集合锚点和别名，接受有限深度的 Map/List/标量/null，并递归冻结解析结果。
- 摘要按名称、描述、规范元数据、正文以及排序后的资源路径和内容计算长度前缀 SHA-256，ZIP 时间戳变化不会改变摘要。
- `SkillProperties` 的上传和运行限制只能从默认值向下调低；`SkillConfiguration` 在 Bean 创建时先校验配置。

### Task 3 实际实现

- Core 新增定义、版本、资源、Agent 绑定、Run 快照和读取记录六个 Repository 接口，所有查询显式携带可信租户标识。
- `InMemorySkillStore` 使用一把可重入锁、`ThreadLocal` 暂存状态和容器浅复制实现进程内工作单元；领域值保持不可变，失败不会发布部分写入。
- 重名、重复绑定、重复快照及重复模型调用标识均在同一工作单元内拒绝；解绑后重绑必须使用新绑定标识。
- 定义分页按更新时间和标识稳定倒序，读取详情按创建时间倒序，预算累计按尝试序号正序。
- 内存存储 Bean 与六个仓储视图统一由 `ServerRepositoryConfiguration` 装配，确保独立加载该配置时共享同一工作单元。

### Task 4 实际实现

- PostgreSQL 与 MySQL 的 V12 迁移分别创建六张技能表，并为表及所有字段写入原生中文注释；名称和资源路径唯一性在 MySQL 使用二进制排序规则保持大小写敏感语义。
- 数据库约束覆盖租户内技能名、版本号、资源路径、Agent 绑定、Run 快照和模型调用读取记录的唯一性；复合外键约束资源版本、Agent 绑定与 Run 归属，当前版本继续采用事务内验证的软指针。
- 六个 JDBC Repository 复用 Core 合同，行锁查询始终包含 tenant 条件；定义版本更新使用期望版本指针进行 CAS，快照和读取记录不会动态回退到当前版本。
- `JdbcPersistenceConfiguration` 使用现有 `TransactionTemplate` 提供技能工作单元，与同数据源上的审计写入共享事务边界。该装配放在 JDBC 条件配置中，因此无需修改仅负责通用限额 Bean 的 `SkillConfiguration`。
- `JdbcAgentDefinitionRepository` 删除 Agent 前先删除同租户绑定；并发绑定测试通过锁定 Agent 行串行计算数量，验证 20 个上限不会被竞争绕过。

### Task 5 实际实现

- `SkillManagementService` 将创建、版本更新、启停、绑定和解绑统一放入 `SkillUnitOfWork`，并在同一边界追加严格审计；tenant 只取认证主体。
- `SkillController` 提供能力、分页、详情、创建、版本更新、启停及固定版本资源查询；`AgentSkillController` 提供 Agent 技能列表、幂等绑定和解绑。
- 初次上传保持停用；更新采用预期版本指针，名称不可修改，相同摘要不会生成新版本；从启用切到停用才增加访问纪元。
- 绑定要求目标技能已启用，并在锁定 Agent 后检查 20 个上限；重复绑定返回原绑定，Agent 硬删除在同一管理流程清理绑定。
- 功能开关关闭时拒绝创建、更新、启用和绑定，保留能力查询、历史读取、停用和解绑，便于安全退出。
- multipart 缺失、超限、请求媒体类型错误和文件媒体类型错误均返回技能专属 JSON 错误；同名、跨租户和版本冲突使用稳定错误码。
- bootstrap 管理员权限增加 `skill:read`、`skill:write`；`SkillResponses` 只暴露业务字段和公开限制，不返回持久化或认证内部信息。

### Task 6 实际实现

- `SkillRuntimeService` 对每个 Run 保存一条快照，空集合也保存；已有快照只能恢复，不能被新的绑定或当前版本覆盖。恢复校验原 Run 主体、Agent、绑定 ID、访问纪元、启用状态和历史版本；停用再启用或解绑再绑定均永久拒绝旧快照。
- `GovernedSkillAccessService` 以 Run、快照、绑定、技能和固定版本为可信边界。相同模型调用标识只重放同一成功读取；参数冲突拒绝，拒绝或加载失败先写记录与严格审计后再在事务外抛受控异常。
- 读取次数与累计已交付字节从持久读取记录计算；原生加载器只在授权成功后调用，并在返回前再次复核撤销状态。基础设施异常不伪造成拒绝记录。
- `SkillLoadQueryService` 根据记录中的历史 versionId 解析版本号，不回退到当前版本。`GET /api/agents/{agentId}/runs/{runId}/skill-loads` 先通过 Run 归属校验，再返回不含正文的分页记录。
- `RunExecutionService` 在通用运行异常前保留 `SkillAccessException` 的 code/errorId；`RunController` 和 `ConversationController` 的 SSE 错误事件使用相同编号。

### Task 7 实际实现

- `AgentScopeSkillRepository` 将每次 Run 的 `SkillVersionView` 映射为只读内存 `AgentSkill`，source 为固定非路径标识且 `originDir` 为空；保存、删除和打开可写状态全部被拒绝。
- `AgentScopeSkillSession` 为每次运行创建独立 SkillBox，关闭自动上传、代码执行和全量元数据暴露。它使用 AgentScope 生成的内部 skillId 映射固定版本，目录提示不含正文；关闭时只清理内存引用。
- 原生 `load_skill_through_path` 被 `AgentScopeSkillLoadBridge` 替换。桥接器只接受当前快照中的内部 ID 和 `SKILL.md` 或已登记资源路径，调用 `SkillAccessGateway` 后才委托原生内存加载；普通拒绝返回安全工具错误，fatal 失败交运行门控中断整轮。
- `AgentScopeRunGate` 让技能读取与业务工具共用公平锁，保留首次致命 `SkillAccessException`，并由执行器在事件和收尾边界重新抛出，避免 AgentScope 把它吞成普通工具文本。
- `AgentScopeRuntimeAdapter` 新增六参数工厂，Server 的 `AgentScopeRuntimeConfiguration` 注入实际 `SkillAccessGateway`；旧四、五参数入口使用拒绝型网关，空技能运行保持兼容。

## 拟实现的数据与调用链

### 管理与绑定

`认证主体 → Controller 权限入口 → 无落盘 ZIP 校验 → SkillManagementService 工作单元 → 版本/资源/绑定 Repository + 严格审计 → 前端详情`

新增六表保存定义、不可变版本、文本资源、Agent 绑定、Run 快照和读取记录。新上传默认停用；更新创建新版本；绑定不会授予业务工具权限。memory 和 JDBC 采用相同合同，生产沿用 JDBC/Flyway。

### 运行与读取

`可信运行主体 → 固定版本及绑定身份快照 → 原生技能目录摘要 → 模型请求读取 → 受控网关复核租户/撤销/预算 → 内存原生读取 → 记录与审计提交 → 正文交给模型`

审批恢复复用原快照和累计预算。停用后重新启用、解绑后重新绑定均不恢复旧运行权限。无法撤回已经交给模型的文本，因此撤销边界是后续读取与恢复。

读取记录独立于业务工具调用记录，不伪造 `toolId`。未知故障、审计或持久化故障不能变成普通文本后继续运行；聊天、运行和审批恢复的错误出口必须保留同一个错误码与编号。

### 前端完整闭环

`能力查询 → ZIP 上传 → 技能详情与文本预览 → 启用 → Agent 绑定 → 正常聊天 → 运行读取记录 → 更新及撤销验证`

前端复用原生 HTML/CSS/JavaScript、现有中文布局、权限与会话状态管理。新增技能页和独立技能组件脚本，保留现有页面职责；T8～T10 是独立交付任务，T11 执行完整联调。

## 与原设计的差异及补充

- 已确认的功能范围不变：指令与文本资源型 Skill、控制台管理、自动按需读取、CM Agent 治理和 AgentScope 原生能力。
- 用户要求增加的前端范围已落入规格第 9 节和 T8～T11，不再作为后续独立需求。
- 计划自查补充聊天流错误出口与新增 `ConversationControllerTest`，避免后端保留的错误码在会话层丢失。
- 明确当前版本采用事务内校验的非空软指针，避免定义与版本的即时外键形成循环插入。
- 明确第一版配置上限只能调低，并限制包含目录在内的总归档条目；这是原有资源限制的具体化。
- T5 将不支持的文件媒体类型在进入 ZIP 解析前拒绝，并允许浏览器常见的 `application/zip`、`application/x-zip-compressed` 和 `application/octet-stream`；这是 multipart 错误边界的具体化，未扩大资源类型。
- 后续若修改公开契约，必须同步规格和本说明。

## 验证与未完成边界

规划阶段完成了代码结构、依赖接口和文档一致性核对。Task 1 已按红绿循环执行 `SkillDomainTest`、`AgentRunRequestTest`，并完成 Core 全模块回归。历史页面截图仅用于理解原有风格，不属于本次 Skill 功能的浏览器验证。

已运行 Task 1 领域测试、Task 2 解析/配置测试、Task 3 memory 工作单元测试和仓储 Spring 装配测试。Task 4 在 Rocky Linux 的 `maven:3.9.9-eclipse-temurin-21` 容器中，使用 PostgreSQL 16 与 MySQL 8.4 完成迁移和 JDBC 合同验证；精确提交 `38c9c38f67a5a9017111eea538bc73515b3351a5` 共执行 4 项测试且全部通过。

Task 5 本地最终回归执行 `SkillControllerTest`、`AgentSkillControllerTest`、`ApiExceptionHandlerTest`、`AuthControllerTest`、`AgentControllerTest` 共 28 项测试，0 失败、0 错误。Rocky Linux Docker 23.0.6 使用精确提交 `181468cf9ccf3e258c0f735c0e9ff96122449e12` 和 `maven:3.9.9-eclipse-temurin-21`，在 PostgreSQL 16-alpine 上执行 `SkillManagementJdbcPersistenceTest` 1 项通过，证明审计失败时定义、版本和资源写入回滚。

Task 6 本地最终回归执行 `SkillRuntimeServiceTest`、`GovernedSkillAccessServiceTest`、`ToolApprovalServiceTest`、`RunControllerTest`、`ConversationControllerTest`，四个存在的测试类共 44 项通过、0 失败、0 错误；仓库当前没有独立 `ConversationControllerTest`，因此 `surefire.failIfNoSpecifiedTests=false` 只允许该空选择，不隐藏已存在测试的结果。Rocky Linux 9.3、Docker 23.0.6 的 `maven:3.9.9-eclipse-temurin-21` 使用精确提交 `ee703054231e6cbc8580d59762cf270f699dbfdf`，在 PostgreSQL 16-alpine 与 MySQL 8.4 各执行一次 `SkillRuntimeJdbcPersistenceTest`，均为 1 项通过、0 失败、0 错误。

Task 7 本地使用 Temurin 21 执行 `AgentScopeSkillSessionTest`、`AgentScopeSkillContractTest`、`AgentScopeRunGateTest` 和既有 `AgentScopeRuntimeContractTest`，退出码为 0；随后 `mvn -q -pl cm-agent-server -am -DskipTests compile` 退出码为 0，确认 Server 可装配技能读取网关。本任务未改数据库结构，未执行 Rocky/Testcontainers 验证。

实施计划已安排 Java 21 本地快速测试，以及 Rocky 上 PostgreSQL 16/MySQL 8.4 的 JDBC/Flyway/Testcontainers 验证；执行前核对远端提交与本地一致。实际命令和结果持续写入进度账本。

本次未更新 `README.md`、配置说明或 `docs/release-notes.md`：产品行为未变化，避免提前宣称支持 Skill；正式功能文档更新已列入 T11。
