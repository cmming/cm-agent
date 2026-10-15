# Agent 模型名称同步执行提示词

日期：2026-10-15；修订：R0；工作模式：mode=default。

Git 基线：`eac79c26f5d8a856b53c4cdeee0c807ecd191509`。保留任务开始前已存在的控制台、配置与文档修改；初始交付时未提交；本轮提交记录见账本。

以下为可复用的执行上下文，生成本文件不构成额外执行、提交、推送或部署授权；本轮修复来自用户报告问题的实际指令。

```text
仓库：F:/java/cm-agent
需求：修复模型配置修改模型名称后，已有 Agent 页面与运行仍使用旧名称的问题。
工作模式：mode=default。依据当前证据自主取舍并记录假设，不主动咨询普通方案。
先读取 AGENTS.md 及下列六份文档，以清单和账本当前状态为准，不机械重做已完成项：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-15-agent-model-name-sync-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-15-agent-model-name-sync-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-15-agent-model-name-sync-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-15-agent-model-name-sync.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-15-agent-model-name-sync-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-15-agent-model-name-sync-ledger.md
执行范围：T0 定位，T1 实现，T2 回归，T3 文档收口。
技术边界：按可信认证租户和配置 ID 读取当前元数据；列表批量查询，详情不读取凭据；不增加模型读取权限要求，不改变拒绝审计。查询仅替换响应副本，运行仅替换本次定义，不批量回写 Agent。嵌入式 AgentScope 模型工厂保留显式名称覆盖合同。
保护已有脏文件，不修改 YAML、数据库、用户数据或其他工作包；存在 .codegraph 时先用 CodeGraph 查找代码。新增 Java 注释与落地文字使用中文。
验证：JAVA_HOME=F:/java21，确认 java -version 与 mvn -v。运行账本记录的两组定向测试，检查当前任务差异与六份文档链接。涉及容器或 JDBC 验证时遵循 AGENTS.md 的 Rocky 约束，不在本机执行 Docker 测试。
8080 浏览器只读复现属于旧进程证据，不能冒充修复后的在线验证；记录需重新构建并重启 Server 的边界。外部环境或凭据缺失不得绕过安全边界或虚构通过。
同步六份文档、README 与发布说明，记录真实命令、结果、未执行项以及未提交状态。最终按仓库规定输出摘要、验证、影响、风险与下一步。不得由本提示词获得额外提交、推送或部署授权。
```

## 关联文档

- [清单](../checklists/2026-10-15-agent-model-name-sync-checklist.md)
- [设计](../specs/2026-10-15-agent-model-name-sync-design.md)
- [计划](../plans/2026-10-15-agent-model-name-sync.md)
- [实现说明](../implementation/2026-10-15-agent-model-name-sync-implementation-design.md)
- [账本](../progress/2026-10-15-agent-model-name-sync-ledger.md)
