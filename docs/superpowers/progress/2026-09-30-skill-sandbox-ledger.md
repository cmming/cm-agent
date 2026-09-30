# 技能沙箱进度账本

日期沿用任务启动日 2026-09-30；验证跨日到 2026-10-01。初始 HEAD 为 `a72f9228eb07242f303d5e033adaf8b1a56e6241`，任务期间 HEAD 前移到 `5c60ad53372f231f004bf7057b96c92ad07cc441`，已重新核对并在远端采用相同 HEAD。用户于 2026-10-01 授权本地提交，本任务代码、测试及六份工作包纳入同一次提交；提交编号以本文件对应的 Git 历史为准，未推送、未部署。

用户已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、`.codex/` 和 `.workbuddy/` 改动保持不动。远程采用 HEAD 配置加本任务明确文件覆盖，不复制这些未提交配置或凭据。CodeGraph 索引无法定位当前 Skill 类，已先调用并回退源码核对。

| 编号 | 状态 | 目标与文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| T0 | 完成 | 核实 Skill 链路、工作树与环境，生成六份文档 | 无 | 六份产物齐全，已有配置及工作包保留 |
| T1 | 完成 | Core 网关默认执行扩展；SkillSandboxProperties、DockerSkillSandbox | T0 | 默认关闭；固定命令；有界 stdin、输出、并发、超时及唯一容器清理 |
| T2 | 完成 | GovernedSkillAccessService、AgentScopeSkillExecutionBridge 与准备状态 | T1 | 固定快照、租户/主体、运行状态、撤销、持久化预算、重复拒绝、严格审计、发布门禁测试 |
| T3 | 完成 | 能力接口、部署 profile、V15 状态注释、中文状态标签与生产说明 | T2 | 策略与导入白名单一致；不改变表结构；准备不显示为成功 |
| T4 | 完成 | Java 21 回归、Rocky 真实容器与双数据库验证、工作包核查 | T1,T2,T3 | 首轮全量通过；最终专项 215 项、JS 85 项通过，哈希/文档/零残留已核对 |

## 已执行证据

- CodeGraph 两次 explore 未定位当前 Skill 类，回退源码核查；默认 java/mvn 为 JDK 17，改用 F:/java21 后确认 Java 21.0.11、Maven 3.9.4。
- 本地 `mvn -q -pl cm-agent-server -am -DskipTests compile` 通过。
- 本地 Java 21 相关领域、Skill、Adapter、治理与 API 测试通过；真实 ReActAgent 与本地 OpenAI 协议 stub 的脚本调用和致命失败停止合同测试通过，不使用真实模型凭据。
- `node --test cm-agent-console/src/test/js/console-core.test.cjs cm-agent-console/src/test/js/skills.test.cjs`：85 项通过，失败 0，跳过 0。
- Rocky Docker 23.0.6、Maven 3.9.9 / JDK 21.0.7 已确认；预拉取 python:3.12-alpine，digest 为 sha256:4c47124a8391cb7a9f571164147d154777cf012a4ece5f86097130d7a4478111。
- 远程验证目录 `/root/cm-agent-skill-sandbox-a72f922-20261001` 的 HEAD 为 5c60ad53372f231f004bf7057b96c92ad07cc441。初始覆盖 24 文件 SHA256 全部匹配，随后最终覆盖 35 文件单独生成 SHA256 清单。
- 在 maven:3.9.9-eclipse-temurin-21 内 `mvn -q test` 返回 0。日志为远端 `validation.log`；该轮在独立准备状态与 V15 最终调整之前，不能冒充最终调整后的全量结果。
- 该轮 DockerSkillSandboxIntegrationTest 7 项通过（失败/错误/跳过均为 0）：成功资源+stdin、隔离、超时与子进程、输出限额、失败脱敏、成功脱敏、镜像缺失拒绝。完成后未发现随机命名沙箱残留。

## 最终专项复验

Rocky Maven 21 内执行：

```text
mvn -q -pl cm-agent-server -am "-Dtest=Skill*Test,AgentSkill*Test,GovernedSkillAccessServiceTest,DockerSkill*Test,AgentScope*Test,MigrationTest,JdbcSkillRepositoriesTest,ApiExceptionHandlerTest,ConsoleResourceTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

设置 CM_AGENT_TEST_SANDBOX=true，仅验证容器挂载 Docker CLI/socket；实际脚本容器不挂载它们。最终复验增加准备状态、预算、发布门禁、能力/导入白名单、V15、原生注释和实际 cgroup 配额断言。第一次 V15 复验发现 MigrationTest 的 V12 升级计数仍为 2，实际为 V13/V14/V15 共 3；已同步修正，不改迁移行为。第二次最终专项运行返回 0，日志为远端 `validation-final.log`。

## 修正记录与限制

首轮旧错误文案兼容断言已保留；测试辅助方法由 insert 改为仓库真实 insertAll、JWT 使用 createToken。首次跨平台 SHA256 清单含 CRLF，已改 LF 并核验。任务 HEAD 前移已同步，不重置用户提交。

本任务路径 diff --check 通过；全仓库检查发现用户既有 application.yml 末尾空行，保持不动。未执行真实公网模型、生产部署、强化 runtime/虚拟机隔离测试；采用本地协议合同与实际 Docker 隔离验证，不将其等同于正式发布或生产接受。旧版本回退和主机故障后的孤立容器处理见 README。提交信息：用户已授权本地提交，提交说明为「新增技能 Python 容器沙箱与受治理执行」；编号以本文件对应的 Git 历史为准。


## 最终验收结果（2026-10-01）

最终专项命令返回 0：35 份待验证文件 SHA256 均与本地一致，远程 HEAD 与本地同为 5c60ad53372f231f004bf7057b96c92ad07cc441。31 份匹配 Surefire 报告共 215 项，失败 0、错误 0、跳过 0：Adapter 79、Console 14、Core 10、Persistence 5、Server 107。Persistence 中 MigrationTest 3 项与 JdbcSkillRepositoriesTest 2 项均通过，覆盖 PostgreSQL 16 / MySQL 8.4 的 V1–V15、逐表逐字段非空注释、V15 状态注释、准备状态往返与 tenant 隔离。DockerSkillSandboxIntegrationTest 7 项通过，包含实际 cgroup 资源配额断言。

最后本地治理/错误 API 复验通过；Node 两个脚本共 85 项通过，失败/跳过 0。六份工作包齐全、内部相对链接均有效；本任务路径 diff --check 通过。Rocky 上 cm-agent-skill-* 容器查询结果为空，无本次执行容器残留。全仓库 diff --check 的 application.yml 既有末尾空行不属于本任务，未修改。

T0–T4 全部完成，必需验证已通过。首轮全量 mvn -q test 返回 0；最终调整后的复验范围为上述 215 项，不声称再次运行最终版本全量测试。真实公网模型、生产部署、强化 runtime 验证未执行：前者使用真实 AgentScope 与本地协议 stub 替代，后两者不属于本次交付范围。仍需部署者按 README 显式启用并选定可信镜像/runtime。用户于 2026-10-01 授权本地提交，本任务代码、测试及六份工作包纳入同一次提交；提交编号以本文件对应的 Git 历史为准，未推送、未部署。


关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md)
