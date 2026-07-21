# 动态 HTTP 工具与 MCP 发布进度账本

| 任务 | 状态 | RED | GREEN/回归 | 审查修复 | commit |
| --- | --- | --- | --- | --- | --- |
| Task 1：核心领域与依赖契约 | 已完成 | 新类型和结果工厂缺失，`cm-agent-core` 测试编译失败 | 指定测试 11 项通过；完整 core 回归通过；server 跳过测试打包通过 | 双阶段审查通过；`mcp:0.17.0` 为 0 个 class 的元数据 JAR，未与 MCP 2.0.0 重复 | `70736c5` |
| Task 2：V4 与 JDBC/memory Repository | 已完成 | Rocky 容器在 `c5e030d` 上因两个 JDBC Repository 缺失而 testCompile 失败 | Rocky 容器在 `fbd1e8b` 上通过 Migration 2 项、新 JDBC 4 项及 MySQL 回归 4 项；`274e9e1` 上 server 配置 10 项通过 | 发现 memory MCP 删除方法遗漏；先补失败断言并以 `274e9e1` 修复；复核所有 JDBC 查询、更新和删除均含 tenant 条件 | `c5e030d`、`fbd1e8b`、`6008891`、`274e9e1` |
| Task 3：HTTP 工具创建链路 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 4：输入校验与参数映射 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 5：安全 HTTP 执行器 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 6：统一治理执行入口 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 7：调试与 MCP 发布服务 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 8：MCP Streamable HTTP 端点 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 9：控制台与端到端验证 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 10：发布收尾与兼容性复核 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
