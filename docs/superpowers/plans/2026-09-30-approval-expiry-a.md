# A 批次实施计划

关联：[设计](../specs/2026-09-30-approval-expiry-a-design.md)、[实现](../implementation/2026-09-30-approval-expiry-a-implementation-design.md)、[账本](../progress/2026-09-30-approval-expiry-a-ledger.md)。

1. T0 核对基线、读取仓库约束及模块 POM；定位审批创建、决定、恢复及 TEST 链路。
2. T1 core 定义分页扫描及安全 CAS；persistence/memory 实现，新增 V14 扫描索引。
3. T2 ToolApprovalService 统一过期，RunRepository 增加仅等待态收口。
4. T3 服务端调度配置和诊断；测试开关、非法值、失败隔离与重试。
5. T4 核对聊天/运行/TEST 权威恢复，局部提示和操作状态修正。
6. T5 Java 21 非容器回归及 Node 测试；保留授权和发布门禁。
7. T6 主智能体独占 Rocky；隔离提交同步后运行双库故障和全量测试。
8. T7 隔离可控 Runtime fixture，桌面/390×844 浏览器验收。
9. T8 更新生产文档，审查 diff、注释、敏感内容，记录每项证据与交付状态。
