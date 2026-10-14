# T5 进度与证据账本

日期：2026-10-12；主题：harness-t5；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

| 编号 | 状态 | 证据 |
|---|---|---|
| W0 | 完成 | CodeGraph优先、POM/配置/README及before-hashes.json；JDK21.0.11/Maven3.9.4 |
| W1 | 完成 | 快照/文件/配置实现；Windows符号链接/目录联接、路径与文件边界本地通过 |
| W2 | 完成 | 加密冻结/守卫/恢复/清理；本地实际Harness已通过 |
| W3 | 完成 | 双策略输入与能力、HTTP/SSE安全编号合同已通过 |
| W4 | 完成 | 当前不同本地合同185项通过，含35项新合同；全模块打包通过 |
| W5 | 完成 | SSH恢复；HEAD/575份哈希一致；两库各29项、0失败/错误、Windows专属1跳过；Linux安全句柄确认 |
| W6 | 完成 | 六文档与4份生产说明同步，原36工作包及无关改动保持 |

证据存于忽略目录 `cm-agent-server/target/harness-t5`，不提交。`local-first.log`含原Adapter62、Server配置10/停止4/实际审批19通过；合并轮Workspace夹具baseUrl错误导致BUILD FAILURE，不称整轮通过。中间阶段`local-service.log`14通过、`local-context-final.log`实际Harness12通过、`local-workspace-final.log`Adapter2/Server31通过；这些中间证据由下述最终路径/目录联接、严格审计与诊断合同更新，不以旧通过替代新增验收。

最后小样本对照：新增请求UTF8字节591；字节/4启发式估算148token；回环单轮13ms→18ms；资料临时副本0字节。不同机器/模型/缓存下不保证此结果；不是Provider tokenizer或计费证明。文件 `overhead-comparison.txt`只写统计，不存资料或请求体。等待审批额外2个密文资料/守卫条目，SDK消息中也包含原参考文本，需计入检查点容量；无新磁盘临时资料。

## 最终实际命令与结果

均在本机JAVA_HOME=F:/java21，Maven3.9.4/JDK21.0.11，不执行Docker/JDBC。

~~~powershell
mvn -B -pl cm-agent-server -am '-Dtest=AgentScopeWorkspaceContextContractTest,HarnessWorkspaceContextFlowTest,WorkspaceContextServiceTest,WorkspaceContextConfigurationTest,AgentScopeRuntimeContractTest,AgentScopeHarnessRuntimeContractTest,AgentScopeHarnessBoundaryTest,AgentScopeHarnessIsolationTest,AgentScopeSkillRuntimeContractTest,HarnessApprovalFlowTest,ChatStopFlowTest,SkillRuntimeServiceTest,SkillTrialServiceTest,GovernedSkillAccessServiceTest,AgentScopeRuntimeConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -pl cm-agent-server -am '-Dtest=HarnessWorkspaceContextFlowTest,WorkspaceContextServiceTest,WorkspaceContextConfigurationTest,AgentScopeWorkspaceContextContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B '-DskipTests' package
~~~

- `local-accepted.log`：Adapter87、Server97通过，BUILD SUCCESS；加入严格失败审计边界后服务单测由15增为16，以后两轮覆盖受影响新合同。
- `local-security-final.log`：Adapter2、Server33通过，BUILD SUCCESS，验证拒绝审计故障优先/安全原因/原编号。
- `local-diagnostic-final.log`：Adapter2、Server33通过，BUILD SUCCESS，验证诊断单次职责与HTTP/SSE合同；服务16、配置4、实际Harness13。
- 不重复计数的当前合同：Adapter87 + Server98 =185，其中新合同2+16+4+13=35。所有本地选择合同无失败、错误、跳过。不是全量`mvn test`。
- `package-final.log`：全模块及starter-local-tool/http-tool-client/dashscope-mcp-agent示例编译、测试编译及打包通过，BUILD SUCCESS；跳过测试执行，不代表容器验收。
- 最后一项接口诊断上下文复用仅调整日志关联字段，已随全模块打包编译，无输入/错误响应行为变化。
- `git diff --check`、六文档相对链接/模式/修订号、原文件哈希和Java注释触发清单核对完成。

实际问题修正：测试AgentDefinition构造器参数、ModelConfig非空baseUrl、系统提示与描述字段及SSE实际decision/stream路由；Testcontainers通配导入Container歧义。Windows/JDK21 fileKey实测为空，不能用非空断言让整个Windows路径不可用；私有ACL下采用创建时间补充更新检测并检查最终路径。所有失败轮保留证据，不覆盖为通过。

实际15个原文件修改、18个新文件。Core/API、adapter、server、configuration/operations/release-notes/adapter README涉及本轮；未改POM、数据库结构、application*.yml、root README、console index、.gitignore、.codex/.workbuddy/experiments。原36份Harness工作包保持本轮前哈希；最终修改清单在忽略目录changed-files.txt。

## 历史远端阻塞与恢复

多次 `ssh -o ConnectTimeout=10 rocky 'true'` 返回 `Connection closed by 192.168.0.66 port 22`。只读详细握手确认TCP已建立，但 `kex_exchange_identification: Connection closed by remote host`，没有得到远端SSH协议版本、没有进入认证。未修改SSH配置、主机密钥或VM服务；无法核实远端Docker、JDK、HEAD或本轮时钟。

待条件恢复后，在新的隔离目录按HEAD加未提交源码/测试资源覆盖和sha256核对执行：

本机已备妥`baseline.bundle`、`overlay-final.tar.gz`、`overlay-files.txt`、`source.sha256`，只含当前HEAD及必要Java/SQL/POM/固定测试资源，不复制无关脏YAML。未上传或在远端核对，不能称源码远端一致。

~~~sh
docker run --rm --network host -v "$PWD:/workspace" -v /root/.m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 -e CM_AGENT_TEST_WORKSPACE_JDBC=true \
  maven:3.9.9-eclipse-temurin-21 mvn -B -pl cm-agent-server -am \
  -Dtest=HarnessWorkspaceContextJdbcFlowTest,WorkspaceContextServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.test.database=postgresql test
# 再将数据库属性改为mysql，不能同时修改同一构建目录。
~~~

以上命令在初轮尚未运行；2026-10-12继续验收已分别以postgresql和mysql实际执行通过。新测试不调用Docker CLI创建Python沙箱，因此只挂载Testcontainers socket；若补跑T4实际Python，再增加Rocky CLI只读挂载。禁止本机Docker替代、部署库/真实凭据、全局容器清理。

初轮未执行两库与Linux安全目录句柄，现已补齐。仍未执行：进程强杀、多实例、真实Provider/tokenizer、浏览器与T6；这些不能由T5通过替代。

## 2026-10-12 接续验收

SSH别名仍为root@192.168.0.66:22。只读核对发现运行中的rr93为Rocky，尝试网卡链路重接后仍不通；没有重启虚拟机、修改SSH密钥/配置或重置密码。用户随后回复“已恢复”，复测SSH成功；不把恢复原因归结为未经证实的sshd故障。

远端Docker23.0.6；指定Maven容器3.9.9、JDK21.0.7。远端UTC日期2026-10-05，本机任务日期2026-10-12；未调整VM时钟。新目录 `/root/cm-agent-harness-t5-6752bf8-20261012-recovery/repo` 使用baseline.bundle与必要覆盖，HEAD一致、575份sha256全部匹配，未复制脏YAML或真实凭据。

- `postgresql.log`：HarnessWorkspaceContextJdbcFlowTest 13项，WorkspaceContextServiceTest 16项；0失败/错误，1项Windows目录联接在Linux跳过，28项实际通过，BUILD SUCCESS。
- `mysql.log`：同样29项、0失败/错误、1项平台跳过，28项实际通过，BUILD SUCCESS。
- `linux-directory-provider.log`：同一JDK21容器目录Provider的 `SECURE_DIRECTORY_STREAM=true`。Linux链接拒绝、私有权限、冻结/撤销、错误脱敏、审计/清理合同实际运行，不能扩展为同服务账号恶意并发写入、强杀或多实例证明。
- `hash-check.log`：575份哈希通过；证据已取回忽略目录 `cm-agent-server/target/harness-t5`。

W5关闭，T5完成。本轮没有业务代码、依赖、迁移或部署变更；用户要求接续T6，按独立工作包推进。未提交、未推送、未部署。

关联：[清单](../checklists/2026-10-12-harness-t5-checklist.md)、[提示词](../prompts/2026-10-12-harness-t5-prompt.md)、[设计](../specs/2026-10-12-harness-t5-design.md)、[计划](../plans/2026-10-12-harness-t5.md)、[实现](../implementation/2026-10-12-harness-t5-implementation-design.md)。
