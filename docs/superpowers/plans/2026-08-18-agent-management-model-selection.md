# Agent 管理编辑、删除与模型选择实施计划

关联设计：[设计说明](../specs/2026-08-18-agent-management-model-selection-design.md)。

1. 扩展 Agent 与工具授权仓储契约，实现 JDBC 与内存的 Agent 更新、历史检查、授权清理和删除。
2. 新增 Agent 写命令服务，在事务内校验已启用模型配置、保存受控模型名称并写入严格审计。
3. 扩展 Agent Controller 的 `PUT`、`DELETE` 与 `agent:delete` 权限；保留旧客户端模型名称的唯一匹配兼容。
4. 将 v2 Agent 表单改为模型配置下拉框，并补充编辑、删除、状态提示和资源测试。
5. 增加 Web 回归测试，核验模型选择、编辑、删除、历史冲突和审计；执行 JDK 21 构建与相应测试。
