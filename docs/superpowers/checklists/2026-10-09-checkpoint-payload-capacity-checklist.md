# 问答检查点容量修复清单

日期：2026-10-09；主题：checkpoint-payload-capacity；修订：R0；模式：mode=default。

基线：`3f9c026e3afbe052d9ba51ef911b828f8d9deae7`。实现验收阶段未提交；本轮提交信息见账本。

## 需求与证据

用户反馈生成 TXT 时问答报错。日志编号 `88f01e4e-0d36-4e1d-80b7-c54226e90b37`，边界 CONVERSATION_STREAM，错误 PERSISTENCE_UNAVAILABLE，SQLState 22001 / MySQL 1406；堆栈指向 JdbcRuntimeCheckpointRepository.save 的 INSERT。脱敏日志未给出列名与载荷大小。

源码事实：V11 MySQL encrypted_payload 为 TEXT；RepositoryAgentStateStore 序列化状态后使用随机 IV AES/GCM 和 Base64 写入，AgentScopeReActExecutor 按 runId 设置状态槽。既有 SkillArtifactCheckpointIntegrationTest 合成复现该列的长度错误。新测试必须区分原真实请求与受控复现。

范围仅新增迁移、容量与交付测试、部署说明；不关闭状态保存、不截断、不更改文件授权、不操作部署数据库。旧文件产物分析见 [原账本](../progress/2026-10-08-skill-artifacts-ledger.md)。

| 编号 | 任务 | 验收 |
| --- | --- | --- |
| T0 | 定位日志与检查点保存链 | 分开记录用户日志、源码事实与合成复现，保护既有脏文件 |
| T1 | 新增 V20 方言迁移 | MySQL LONGTEXT、PostgreSQL TEXT 与中文注释，V19 数据保留，历史迁移不变 |
| T2 | 验证大状态与 TXT 交付 | 双库 INSERT/UPDATE、AES/GCM 往返、列表与 Unicode、租户隔离、V19 失败补偿、V20 TXT 下载 |
| T3 | 同步文档与交付 | 六份路径及编号一致，配置与发布说明准确，报告实际命令和未验收项 |

## 依赖和当前状态

T0～T3 已完成本轮仓库修复及受控验收。T1 依赖 T0，T2 依赖 T1，T3 依赖 T2 实际结果。Rocky 定向13项全部通过；真实模型原请求未复验、运行环境未部署，不能将受控验收当作原请求已成功。

CodeGraph 三次查询未定位新检查点类型而返回其他符号，已直接复核当前源码；不将图索引视为准确现状。现存 index.html、application 配置、.codex 和 .workbuddy 改动不纳入本次。

## 关联文档

[提示词](../prompts/2026-10-09-checkpoint-payload-capacity-prompt.md) · [设计](../specs/2026-10-09-checkpoint-payload-capacity-design.md) · [计划](../plans/2026-10-09-checkpoint-payload-capacity.md) · [实现说明](../implementation/2026-10-09-checkpoint-payload-capacity-implementation-design.md) · [账本](../progress/2026-10-09-checkpoint-payload-capacity-ledger.md)
