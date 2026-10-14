# T5 实际实现说明

日期：2026-10-12；主题：harness-t5；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

W1：Core新增WorkspaceContextResource/Snapshot，逐项身份、摘要、UTF8、预算及不可变集合校验，诊断toString隐藏正文。AgentRunRequest新增可选workspaceContext，旧构造器保留；Server独立配置及私有文件入口，保护应用/主目录、链接/别名和文本限额。

W2：WorkspaceContextService复用宿主AES-GCM/runtime_checkpoints，固定版本及映射摘要，增加资料守卫键；恢复不碰源文件。RunExecutionService在Runtime前准备，ASK保留/刷新，终态和失败在最终持久化之前清理；清理失败保留原原因为suppressed。ASK返回后停止赢得锁时，取消终态前也清理原保留资料。

W3：ReAct/Harness共用workspaceInput，首次用户消息携带JSON数据，系统仅加入可信资料规则；恢复不重复注入。AgentRuntime/AgentScopeExecutor默认能力false，具体内置策略明确支持；开启配置但Runtime不支持会启动失败。新增WorkspaceContextException与五类API错误码，HTTP及两种SSE入口保留安全原因和原Run编号。

W4：新增16项服务单测、4项配置单测、双模式2项实际协议合同、13项Spring实际Harness/HTTP/SSE合同；覆盖冻结/新版本/删除、身份/撤销、预算、注入角色与权限、缺失/损坏、审计/清理严格失败、停止、Windows目录联接及开销。35项新合同全部通过，受影响回归后不同本地合同合计185项，均无失败或跳过；全模块含三个示例打包通过。不是全量测试，不能代替W5。

W5：2026-10-12 SSH恢复后，在Rocky独立目录核对HEAD和575份源码哈希。PostgreSQL16/MySQL8.4各执行13项实际Harness JDBC流程和16项服务合同，0失败/错误，Windows目录联接1项按平台跳过；各28项实际执行通过。JDK21目录Provider确认SecureDirectoryStream=true。JDBC密文重新加载和清理已验收；同进程对象重建不等同进程重启，强杀和多实例不在本次证明范围。

设计差异：Windows/JDK21实测fileKey可为空，采用私有ACL、最终路径和创建时间/大小/修改时间复核；Linux另用安全目录句柄。真实Windows目录联接夹具已被拒绝。拒绝/失败补严格WORKSPACE_CONTEXT_FAILURE元数据审计，审计不可用优先且保留原原因为suppressed；异常诊断职责只领取一次，HTTP/SSE来源未记录时仍补安全日志。资料根不产生临时复制，关闭的是读取句柄与运行状态；不把资料挂载到Harness原生工作区或Python沙箱。

无新增依赖、迁移、上传/文件操作API或前端改造；新增显式配置、SDK可选组件与错误码。原react默认、AgentScope2.0.2、MCP版本、Skill治理、历史和停止合同保留。W0证据核对/W6生产说明与保护检查已完成；实际15个原文件修改、18个新文件（12个Java实现/测试及6文档）。

关联：[清单](../checklists/2026-10-12-harness-t5-checklist.md)、[提示词](../prompts/2026-10-12-harness-t5-prompt.md)、[设计](../specs/2026-10-12-harness-t5-design.md)、[计划](../plans/2026-10-12-harness-t5.md)、[账本](../progress/2026-10-12-harness-t5-ledger.md)。
