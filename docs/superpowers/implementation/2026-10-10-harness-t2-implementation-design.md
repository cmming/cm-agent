# T2 Harness 生产接入实际实现

日期：2026-10-10；主题：harness-t2；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

H0–H5已完成；仅本轮生产策略接入及合同验证，不等于上线准入。

生产执行链：

- H1：Adapter新增AgentScopeHarnessIsolation。随机绝对路径只由当前进程创建，POSIX权限700或Windows所有者ACL。文件接口仅AGENTS.md合成缺失；总线仅空inboxDrain、登记仅空findStale；首个拒绝持续保存，在构造/事件完成边界重抛。close不跟随链接，仅清理自有目录且幂等。
- H2：新增AgentScopeHarnessExecutor及AgentScopeExecutionMode；ReAct的AgentLifecycle增加默认create/streamEvents，保留原治理循环。HarnessLifecycle管理base/包装/delegate/隔离资源，事件真正经过Harness包装，delegate用于状态/中断，审批Store由宿主持有。关闭按现有严格失败优先级归并。构造过滤所有非CM桥接工具并核对最终集合、实例身份和原生Skill源为空。
- H2兼容：全部旧create默认React，新七参数create可选择mode。AgentScopeExecutor仍只有三参数抽象方法，取消能力默认false；真实ReAct/Harness显式true，RuntimeAdapter委托声明。
- H3：根POM管理agentscope-harness使用agentscope.version=2.0.2；Adapter为optional，Server显式依赖。新增execution-mode配置默认react，未知值失败，custom Runtime仍优先。
- H3门禁：Core RuntimeExecutionModeRepository/ConflictException；Persistence JdbcRuntimeExecutionModeRepository及双库V21；Server实际memory存储阻塞探测与JDBC装配。激活目标模式先锁单行，再核对全局非终态Run/TEST和任意检查点。同模式重启允许；当前请求在凭据之前重新核对模式，冲突固定中文DENIED，日志errorId=runId及RUNTIME_MODE_CONFLICT，无凭据/正文。
- H4：Harness继承同一30项运行合同，14项生产构造边界和5项隔离对象合同；固定Skill脚本双策略4项；模式冲突各入口3项与配置10项。双库迁移测试增加V21表/每字段注释及模式排空/重启/并发行锁合同，旧检查点测试覆盖V19到V21。
- H5：新增本组六文档，更新两份README及configuration/operations/release-notes。旧十八份Harness文档保持历史，无关脏文件未变。

自主范围调整：为实现跨重启可核实的切换，增加V21系统部署元数据和仓储SPI；没有新增长期历史/租户策略字段或API。生产隔离替代实验对象，测试断言沿用真实框架和共用治理合同。未复制原生Skill目录/动态工具。

关键位置：Adapter包内HarnessExecutor/HarnessIsolation/ReActExecutor/RuntimeAdapter；Server config的RuntimeProperties、RuntimeConfiguration、RepositoryConfiguration、JdbcPersistenceConfiguration；Persistence模式仓储和mysql/postgresql的V21；测试以账本命令及报告为准。

已验证当前代码，未提交、未推送、未部署。外部停止用例未配置环境变量，真实模型/浏览器/生产Studio/MCP、完整审批恢复竞态、真实沙箱产物及旧二进制回滚不在本轮证明范围；不得用H4通过代替T3–T6。

## 关联产物

- [清单](../checklists/2026-10-10-harness-t2-checklist.md)
- [提示词](../prompts/2026-10-10-harness-t2-prompt.md)
- [设计](../specs/2026-10-10-harness-t2-design.md)
- [计划](../plans/2026-10-10-harness-t2.md)
- [实现说明](../implementation/2026-10-10-harness-t2-implementation-design.md)
- [账本](../progress/2026-10-10-harness-t2-ledger.md)
