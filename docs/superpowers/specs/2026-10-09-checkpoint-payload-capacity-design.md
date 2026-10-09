# 问答检查点容量修复设计

日期：2026-10-09；主题：checkpoint-payload-capacity；修订：R0；模式：mode=default。

基线：`3f9c026e3afbe052d9ba51ef911b828f8d9deae7`。实现验收阶段未提交；本轮提交信息见账本。

## 背景与目标

文件执行完成后，AgentScope 保存运行状态也可能失败。日志证明 MySQL 字段长度超限；对应源码及已有合成测试指向 encrypted_payload 的 TEXT 容量。目标是在不丢失状态、不放松交付门禁的前提下消除这一容量瓶颈。

## 方案与约束

新增 V20：MySQL 将 encrypted_payload 扩为 LONGTEXT，保留 NOT NULL 和中文原生注释；PostgreSQL 已有 TEXT 不改类型，只同步语义。LONGTEXT 避免 MEDIUMTEXT 引入另一较小上限；实际存储仍按载荷大小，并受通信包、内存和应用预算约束。

[MySQL 8.4 官方存储说明](https://dev.mysql.com/doc/refman/8.4/en/storage-requirements.html)确认 TEXT 字节长度小于 2^16；Base64 会使状态密文变大，因此不能依据生成文件大小推断检查点大小。不引入压缩、密钥变更、截断、保存降级或历史迁移修改。

调用链保持：会话发送 → Run → AgentScope → 状态序列化/加密 → JDBC 检查点；只有 Run 成功并完成既有持久化/审计才交付文件。

## 自主决定与未验证项

A1：依据源码和已有复现自主采用扩容，用户原请求具体载荷及唯一失败列仍未读取，不打印模型内容或密文。A2：本任务修复共用状态存储，使用固定 Python TXT 与合成状态验证，不冒充真实模型验收。A3：外部部署不在本轮范围。

本轮验收：Rocky 的13项定向测试全部通过，V19 原失败可复现，V20 两库旧数据保留、大状态完整往返及 TXT 交付通过；没有新增运行逻辑或偏离原方案。真实模型原请求、部署和通信包极限均未验收。

| 编号 | 任务 | 验收 |
| --- | --- | --- |
| T0 | 定位日志与检查点保存链 | 分开记录用户日志、源码事实与合成复现，保护既有脏文件 |
| T1 | 新增 V20 方言迁移 | MySQL LONGTEXT、PostgreSQL TEXT 与中文注释，V19 数据保留，历史迁移不变 |
| T2 | 验证大状态与 TXT 交付 | 双库 INSERT/UPDATE、AES/GCM 往返、列表与 Unicode、租户隔离、V19 失败补偿、V20 TXT 下载 |
| T3 | 同步文档与交付 | 六份路径及编号一致，配置与发布说明准确，报告实际命令和未验收项 |


## 关联文档

[清单](../checklists/2026-10-09-checkpoint-payload-capacity-checklist.md) · [提示词](../prompts/2026-10-09-checkpoint-payload-capacity-prompt.md) · [计划](../plans/2026-10-09-checkpoint-payload-capacity.md) · [实现说明](../implementation/2026-10-09-checkpoint-payload-capacity-implementation-design.md) · [账本](../progress/2026-10-09-checkpoint-payload-capacity-ledger.md)
