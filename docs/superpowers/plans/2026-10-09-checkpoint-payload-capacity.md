# 问答检查点容量修复计划

日期：2026-10-09；主题：checkpoint-payload-capacity；修订：R0；模式：mode=default。

基线：`3f9c026e3afbe052d9ba51ef911b828f8d9deae7`。实现验收阶段未提交；本轮提交信息见账本。

| 编号 | 任务 | 验收 |
| --- | --- | --- |
| T0 | 定位日志与检查点保存链 | 分开记录用户日志、源码事实与合成复现，保护既有脏文件 |
| T1 | 新增 V20 方言迁移 | MySQL LONGTEXT、PostgreSQL TEXT 与中文注释，V19 数据保留，历史迁移不变 |
| T2 | 验证大状态与 TXT 交付 | 双库 INSERT/UPDATE、AES/GCM 往返、列表与 Unicode、租户隔离、V19 失败补偿、V20 TXT 下载 |
| T3 | 同步文档与交付 | 六份路径及编号一致，配置与发布说明准确，报告实际命令和未验收项 |

## 执行顺序

1. T0：检查日志、HEAD/工作树、POM、README、配置说明；优先 CodeGraph，缺失后读源码。
2. T1：增加 persistence 的 mysql/postgresql V20__expand_runtime_checkpoint_payload.sql；不修改 V1～V19。
3. T2：MigrationTest 保留 V18/V19 分阶段验收并加 V20；新增 JdbcRuntimeCheckpointRepositoryTest 验证 V19 → V20 数据及租户边界；更新 SkillArtifactCheckpointIntegrationTest 保留 V19 失败补偿并新增 V20 两库 TXT 下载。
4. T3：同步 docs/configuration.md、docs/release-notes.md 和六份文档，检查链接、差异与状态。

## 验证方法

Docker/JDBC/Testcontainers 全部经 ssh rocky，在 maven:3.9.9-eclipse-temurin-21 中执行；核对 Docker、JDK21、Git HEAD 和显式覆盖 SHA256。临时 checkout 从 HEAD 构建，不复制用户工作树配置。数据库镜像 PostgreSQL16-alpine / MySQL8.4。

定向范围：MigrationTest、JdbcRuntimeCheckpointRepositoryTest、SkillArtifactCheckpointIntegrationTest、RepositoryAgentStateStoreTest；必要时加原审批 Repository 回归。启用 CM_AGENT_TEST_SANDBOX=true，仅用于固定 TXT 脚本容器。核对实际 Surefire 报告及无跳过，未运行的真实模型复验单独记录。

执行结果：上述范围含 JdbcToolApprovalRepositoryTest 已在 Rocky 指定镜像中执行，13项全部通过、退出0。T0～T3 完成，本计划不另授权运行环境部署；实际命令与证据见账本。

## 关联文档

[清单](../checklists/2026-10-09-checkpoint-payload-capacity-checklist.md) · [提示词](../prompts/2026-10-09-checkpoint-payload-capacity-prompt.md) · [设计](../specs/2026-10-09-checkpoint-payload-capacity-design.md) · [实现说明](../implementation/2026-10-09-checkpoint-payload-capacity-implementation-design.md) · [账本](../progress/2026-10-09-checkpoint-payload-capacity-ledger.md)
