# 会话问答停止本轮：需求清单

- 日期：2026-10-10；主题：`chat-stop`；修订：R0；工作模式：`mode=interactive`。
- 需求来源：用户新增“让问答支持暂停”，经 D1～D3 明确为控制台会话聊天的停止本轮能力。
- Git 基线：`8cd22b3f031c5887cba86d1ba3f4222d9a057184`，分析对象为当前工作树。
- 当前交付：需求分析及六份工作包；业务实施尚未开始；未提交。

## 已确认范围

1. D1：作用于 CM Agent 控制台会话聊天。
2. D2：停止本轮，保留已输出内容；收口后可以发送新消息；原回答不续跑。
3. D3：等待人工审批时也可停止，关闭待审批请求并清理检查点；旧批准请求不能恢复工具执行。

产品按钮采用“停止”，运行中采用“正在停止”，终态采用“已停止”，避免把不可恢复的结束表现成可继续的暂停。保留脱敏的部分回答和已发生的工具记录；刷新后可回看。范围包含初次发送、审批后的恢复运行及等待审批。暂不提供原 Run 恢复、重新生成、独立 TEST/调试页停止入口或跨实例运行接管。

## 当前证据

| 编号 | 当前文件及位置 | 查证结论 |
|---|---|---|
| E1 | `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`；`assets/app.js:3110`、`:3138`（assets 为资源根目录下目录） | v2 聊天只有发送入口，忙碌/审批期间禁用发送；实际聊天逻辑在共享 app.js |
| E2 | `cm-agent-server/src/main/java/com/cmagent/server/web/ConversationController.java:175`、`:476` | SSE 已提供 started/delta/progress/审批/终态；连接断开后继续后台运行，没有用户停止接口 |
| E3 | `cm-agent-server/src/main/java/com/cmagent/server/runtime/ConversationService.java:193`、`:210`、`:237` | USER 与 Run 先提交，再发送 runId；assistant 在运行返回后追加，临时 delta 不等于已保存消息 |
| E4 | `cm-agent-core/src/main/java/com/cmagent/core/domain/RunStatus.java`；`ToolApprovalStatus.java` | Run 没有停止中/已停止状态；审批已有 CANCELLED，应该复用 |
| E5 | `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeReActExecutor.java:339`、`:469`；`AgentScopeRunGate.java` | 执行器同步等待事件流，门控已有中断、超时和迟到结果抑制；尚未连接用户停止信号 |
| E6 | `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRunRepository.java:112`；`cm-agent-server/src/main/java/com/cmagent/server/runtime/RunPersistenceService.java:188` | Run 终态更新有状态条件及审计边界，需要增加停止与正常完成的竞争处理 |
| E7 | `README.md:117`、`:160`、`:186` | 明确不提供手动取消；审批有加密检查点与过期处理，现有跨实例执行租约尚未提供 |

CodeGraph 已先行调用，结果存在截断和前端定位不完整，随后用源码定点查询核实；“未找到覆盖测试”的索引提示不作为测试不存在的证据。未启动服务器、浏览器、模型或数据库。现有 index.html、SkillArtifactProtocol.java、application 配置及 .codex/.workbuddy 修改保持原样，不作为本次交付。

## 任务与验收

| 编号 | 状态 | 目标及涉及文件 | 依赖 | 验收条件 | 验证方法 |
|---|---|---|---|---|---|
| T0 | 完成 | 查证需求、记录 D1～D3，交付本组六份文档 | 无 | 用户选择可追溯；实施与分析状态分开 | 文档、引用和任务一致性检查 |
| T1 | 待做 | 定义 Run 停止状态、控制契约及能力边界；core domain/runtime/repository | T0 | CANCELLING 为非终态，CANCELLED 为终态；审批复用 CANCELLED；旧 Runtime 不伪报停止成功 | core 状态、不变量与兼容契约测试 |
| T2 | 待做 | 接入实际运行控制；AgentScopeRuntimeAdapter、ReActExecutor、RunGate、工具与技能网关 | T1 | 停止信号阻止后续模型/工具/沙箱调用；中断及释放只执行一次；迟到结果不改写停止终态 | adapter 本地模型 Stub、并发及受控阻塞测试 |
| T3 | 待做 | 持久化停止、部分回答、审批撤销和审计；RunPersistenceService、ConversationService、ToolApprovalService、memory/JDBC 仓储 | T1、T2 | 停止/完成/批准/过期原子竞争；审批检查点清理；部分消息不重复；审计失败不能伪报成功 | 服务单测；Rocky PostgreSQL 16/MySQL 8.4 集成测试 |
| T4 | 待做 | 停止与状态查询接口、SSE 收口、权限和诊断；ConversationController、运行控制服务 | T3 | 认证 tenant、Agent、会话、Run、发起人及 agent:run 校验；幂等停止；错误编号和日志一致 | MockMvc 与流式协议测试 |
| T5 | 待做 | v2 聊天入口及状态恢复；chat.html、assets/app.js、assets/console-core.js、必要 CSS | T4 | 生成/执行/审批恢复/等待审批均可停止；确认终态后发送；断线、刷新和重复点击正确；v1 不回归 | 前端契约与桌面/移动浏览器交互验证 |
| T6 | 待做 | 端到端验收及生产说明；README、配置/运维说明、release-notes、实施工作包 | T1～T5 | 完成验收矩阵，说明副作用及运行部署边界；必需检查真实通过 | Java 21 定向测试、Rocky 数据库测试、真实模型和浏览器证据 |

执行顺序：T1 → T2 → T3 → T4 → T5 → T6。T0 的完成只证明需求分析及文档交付，不证明任何停止行为可用。

## 门禁与假设

- A1：停止只允许本轮发起人且具备 `agent:run`；管理员代停未纳入首版。实施时按可信主体核实资源链。
- A2：首版以当前单实例执行边界交付；数据库中的停止状态需要持久化。多实例部署必须有经验证的命令路由/门控机制才能声称可用；缺失时明确拒绝并记录运维限制。
- A3：后端先确认执行已收口才显示“已停止”；外部工具忽略中断时仍显示停止中，当前会话不得启动下一轮。
- A4：部分回答作为已停止消息保留；续聊上下文注明中断事实，只带安全完整的工具摘要，不伪造工具结果。
- A5：现有状态字段可能容纳新增字符串，是否需要新增停止元数据、索引或迁移，由 T1/T3 实际设计核实。禁止修改历史迁移。
- 未验证：AgentScope 用户中断在各模型 Provider 下的停止时机、外部工具取消能力、服务重启后的孤立停止中记录收口策略，均为实施验收项。

## 关联产物

- [执行提示词](../prompts/2026-10-10-chat-stop-prompt.md)
- [设计](../specs/2026-10-10-chat-stop-design.md)
- [计划](../plans/2026-10-10-chat-stop.md)
- [实现说明](../implementation/2026-10-10-chat-stop-implementation-design.md)
- [账本](../progress/2026-10-10-chat-stop-ledger.md)
