# 工作区目录选择执行提示词

日期：2026-10-27；主题：workspace-directory-selection；修订：R0；模式：mode=default。

本文件已生成，以下实施命令尚未执行。进入需求问答时只作为上下文；只有用户明确要求实施后才执行。后续独立实施任务建立自己的六份实施工作包并引用本分析包，保留 R0 分析历史。

```text
仓库：F:/java/cm-agent。
需求：让系统支持选择工作区目录，同时完成前后端改造。
当前分析工作包：2026-10-27 / workspace-directory-selection / R0。
当前模式：mode=default。合理工程取舍依据当前证据自主处理并记录假设，不主动咨询普通方案；用户显式切换模式时以新指令为准。

开始前读取以下六份真实文件及仓库适用 AGENTS.md：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-27-workspace-directory-selection-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-27-workspace-directory-selection-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-27-workspace-directory-selection-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-27-workspace-directory-selection.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-27-workspace-directory-selection-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-27-workspace-directory-selection-ledger.md

本提示词仅在用户明确授权实施后使用。T0 是已完成的需求分析与文档任务；当前执行范围为 T1–T7 的登记目录、持久化绑定、API、真实运行接入、前端、测试及部署说明。先复核 HEAD、暂存和脏文件；分析基线为 369418b3bdc08b096de7854f9d04c982ec8be050，不能把此历史基线当作当前状态。保留无关配置、.gitignore、.workbuddy 和发布说明改动。存在 .codegraph 时先用 CodeGraph，缺失结果再定向复核源码。

采用设计 A1–A6：工作区是服务端受信配置登记的只读参考目录；用户按当前 tenant/Agent 可用目录选择；不访问浏览器电脑路径，不开放任意绝对路径/磁盘树/上传/写文件/Shell，不改变 Harness 原生工作目录或 Skill 沙箱宿主挂载。它必须实际影响 workspaceContext 参考资料，不能只做 UI 选择框或数据库标签。

T1：扩展 WorkspaceContextProperties/Configuration 的多目录 catalog，稳定 workspaceId、名称、授权与声明身份；保留旧 bindings、默认关闭、文件显式声明及限额。严格检查租户、目录重叠、别名、私有ACL和现有禁止来源。HTTP/模型不能提供可信 tenant 或宿主路径。
T2：增加会话绑定与 Run 选择元数据的领域/Repository、memory/JDBC 与新方言迁移；DEFAULT/SELECTED/NONE 分清省略/选择/明确不用。建议使用独立元数据，避免破坏 Conversation/RunRecord 现有构造器。正文继续沿用加密检查点，不落普通元数据表。版本号实施时重查，表和每字段中文数据库原生注释，双库同语义。
T3：按设计接口表提供授权目录列表、会话绑定查询/条件更新、可选创建参数、同步/流式单轮选择及 Run 查询安全视图。旧无请求体创建和 input-only 调用兼容。绑定写入只允许创建者、空会话；既有会话读取权限不顺带重构。跨租户/Agent资源统一404，绑定版本冲突/已发消息409，拒绝必须审计。异常返回中文原因、正确HTTP/错误码和关联errorId；SSE建连后用既有错误事件，不能假改HTTP状态。
T4：在 ConversationService 的首发控制与短事务中固定 Run 选择，JDBC用行锁或等价CAS保障跨实例；模型调用和文件读取在事务外。WorkspaceContextService 的 applies/prepare/摘要/恢复/retain/cleanup 全部检查，不再按 findFirst 把不同目录混用。Run恢复只用原选择与原快照；撤销、身份变更、缺失/过期/损坏失败关闭，不能静默换目录。正文变化仅影响新Run。NONE不注入旧绑定，DEFAULT保留旧规则，SELECTED被禁用时明确失败。审计/持久化/清理错误不吞掉。
T5：在真实 META-INF/resources/console/v2/chat.html 和运行详情增加目录选择、摘要和只读信息；沿用受控工作台和旧DOM ID。ensureConversation 自动建会话必须带本次选择；刷新/Agent/会话切换从后端恢复，sessionEpoch防慢响应串台。已有消息锁定目录，换目录引导新会话；关闭弹层不保存、写操作不自动重试。覆盖空、关闭、权限、失效、冲突、超长名称和网络错误。键盘/焦点/Esc/aria-live可用，390px不溢出。共享app.js与console-core.js必须回归v1、跨页登录、审批、停止、失败提示和文件产物。
T6：按计划运行Java21的core/adapter/console快速测试、server定向MockMvc/运行合同、JS行为与语法检查。每个新增失败分支覆盖中文脱敏原因、HTTP/错误码/errorId、日志同号与敏感信息不泄露。record逐字段、枚举逐常量、权限/事务/资源/异常等命中项落实中文注释。涉及JDBC/Flyway/Testcontainers及完整server容器用例必须ssh rocky，在maven:3.9.9-eclipse-temurin-21中执行；验证Docker/JDK21、远程与本地待验提交和补丁一致，PostgreSQL16/MySQL8.4均通过，不使用本机Docker或全局清理。
T7：真实Provider两策略及桌面/390px浏览器完整验收、JDBC进程重启恢复；保留截图与操作证据，不用静态字符串测试或旧账本代替。更新README、配置/运维说明、发布说明，包含部署路径、卷、只读限制、兼容与排空回退。凭据只用于验证，不打印/记录/提交。外部环境不具备时记录确切阻塞、受影响验收和继续条件，继续独立工作，不伪造测试通过。

按T1→T2→T3→T4→T5→T6→T7推进。先读取相关POM/README/配置和最近规范再改；业务实现以清单/设计为验收依据，计划中拟新增文件名需要复核。建立独立实施包，沿用T0–T7映射或明确记录映射，同步实际代码位置、状态、命令、结果和遗留，不覆盖本分析包的历史事实。业务完成与验收通过分开记录，未提交写“未提交”。此提示词不授予提交、推送、合并、部署或外部消息权限，不强制启动子智能体。

交付依仓库顺序：变更摘要；实际验证命令与结果；影响范围；风险与注意事项；直接相关后续建议。链接实施工作包及原分析包，保留可继续问答入口。
```

## 关联产物

- [清单](../checklists/2026-10-27-workspace-directory-selection-checklist.md)
- [设计](../specs/2026-10-27-workspace-directory-selection-design.md)
- [计划](../plans/2026-10-27-workspace-directory-selection.md)
- [实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-design.md)
- [账本](../progress/2026-10-27-workspace-directory-selection-ledger.md)
