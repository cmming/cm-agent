# 动态 HTTP 工具与 MCP 发布进度账本

| 任务 | 状态 | RED | GREEN/回归 | 审查修复 | commit |
| --- | --- | --- | --- | --- | --- |
| Task 1：核心领域与依赖契约 | 已完成 | 新类型和结果工厂缺失，`cm-agent-core` 测试编译失败 | 指定测试 11 项通过；完整 core 回归通过；server 跳过测试打包通过 | 双阶段审查通过；`mcp:0.17.0` 为 0 个 class 的元数据 JAR，未与 MCP 2.0.0 重复 | `70736c5` |
| Task 2：V4 与 JDBC/memory Repository | 已完成 | Rocky 容器在 `c5e030d` 上因两个 JDBC Repository 缺失而 testCompile 失败；`5221fdc` 覆盖 Secret 引用与并发首次保存 | Rocky 容器最终通过核心 3 项、PostgreSQL 4+3 项、MySQL 6 项、迁移 2 项及 server 配置 10 项；PG/MySQL 双实例并发保存均通过 | 首轮补齐 memory 删除；审查后增加 `secret/` 安全引用约束、父工具行事务锁和双数据库并发测试，复审 Critical/Important 均关闭 | `c5e030d`..`ba9a1c9` |
| Task 3：HTTP 工具创建链路 | 已完成 | 初始 4 项 API RED；两次审查 RED 覆盖无效配置 memory 残留、唯一索引冲突、空映射、批量查询契约缺失，以及 memory 同名并发与按名称回查响应错误 | 本地 Management 4、Controller 7、memory 配置 8、RunController 25 项通过；Rocky Linux 的 `maven:3.9.9-eclipse-temurin-21` 容器在 `4fdfce3` 上完成 PostgreSQL/MySQL 同名并发及审计失败回滚 4 项验证 | 补齐 memory 补偿、精确冲突映射、批量 tenant 查询、原子 tenant/name 索引和按 ID 响应；未提前实现 Task 4 深层校验 | `36d775f`、`37b1823`、`4fdfce3` |
| Task 4：输入校验与参数映射 | 已完成 | 八轮复审最终补齐线性 `$ref` 与深 `properties/items` 调用栈边界；既有 RED 覆盖 discriminator oneOf、Schema-bearing 子树引用、数组索引、引用链绕过、重复 DAG 与宽 Schema | Validator 30、Mapper 13、Management 5、ToolController 7，共 55 项通过；networknt 依赖树确认 2.0.0 | 调用局部上下文独立限制 256 层活动递归和 10,000 个遍历状态；缓存按 schema path 与直接引用链隔离；257 层固定拒绝、256 层正常，历史环检测与合法递归保持 | `29b01fa`、`e4587a9`、`537274f`、`37caa27`、`0dfde42`、`ac35d45`、`87ad973`、`bee1046`、本提交 |
| Task 5：安全 HTTP 执行器 | 已完成 | Secret/属性、URL 策略、执行器、profile 均先观察到缺类、缺行为或错误语义失败；复审 RED 继续覆盖跨源重定向、统一 deadline、IPv6 特殊前缀、歧义响应头、结构化脱敏、阻塞/累计 Secret Provider 及 Provider 捕获中断后错误继续循环 | Secret、URL、执行器、profile 最终指定 144 项通过；跳过测试的 reactor 打包通过 | 同源重定向、严格 profile、统一 deadline、可关闭传输、保守 IPv6、identity 编码、递归 JSON/text 脱敏和序列化后二次限长已补齐；完整 Secret 循环纳入同一 deadline，并在每次 Provider 调用前后及异常路径传播取消状态，超时后不查询剩余引用且 DNS/网络零调用。自定义 Provider 必须响应中断并配置自身 I/O timeout；忽略中断时调用方可及时返回，但后台虚拟线程可能等到 Provider 返回。JDK HttpClient 仍保留 DNS TOCTOU 风险并要求基础设施 egress | `e7e444c`、`041929d`、`64e3594`、本提交 |
| Task 6：统一治理执行入口 | 进行中 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 7：调试与 MCP 发布服务 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 8：MCP Streamable HTTP 端点 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 9：控制台与端到端验证 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
| Task 10：发布收尾与兼容性复核 | 待开始 | 未执行 | 未执行 | 未执行 | 未提交 |
