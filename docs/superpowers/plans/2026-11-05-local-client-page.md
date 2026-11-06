# 本机客户端页 2026-11-05 变更计划

> 页面：`/console/v2/local-client.html`  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`

## 执行顺序

| 编号 | 操作 | 验证 |
|---|---|---|
| T0 | 复现 read/change Schema 错误和网络空 URL | 真实 8080 Run |
| T1 | 调整 `LocalClientToolCatalog` | Schema 无 `oneOf`，数字字符串通过 |
| T2 | 调整 `LocalClientTaskExecutor` | 字符串整数有界解析 |
| T3 | 调整 `ConversationService` 的本机输入选择 | 普通/本机输入分流测试 |
| T4 | 验证审批摘要和加密检查点 | 摘要 `<已脱敏>`，执行使用原参数 |
| T5 | 运行定向测试和真实 Run | 测试通过、目录读取和网络请求成功 |
| T6 | 补齐文件追加 R1 验收 | 测试文件从 `abc` 变为 `abcdef` |

## 完成标准

- 参数校验稳定通过 AgentScope。
- 本机原始输入只用于受控 Run。
- 持久化、审批和日志继续脱敏。

## 关联文档

- [清单](../checklists/2026-11-05-local-client-page-checklist.md)
- [设计](../specs/2026-11-05-local-client-page-design.md)
- [提示词](../prompts/2026-11-05-local-client-page-prompt.md)
- [实现说明](../implementation/2026-11-05-local-client-page-implementation-design.md)
- [账本](../progress/2026-11-05-local-client-page-ledger.md)
