# 会话停止本轮实施：需求清单

- 日期：2026-10-10；主题：`chat-stop-delivery`；修订：R0；模式：`mode=default`。
- 来源：用户明确要求依据原 [设计](../specs/2026-10-10-chat-stop-design.md) 与 [提示词](../prompts/2026-10-10-chat-stop-prompt.md) 推进实施。原 `chat-stop` 六份分析文档保留为形成需求时的历史快照，不覆盖历史状态。
- Git 基线：`8cd22b3f031c5887cba86d1ba3f4222d9a057184`；验证对象为本次交付工作树；已按用户指令纳入本地提交，未推送、未部署。提交说明见账本。
- 本独立实施包采用默认咨询模式；继承已确认 D1～D3，而不重新询问。真实模型授权 D4 见设计和账本。

## 范围

控制台持久化会话的初次运行、审批等待及批准后的恢复均可停止；不续跑原回答，保留脱敏正文，安全终态后可发新消息。原发起主体及 `agent:run` 才能停止；查询按 `agent:read`。首版仅单实例活跃句柄；缺失句柄、旧 Runtime 明确拒绝。独立 TEST/单轮 API、跨实例接管、强制结束、重启补偿和外部副作用回滚不在范围内。

## 任务与验收

| 编号 | 状态 | 目标与实际文件线索 | 依赖 | 验收条件与证据 |
|---|---|---|---|---|
| T0 | 完成 | 复核授权、原设计、POM、配置与脏树 | 无 | 已保留分析历史和无关用户修改；CodeGraph 先定位、截断后复核源码 |
| T1 | 完成 | core RunStatus、RunRecord、RunCancellation、Repository/SPI | T0 | 停止中非终态；终态不可覆盖；许可退出和旧构造器兼容；core 定向测试 |
| T2 | 完成 | adapter ReActExecutor、RunGate、RuntimeAdapter | T1 | 用户信号阻止后续准入；等待忽略中断调用实际退出；迟到严格失败传播；adapter 合同及外部测试 |
| T3 | 完成 | server 会话/审批/持久化编排、memory/JDBC 仓储 | T1、T2 | CAS、短事务、审批取消/检查点清理、接受历史保留、部分正文与审计；Rocky 双库各 19 项 |
| T4 | 完成 | ConversationController、ApiExceptionHandler、RunControlRegistry/Exception | T3 | 200/202/204/403/404/409、可信资源链、同号日志、幂等与失败关闭；MockMvc/服务测试 |
| T5 | 完成 | v2 chat、共享 app/core、图标、CSS、资源版本 | T4 | 停止不 Abort SSE；终态前发送锁；刷新、审批恢复、网络失败不自动重试；78 项 JS、桌面/390px 浏览器 |
| T6 | 完成 | 最终回归、README、configuration、operations、release-notes、本六份文档 | T1～T5 | core96、服务端97、adapter82、console14通过；外部专项1、JS78、双库各19、最终浏览器通过；六份33链接有效 |

执行顺序沿用 T0 → T1 → T2 → T3 → T4 → T5 → T6。实际命令、失败修复过程及证据边界只以账本为准。

## 当前证据和限制

- 核心调用链、文件位置见实现说明；没有新增 schema 或配置键。
- 浏览器 fixture 使用真实 Spring、认证、权限、仓储与 SSE，仅替换模型和外部工具；它不能替代真实 Provider 证据。
- 已实际执行用户指定 `qwen3.8-27b` 的外部停止测试；凭据只从环境读取，不修改既有配置或绑定。
- Rocky 与本机同 HEAD、源代码覆盖归档 SHA-256 一致；配置来自基线，未传用户 application 文件。后续本机句柄清理修复单独做单元/浏览器复验，不冒充已在双库重跑。
- 不承诺外部系统撤销或供应商立即停止计费；服务重启遗留活跃状态需要人工排查，不自动重放。


## 关联产物

- [提示词](../prompts/2026-10-10-chat-stop-delivery-prompt.md)
- [设计](../specs/2026-10-10-chat-stop-delivery-design.md)
- [计划](../plans/2026-10-10-chat-stop-delivery.md)
- [实现说明](../implementation/2026-10-10-chat-stop-delivery-implementation-design.md)
- [账本](../progress/2026-10-10-chat-stop-delivery-ledger.md)
