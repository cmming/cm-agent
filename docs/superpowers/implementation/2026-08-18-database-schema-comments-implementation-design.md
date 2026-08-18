# 数据库表与字段注释实现说明

## 1. 关联文档

- [需求设计](../specs/2026-08-18-database-schema-comments-design.md)
- [实施计划](../plans/2026-08-18-database-schema-comments.md)
- [进度账本](../progress/2026-08-18-database-schema-comments-ledger.md)

## 2. 最终实现

`CmAgentFlyway` 成为生产与测试共用的 Flyway 配置入口。它临时获取一个数据源连接，读取 JDBC `DatabaseProductName`，仅接受 PostgreSQL 和 MySQL，然后加载公共根目录 SQL 与对应方言目录。公共位置使用 `classpath:db/migration/*.sql`，从扫描层面排除子目录，避免两个数据库的 V8 被同时识别为重复版本。

PostgreSQL V8 使用 18 条 `COMMENT ON TABLE` 和 135 条 `COMMENT ON COLUMN`。MySQL V8 使用 18 条 `ALTER TABLE`，每条同时设置表注释并对该表全部字段执行带注释的 `MODIFY COLUMN`；所有类型、长度和可空性均按 V7 后最终结构重述。

`MigrationTest` 将预期迁移数更新为 8，并对 `REQUIRED_TABLES` 中每一张表读取 JDBC `REMARKS`。字段断言不是维护一份容易过期的固定字段列表，而是通过 `DatabaseMetaData#getColumns` 枚举迁移后的实际字段，因此未来新增未注释字段会直接失败。原有索引、外键、唯一索引和可空性断言保持不变。

## 3. 关键代码位置

- 方言选择：`cm-agent-persistence/src/main/java/com/cmagent/persistence/CmAgentFlyway.java`
- PostgreSQL V8：`cm-agent-persistence/src/main/resources/db/migration/postgresql/V8__add_schema_comments.sql`
- MySQL V8：`cm-agent-persistence/src/main/resources/db/migration/mysql/V8__add_schema_comments.sql`
- 迁移契约：`cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java`
- Server 启动接入：`cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`

## 4. 调用链变化

```text
DataSource
  -> CmAgentFlyway 读取 JDBC 产品名称
  -> 公共 V1–V7 + 当前方言 V8
  -> Flyway migrate
  -> JdbcClient / Repository 初始化
```

外部 API、Repository 契约和数据库业务数据均不改变。Supabase 仍通过 PostgreSQL JDBC 元数据选择 PostgreSQL V8。

## 5. 与原方案的差异

最初评估过 Java 版本化迁移，但仓库规则要求结构变更保留为 `Vn__description.sql`，因此最终采用同版本方言 SQL。Flyway 11.7.2 会递归扫描普通目录，且当前版本没有按数据库自动替换迁移位置的 `{vendor}` 逻辑，所以增加了显式 JDBC 方言选择器和根目录文件通配位置。

## 6. 安全与兼容性

- 注释不包含凭据；敏感字段只描述其安全边界，不写入任何真实值。
- MySQL 脚本不禁用外键检查，依靠类型完全一致的 `MODIFY COLUMN` 保持约束。
- 不修改 V1–V7，不影响已有 Flyway 校验和。
- 未识别的数据库类型会在迁移前失败，避免误执行不匹配的 DDL。

## 7. 验证结果

本机使用 Temurin 21.0.11、Maven 3.9.4 完成 Persistence 与 Server 跳过测试打包。Rocky Linux 使用与本地一致的临时提交 `ff68c612bcdc349b1446591eefc316fdb07c3f46`，在 `maven:3.9.9-eclipse-temurin-21` 容器中运行 Persistence reactor：PostgreSQL 16.14、MySQL 8.4 均从空库执行 8 个迁移到 V8，包含逐表逐字段注释断言的 110 个测试全部通过，失败、错误和跳过均为 0。Flyway 11.7.2 对 MySQL 8.4 输出高于已测试支持版本 8.1 的升级建议，但实际迁移及结构契约验证通过。
