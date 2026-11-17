# cm-agent-core 模块 README 进度账本

对应设计：[specs/2026-09-01-core-module-readme-design.md](../specs/2026-09-01-core-module-readme-design.md)
对应计划：[plans/2026-09-01-core-module-readme.md](../plans/2026-09-01-core-module-readme.md)
实现说明：[implementation/2026-09-01-core-module-readme-implementation-design.md](../implementation/2026-09-01-core-module-readme-implementation-design.md)

## 任务状态

| 任务 | 状态 | 说明 |
| --- | --- | --- |
| 任务 1：核对源码事实 | 已完成 | 全量列出 66 个主源码文件与 16 个测试文件；精读 8 个关键类型确认签名与校验规则；确认 POM 仅依赖 `cm-agent-api` 与 test 作用域测试依赖 |
| 任务 2：撰写 `cm-agent-core/README.md` | 已完成 | 10 个章节全部中文；修正 3 处草稿笔误；类型数量按逐包统计修正为 66（audit 3 / domain 33 / repository 10 / runtime 9 / security 5 / tool 6） |
| 任务 3：生成 superpowers 配套文档 | 已完成 | 设计、计划、实现说明、进度账本四份齐备，同日期 `2026-09-01`、同 topic `core-module-readme`，相互引用 |
| 任务 4：验证 | 已完成 | 见下方“实际验证结果” |

## 实际验证结果

- README 事实一致性：人工核对通过——包数量与文件清单一致、SPI 签名与源码一致、枚举取值与源码一致、测试类清单与 `src/test` 目录一致。
- 敏感信息检查：README 不含任何凭据、secret、数据库密码或生产 JDBC URL；测试命令仅为 AGENTS.md 既定的模块测试命令。
- 构建与测试：未运行 `mvn` 命令。理由：本任务为纯文档新增（一个 Markdown 文件），不触碰任何 Java 源码、资源或构建文件，模块编译与测试结果不受影响。

## 遗留问题

- 无。后续若 `cm-agent-core` 新增类型或 SPI，需按 AGENTS.md 注释同步规则一并更新 README 中的数量与清单。

## 提交信息

未提交。


## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：core README 源码核对和文档交付完成，原账本无遗留。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
