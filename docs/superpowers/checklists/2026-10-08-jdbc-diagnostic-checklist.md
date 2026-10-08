# JDBC 诊断日志修正清单

修订：R0；日期：2026-10-08；工作模式：mode=default。

需求来源：用户要求按建议优化日志；数据库原因被 SQL 正则和异常包装隐藏。仅修改服务端日志快照与测试，不改变 API 错误映射、权限、配置或数据库结构。

基线：`codex/skills-version-console`，HEAD `5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443`。已有控制台、V18、README、配置与旧工作包改动保持；不提交、不推送、不合并、不部署，不触碰运行中的服务或真实凭据。

证据：`ErrorDiagnosticLogger` 原实现以 SQL 关键词至消息末尾替换，并创建无 cause 的 RuntimeException。CodeGraph 返回部分源码后，精确读取剩余代码与现有测试。

| 编号 | 状态 | 目标与验收 | 依赖 |
|---|---|---|---|
| T1 | 完成 | 核实现有 SQL 全尾脱敏、异常链丢失、Git 基线和保护范围 | 无 |
| T2 | 完成 | 复制脱敏原因链，记录 SQLState/厂商编号/固定数据库原因；控制异常图规模 | T1 |
| T3 | 完成 | 单元、API 及 Rocky 双库真实错误验证；55 项通过，编号关联、原因、脱敏断言通过 | T2 |
| T4 | 完成 | 六份工作包、运维与发布说明已同步；链接及差异检查通过，详见账本 | T3 |

验证：`ErrorDiagnosticLoggerTest`、`ApiExceptionHandlerTest`、`DiagnosticJdbcLoggingTest` 及既有调用方专项回归。数据库与容器测试仅 ssh rocky；先核对 Docker、Maven JDK 21、Git HEAD 及文件 SHA256。

## 关联产物

- [提示词](../prompts/2026-10-08-jdbc-diagnostic-prompt.md)
- [设计](../specs/2026-10-08-jdbc-diagnostic-design.md)
- [计划](../plans/2026-10-08-jdbc-diagnostic.md)
- [实现说明](../implementation/2026-10-08-jdbc-diagnostic-implementation-design.md)
- [账本](../progress/2026-10-08-jdbc-diagnostic-ledger.md)
