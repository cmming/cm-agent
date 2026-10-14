# T6 最终实现说明

日期：2026-10-12；主题：harness-t6；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署生产；隔离验收实例已清理。

初始阶段只核对T5和建立六文档；最终交付如下，未以计划代替实现。

- Adapter新增AgentScopeLiveProviderAcceptanceTest：显式环境开关，ReAct/Harness×DASHSCOPE_NATIVE/OPENAI_COMPATIBLE，两任务共8项。qwen-plus实际中文响应和一次合成CM网关随机回执；凭据不回显，失败断言不复制完整响应。原生baseUrl按2.0.2实际客户端拼接方式修正。
- Server新增HarnessBinaryRollbackIntegrationTest：显式环境开关与新旧可执行包路径；两个Testcontainers库、随机子进程环境认证材料、固定回环模型、实际Server HTTP创建模型/Agent及Run。每次关闭实例前核对Run/TEST/检查点排空；新包切回react后全部退出再启动无V21门禁旧包。备份仅内存生成/摘要，旧包新增Run且原历史/V21不丢失；不是生产备份恢复或带ASK回退。
- Console app.js的submitApprovalDecision保留completed事件run数据，刷新后仅在原主体/会话更新chatFormStatus。DENIED不保留暂停提示，CANCELLED/FAILED/SUCCEEDED与新审批分别显示；FAILED使用API已有安全原因及运行编号。chat.html脚本查询版本2.0.32。新增chat-approval-terminal.test.cjs五项，覆盖无assistant正文的完整拒绝、新审批等待、终态分类、安全失败信息、迟到会话结果与重复写入门控。
- README/adapter README删除不支持State Store/HITL/手动取消的过时表述，区分短期受控恢复与长期Memory；configuration说明真实端点，operations补打包/排空/回退和时钟边界，release-notes与六文档同步。

实际验收：8真实协议+72双策略/装配+46JS+3双库迁移/模式+2实际二进制=131项，均0失败/错误/跳过；不是全量测试。另有真实浏览器、两协议Skill TEST/DOCX下载与HTTP目标拒绝证据。T5双库各29项中的平台跳过单独记录，不混入T6统计。

CodeGraph未覆盖新增类，采用当前源码补足，没有重建索引。普通package是薄JAR，实际进程须显式repackage；最后静态修复重打包采用maven.jar.forceCreation避免复用旧可执行包。22份静态资源与当前源码归一化摘要相同；最终后端CM字节码摘要与回退测试包一致，所以不重复无变化后端合同。

证明范围：DashScope qwen-plus原生及其OpenAI兼容接口；v2浏览器在Harness下验收；ReAct另有真实协议、双策略合同与实际Server进程证据。旧包仅Git基线6752bf8、新模式仅已排空/无检查点/停掉全部测试实例。未验证任意第三方网关、任意旧版本、带ASK旧包恢复、生产备份还原、强杀或多实例接管；不把这些计为通过。默认仍react，不自动进入T7/T8。

实际T6修改7个原文件（两README、configuration/operations/release-notes、app.js/chat.html），新增9个文件（两Java合同、一JS合同、六文档）。POM/YAML/用户root index及其他原修改保留；没有新依赖、迁移、API或默认模式调整。

关联：[清单](../checklists/2026-10-12-harness-t6-checklist.md)、[提示词](../prompts/2026-10-12-harness-t6-prompt.md)、[设计](../specs/2026-10-12-harness-t6-design.md)、[计划](../plans/2026-10-12-harness-t6.md)、[账本](../progress/2026-10-12-harness-t6-ledger.md)。

