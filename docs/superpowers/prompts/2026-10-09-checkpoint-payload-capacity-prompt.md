# 问答检查点容量修复执行提示词

日期：2026-10-09；主题：checkpoint-payload-capacity；修订：R0；模式：mode=default。

基线：`3f9c026e3afbe052d9ba51ef911b828f8d9deae7`。实现验收阶段未提交；本轮提交信息见账本。

生成此提示词不等于执行其内容，后续操作以用户实际指令为准。

```text
仓库 F:\java\cm-agent。工作模式 mode=default，依据证据自主判断并记录假设，不主动征询普通工程方案。
需求：修复较大问答状态保存到 MySQL 检查点时的长度超限，验证 TXT 生成后的完整交付。
先读取本工作包六份文档和当前 AGENTS.md，逐项核对当前实现，已有通过证据适用时不机械重做。
本轮 T0～T3 已完成，Rocky 定向13项全部通过。仅根据用户最新指令继续，不能机械重做已完成任务；原真实模型请求复验和部署未执行，需要对应运行环境及实际指令。
新增 V20 扩容，不修改历史迁移、不截断或关闭状态，不放松 tenant、AES/GCM、审计及文件交付门禁。
容器/JDBC/迁移测试只经 ssh rocky 使用 maven:3.9.9-eclipse-temurin-21，先确认 JDK21、Docker、HEAD 与源码 SHA256；PostgreSQL16-alpine / MySQL8.4。核对新增/升级迁移、旧记录保留、INSERT/UPDATE、Unicode/列表往返和 TXT 下载。保留 V19 失败补偿回归。
保护用户 application 配置、index.html、.codex 和 .workbuddy 改动；不打印凭据、模型正文或检查点密文。
同步六份文档、configuration 和 release-notes，记录实际命令、结果、未执行项与确切阻塞。真实模型未验收必须单列。
此提示词不授予提交、推送、合并或部署权限。交付按项目要求简述变更、验证、影响、风险和下一步。
清单：F:\java\cm-agent\docs\superpowers\checklists\2026-10-09-checkpoint-payload-capacity-checklist.md
账本：F:\java\cm-agent\docs\superpowers\progress\2026-10-09-checkpoint-payload-capacity-ledger.md
其余四份文档见当前文件链接。
```

## 关联文档

[清单](../checklists/2026-10-09-checkpoint-payload-capacity-checklist.md) · [设计](../specs/2026-10-09-checkpoint-payload-capacity-design.md) · [计划](../plans/2026-10-09-checkpoint-payload-capacity.md) · [实现说明](../implementation/2026-10-09-checkpoint-payload-capacity-implementation-design.md) · [账本](../progress/2026-10-09-checkpoint-payload-capacity-ledger.md)
