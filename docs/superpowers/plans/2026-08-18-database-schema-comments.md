# 数据库表与字段注释实施计划

## 1. 关联文档

- [需求设计](../specs/2026-08-18-database-schema-comments-design.md)
- [实现说明](../implementation/2026-08-18-database-schema-comments-implementation-design.md)
- [进度账本](../progress/2026-08-18-database-schema-comments-ledger.md)

## 2. 实施任务

### Task 1：确认最终 Schema

- 阅读父 POM、Persistence POM、README、数据库配置与部署文档。
- 按 V1、V4、V5、V6、V7 的顺序统计最终业务表和字段。
- 确认工作区已有用户修改并从任务范围排除。

### Task 2：新增方言迁移选择

- 新增 `cm-agent-persistence/src/main/java/com/cmagent/persistence/CmAgentFlyway.java`。
- 公共位置使用 `classpath:db/migration/*.sql`，方言位置根据 JDBC 产品名称选择。
- Server 启动配置和 JDBC/Testcontainers 测试统一复用该配置，避免测试与生产迁移位置漂移。

### Task 3：新增 V8 注释迁移

- 新增 PostgreSQL `COMMENT ON TABLE/COLUMN` 脚本。
- 新增 MySQL `ALTER TABLE ... COMMENT/MODIFY COLUMN` 脚本，完整保留类型与空值约束。
- 静态核对两个脚本均为 18 个表注释、135 个字段注释。

### Task 4：增强迁移测试

- 迁移执行数从 7 更新为 8。
- 使用 JDBC 元数据逐表、逐字段断言注释非空。
- 保留并运行既有索引、外键、唯一约束和可空性断言。

### Task 5：更新规则与文档

- 在根目录 `AGENTS.md` 增加表/字段中文数据库注释强制规则、方言目录规则和双数据库测试规则。
- 更新配置、部署、架构和发布说明。
- 补齐同主题的设计、计划、实现、进度四份中文文档。

### Task 6：验证

1. 使用本机 Temurin JDK 21 与 Maven 3.9.4 编译 Persistence、Server 及测试源码。
2. 创建只包含本任务文件的临时验证提交，不纳入用户已有配置改动。
3. 将临时提交 bundle 同步到 Rocky 独立 worktree，确认本地与远程提交一致且状态干净。
4. 确认 Rocky Docker 和 `maven:3.9.9-eclipse-temurin-21` 中的 Java/Maven 版本。
5. 在容器中运行 `mvn -q -pl cm-agent-persistence -am test`，覆盖 PostgreSQL 16 与 MySQL 8.4。
6. 运行 `git diff --check`、变更范围和敏感信息检查，更新进度账本的实际结果。

## 3. 回滚与发布

V8 只增加元数据注释。迁移失败时停止发布并保留 Flyway 错误上下文，不修改历史迁移；MySQL DDL 可能已自动提交部分表注释，因此应从备份恢复或通过修正后的更高版本迁移收口，不能改写已经在环境中执行过的 V8。
