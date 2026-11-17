# OpenCode Go 消息名称兼容进度账本

| 任务 | 状态 | 证据 |
| --- | --- | --- |
| 根因核对 | 完成 | 实际 2.0.2 字节码显示默认转换器写入消息 name；用户日志明确拒绝该字段 |
| 格式化及工厂装配 | 完成 | 专用格式化器和精确主机/路径匹配 |
| 定向报文回归 | 通过 | OpenCodeGoRequestTest 8 项、AgentScopeModelFactoryTest 6 项，全部通过 |
| 完整适配器及上游测试 | 通过 | Core 84 项、adapter 81 项，共 165 项，零失败、零错误、零跳过 |
| 控制台检查 | 完成 | 浏览器登录成功，聊天页可见对应 OpenCode Agent 与历史会话 |
| 真实 Provider 修复后验收 | 待执行 | 当前服务进程启动于修改之前，需重启加载新类后发送消息验证 |
| 文档 | 完成 | README、发布说明及本任务四份文档 |
| Git 提交 | 未提交 | 保留用户已有配置及未跟踪目录 |

## 实际命令

- `java -version` / `mvn -v`：Temurin 21.0.11、Maven 3.9.4。
- `mvn -pl cm-agent-agentscope-adapter -am -Dtest=OpenCodeGoRequestTest,AgentScopeModelFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test`：14 项通过。
- `mvn -pl cm-agent-agentscope-adapter -am test`：165 项通过，BUILD SUCCESS。
- `git diff --check -- cm-agent-agentscope-adapter docs/release-notes.md docs/superpowers`：通过。

全工作区差异检查发现用户原有 `application.yml` 文件末尾空行提示，不属于本次改动，保持原样。

不涉及 JDBC、迁移或容器，本次不执行数据库集成测试。凭据仅用于控制台登录，不写入代码或文档。

关联：[设计](../specs/2026-09-21-opencode-message-name-design.md)、
[计划](../plans/2026-09-21-opencode-message-name.md)、
[实现](../implementation/2026-09-21-opencode-message-name-implementation-design.md)。

## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：消息名称兼容和会话路由修复及目标验证已记录完成。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
