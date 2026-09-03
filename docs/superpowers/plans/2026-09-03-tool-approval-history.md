# 会话审批历史实施计划

关联：[设计](../specs/2026-09-03-tool-approval-history-design.md)、[实现说明](../implementation/2026-09-03-tool-approval-history-implementation-design.md)、[账本](../progress/2026-09-03-tool-approval-history-ledger.md)。

## 执行顺序

1. core 新增 `ToolApprovalHistoryPageRequest` 与 Repository 历史查询合同，约束分页大小和成对游标。
2. memory/JDBC Repository 实现 tenant、Agent、会话过滤及 decidedAt/id 稳定分页；覆盖同时间 UUID 排序一致性。
3. `ToolApprovalService` 输出脱敏只读视图；`ConversationController` 新增历史 GET、归属校验及游标；统一错误边界记录可信诊断上下文。
4. console-core 增加按 approvalId 去重和按 Run 分组；app.js 合并历史、隔离加载代次、展示只读折叠详情、分页/刷新/错误；chat.html 与 styles.css 添加历史区域，更新 v2 资源版本。
5. 增加领域、服务、内存、接口、安全错误、双数据库、前端纯逻辑与真实编排测试。
6. 更新生产 README/配置/架构/运维/发布说明，以及原 UI 任务中的历史缺口状态。

## 验证方式

- JDK 21 本地执行 core/server 定向 Maven 测试，Node 控制台测试、ConsoleResourceTest、跳过测试打包。
- JDBC/Flyway 测试仅在 `ssh rocky` 的 Docker 环境中，以 `maven:3.9.9-eclipse-temurin-21` 执行；校验相同 Git 基线以及未提交源代码归档校验和。
- 静态检查无真实凭据、无新增 schema、未修改用户配置；检查 Java 中文注释与 record 字段合同。
- 未执行的浏览器端到端和真实模型工具调用如实列入账本，不用单元测试代替声明。
