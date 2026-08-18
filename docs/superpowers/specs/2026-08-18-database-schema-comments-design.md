# 数据库表与字段注释设计

## 1. 背景

当前 Flyway V1–V7 建立了 18 张业务表和 135 个有效字段，但数据库元数据中没有完整的表、字段注释。仅阅读迁移文件无法让数据库管理工具、元数据接口和后续维护者直接理解字段语义，也缺少阻止新结构遗漏注释的自动化约束。

关联文档：

- [实施计划](../plans/2026-08-18-database-schema-comments.md)
- [实现说明](../implementation/2026-08-18-database-schema-comments-implementation-design.md)
- [进度账本](../progress/2026-08-18-database-schema-comments-ledger.md)

## 2. 目标

- 为 PostgreSQL 16 与 MySQL 8.4 中全部 18 张业务表写入准确的中文原生注释。
- 为迁移到 V7 后保留的全部 135 个字段写入准确的中文原生注释。
- 不修改任何已发布迁移，不改变列、索引、外键、租户隔离或数据内容。
- 迁移测试逐表、逐字段验证注释非空，使后续新增结构遗漏注释时测试失败。
- 将“新增或修改表、字段必须同步维护中文数据库注释”的规则写入根目录 `AGENTS.md`。

## 3. 范围

本次新增 V8 方言迁移、统一 Flyway 方言选择配置、迁移测试、数据库部署说明、发布说明和任务过程文档。Repository 查询、领域模型、REST API、权限、审计和前端行为不在变更范围内。

## 4. 非目标

- 不修改 V1–V7 的历史内容或校验和。
- 不引入 JPA、MyBatis 或新的数据库框架。
- 不为 `flyway_schema_history` 等 Flyway 内部表设置业务注释。
- 不改变历史兼容字段的存储策略；例如 `model_configs.encrypted_api_key` 仍不得保存明文 API Key。

## 5. 方案

公共迁移继续放在 `db/migration` 根目录。由于 PostgreSQL 使用 `COMMENT ON`，MySQL 只能通过 `ALTER TABLE ... COMMENT` 与完整的 `MODIFY COLUMN ... COMMENT` 写入注释，V8 分别放在 `db/migration/postgresql` 和 `db/migration/mysql`。

`CmAgentFlyway` 先通过 JDBC 元数据识别数据库类型，再配置两个位置：只匹配根目录 SQL 文件的 `classpath:db/migration/*.sql`，以及当前方言目录。根目录使用文件级通配符是为了阻止 Flyway 递归扫描并同时发现两个同版本 V8。

迁移测试通过 JDBC `DatabaseMetaData` 读取 `REMARKS`，对固定业务表集合逐表断言表注释非空，并枚举每张表的全部字段断言字段注释非空。原有索引、外键和空值约束断言继续保留，用于发现 MySQL `MODIFY COLUMN` 意外改变结构。

## 6. 约束与风险

- MySQL 修改字段注释时必须完整重述类型与空值约束，否则可能重置未声明的字段属性。
- V8 的 PostgreSQL/MySQL 文件版本号和业务语义必须保持一致，只能由统一配置选择一个方言。
- Flyway/JDBC/Testcontainers 验证必须在 Rocky Linux 的 `maven:3.9.9-eclipse-temurin-21` 容器中执行。
- 迁移账号需要修改表和字段注释的 DDL 权限；生产发布仍须先备份并核对 Flyway 历史。

## 7. 验收标准

- 两个 V8 脚本各覆盖 18 张表、135 个字段。
- PostgreSQL 16 与 MySQL 8.4 从空库执行 V1–V8 成功。
- 迁移后每张业务表及其每个字段的 JDBC 注释均非空。
- 原有索引、外键、唯一约束和空值约束断言通过。
- JDK 21 下相关模块编译通过，`git diff --check` 无错误。
- `AGENTS.md`、生产文档和四份任务文档同步更新。
