# 数据库表与字段注释进度账本

## 1. 关联文档

- [需求设计](../specs/2026-08-18-database-schema-comments-design.md)
- [实施计划](../plans/2026-08-18-database-schema-comments.md)
- [实现说明](../implementation/2026-08-18-database-schema-comments-implementation-design.md)

## 2. 任务状态

| 任务 | 状态 | 实际结果 |
| --- | --- | --- |
| Schema 与工作区检查 | 已完成 | 确认 V7 后为 18 张业务表、135 个字段；任务开始前已有 3 个配置文件改动和 `temp_reference.java`，均未纳入本任务。 |
| Flyway 方言选择 | 已完成 | 新增 `CmAgentFlyway`，生产与 JDBC 测试统一加载公共 SQL 和当前数据库方言目录。 |
| PostgreSQL/MySQL V8 | 已完成 | 静态计数均为 18 个表注释、135 个字段注释。 |
| 迁移测试 | 已完成 | 新增逐表、逐字段 JDBC `REMARKS` 非空断言，保留既有结构约束断言。 |
| 规则与文档 | 已完成 | 已更新 `AGENTS.md`、配置、部署、架构、发布说明和四份任务文档。 |
| Rocky 数据库验证 | 已完成 | 临时提交 `ff68c612bcdc349b1446591eefc316fdb07c3f46` 在远程独立 worktree 状态干净；Persistence reactor 110 个测试全部通过。 |

## 3. 已执行验证

- 本机默认 Java 为 17，不满足项目要求；显式切换到 `F:\java\temurin21\jdk-21.0.11+10` 后，Java 为 21.0.11、Maven 为 3.9.4。
- `mvn -q -pl cm-agent-persistence -am "-DskipTests" package`：通过。
- `mvn -q -pl cm-agent-server -am "-DskipTests" package`：通过，包含测试源码编译。
- PostgreSQL/MySQL 脚本静态计数：两者均为 18 张表、135 个字段。
- Rocky Docker 23.0.6 可用；`maven:3.9.9-eclipse-temurin-21` 中 Maven 为 3.9.9、Java 为 21.0.7。
- 远程提交 `ff68c612bcdc349b1446591eefc316fdb07c3f46` 与本地临时验证提交一致，远程 worktree 状态为空。
- `mvn -q -pl cm-agent-persistence -am test`：110 个测试通过，失败 0、错误 0、跳过 0；PostgreSQL 16.14 与 MySQL 8.4 均执行 V1–V8，所有表/字段注释和原有结构约束断言通过。
- 最终任务文件临时提交 `a16e1ed9035e04392dd4922b75fe54d55e998339` 在 Rocky 环境执行 `MigrationTest`：2 个测试通过，失败 0、错误 0、跳过 0；远程文件哈希与本地任务文件一致。
- Flyway 11.7.2 对 MySQL 8.4 输出“高于当前已测试支持版本 8.1”的升级建议；本次实际迁移和测试均成功，未升级依赖。

## 4. 遗留问题与提交信息

- 无功能遗留问题；生产升级前仍须备份、核对 `flyway_schema_history` 和迁移账号 DDL 权限。
- 实现与设计文档已提交为 `67784e0`（`补齐数据库表与字段注释`）；本进度账本随同一 PR 单独提交。用户已有的 3 个配置文件改动和 `temp_reference.java` 未纳入提交。

## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：双方言数据库注释与 Rocky 迁移验证完成，升级前备份属于使用条件。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
