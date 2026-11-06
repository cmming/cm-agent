# 本机客户端页 2026-11-05 变更设计

> 页面：`/console/v2/local-client.html`  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`

## 背景

真实运行发现三处相关问题：AgentScope 对 `oneOf` 参数的类型转换导致本机工具在网关前失败；本机网络 URL 在模型调用前被整体脱敏；审批摘要需要保留非空脱敏占位符。

## 方案

| 决定 | 选择 |
|---|---|
| 工具 Schema | `read_files`、`change_files` 使用单一对象 Schema，不使用 `oneOf` |
| 数字参数 | Schema 接受整数或十进制字符串；客户端执行严格范围和动作校验 |
| 模型输入 | 只有显式本机 Run 使用原始当前输入；普通运行继续使用脱敏文本 |
| 持久化与展示 | 消息、Run、审批摘要、进度、日志和审计继续脱敏 |
| 审批恢复 | 使用加密检查点中的原始工具参数，不把摘要当执行参数 |

## 安全边界

- 原 URL 只用于当前已配对设备 Run 的模型决策。
- 设备签名、Run 授权、高风险审批和审计仍全部生效。
- URL 中的敏感查询参数不得进入持久化消息、日志或审批摘要。

## 页面边界

本页面负责设备配对、状态和本机操作入口；实际文件、网络和命令在浏览器所在电脑执行。它不等同于 `projects.html` 的服务端本机项目目录。

## 关联文档

- [清单](../checklists/2026-11-05-local-client-page-checklist.md)
- [计划](../plans/2026-11-05-local-client-page.md)
- [提示词](../prompts/2026-11-05-local-client-page-prompt.md)
- [实现说明](../implementation/2026-11-05-local-client-page-implementation-design.md)
- [账本](../progress/2026-11-05-local-client-page-ledger.md)
