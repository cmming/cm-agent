# Agent 模型名称同步计划

日期：2026-10-15；修订：R0；工作模式：mode=default。

Git 基线：`eac79c26f5d8a856b53c4cdeee0c807ecd191509`。保留任务开始前已存在的控制台、配置与文档修改；初始交付时未提交；本轮提交记录见账本。

1. T0：先检查工作树、模块 POM、README 与配置，CodeGraph 定位命令服务、Controller 和运行解析，浏览器只读检查同一 Agent 的名称差异。
2. T1（依赖 T0）：在 `cm-agent-core/.../AgentDefinition.java` 增加不可变副本方法；新增 `cm-agent-server/.../service/AgentDefinitionQueryService.java` 组合当前租户模型信息；Controller 使用查询服务；运行解析阶段使用当前名称。补充对应中文 JavaDoc 与安全/兼容性注释。
3. T2（依赖 T1）：通过真实模型更新 API 验证已有 Agent 查询，覆盖只读主体与跨租户读取；通过捕获运行请求验证新名称；对查询批量读取、缺失/停用绑定与适配器显式名称覆盖回归。命令和实际结果写入账本。此计划不是通过证据。
4. T3（依赖 T2）：更新 README、发布说明与六份工作包，检查只修改当前任务文件中的目标位置。记录未提交、旧进程、未执行的浏览器在线验收及真实模型/数据库测试边界。

## 关联文档

- [清单](../checklists/2026-10-15-agent-model-name-sync-checklist.md)
- [提示词](../prompts/2026-10-15-agent-model-name-sync-prompt.md)
- [设计](../specs/2026-10-15-agent-model-name-sync-design.md)
- [实现说明](../implementation/2026-10-15-agent-model-name-sync-implementation-design.md)
- [账本](../progress/2026-10-15-agent-model-name-sync-ledger.md)
