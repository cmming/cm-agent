# T4 技能沙箱与产物设计

日期：2026-10-11；主题：harness-t4；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

Harness 已通过 T2/T3 接入，历史测试的 Skill 快照为空，不能作为实际脚本与文件交付证明。本轮验证架构 T4，复用生产能力，只修复当前实验暴露的合同缺陷。

实际链：会话或 SkillTrialService 创建可信 Run → SkillRuntimeService 固定版本/授权纪元/依赖 → 实际 Harness 私有隔离与 CM SkillBox 桥接 → GovernedSkillAccessService 的短事务预算/防重 → ManagedSkillSandbox → 固定 Python Docker → SkillArtifactService 暂存加密文件 → 清理、二次授权和严格执行审计 → PENDING → Run 成功及发布审计 → READY → 当前主体/租户、完整性及租约下载。

实际实验补充：在途脚本因用户停止中断时，ManagedSkillSandbox 原本返回 SKILL_SANDBOX_TIMEOUT，门控记录为致命错误，实际 Run FAILED。最小修复位于治理最终边界：同一租户、Agent、Run、原主体的可信状态为 CANCELLING，线程确实中断且异常没有附加清理失败，才记录严格 CANCELLED 审计并抛出已有 RunCancellation.StoppedException；执行器继续等待在途许可退出。普通截止超时和任何严格故障保持失败，不能仅凭模型参数、停止信号或异常文案降级。

自主假设：采用回环模型 SSE 桩来控制边界时序，保留实际执行器、Docker、JDBC、AES-GCM 和 Web 鉴权。版本更新不撤销旧快照；访问纪元变化永久撤销旧快照；TEST 显式候选覆盖绑定但不改变发布事实。故障注入只作用于审计及边界时序，不替换 Runtime 或沙箱。

防重证据分层：真实 Harness 对相同模型调用标识没有再进入沙箱，正常完成时只留下一个准备记录和文件；直接再次进入生产网关仍由既有严格 SKILL_SANDBOX_DUPLICATE 合同拒绝。不能假设所有重复模型响应都必然向外抛出该错误。

范围：固定版本审批恢复、撤销、预算、防重、TEST、路径拒绝、固定 Python、文件失败清理、加密及归属下载、服务/存储对象重建。非目标：新增 API、配置、迁移、依赖升级、默认切换；T5 只读上下文和 T6 真实 Provider/浏览器/发布回退。

验收以清单 S0–S5 和账本实测为准，历史通过不能替代本轮验收。Rocky 时钟与本机偏差不修改；两库验证使用各自容器主机时间，不声称跨主机期限证明。

关联：[清单](../checklists/2026-10-11-harness-t4-checklist.md)、[提示词](../prompts/2026-10-11-harness-t4-prompt.md)、[计划](../plans/2026-10-11-harness-t4.md)、[实现](../implementation/2026-10-11-harness-t4-implementation-design.md)、[账本](../progress/2026-10-11-harness-t4-ledger.md)。
