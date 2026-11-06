# 本机客户端页 2026-11-05 变更提示词

> 页面：`/console/v2/local-client.html`  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`

```text
仓库：F:\java\cm-agent
页面：/console/v2/local-client.html
主题：2026-11-05 本机客户端页变更

先读取本组清单、设计、计划、实现说明和账本。维护以下边界：
1. local_client_read_files/change_files 使用扁平 Schema，不使用 oneOf。
2. 数字参数接受整数或十进制字符串，客户端继续做范围与动作校验。
3. 仅本机设备 Run 使用原始当前输入；普通运行、持久化、审批、日志和审计继续脱敏。
4. 审批摘要使用 <已脱敏> 占位符，但执行使用加密检查点原始参数。
5. 不改变设备签名、RBAC、租户隔离、Run 授权和审批策略。
6. 使用 JDK 21 运行 LocalClientToolCatalogTest、LocalClientTaskExecutorTest、ConversationServiceInputTest、ToolApprovalServiceTest。
7. 更新本组六份文档，不提交、推送或部署，除非用户另有明确指令。
```

## 关联文档

- [清单](../checklists/2026-11-05-local-client-page-checklist.md)
- [设计](../specs/2026-11-05-local-client-page-design.md)
- [计划](../plans/2026-11-05-local-client-page.md)
- [实现说明](../implementation/2026-11-05-local-client-page-implementation-design.md)
- [账本](../progress/2026-11-05-local-client-page-ledger.md)
