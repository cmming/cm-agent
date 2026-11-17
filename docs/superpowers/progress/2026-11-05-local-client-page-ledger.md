# 本机客户端页 2026-11-05 变更账本

> 页面：`/console/v2/local-client.html`  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`  
> Git 基线：`60d0fd2`  
> 提交状态：未提交

## 任务状态

| 编号 | 状态 | 证据 |
|---|---|---|
| T0 | 完成 | 真实 8080 Run 复现 `oneOf` 数字类型错误和空 URL |
| T1 | 完成 | 两个本机工具 Schema 已扁平化 |
| T2 | 完成 | 字符串整数解析与范围测试通过 |
| T3 | 完成 | 本机/普通输入分流与审批摘要测试通过 |
| T4 | 完成 | 真实 read_files、network 完成设备派发和回报 |
| T5 | 完成 | 本组六份文档已建立 |

## 实际验证

```powershell
mvn -q -pl cm-agent-server,cm-agent-local-client -am `
  "-Dtest=LocalClientToolCatalogTest,LocalClientTaskExecutorTest,ConversationServiceInputTest,ToolApprovalServiceTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

退出码 0。客户端模块完整测试也通过。

真实 Run 证据：

- 目录读取成功，返回 5 个条目。
- 网络请求返回 HTTP 200 / `UP`。
- 文件追加后内容为 `abcdef`，长度 6 字节。

## 风险

- 本机 Run 会把本轮原始输入发给模型提供方。
- 普通运行和历史消息仍使用脱敏输入。

## 关联文档

- [清单](../checklists/2026-11-05-local-client-page-checklist.md)
- [设计](../specs/2026-11-05-local-client-page-design.md)
- [计划](../plans/2026-11-05-local-client-page.md)
- [提示词](../prompts/2026-11-05-local-client-page-prompt.md)
- [实现说明](../implementation/2026-11-05-local-client-page-implementation-design.md)

## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：T0–T5 输入 Schema/解析修复与实际设备回报有记录，本包范围完成。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
