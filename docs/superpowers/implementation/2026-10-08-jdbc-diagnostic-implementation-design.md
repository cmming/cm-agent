# JDBC 诊断日志修正实现说明

修订：R0；日期：2026-10-08；工作模式：mode=default。

已完成：DiagnosticExceptionSanitizer 复制安全快照；ErrorDiagnosticLogger 新增结构化 JDBC 字段并打印原因链。数据库节点及其包装/附加消息均不透传原文；普通异常逐层使用既有脱敏器。每层消息保留原异常类型及堆栈位置；后续 JDBC 异常使用标签标明。

新增 ErrorDiagnosticLoggerTest 覆盖白名单原因、敏感行数据、非法 SQLState、原因/附加/JDBC 链、循环、深度和体积；DiagnosticJdbcLoggingTest 验证两库真实长度与唯一约束；ApiExceptionHandlerTest 验证接口与日志关联。最终 55 项专项测试全部通过，实际执行证据以账本为准。

复核补充：预扫描有遍历预算，复制时仍对深处 SQLException/数据访问包装重新建立拒绝边界；添加预算耗尽回归。MySQL 23000 的未知厂商编号不一概认定为约束错误（1052 等例外），回退固定安全说明并补充回归。

生产说明更新位于 docs/operations.md 的“日志与告警”和 docs/release-notes.md 的“数据库诊断日志修正”。没有改动数据库结构、配置或 HTTP 异常映射。

方案边界：保留副本 RuntimeException 类型以避免重建第三方异常的隐式副作用；原始类型在消息与结构化字段中保留，不直接输出数据库表/列/参数。现有受控错误、上游正文 SQL 脱敏和 API 映射保持。

## 关联产物

- [清单](../checklists/2026-10-08-jdbc-diagnostic-checklist.md)
- [提示词](../prompts/2026-10-08-jdbc-diagnostic-prompt.md)
- [设计](../specs/2026-10-08-jdbc-diagnostic-design.md)
- [计划](../plans/2026-10-08-jdbc-diagnostic.md)
- [账本](../progress/2026-10-08-jdbc-diagnostic-ledger.md)
