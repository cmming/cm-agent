# 会话停止本轮实施：计划

日期：2026-10-10；主题：`chat-stop-delivery`；修订：R0；模式：`mode=default`。已执行结果看账本，本文件保留实施顺序，不作为通过证明。

| 编号 | 依赖 | 实施方法、文件及完成标准 |
|---|---|---|
| T0 | 无 | 读取原 chat-stop 六份、相关 POM/配置、AGENTS、Git 状态；保护用户配置和 SkillArtifactProtocol 修改 |
| T1 | T0 | core 定义 CANCELLING/CANCELLED、停止许可、兼容构造器、仓储 CAS 和旧 Runtime 能力默认；测试活跃与终态不变量 |
| T2 | T1 | adapter 绑定信号、takeUntilOther、实际准入/退出许可、停止清理检查点、严格异常二次检查；本地 HTTP、忽略中断和迟到失败测试 |
| T3 | T1、T2 | 修改 ConversationService/ToolApprovalService/RunPersistenceService 和 memory/JDBC；短事务竞争及部分正文保存；Rocky 双库覆盖回滚、取消和恢复 CAS |
| T4 | T3 | ConversationController 提供取消及两个查询；ApiExceptionHandler 提供中文安全原因和同号日志；MockMvc 认证/租户/原主体/关联/不可达/重复请求测试 |
| T5 | T4 | v2 chat 图标入口、共享 app/core 状态恢复、资源版本及固定尺寸；78 项 JS 和真实服务 fixture 的桌面/手机交互 |
| T6 | T1～T5 | 显式授权的外部模型两轮隔离测试；最终回归与句柄身份释放测试；同步生产说明和六份交付文档、引用/差异/敏感信息检查 |

## 验证安排

本机 Maven 必须显式选择 `F:/java21`，测试 profile 显式启用。普通测试不联网，外部模型仅 `CM_AGENT_EXTERNAL_STOP_TEST=true` 时运行；凭据不得写入命令、配置、日志或断言。

所有 JDBC/Testcontainers 在 `ssh rocky` 的 `maven:3.9.9-eclipse-temurin-21` 容器执行，镜像 PostgreSQL 16-alpine / MySQL 8.4。同 HEAD 加校验源覆盖包，绝不传用户凭据配置或使用本机 Docker。

浏览器脚本先拒绝占用端口，生成内存随机登录凭据，仅启动 127.0.0.1:18094 test fixture；最终关闭自己的进程。原 8080 服务不更新、不停止。

阻塞时记录确切原因及未验收项，不把受控 fixture 或历史测试作为真实模型/部署通过。不自动提交、推送或部署。


## 关联产物

- [清单](../checklists/2026-10-10-chat-stop-delivery-checklist.md)
- [提示词](../prompts/2026-10-10-chat-stop-delivery-prompt.md)
- [设计](../specs/2026-10-10-chat-stop-delivery-design.md)
- [实现说明](../implementation/2026-10-10-chat-stop-delivery-implementation-design.md)
- [账本](../progress/2026-10-10-chat-stop-delivery-ledger.md)
