# 动态 HTTP 工具与 MCP 发布进度账本

| 任务 | 状态 | RED | GREEN/回归 | 审查修复 | commit |
| --- | --- | --- | --- | --- | --- |
| Task 1：核心领域与依赖契约 | 已完成 | 新类型和结果工厂缺失，`cm-agent-core` 测试编译失败 | 指定测试 11 项通过；完整 core 回归通过；server 跳过测试打包通过 | 双阶段审查通过；`mcp:0.17.0` 为 0 个 class 的元数据 JAR，未与 MCP 2.0.0 重复 | `70736c5` |
| Task 2：V4 与 JDBC/memory Repository | 已完成 | Rocky 容器在 `c5e030d` 上因两个 JDBC Repository 缺失而 testCompile 失败；`5221fdc` 覆盖 Secret 引用与并发首次保存 | Rocky 容器最终通过核心 3 项、PostgreSQL 4+3 项、MySQL 6 项、迁移 2 项及 server 配置 10 项；PG/MySQL 双实例并发保存均通过 | 首轮补齐 memory 删除；审查后增加 `secret/` 安全引用约束、父工具行事务锁和双数据库并发测试，复审 Critical/Important 均关闭 | `c5e030d`..`ba9a1c9` |
| Task 3：HTTP 工具创建链路 | 已完成 | 新增 5 项 Controller 测试中 4 项按预期失败：HTTP 缺配置、配置互斥、列表扩展、同租户重名均尚未实现；自审补充 endpoint 断言先失败 | 指定 Management 1 项、Controller 5 项及 RunController 25 项均通过 | 自审确认 tenant 来源、Secret 引用与事务边界；未提前实现 Task 4 深层校验 | `2ba14d0` |
| Task 4：输入校验与参数映射 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 5：安全 HTTP 执行器 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 6：统一治理执行入口 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 7：调试与 MCP 发布服务 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 8：MCP Streamable HTTP 端点 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 9：控制台与端到端验证 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 10：发布收尾与兼容性复核 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
