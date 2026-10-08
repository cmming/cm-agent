# JDBC 诊断日志修正账本

修订：R0；日期：2026-10-08；工作模式：mode=default。

Git：`codex/skills-version-console`，验证基线 `5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443`；当时未提交，当前提交见末尾记录。已有无关改动未覆盖。

| 编号 | 状态 | 目标与验收 | 依赖 |
|---|---|---|---|
| T1 | 完成 | 核实现有 SQL 全尾脱敏、异常链丢失、Git 基线和保护范围 | 无 |
| T2 | 完成 | 复制脱敏原因链，记录 SQLState/厂商编号/固定数据库原因；控制异常图规模 | T1 |
| T3 | 完成 | 单元、API 及 Rocky 双库真实错误验证；55 项通过，编号关联、原因、脱敏断言通过 | T2 |
| T4 | 完成 | 六份工作包、运维与发布说明已同步；链接及差异检查通过 | T3 |

T1/T2：已核对当前日志与调用方；已落地安全异常快照与 JDBC 固定原因字段。工作包文档已生成，不能以文档代替验证完成。

环境证据：本机默认 Java 17，未用于构建；Rocky Docker 23.0.6，验证镜像 Maven 3.9.9/JDK 21.0.7。远程隔离目录 `/root/cm-agent-diagnostic-20261008`，HEAD 与本机一致；五个验证源码 SHA256 全部一致。Rocky 时钟落后本机，未调整系统时间。

## 实际验证

在 ssh rocky 中执行如下最终命令，退出码 0；镜像 Maven 3.9.9/JDK 21.0.7，Docker 23.0.6。验证前后五个源码 SHA256 与本机全部一致，Git HEAD 一致；未提交改动以显式文件覆盖及 SHA256 标识，不冒称已经提交。

```bash
cd /root/cm-agent-diagnostic-20261008
sha256sum -c SHA256SUMS
docker run --rm --network host \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /root/cm-agent-diagnostic-20261008:/workspace \
  -v /root/.m2:/root/.m2 \
  -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  -w /workspace maven:3.9.9-eclipse-temurin-21 \
  mvn -o -q -pl cm-agent-server -am test \
  -Dtest=ErrorDiagnosticLoggerTest,DiagnosticJdbcLoggingTest,ApiExceptionHandlerTest,ApprovalExpiryScannerTest,ModelCatalogDiscoveryServiceTest,ModelConfigControllerTest,SandboxEndpointControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcm-agent.agentscope.studio.enabled=false
```

| 测试类 | 测试数 | 失败/错误/跳过 |
|---|---:|---|
| ErrorDiagnosticLoggerTest | 21 | 0/0/0 |
| DiagnosticJdbcLoggingTest | 2 | 0/0/0 |
| ApiExceptionHandlerTest | 8 | 0/0/0 |
| ApprovalExpiryScannerTest | 7 | 0/0/0 |
| ModelCatalogDiscoveryServiceTest | 4 | 0/0/0 |
| ModelConfigControllerTest | 7 | 0/0/0 |
| SandboxEndpointControllerTest | 6 | 0/0/0 |
| 合计 | 55 | 0/0/0 |

真实 PostgreSQL 16/MySQL 8.4 驱动均验证字段长度超限和唯一约束冲突，日志包含原始类型、堆栈、原因与编号，不含 SQL、参数、失败行和内部 URL。API 回归验证 503/PERSISTENCE_UNAVAILABLE、中文消息、响应/响应头/日志 errorId 一致；新字段不进入响应。模型发现、审批扫描和沙箱端点调用方回归通过。

原始测试输出仅保存于隔离验证目录 final-verification.log；只复制安全测试统计至本机 [验证摘要](C:/Users/chmi/Documents/Codex/diagnostic-verification/verification-summary.json)。远程同目录还保存 SHA256SUMS、verification-summary.json 和 Surefire 报告。本次未回传服务原始日志或凭据。

最终静态检查：git diff --check 通过；六份文档齐全、相对链接检查通过；Java 注释/JavaDoc 已覆盖数据库消息拒绝策略、异常图复制、资源/预算、框架差异及 record 全部组件。保留原控制台、V18、配置及旧工作包改动。未提交、未推送、未合并、未部署；没有重启服务、没有修改运行数据库。

## 限制与后续

无外部阻塞。未运行全仓库测试、浏览器与真实模型调用：本需求仅修改日志边界，已执行相关 API/调用方与真实 JDBC 专项回归。正常异常沿用既有文本脱敏规则；数据库未知编号不会透传原文。异常图超出预算会明确截断，不保证无限链全部保留。

运行中进程尚未加载源代码改动，需由用户按发布流程构建并重启后生效。没有新增配置开关或迁移步骤。

## 关联产物

- [清单](../checklists/2026-10-08-jdbc-diagnostic-checklist.md)
- [提示词](../prompts/2026-10-08-jdbc-diagnostic-prompt.md)
- [设计](../specs/2026-10-08-jdbc-diagnostic-design.md)
- [计划](../plans/2026-10-08-jdbc-diagnostic.md)
- [实现说明](../implementation/2026-10-08-jdbc-diagnostic-implementation-design.md)

## 2026-10-08 本地提交记录

- 用户已明确授权提交已完成修正，并选择同时纳入手工容量调整、先同步测试与配置文档。
- 实现提交：`7337e6388f2de4b344a4431581493c3ff65bfbd1`，标题“完善技能版本控制台、导入容量与数据库诊断”，分支 `codex/skills-version-console`，59 个文件。包含技能版本控制台/导航、V18 长描述修复、JDBC 诊断及容量同步；本次补记提交编号另作纯文档提交。
- 提交前按当前源码复核：Rocky Java 专项119项和MySQL原包接口3项通过（共122项执行），Node111项通过；失败/错误/跳过均0。容量默认值与硬边界、沙箱累计准备、大资源原包双库导入均已检查；历史测试记录保持，不冒充全仓库或真实脚本验收。
- 59 个显式路径的暂存范围、敏感片段、文档链接及 `git diff --cached --check` 已检查。初次暂存发现新文档 EOF 多余空行，仅修正本任务文档后检查通过。
- `application.yml`、`application-mysql.yml`、未跟踪 `application-ok.yml` 与 `.codex/`、`.workbuddy/` 保持未提交。未推送、未合并、未部署，未重启用户服务或操作其数据库。
- 原生浏览器 ZIP 上传仍保留技能控制台 R0 的扩展权限阻塞；本轮数据库原包导入测试不替代该浏览器验收。
