# MySQL 排空与迁移接续清单

日期：2026-10-12；主题：harness-drain-readiness；修订：R1；实际修订日期：2026-10-13；模式：mode=default。
当前状态：G11、G12、G13、G14完成；G5/G9/G10移出当前范围，保留历史阻塞，未完成Rocky生产部署。

## R1 当前范围：本机 Windows 运行 Harness

用户最新要求“在本机运行部”，按“改为本机运行吧”理解并已在本轮说明。应用改在Windows本机运行，沿用Rocky MySQL `cm_agent`、原管理员账号及密钥；仅回环8080、`mysql`开发profile、真实Harness。R0的Rocky生产Secret准入没有被满足，生产装配及私有备份继续保留。

| 编号 | 状态 | 目标/位置 | 依赖 | 验收条件与本轮证据 |
|---|---|---|---|---|
| G11 | 完成 | 本机启动准备；target/harness-local-runtime | G4 | 8080空闲、旧IDE实例已退出；577份Java/SQL/POM/固定资源与T6覆盖摘要一致，清洁候选JAR同SHA；既有模型主密钥格式有效；原库V21/react已排空 |
| G12 | 完成 | Windows前台Java进程与SSH转发 | G11 | JDK21、JDBC、真实harness/fake=false/Studio=false；127.0.0.1:8080健康UP及登录页200，JMX实际配置；本机不运行Docker |
| G13 | 完成 | 登录和真实无工具Run；保留历史 | G12 | DeepSeek现有配置Run成功、随机字符串匹配、0工具；前两轮失败保留，3个临时Agent均停用；原库harness、173Run/22TEST全终态、检查点/待审批0 |
| G14 | 完成 | scripts/start-local-harness.ps1、六文件及运维/发布说明 | G13 | 脚本语法/CheckOnly、文件及链接、摘要保护、凭据检查；保持服务与转发运行，交付重启及正常停止方法；未提交 |

现有DeepSeek成功Run为 `ec98263b-3bcd-4f26-8c5a-5e399642f0db`。GLM一次验收收到上游HTTP429/code1113/余额不足；首轮Qwen Provider失败，未确认细分原因，不泛化为所有模型可用。该轮不重新验收技能容器、审批、停止或产物恢复，历史T6通过仅作历史依据。

证据：`cm-agent-server/target/harness-local-runtime/` 的runtime.json、api-acceptance-third.log、final-db.log；详细差异及失败见本账本R1。默认react源配置、Java、POM、YAML、迁移和既有密钥保持；模式由本次正常应用启动门禁切为harness。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。

## R0 历史快照（2026-10-12）

以下为保留的首轮证据；其中当时的当前状态、react/170Run及未启用描述只适用于首轮，最新范围与状态以上方R1为准。

历史基线：R0，2026-10-12，mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；接续[前轮评估](../progress/2026-10-12-harness-controlled-enablement-ledger.md)，保留其 V20 历史记录。

用户要求继续下一步，并明确正式数据集为 Rocky MySQL `cm_agent`（回复 `cm_agen` 按已核实的库名处理）。本轮推进备份还原、V21补齐和历史Run调查；启用仍必须满足全部排空与生产配置条件。

| 编号 | 状态 | 目标/涉及位置 | 依赖 | 验收方法与结果 |
|---|---|---|---|---|
| G0 | 完成 | 确定MySQL目标与时间/包身份 | 无 | 用户回复；SSH waitsync成功；T6候选包摘要匹配 |
| G1 | 完成 | 8条历史Run逐项元数据调查；账本 | G0 | 每条0已存工具/审批、仅1条开始审计；共8条关联消息，无完成记录；不输出正文 |
| G2 | 完成 | 私有V20备份和隔离还原 | G1 | 原库只读mysqldump，备份600/目录700；全部表数据与还原副本匹配 |
| G3 | 完成 | 副本迁移/拒绝合同，再原库正常补齐V21 | G2 | 指定Maven21容器、实际Flyway；原库模式react，全部业务表数据摘要不变 |
| G4 | 完成 | 用户确认的8条中断测试逐项收口 | G3 | 副本故障回滚/重复/排空模式往返通过；原库8 FAILED、16新审计，历史内容不变 |
| G5 | 阻塞 | 移出当前范围； Rocky生产受控启用并验证 | G4/G7–G10 | 用户已选择Rocky生产；数据库已排空，7个生产Secret及真实恢复/全实例停机材料仍缺失，未启动 |
| G6 | 完成 | 工作包、运维/发布说明和资源关闭 | G3 | 六文件/链接/差异与保护检查；仅清理专属副本，保留私有备份，未提交 |
| G7 | 完成 | scripts/rocky-harness生产装配、候选包暂存 | G4 | Rocky/home私有目录、production/react/回环地址、外部ConfigTree、脚本语法与缺Secret拒绝通过 |
| G8 | 完成 | 找到并备份/暂存旧5个产物密文 | G4 | 本机配置目录定位，密文ZIP23096字节，远端同摘要、5个CMA1文件600；不代表解密认证通过 |
| G9 | 阻塞 | 移出当前范围； 生产Secret供给与成套恢复验证 | G7/G8 | 7个文件缺失；须真实验证5模型/1端点/5产物原密钥和成套恢复，不能随机替换 |
| G10 | 阻塞 | 移出当前范围； Rocky真实production/react基线与Harness受控验收 | G9 | 最小权限DB账号、外部JWT、旧数据认证、全部实例停机、真实Provider/审批/停止/文件及审计；未运行 |

当前MySQL：V21、react、Run170条全部终态（FAILED67/SUCCEEDED99/DENIED3/CANCELLED1）；原8条RUNNING已按中断测试收口为FAILED。TEST22条全终态、检查点0、待审批0；产物元数据5条。数据库逻辑备份还原通过不代表独立密钥和密文产物卷恢复已验证。

本轮用户明确“8条旧Run属于已中断的测试，继续下一步”。原库修复前新增V21私有备份，先在实际还原副本验证，再使用现有Run/Audit仓储及同一短事务逐项更新；保留开始审计，新增8条AGENT_RUN/FAILED与8条AGENT_RUN_RECONCILE/SUCCESS。原始输入/输出/消息、其他162条Run与旧审计保持。

修复时发现本机IDE实例PID62532有10个MySQL连接，首次前置检查拒绝、写入0；通过已启用的Spring Admin正常关闭上下文，8080入口/数据库连接归零、Admin注销后重试成功。JVM仍存活，未强杀，也未恢复该开发入口。随后用户明确选择Rocky生产部署；配置询问仅回复“直接ssh rocky”，未提供受控Secret来源。已通过SSH自主完成查找与装配，不把SSH可用当作生产配置齐备。

Rocky `/home/cm-agent-production` 已有同摘要候选包、私有产物副本、非敏感production配置及预检查/启动脚本；默认react、127.0.0.1:18086，未创建生产容器。常规部署目录与CM Agent私有任务目录未找到现成生产配置，SSH环境未有已检查的主密钥别名；这不证明未知外部Secret管理平台不存在。当前7个所需Secret文件为空缺，拒绝启动的实际负例通过。

证据：前轮 `cm-agent-server/target/harness-drain-readiness/`；G4接续 `cm-agent-server/target/harness-test-run-reconciliation/` 的backup.log、probe.log、apply-final.log、context-after-shutdown.log。真实业务备份仅位于Rocky私有目录，未下载或进入Git。

关联：[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。
