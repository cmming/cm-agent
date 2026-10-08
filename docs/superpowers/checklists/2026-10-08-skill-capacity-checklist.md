# 技能容量同步与提交清单

日期：2026-10-08；修订：R0；mode=default；分支 codex/skills-version-console；基线 5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443。

用户选择：同时提交手工容量调整，先同步测试与配置文档。本次提交同时覆盖此前已实施的控制台技能升级/导航、V18 长描述导入和 JDBC 诊断；历史工作包保留。

现状：用户将 ZIP 默认改为 4 MiB、文件数改为 256、资源默认 256 KiB、运行累计预算 16 MiB，并分别修改硬上限。旧测试仍断言 64 KiB、256 KiB 与 2 MiB 硬上限，与源码不符。

| 编号 | 状态 | 目标与验收 | 依赖 |
|---|---|---|---|
| T1 | 完成 | 核对用户容量修改和提交范围 | 无 |
| T2 | 完成 | 保留容量值，更新旧测试、运行累计预算回归、注释与配置文档 | T1 |
| T3 | 完成 | Rocky 当前源码119项加MySQL接口3项全部通过，预算/双库原包回归通过 | T2 |
| T4 | 进行中 | 已核实59个精确路径，提交与记录实际编号待完成 | T3 |

保护范围：application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/；无用户授权的连接配置不纳入提交。不推送、不合并、不部署、不重启服务、不输出凭据。

## 关联产物

- [提示词](../prompts/2026-10-08-skill-capacity-prompt.md)
- [设计](../specs/2026-10-08-skill-capacity-design.md)
- [计划](../plans/2026-10-08-skill-capacity.md)
- [实现说明](../implementation/2026-10-08-skill-capacity-implementation-design.md)
- [账本](../progress/2026-10-08-skill-capacity-ledger.md)
