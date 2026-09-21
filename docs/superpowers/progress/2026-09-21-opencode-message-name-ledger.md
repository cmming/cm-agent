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
