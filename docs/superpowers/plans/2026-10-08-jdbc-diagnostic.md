# JDBC 诊断日志修正计划

修订：R0；日期：2026-10-08；工作模式：mode=default。

T1：核对 AGENTS、server POM、运维说明、Git、日志实现与现有 API/调用方测试，保存本轮保护范围。

T2：在 server diagnostic 包增加 DiagnosticExceptionSanitizer，按 JDBC 元数据构造固定分类与独立异常图；ErrorDiagnosticLogger 接入快照及结构化字段。补充安全边界、复制、循环与第三方 JDBC 行为的中文说明。

T3：增加 ErrorDiagnosticLoggerTest、DiagnosticJdbcLoggingTest；扩展既有 ApiExceptionHandlerTest，检查 API 503/错误码/编号未变且日志同编号。Rocky 独立工作区采用相同 HEAD、显式五文件覆盖和 SHA256 验证，maven:3.9.9-eclipse-temurin-21 执行测试。数据库使用 postgres:16-alpine/mysql:8.4 的 Testcontainers 临时实例，不连接用户运行数据库。

T4：更新 docs/operations.md 与 docs/release-notes.md，完成此六份产物、Git diff --check 和链接检查；账本记录确切命令、结果、未执行项和未提交。

## 关联产物

执行结果：T1～T4 已完成，最终专项验证 55 项通过，实际命令与统计见账本；不以此计划替代测试证据。

- [清单](../checklists/2026-10-08-jdbc-diagnostic-checklist.md)
- [提示词](../prompts/2026-10-08-jdbc-diagnostic-prompt.md)
- [设计](../specs/2026-10-08-jdbc-diagnostic-design.md)
- [实现说明](../implementation/2026-10-08-jdbc-diagnostic-implementation-design.md)
- [账本](../progress/2026-10-08-jdbc-diagnostic-ledger.md)
