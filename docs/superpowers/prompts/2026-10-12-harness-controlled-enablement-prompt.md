# 时钟校准与 Harness 受控启用评估提示词

日期：2026-10-12；主题：harness-controlled-enablement；修订：R0；模式：mode=default。
本轮E0–E4已完成，准入结论是暂缓。以下供后续明确授权的复核执行；生成提示词不等于执行它，也不授予部署权限。

```text
在 F:/java/cm-agent 接续“校准目标环境时钟，再按排空流程评估受控启用 Harness”。
当前模式 mode=default，自主核实并记录假设，不主动咨询普通选项。用户后续明确指令优先。
先读 AGENTS.md 和 docs/operations.md，再读以下六份文件：
docs/superpowers/checklists/2026-10-12-harness-controlled-enablement-checklist.md
docs/superpowers/prompts/2026-10-12-harness-controlled-enablement-prompt.md
docs/superpowers/specs/2026-10-12-harness-controlled-enablement-design.md
docs/superpowers/plans/2026-10-12-harness-controlled-enablement.md
docs/superpowers/implementation/2026-10-12-harness-controlled-enablement-implementation-design.md
docs/superpowers/progress/2026-10-12-harness-controlled-enablement-ledger.md
历史验收见 docs/superpowers/progress/2026-10-12-harness-t6-ledger.md；不重复未变化合同或覆盖旧证据。

当前事实：Rocky时钟已经NTP校准并同步RTC，chronyd active/enabled；本机未见CM Agent应用，只有项目数据服务。
MySQL候选cm_agent迁移V20，170条Run中8 RUNNING，22条TEST全终态，0检查点/0待审批；PostgreSQL候选仅V1、Run0且缺TEST/检查点/模式表。
这些不是确认的生产部署目标，不据此自动迁移、启动或切换。

按E0–E4复核发生变化的条件，保持已有摘要和无关脏文件；存在.codegraph时先定位，再核对当前源码。
时间复核用ssh rocky中的chronyc activity/sources -v/tracking、timedatectl和chronyc waitsync 6 0.1 0 5。
已经同步时不重复makestep；再次偏移时先检查全部应用和时间源新样本，不能用陈旧offset盲调。
Docker/数据库仅ssh rocky；只读一致性事务输出版本和状态计数，禁止输出环境、密码、密钥、连接串、进程参数或业务正文。
门禁白名单：Run SUCCEEDED/FAILED/DENIED/CANCELLED；TEST PASSED/NOT_TRIGGERED/FAILED；全部runtime_checkpoints为零，过期/孤立也计数。
缺失表不是零，缺V21不能当成模式门禁已就绪。入口和全实例未核实不能把本机无进程当成排空证明。

只有用户另行明确授权实际部署/数据修复后，才按设计中的窗口推进：确定正式目标和所有入口/实例，冻结新Run/TEST/自动任务，落实可还原备份，正常迁移并先保持react，通过审批/停止生命周期与副作用核查收口旧RUNNING。
失去执行句柄的历史RUNNING不能自动重放、批量改终态、删除记录或清检查点；逐项核查后制定另行受控修复。
全部排空、实例正常退出、生产身份/配置/持久卷/独立密钥/固定沙箱就绪后，在外部配置统一设harness，由启动门禁检查，受控验证通过才恢复流量。
同版本回切react同样排空；旧无V21门禁包不得混用，不删历史迁移。

本轮复核默认只更新评估和六文档，不改Java/POM/YAML/业务记录/默认react，不提交推送，不推进T7/T8。
无法获得正式目标、入口关闭、备份恢复等事实时记录确切缺口和继续条件，完成可独立复核，不编造通过。
交付按仓库五项格式，给出当前准入判断、实际命令结果、未执行验收和提示词/问答入口。
```

关联：[清单](../checklists/2026-10-12-harness-controlled-enablement-checklist.md)、[设计](../specs/2026-10-12-harness-controlled-enablement-design.md)、[计划](../plans/2026-10-12-harness-controlled-enablement.md)、[实现](../implementation/2026-10-12-harness-controlled-enablement-implementation-design.md)、[账本](../progress/2026-10-12-harness-controlled-enablement-ledger.md)。
