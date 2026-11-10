# 本机客户端项目工作区执行提示词

日期：2026-11-09；当前修订 R2（2026-11-09）；mode=default。本提示词尚未执行；阅读本文件不授权执行。R2 与旧修订冲突时以 R2 为准，差异见设计和账本。

关联：[清单](../checklists/2026-11-09-client-project-workspace-checklist.md)｜[设计](../specs/2026-11-09-client-project-workspace-design.md)｜[计划](../plans/2026-11-09-client-project-workspace.md)｜[实际交付](../implementation/2026-11-09-client-project-workspace-implementation-design.md)｜[账本](../progress/2026-11-09-client-project-workspace-ledger.md)。

```text
仓库：F:\java\cm-agent
需求：用户从项目页选择自己已配对的 Windows 电脑，客户端显示关联码并经用户确认选择本机文件夹，在原项目中安全浏览、开发、受控执行命令与条件撤销。
先读取仓库 AGENTS.md、相关子目录规则，以及 docs/superpowers/checklists/2026-11-09-client-project-workspace-checklist.md、docs/superpowers/specs/2026-11-09-client-project-workspace-design.md、docs/superpowers/plans/2026-11-09-client-project-workspace.md 和 docs/superpowers/progress/2026-11-09-client-project-workspace-ledger.md。当前 mode=default，按证据自主选择工程细节并记录假设，不主动咨询；用户之后的新指令优先。

只有用户明确授权实施后执行本提示词。实施范围为 T0–T18，依清单依赖推进，另建实施工作包引用本设计包。保护现有脏文件；先核对 Git、实际代码和测试，仓库存在 .codegraph 时先使用 CodeGraph。首版限 Windows x64 有交互桌面的客户端、单 Server 实例、JDBC 非生产环境，默认关闭。保留原 Server 项目和普通聊天/客户端，不扩展生产或其他操作系统。

新增来源及 pre-Run 选择控制请求，不把服务端根与客户端设备根混为一谈；用户在目标设备上确认关联码、选择器及根，设备签名回传单次票据，目录绝对路径不交给浏览器/Server。项目会话和 Run 固定来源、设备、rootId 与版本，所有派生资源验权、损坏拒绝、不回退。不得直接开放通用绝对路径工具或删除项目会话与普通本机授权互斥校验。文件操作必须安全根约束、版本与摘要核对，精确预览并逐次审批；命令使用客户端固定工具链，显示无 OS 沙箱。对撤销/归档/断线/超时/客户端重启做竞态和持久恢复设计；UNKNOWN 不自动重放，不承诺 exactly-once 或撤销已经产生的副作用。

执行设计 R1 与 R2 当前决定：客户端项目 Bean 与 Server 本机项目开关分离；清点全部来源判断和访问路径，候选协议值/迁移号须实际核对；维护窗口停机升级，未证明安全前禁止混版及旧程序接触新来源。选择、项目级只读、Run 副作用分为有界任务；审批摘要覆盖真实意图和原始待写字节，掩码遮挡实际变更则拒绝审批。明确许可先提交与撤销先提交的不同结果，不承认许可后可即时阻断本机副作用；恢复回执身份和证据不足保持 UNKNOWN。

R2 还要求：客户端项目使用独立 `cm-agent/client-project/v1` 工具 ID 和 `client_project_*` 模型名，网关按精确 ID、权威来源、Run 固定目录、权限和风险路由；旧 `project_*`、普通会话、调试和 MCP 不得落入 Server 本机或客户端执行器。把项目载荷保护接入 ReAct/Harness 的工具输入、进度、失败、思考、审批、最终消息、ToolCall、SSE 和加密检查点，并用敏感标记覆盖成功、异常、超时、暂停和恢复。客户端用实际目录对象身份、重叠根和跨进程持久排他组成统一冲突域，禁止同根、路径别名及父子根重复登记；项目 UNKNOWN、已许可或进程未证明退出时继续隔离。写前预留 Run 快照空间，写后只按已验证 operationId/摘要幂等推进，迟到读取不得覆盖新代次。

远端命令模型回答结束不等于 Run 成功；所有关联命令退出或进入明确失败/UNKNOWN 前不得宣告成功。完成屏障必须有界、可中断且不占 JDBC 事务或 HTTP 线程；超预算时使用既有 Run 终态、保留受限停止/查询/对账入口并隔离物理根。人工终结逻辑意图不释放物理隔离，只有旧许可失效且 Job 静止有可信证据时才能解除。命令批准冻结工具链定义、可执行文件身份、argv、工作目录、环境及构建配置摘要，并明确脚本、插件、依赖和外部服务未完全冻结。

项目页采用 Server 本机与客户端项目两个独立来源分栏和独立 capability/列表/详情合同，客户端项目候选路由为 `/api/client-projects`，实施前复核冲突。异步适配 202→状态轮询→终态数据，绑定 sessionEpoch、来源、设备、项目/root 版本、会话和 UI 加载代次；导航、退出或切项目后丢弃迟到结果，AbortController 不等同于服务端取消。客户端来源只允许 ASK_ON_EFFECT；后端同样拒绝其他模式。离线、撤销或根失效时仅展示已完整授权的历史，损坏/跨主体/权限撤销仍拒绝。落实 C01–C10 和四张旧矩阵，残余风险必须在界面、配置和运维文档中明示。

Flyway 用新版本且 PostgreSQL/MySQL 均提供原生中文注释，保持历史迁移不变。每个安全、权限、失败分支落实中文注释、审计、同号错误日志和脱敏测试。执行与修改相关的 Node、Java 单元/MockMvc、双库迁移/并发、Windows 客户端原生安全及真实远端浏览器/模型验收；Docker、Testcontainers 与数据库测试严格在 ssh rocky 上使用 maven:3.9.9-eclipse-temurin-21，先核对远端 Docker、JDK、提交及工作区与待测内容一致，禁止全局清理。如果无法验证，报告确切原因、影响与继续条件，不以历史测试或模拟替代。

完成后同步实际实施工作包的清单、提示词、设计、计划、实现说明和账本，区分真实通过与未验证；更新相关 README、配置、运维及 release-notes。未经额外授权不提交、推送、部署或启动/重启既有服务；避免输出凭据。最终按仓库 AGENTS.md 格式报告变更、验证、影响、风险和直接后续。
```
