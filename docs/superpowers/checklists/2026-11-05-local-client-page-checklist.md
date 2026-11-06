# 本机客户端页 2026-11-05 变更清单

> 页面：`/console/v2/local-client.html`  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`  
> Git 基线：`60d0fd2`

## 需求与范围

合并原 `local-client-tool-input-fix` 工作包，集中记录本机客户端页相关的工具参数、本机运行输入和审批摘要修复。

范围：

- 修复 `local_client_read_files`、`local_client_change_files` 的 `oneOf` 数字类型问题。
- 本机客户端 Run 把原始当前输入交给模型，同时保持持久化和展示脱敏。
- 验证目录读取、网络请求和文件追加的真实链路。

非目标：

- 不改变设备配对、RBAC、租户隔离或审批策略。
- 不增加跨平台安装包或远程 TLS。

## 任务与验收

| 编号 | 状态 | 目标 | 验收 |
|---|---|---|---|
| T0 | 完成 | 复现 Schema 数字类型失败和空 URL | 定位 Schema 与脱敏顺序 |
| T1 | 完成 | 扁平化 read/change Schema | 数字和数字字符串均可传递，客户端继续做动作校验 |
| T2 | 完成 | 分离持久化脱敏文本和本机模型输入 | 普通运行仍脱敏，本机 Run URL 不丢失 |
| T3 | 完成 | 补 Schema、输入和审批摘要测试 | 定向测试通过 |
| T4 | 完成 | 真实 8080 Run 验证 | read、network、change_files 均完成派发和回报 |
| T5 | 完成 | 合并原六份过程文档到本页面组 | 组内六份文件完整 |

核心验收：

- A1：`read_files` 和 `change_files` Schema 不含 `oneOf`。
- A2：`offset`、`limit`、`expectedSize` 数字字符串可通过且越界仍拒绝。
- A3：`local_client_network` 审批摘要含 `<已脱敏>`，执行参数仍为原始 URL。
- A4：真实本机操作状态、文件结果和网络响应与预期一致。
- A5：普通运行的脱敏输入边界不退化。

## 关联文档

- [设计](../specs/2026-11-05-local-client-page-design.md)
- [计划](../plans/2026-11-05-local-client-page.md)
- [提示词](../prompts/2026-11-05-local-client-page-prompt.md)
- [实现说明](../implementation/2026-11-05-local-client-page-implementation-design.md)
- [账本](../progress/2026-11-05-local-client-page-ledger.md)
