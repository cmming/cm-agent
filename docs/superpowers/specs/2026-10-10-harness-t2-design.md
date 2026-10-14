# T2 Harness 生产接入设计

日期：2026-10-10；主题：harness-t2；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

用户目标是在生产模块建立受控Harness执行策略及模式装配，保留当前ReAct默认行为。既有ReAct治理循环已有审批、Skill、超时和停止；2.0.2 Builder实验发现即使关闭扩展能力仍注册wait_async_results并探测工作区/空消息。本轮使用生产对象核对这些合同，不复制实验Mockito替身。

范围为H0–H5。非目标：T3–T6完整验收、在线按Agent灰度、原生宿主工具、Shell/Web/子Agent/动态Skill、长期记忆、压缩/转录/第二套历史。无Controller/SSE/前端协议改变，用户已有配置文件不改。

方案选择与依据：

- D1：生命周期create/streamEvents扩展复用AgentScopeReActExecutor；Harness包装为事件入口，delegate用于状态和中断。避免复制安全与结果映射，所有状态身份仍来自可信请求。
- D2：AgentScopeHarnessIsolation独占随机私有空目录；实际拒绝型文件系统、空总线及拒绝异步登记仅提供框架必要合成查询。首个越界记录到事件边界再次抛出，构造和关闭明确失败；删除不跟随链接。
- D3：最终白名单核对工具全集与实例身份，仅保留CM业务及固定Skill桥接；移除默认wait_async_results并核对原生Skill repositories为空。Builder开关不能单独当作隔离证明。
- D4：仅靠配置无法辨认重启前的数据集策略，自主增加V21单行系统模式元数据。与原需求相比增加Core模式SPI、Persistence迁移及Server绑定，未增加业务策略字段或历史；是实现排空门禁的工程选择，未冒称用户另行确认。
- D5：模式行锁和排空检查共用短事务；所有非终态Run/TEST和所有检查点阻塞变化。同模式重启允许恢复；每次凭据解析前查询当前模式，不能缓存或读取故障放行。memory仅供测试/本地，替换仓储需提供真实门禁SPI。

调用链：Server登记RUNNING→受控模式核对→租户模型凭据解析→选定策略→共享治理循环→Harness包装事件（ReAct则原事件入口）→当前工具/Skill治理网关→统一结果/审批状态→关闭包装、base和私有目录。模式核对通过的在途Run阻塞切换；发生切换的旧模式实例在模型之前DENIED。

系统模式表不含tenant业务资源，无租户写入接口；跨租户只检测是否有阻塞状态，既不返回ID/计数也不读取正文。共享数据集的新版本使用同一数据库门禁；未实现门禁的旧二进制不在保护范围，部署必须停用。

验收标准与H1–H4一致：实际工具全集/实例身份、宿主注入负向、权限/ASK/恢复/停止/清理/固定Skill共用合同；默认React、未知模式失败、custom Runtime优先、旧工厂无Harness JAR兼容；PG16/MySQL8.4 V21注释/升级/排空/重启/并发门禁。实际结果见账本，不能把此标准或计划当作真实生产准入完成。

## 关联产物

- [清单](../checklists/2026-10-10-harness-t2-checklist.md)
- [提示词](../prompts/2026-10-10-harness-t2-prompt.md)
- [设计](../specs/2026-10-10-harness-t2-design.md)
- [计划](../plans/2026-10-10-harness-t2.md)
- [实现说明](../implementation/2026-10-10-harness-t2-implementation-design.md)
- [账本](../progress/2026-10-10-harness-t2-ledger.md)
