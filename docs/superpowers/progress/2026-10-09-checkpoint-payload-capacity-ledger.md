# 问答检查点容量修复进度账本

日期：2026-10-09；主题：checkpoint-payload-capacity；修订：R0；模式：mode=default。

基线：`3f9c026e3afbe052d9ba51ef911b828f8d9deae7`。实现验收阶段未提交；本轮提交信息见账本。

| 编号 | 任务 | 验收 |
| --- | --- | --- |
| T0 | 定位日志与检查点保存链 | 分开记录用户日志、源码事实与合成复现，保护既有脏文件 |
| T1 | 新增 V20 方言迁移 | MySQL LONGTEXT、PostgreSQL TEXT 与中文注释，V19 数据保留，历史迁移不变 |
| T2 | 验证大状态与 TXT 交付 | 双库 INSERT/UPDATE、AES/GCM 往返、列表与 Unicode、租户隔离、V19 失败补偿、V20 TXT 下载 |
| T3 | 同步文档与交付 | 六份路径及编号一致，配置与发布说明准确，报告实际命令和未验收项 |

## 初始阶段快照

T0 完成；T1 迁移已落地待双库验收；T2 进行中；T3 文档已生成待收口。

环境：本机默认 Java17/Maven3.9.4，不用于容器验证；ssh rocky 已确认 Docker23.0.6，指定 Maven 镜像内 Maven3.9.9/JDK21.0.7。旧 checkpoint 远程目录 HEAD=fb601ed 与本地当前 HEAD 不同，不能直接作为本次证据；新建独立 checkout 并核对。

## 初始验证计划

待执行：双库 V19→V20、逐表逐字段注释、新建和升级、检查点大状态/租户、TXT 交付及旧失败补偿。真实模型原请求未执行；没有取得原载荷，不关闭此验收缺口。未提交、未推送、未部署，未操作运行服务或部署数据库。

## 最终验收与当前状态

T0～T3 完成本轮仓库修复及受控验收，未提交。实际环境 Docker23.0.6，Maven3.9.9/JDK21.0.7；Rocky 时钟与会话日期不同，未修改系统时间，文档日期采用用户会话日期。新独立目录 `/root/cm-agent-checkpoint-capacity-3f9c026-20261009` 的 HEAD 与本机 `3f9c026e3afbe052d9ba51ef911b828f8d9deae7` 一致；git bundle 构建基线后仅覆盖13个明确任务文件，SHA256 在验证前后全部通过。未复制用户工作树脏配置。

初次校验清单因 Windows CRLF 被 Linux 当成路径后缀而失败；转换清单为 LF 后所有文件校验通过才启动测试。该问题未改变源码或数据库验证范围。

实际执行命令（通过 `ssh rocky` 执行，退出0）：

```bash
cd /root/cm-agent-checkpoint-capacity-3f9c026-20261009
sha256sum -c checkpoint-capacity-SHA256SUMS
docker run --rm --name cm-agent-checkpoint-capacity-validation --network host \
  -e CM_AGENT_TEST_SANDBOX=true \
  -v /root/cm-agent-checkpoint-capacity-3f9c026-20261009:/workspace \
  -v /root/.m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /usr/bin/docker:/usr/bin/docker:ro \
  -w /workspace maven:3.9.9-eclipse-temurin-21 \
  sh -c 'mvn -v && mvn -o -q -s /root/.m2/settings-docker.xml -Dmaven.repo.local=/root/.m2/repository -pl cm-agent-server -am -Dtest=MigrationTest,JdbcRuntimeCheckpointRepositoryTest,SkillArtifactCheckpointIntegrationTest,RepositoryAgentStateStoreTest,JdbcToolApprovalRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx384m -Dcm-agent.agentscope.studio.enabled=false test'
```

容器 socket 与 Docker CLI 仅供可信测试进程管理夹具，技能容器仍使用既有隔离配置；未使用本机 Docker。

| 测试类 | 项数 | 失败/错误/跳过 |
| --- | ---: | --- |
| MigrationTest | 3 | 0/0/0 |
| JdbcRuntimeCheckpointRepositoryTest | 2 | 0/0/0 |
| JdbcToolApprovalRepositoryTest | 1 | 0/0/0 |
| RepositoryAgentStateStoreTest | 4 | 0/0/0 |
| SkillArtifactCheckpointIntegrationTest | 3 | 0/0/0 |
| 合计 | 13 | 0/0/0 |

报告位于远程对应模块 `target/surefire-reports`，远程执行日志为 `capacity-validation.log`，均不加入版本控制。两库新增/升级至 V20、旧密文与槽属性保留、全表全字段非空中文注释、200000字符合成密文 INSERT/UPDATE、租户拒绝和状态清理、AES/GCM 50000字符状态、中文/emoji及列表往返全部通过。TXT 固定脚本实际运行并通过授权下载，字节内容匹配；V19 失败时文件不可交付且完成清理的回归通过。

本地任务范围 `git diff --check` 通过；六份文件齐全且31个本地文档链接有效。中文注释自查通过：新增迁移解释扩容原因和旧数据保留，测试容器注明生命周期，新状态 record 沿用字段 JavaDoc。

未执行：全量测试（本轮仅修改字段容量及定向测试，当前定向覆盖已通过）；真实模型原请求、浏览器问答及运行环境部署（本轮仅受控夹具，无部署者模型调用与部署操作）。不宣称用户原请求已经成功；应用 V20 后应使用原会话场景复验。字段扩容不代表通信包、内存或其他正文列无限容量。

风险：MySQL ALTER 可能因表规模产生执行耗时与 DDL 锁，应在正式发布中评估窗口。现有 Flyway 对 MySQL8.4 的支持提示仍出现，但本轮实际双库迁移通过，没有升级依赖。未提交、未推送、未合并、未部署，既有无关修改保持原状。

## 本轮提交记录

用户后续明确授权“将修改的内容提交”，本次提交范围为 V20 两库迁移、三份容量/交付测试、配置与发布说明及本组六份文档，共13个文件。未授权推送或部署；普通问答生成文件的后续讨论仅为只读说明，没有新增功能实现。

提交分支：`master`；提交说明：`fix: 修复问答文件生成检查点容量超限`。本文件随本次修复提交，最终提交编号以该分支的 `git log -1` 为准，前文“未提交”保留为实现验收阶段的历史快照。

提交前复核：两份迁移和三份测试的 SHA256 与 Rocky 已通过的13项定向验收一致；没有新增代码变化，因此未机械重跑已通过测试。检查显式暂存范围、格式、敏感信息及无关文件保持情况；本次不包含 index.html、application 配置、.codex 或 .workbuddy。

## 关联文档

[清单](../checklists/2026-10-09-checkpoint-payload-capacity-checklist.md) · [提示词](../prompts/2026-10-09-checkpoint-payload-capacity-prompt.md) · [设计](../specs/2026-10-09-checkpoint-payload-capacity-design.md) · [计划](../plans/2026-10-09-checkpoint-payload-capacity.md) · [实现说明](../implementation/2026-10-09-checkpoint-payload-capacity-implementation-design.md)
