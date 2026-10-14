# T6 进度账本

日期：2026-10-12；主题：harness-t6；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署生产；隔离验收实例已清理。

| 编号 | 状态 | 工作与证据 |
|---|---|---|
| V0 | 完成 | T5前置关闭，HEAD/脏修改、SSH、JDK21及577份后端源码哈希核对 |
| V1 | 完成 | 两策略、两真实协议8项；双策略及装配72项通过 |
| V2 | 完成 | Harness真实中文页面/SSE/审批/停止、两协议Skill TEST与下载；拒绝提示修复；JS46项通过 |
| V3 | 完成 | Rocky双库MigrationTest3项，排空、孤立状态、模式锁及本地旧实例凭据前拒绝 |
| V4 | 完成 | 实际新旧Server进程两库2项；策略往返、备份摘要、历史保留与旧包新Run |
| V5 | 完成 | 本轮六文档、生产说明、资源/字节码核对与临时资源关闭 |


## 环境、基线和保护

T5接续后SSH已恢复，原因未归因于未经证实的sshd/密钥故障。Docker23.0.6；指定容器Maven3.9.9/JDK21.0.7；本地JDK21.0.11/Maven3.9.4。RockyUTC2026-10-05，客户端任务日期2026-10-12，未修改系统时钟。用户原POM/YAML/root index/.gitignore/.codex/.workbuddy等保持。

独立目录/root/cm-agent-harness-t6-6752bf8-20261012/repo，旧包old-repo来自同一baseline.bundle。HEAD一致，577份Java/SQL/POM/固定资源哈希通过。22份控制台资源按文本CRLF归一化/二进制原字节核对最终JAR一致，明确排除无关用户root index。证据在忽略目录cm-agent-server/target/harness-t6。

## 自动化实际结果

| 命令/证据 | 结果 |
|---|---|
| CM_AGENT_TEST_LIVE_PROVIDER=true；mvn -B -pl cm-agent-agentscope-adapter -am -Dtest=AgentScopeLiveProviderAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false test；live-provider-accepted.log | 8项真实qwen-plus、两策略/两协议通过 |
| mvn -B -pl cm-agent-server -am -Dtest=AgentScopeRuntimeConfigurationTest,AgentScopeRuntimeContractTest,AgentScopeHarnessRuntimeContractTest -Dsurefire.failIfNoSpecifiedTests=false test；dual-mode-regression.log | Adapter62、Server10通过，含旧已装配实例模式冲突时不读凭据 |
| node --test cm-agent-console/src/test/js/chat-ux.test.cjs cm-agent-console/src/test/js/chat-stop.test.cjs cm-agent-console/src/test/js/skills.test.cjs cm-agent-console/src/test/js/chat-approval-terminal.test.cjs；console-regression-final.log | 46通过；node --check app.js通过 |
| Rocky指定Maven21容器，mvn -B -pl cm-agent-persistence -am -Dtest=MigrationTest -Dsurefire.failIfNoSpecifiedTests=false test；migration-mode.log | 3项通过，两库迁移/注释、V20容量与V21排空/行锁 |
| Rocky指定容器，CM_AGENT_TEST_BINARY_ROLLBACK=true，新旧JAR路径环境；mvn -B -pl cm-agent-server -am -Dtest=HarnessBinaryRollbackIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test；binary-rollback-accepted.log | 2项通过，两库真实Server进程react→harness→react→旧包 |

不同合同合计131项，0失败/错误/跳过；非全量mvn test。新外网/二进制合同默认关闭，普通自动测试不会无意联网或启动进程。数据库容器都在Rocky；本机未运行Docker。

双库回退测试使用--network host、源码/旧包只读挂载、/root/.m2、Testcontainers socket与HOST_OVERRIDE=127.0.0.1。每个实例都确认Run/TEST/检查点零非终态、正常进程退出后才启动下一实例；独占测试客户端停止发请求等同本次停止入口，不代表生产网关维护功能。备份摘要见BINARY_ROLLBACK日志，数据只在测试内存。新旧AgentScope2.0.2/MCP2.0.0/Flyway11.7.2，新包另含harness2.0.2。旧包现有迁移上限V19，实测保留V21并成功新Run，没有删改迁移。

回退测试新包SHA256：2e24354d11460f05d0c5bc492c9bb3ab5a54bbaf1e1a709db072afc136c61961；旧包ecee71fd765be837e46164dbaaec2807aed60e94f605fe78fe15d7a2fb2e158a。最终静态修复包SHA和后端摘要见binary-ui-final.sha256、backend-bytecode-before-ui.txt/after-ui.txt；后端字节码完全一致，静态资源22项一致。新旧打包及最后UI打包BUILD SUCCESS，最后仅重跑受影响JS与实体浏览器。

最终UI包SHA256为b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12；CM后端字节码摘要46f5f6e0ea4b500e9ec78c442ed35f86059c4c823f48a9e084470e604faa6ede。除console模块外，465份后端资源/依赖逐项原字节一致（backend-artifact-unchanged.log），确认不是仅同版本名的假定。最终浏览器读取实体包资源，没有用请求拦截替换脚本冒充打包验证。

## 浏览器与API证据

Playwright操作Rocky隔离test/memory实例，经本机18086 SSH转发：

- 中文口令“松柏”两轮回答正确，证明续聊；消息和审批decision/stream响应Content-Type=text/event-stream，sse-evidence-final.log保存仅路径/type。
- HIGH待审批输入锁定；选择允许后同Run恢复，审批移入历史、发送解锁。example.com在Rocky解析为::1，HTTP_TARGET_REJECTED正确拒绝；不宣称该HTTP工具成功。拒绝流程后端DENIED，但发现旧暂停提示残留，修复后实体包final-denied-result.log显示“本轮已拒绝，不会执行被拒绝的调用”，按钮可用、backend DENIED/零非终态。
- 活跃实际模型Run停止，backend CANCELLED，browser-stop-snapshot.log显示“本轮已停止，已保留输出内容”，输入恢复。没有用模型任务自行结束冒充停止。
- OpenAI兼容及DashScope原生各完成Skill候选预检/真实TEST，PASSED且qualifiesRelease=true；测试后publishedVersionId仍null，Agent绑定0。两次真实Docker固定Python生成DOCX、浏览器下载，各921字节、ZIP三部件含word/document.xml，摘要与受管文件记录一致；未认证下载401。browser-skill-proof.json/native-skill-proof.json及截图/下载为证据。
- 首次读取旧自动快照曾误判选项/停止文案，改读当前snapshot后状态正确；这不构成前端停止缺陷。拒绝完成仍显示暂停则由当前快照和backend DENIED同时证实，属于实际修复。测试页曾对齐服务端日期，后来恢复本机时间再次选择/拒绝正常；不把操作问题归因为未经证实的时钟逻辑。backend以自身Clock决定TTL。
- 普通/favicon.ico存在原有401，未扩大为无关UI修改。没有新增产品功能、生产登录流程或放宽网络/权限。

最终截图harness-denied-final.png、harness-approval.png、harness-resumed.png、两协议Skill TEST截图及下载；只包含合成验收内容。真实凭据仅环境/内存注入，live日志及报告对环境密钥匹配数0；没有凭据、JWT、生产连接或完整模型请求入文档/提交。

## 保留的失败轮与修正

初版Java验收遗漏PrincipalRef导入导致编译失败；修正测试导入。真实协议第一轮8项有4个原生404，核对2.0.2实际字节码后修正测试baseUrl为域名，8项通过。薄JAR启动两项失败(no main manifest)，单独repackage又失败要求同一生命周期；明确install依赖后Server package spring-boot:repackage。登录夹具误用token而非accessToken产生401，修正后实际两库进程通过。浏览器file-upload需先打开文件选择器、工作目录与快照使用方式修正，不降低生产规则。审批提示回归先失败3/5，再修复后5/5及相关46通过，浏览器实体复测通过。

## 清理、范围和未执行

本轮浏览器、SSH转发及专属容器已关闭/删除；没有全局容器/卷/镜像清理。保留独立远端源码/二进制与忽略目录证据，Testcontainers按自身生命周期清理。最后测试实例1项DENIED、非终态0。

证明范围：DashScope qwen-plus原生及其OpenAI兼容接口；v2浏览器在Harness下验收；ReAct另有真实协议、双策略合同与实际Server进程证据。旧包仅Git基线6752bf8、新模式仅已排空/无检查点/停掉全部测试实例。未验证任意第三方网关、任意旧版本、带ASK旧包恢复、生产备份还原、强杀或多实例接管；不把这些计为通过。默认仍react，不自动进入T7/T8。

T5通过不等于所有生产前提满足；Rocky时钟校准、生产配置/实际部署入口和生产备份还原未执行。全量test未跑，按变化运行上述合同；后端最终字节码未变，没有重复两库回退。完整git diff --check提示用户原application-mysql.yml第19行EOF空行，保持不动；本轮文件检查通过。

实际T6修改7个原文件、新增9个文件，未改依赖/schema/API/默认react；另将T5清单W6的“保留阻塞”改为“历史阻塞与接续完成分段保留”，仅同步已关闭的T5验收，不改变需求或历史证据。未提交、未推送、未部署生产。直接后续为目标环境校准时钟并按已验证的排空步骤评估受控启用；T7/T8须另行授权。

关联：[清单](../checklists/2026-10-12-harness-t6-checklist.md)、[提示词](../prompts/2026-10-12-harness-t6-prompt.md)、[设计](../specs/2026-10-12-harness-t6-design.md)、[计划](../plans/2026-10-12-harness-t6.md)、[实现](../implementation/2026-10-12-harness-t6-implementation-design.md)。

