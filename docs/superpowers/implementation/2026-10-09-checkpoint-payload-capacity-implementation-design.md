# 问答检查点容量修复实际实现

日期：2026-10-09；主题：checkpoint-payload-capacity；修订：R0；模式：mode=default。

基线：`3f9c026e3afbe052d9ba51ef911b828f8d9deae7`。实现验收阶段未提交；本轮提交信息见账本。

## 已落地内容

T0：日志与源码核对完成；原错误是数据库检查点保存阶段的长度超限，并非日志可证明的 TXT 格式错误。

T1：新增两库 V20。MySQL encrypted_payload 为 LONGTEXT；PostgreSQL 原 TEXT 保持，中文原生字段注释一致。JdbcRuntimeCheckpointRepository 与 RepositoryAgentStateStore 运行逻辑无需改动，仍按原租户状态槽、加密和过期约束读写。

T2：MigrationTest 分阶段执行至 V19，再执行 V20，当前迁移数契约为20；新增 Repository 测试验证旧 schema 超限、原记录不变、升级保留、较大密文完整插入更新和跨租户拒绝。文件检查点测试采用固定 Python 生成 TXT，保留 V19 失败回收，并验证 V20 的标量、列表、中文/emoji 状态往返及成功后的下载字节。

T3：配置说明及发布说明已同步；本组六份文件已生成并同步实际结果。无依赖、API、配置键、权限或密钥变化。

实际验证：Rocky 指定 Maven/JDK21 容器中13项全部通过，测试源码与两份迁移在执行前后 SHA256 一致。V19 字段超限与失败回收保留；V20 旧数据保留、INSERT/UPDATE、列表/Unicode 解密往返、租户隔离和真实固定 TXT 下载全部通过。测试使用合成状态，不使用真实模型或部署数据库；本次未部署、未提交。

## 关联文档

[清单](../checklists/2026-10-09-checkpoint-payload-capacity-checklist.md) · [提示词](../prompts/2026-10-09-checkpoint-payload-capacity-prompt.md) · [设计](../specs/2026-10-09-checkpoint-payload-capacity-design.md) · [计划](../plans/2026-10-09-checkpoint-payload-capacity.md) · [账本](../progress/2026-10-09-checkpoint-payload-capacity-ledger.md)
