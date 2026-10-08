# JDBC 诊断日志修正执行提示词

修订：R0；日期：2026-10-08；工作模式：mode=default。

```text
请在 F:/java/cm-agent 中处理“JDBC 诊断日志修正”，先读取：
docs/superpowers/checklists/2026-10-08-jdbc-diagnostic-checklist.md
docs/superpowers/progress/2026-10-08-jdbc-diagnostic-ledger.md
docs/superpowers/specs/2026-10-08-jdbc-diagnostic-design.md
docs/superpowers/plans/2026-10-08-jdbc-diagnostic.md
docs/superpowers/implementation/2026-10-08-jdbc-diagnostic-implementation-design.md
以及当前 AGENTS.md、server POM 和 docs/operations.md。

mode=default：自主处理工程细节，依据当前证据记录假设，不主动咨询普通决定。
范围为 T1～T4：核对现状、安全复制异常链、补充 JDBC/API/调用方测试、同步工作包和生产说明。
先查账本；已完成项仅在新变更影响其验收时复核，不能重复实施或拿历史通过代替当前验证。

SQLState、厂商编号、原始异常类型和固定中文数据库原因可进入后台日志；
SQL、参数、重复键值、失败行、凭据和内部 URL 不得进入日志。
数据库节点及其包装消息不能通过只替换 SQL 片段后输出原文；
普通异常沿用现有文本脱敏器。保留安全 cause/suppressed/JDBC nextException，
按身份判循环并限制原异常节点、深度、消息与堆栈大小，不持有原异常引用。
API 状态、错误码、消息及 errorId 关联保持原规则，不增加配置或数据库迁移。

保护工作树现有控制台、V18、配置、旧工作包及用户其他改动，只编辑本需求相关路径。
使用现有 codex/ 分支；不提交、不推送、不合并、不部署，不重启运行中服务。
Docker/JDBC/Testcontainers 验证在 ssh rocky 的独立目录使用
maven:3.9.9-eclipse-temurin-21；核对 Docker、JDK 21、Git HEAD 和待测源码 SHA256。
真实数据库测试仅使用 postgres:16-alpine/mysql:8.4 的临时 Testcontainers。
专项范围：ErrorDiagnosticLoggerTest、DiagnosticJdbcLoggingTest、ApiExceptionHandlerTest、
ApprovalExpiryScannerTest、ModelCatalogDiscoveryServiceTest、ModelConfigControllerTest、
SandboxEndpointControllerTest。完整命令、结果及外部阻塞写入账本。
若 SSH、镜像或版本不一致，记录确切阻塞及未执行验收，不虚报通过。

持续推进至验收，同步此六份文档，核对相对链接及 git diff --check；
最终按仓库规范报告实际变更、验证、影响范围、限制与生效步骤。
```

提示词供后续执行者参考；生成文件本身不新增修改或外部操作授权。本轮用户已授权的修正已实施，55 项专项验证通过，当前状态以账本为准。

## 关联产物

- [清单](../checklists/2026-10-08-jdbc-diagnostic-checklist.md)
- [设计](../specs/2026-10-08-jdbc-diagnostic-design.md)
- [计划](../plans/2026-10-08-jdbc-diagnostic.md)
- [实现说明](../implementation/2026-10-08-jdbc-diagnostic-implementation-design.md)
- [账本](../progress/2026-10-08-jdbc-diagnostic-ledger.md)
