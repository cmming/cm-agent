# 阶段 3：真实 AgentScope Runtime 进度台账

| 计划项 | 状态 | 证据 | 问题与处理 |
|---|---|---|---|
| core 运行请求扩展 | 已完成 | JDK 21 下 `mvn -q -pl cm-agent-core -am test` 通过 | 保留旧构造器兼容测试调用 |
| AgentScope RC3 adapter | 已完成 | adapter 测试命令 exit 0 | AgentScope shutdown hook 输出警告但测试进程最终成功；已使用 try-with-resources 关闭 ReActAgent |
| Starter 条件装配 | 已完成 | Starter 测试命令 exit 0 | Starter 增加直接 agentscope-core 依赖，避免 optional 传递依赖导致运行时缺类 |
| tenant 与工具边界 | 已完成首版 | adapter tenant/enabled 校验测试 | 当前只传递授权工具元数据，未伪造工具执行结果 |
| server/JDBC 回归 | 已完成 | Rocky 远程 `cm-agent-server` 汇总 116 tests、0 failures、0 errors、0 skipped；持久化测试通过 | Rocky 使用 JDK 21、Docker 23.0.6；PostgreSQL 16.14/MySQL 8.4 容器均成功 |
| 文档与配置 | 已完成 | 配置、路线图和本台账已更新 | API Key 仅使用外部配置占位符 |
