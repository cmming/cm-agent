# 技能容量同步与提交账本

日期：2026-10-08；修订：R0；mode=default；分支 codex/skills-version-console；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443。

提交状态：未提交。用户已授权本地提交，也明确纳入手工容量变更；不推送、不合并、不部署。

| 编号 | 状态 | 目标与验收 | 依赖 |
|---|---|---|---|
| T1 | 完成 | 核对用户容量修改和提交范围 | 无 |
| T2 | 完成 | 保留容量值，更新旧测试、运行累计预算回归、注释与配置文档 | T1 |
| T3 | 完成 | Rocky 当前源码119项加MySQL接口3项全部通过，预算/双库原包回归通过 | T2 |
| T4 | 进行中 | 已核实59个精确路径，提交与记录实际编号待完成 | T3 |

已核对其他未提交修正：控制台技能升级与导航 R1、V18 描述扩容、JDBC 脱敏诊断。已有 111 项 Node、11 项数据库导入和 55 项诊断专项证据；本轮容量值变化后重新执行相关 Java 专项，不能沿用旧值证明新值。

当前隔离验证目录 /root/cm-agent-diagnostic-20261008，同基线 HEAD；本轮 commit-SHA256SUMS 核对所有相关源码。部署配置和真实凭据未复制。

## 当前源码的实际验证

- Node：`node --test cm-agent-console/src/test/js/*.test.cjs`，111 项通过，无失败/跳过；日志存于仓库外 commit-node-tests.log。
- ssh rocky 环境：Docker 23.0.6；镜像 maven:3.9.9-eclipse-temurin-21，Maven 3.9.9/JDK 21.0.7。隔离目录 HEAD 与本地基线一致；当前所有相关源码在验证前后 commit-SHA256SUMS 全部通过。未复制用户 application 配置。
- 在该目录使用 host 网络、Docker socket 挂载、Maven缓存和 TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1，执行下列最终专项命令，退出码0。

```bash
mvn -o -q -pl cm-agent-server -am test \
  -Dtest=ErrorDiagnosticLoggerTest,DiagnosticJdbcLoggingTest,ApiExceptionHandlerTest,ApprovalExpiryScannerTest,ModelCatalogDiscoveryServiceTest,ModelConfigControllerTest,SandboxEndpointControllerTest,SkillPropertiesTest,SkillPackageParserTest,GovernedSkillAccessServiceTest,SkillControllerTest,ConsoleResourceTest,MigrationTest,JdbcSkillRepositoriesTest,SkillManagementJdbcPersistenceTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcm-agent.test.skill-packages-dir=/workspace/packages \
  -Dcm-agent.agentscope.studio.enabled=false \
  -Dcm-agent.skills.sandbox.enabled=false
```

- 15 个测试类共119项通过，失败/错误/跳过全部0：ConsoleResource14、迁移3、技能仓储2、PG技能接口3、SkillProperties6、解析17、技能读取/准备12、日志21、真实JDBC日志2、审批7、模型发现4、模型配置7、沙箱端点6、异常响应8、技能Controller7。
- 同容器补跑 `mvn -o -q -pl cm-agent-server -am test -Dtest=SkillManagementJdbcPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.test.skill-packages-dir=/workspace/packages -Dcm-agent.test.skill-database=mysql -Dcm-agent.agentscope.studio.enabled=false -Dcm-agent.skills.sandbox.enabled=false`，退出0，3项通过。合计本轮Java122项执行。
- PostgreSQL16/MySQL8.4均验证原版docx/pptx/pdf ZIP导入201、资源完整回读，以及长描述/Unicode/权限隔离；迁移测试验证V18两库兼容与历史数据。当前容量6项全通过，旧64KiB断言失败已消除。大资源集沙箱准备和累计预算在模拟后端上通过，未声称真实脚本已执行。
- 安全统计位于仓库外 [Java专项摘要](C:/Users/chmi/Documents/Codex/diagnostic-verification/commit-verification-summary.json)、[MySQL接口摘要](C:/Users/chmi/Documents/Codex/diagnostic-verification/commit-mysql-api-summary.json)。远程原始测试日志留在隔离目录 commit-verification.log/commit-mysql-api.log，未回传原始服务日志或凭据。

未运行全仓库测试、真实模型和真实沙箱脚本；当前任务为容量同步及已有改动提交，相关专项已通过。用户运行服务未重启、用户数据库未迁移，未推送/合并/部署。应用连接配置和工具目录保持未暂存；实际提交编号在成功后补记。

## 关联产物

- [清单](../checklists/2026-10-08-skill-capacity-checklist.md)
- [提示词](../prompts/2026-10-08-skill-capacity-prompt.md)
- [设计](../specs/2026-10-08-skill-capacity-design.md)
- [计划](../plans/2026-10-08-skill-capacity.md)
- [实现说明](../implementation/2026-10-08-skill-capacity-implementation-design.md)
