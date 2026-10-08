# JDBC 诊断日志修正设计

修订：R0；日期：2026-10-08；工作模式：mode=default。

## 背景和范围

日志只显示“已脱敏SQL”，原因是关键词全尾正则与无 cause 的异常副本。目标为保留可检索原因，同时遵守原始 SQL、行数据和凭据不可记录的边界。无前端、数据库迁移、配置开关、API 错误分类调整。

## 方案与自主假设

按 SQLException 的 SQLState 与 MySQL 厂商编号白名单生成固定中文分类，不解析本地化原文，不提取表列名称、重复值或失败行。未知编号保留编号和类型，原因降级为固定脱敏说明。PostgreSQL 使用 SQLState，MySQL 的 23000 结合厂商编号区分约束；依据 [PostgreSQL 16](https://www.postgresql.org/docs/16/errcodes-appendix.html)、[MySQL 8.4](https://dev.mysql.com/doc/mysql-errors/8.4/en/server-error-reference.html)。默认认为原始数据库消息整体不可信，拒绝仅替换 SQL 片段后输出尾部 Detail。

复制 cause、suppressed；JDBC nextException 以标记的 suppressed 快照保留。副本不引用原异常，也不实例化驱动异常，防止 toString 扩展字段泄露。非数据库异常沿用既有文本脱敏。身份集合防循环，32 节点、16 层深度、每节点 128 帧和 2048 字符消息限制。

日志新增 sqlState、vendorCode、databaseExceptionType、databaseReason；保持可信 tenant、资源和 errorId。原异常类型写入每层消息，日志中的副本类型仍为 RuntimeException。API 仍返回 503/PERSISTENCE_UNAVAILABLE/数据服务暂不可用及同一 errorId，不向页面返回数据库详情。

## 验收

长度超限、唯一约束等有固定原因；真实双库编号可检索；完整渲染堆栈不含 SQL、参数、失败行、密码、JWT、内部 URL；未知编号、非法 SQLState、循环/深度/附加异常受控；既有模型发现、审批与沙箱诊断回归通过。

## 关联产物

最终验收：55 项专项测试全部通过，包括真实双库长度和唯一约束验证；API 保持 503 与原错误码。无外部阻塞，无生产部署或服务重启。

- [清单](../checklists/2026-10-08-jdbc-diagnostic-checklist.md)
- [提示词](../prompts/2026-10-08-jdbc-diagnostic-prompt.md)
- [计划](../plans/2026-10-08-jdbc-diagnostic.md)
- [实现说明](../implementation/2026-10-08-jdbc-diagnostic-implementation-design.md)
- [账本](../progress/2026-10-08-jdbc-diagnostic-ledger.md)
