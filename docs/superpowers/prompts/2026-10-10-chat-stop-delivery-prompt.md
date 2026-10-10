# 会话停止本轮实施：交付提示词

日期：2026-10-10；主题：`chat-stop-delivery`；修订：R0；模式：`mode=default`。T0～T6已完成，已按用户指令纳入本地提交，未推送、未部署；后续先查账本，以新指令为准，不重复已通过工作。生成本提示词或快捷问答不授权修改、模型费用、后续提交或部署。

```text
你在 F:/java/cm-agent 工作，维护“会话停止本轮”交付。
先读取当前 AGENTS.md 和本组六份：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-10-chat-stop-delivery-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-10-chat-stop-delivery-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-10-chat-stop-delivery-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-10-chat-stop-delivery.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-10-chat-stop-delivery-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-10-chat-stop-delivery-ledger.md
原需求来源为 specs/2026-10-10-chat-stop-design.md 与 prompts/2026-10-10-chat-stop-prompt.md；原分析 R0 保留历史，不改写成已实施。

mode=default：常规细节自主取舍并记录依据，不主动询问普通方案；缺失凭据或外部状态时记录阻塞，不编造。
D1 会话聊天；D2 停止原轮且不续跑；D3 等待审批也取消并清理检查点；D4 用户授权本次 DashScope qwen3.8-27b 两轮停止联调，已执行结果读账本。此授权不等于无限后续付费调用。
先核对 Git HEAD/脏树和已有证据，存在 .codegraph 先定位，再定点读源码。保留用户 SkillArtifactProtocol、application 配置、index.html、.codex/.workbuddy。

任务稳定编号 T0～T6 以当前清单/账本为准；只完成仍待验收工作。已通过测试不机械重复，发现实际回归则补相应测试。
CANCELLING 不等于结束，实际在途调用退出并提交终态前不放行新消息；不把 SSE 断开、interrupt 或超时当作已停止。
tenant/主体来自认证上下文，验证 Agent→会话→Run→USER 与原发起人；POST agent:run、GET agent:read。
停止/完成/审批/过期共用短锁和 CAS/短事务，不在外部调用中持有数据库事务。已接受决定保留历史；旧片段只能释放自己的句柄。
严格审计、持久化或清理失败不能被 CANCELLED 覆盖；中文原因、稳定错误码、同号 errorId 和脱敏诊断必须测试。
旧 Runtime 不支持则明确拒绝，缺失活跃句柄返回 409，保留发送锁，不自动接管、重放或强行收口。
只持久化脱敏部分正文；续聊明确中断，不注入未闭合工具调用；文件产物成功门禁不变。
新增 Java 生命周期、安全、并发、异常、枚举和 record 字段按 AGENTS 落地中文注释/JavaDoc。

本机 Java 21 定向测试；JDBC/Testcontainers 仅 ssh rocky 的 maven:3.9.9-eclipse-temurin-21，PG16-alpine/MySQL8.4；核对 HEAD 和工作树源覆盖哈希。
scripts/verify-chat-stop-browser.cjs 用真实 test fixture、随机内存凭据、18094 回环端口和 Edge 验证桌面/390px；结束清理自己的浏览器/服务，不碰现有 8080。
外部模型仅用户新授权后设置 CM_AGENT_EXTERNAL_STOP_TEST=true；凭据从环境读取、不打印/存储，fixture 与真实 Provider 证据分开。
不执行本机 Docker、全局远程清理或自动部署。同步六份、生产文档和 release-notes，记录真实命令/结果及未执行项。
交付按变更摘要、验证、影响、风险、直接下一步输出，提供此提示词及问答快捷入口；未提交明确记录。
```

## 关联产物

- [清单](../checklists/2026-10-10-chat-stop-delivery-checklist.md)
- [设计](../specs/2026-10-10-chat-stop-delivery-design.md)
- [计划](../plans/2026-10-10-chat-stop-delivery.md)
- [实现说明](../implementation/2026-10-10-chat-stop-delivery-implementation-design.md)
- [账本](../progress/2026-10-10-chat-stop-delivery-ledger.md)
