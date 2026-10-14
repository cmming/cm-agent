# T4 进度账本

日期：2026-10-11；主题：harness-t4；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

| 编号 | 状态 | 当前证据 |
|---|---|---|
| S0 | 完成 | CodeGraph 源码和调用链；本机 JDK21.0.11/Maven3.9.4；Rocky Docker23.0.6/Maven3.9.9/JDK21.0.7、python:3.12-alpine 已缓存 |
| S1 | 完成 | 实际 Harness 固定版本、必需依赖、撤销、预算及防重；两库各16项集成全部通过 |
| S2 | 完成 | 实际容器在途停止暴露 FAILED 误判，生产最小修复后两库取消成功；新增5项严格边界单测，文件失败补偿通过 |
| S3 | 完成 | 原密文服务/存储重建、下载归属、密文损坏安全 HTTP503、候选 TEST 及 PENDING/READY 均通过 |
| S4 | 完成 | 本地不同合同81项；两库集成32项；独立真实 Docker10项；当前源码/固定资源哈希一致 |
| S5 | 完成 | 六份产物、adapter README、operations、release-notes同步；原30份历史工作包及无关脏文件哈希保持 |

证据目录：`cm-agent-server/target/harness-t4`（忽略生成物，不入库）。`before-hashes.json` 保存原有文件；Rocky 独立目录 `/root/cm-agent-harness-t4-6752bf8-20261011-0220/repo`，Git HEAD 一致，563份 Java/SQL/POM及固定测试资源 sha256 校验通过。验证后补充公开方法中文 `@throws` 说明，无执行逻辑变化；最终再次同步核对源码哈希。

## 本地 JDK21 实测

以下命令均在 F:/java/cm-agent、JAVA_HOME=F:/java21 下执行，未使用本机 Docker。

~~~powershell
mvn -B -pl cm-agent-server -am '-Dtest=AgentScopeSkillRuntimeContractTest,AgentScopeSkillExecutionBridgeTest,AgentScopeSkillContractTest,AgentScopeSkillSessionTest,GovernedSkillAccessServiceTest,ManagedSkillSandboxTest,SkillRuntimeServiceTest,SkillTrialServiceTest,SkillArtifactServiceTest,SkillArtifactControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -pl cm-agent-server -am '-Dtest=GovernedSkillAccessServiceTest,ChatStopFlowTest,HarnessApprovalFlowTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -pl cm-agent-server -am '-Dtest=GovernedSkillAccessServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
~~~

- `local.log`：adapter13、server40通过，BUILD SUCCESS。生产修复前的 GovernedSkillAccessService12项由下面最终17项替代，其余无变化的合同保留有效。
- `local-stop-fix.log`：Governed16 + ChatStop4 + 实际HarnessApproval19=39通过，BUILD SUCCESS。
- `local-gateway-final.log`：新增停止事实读取故障覆盖后，Governed17通过，BUILD SUCCESS；其中5项是本轮新增。
- 不重复计数的最终合同：adapter13 + server68=81。不是全量 Maven 测试。

## Rocky Docker/JDBC 实测

核实 Docker23.0.6、Maven3.9.9/JDK21.0.7，python:3.12-alpine缓存可用；未用部署库或真实模型凭据。以下命令在独立 repo 内执行，DB先PostgreSQL后MySQL，不并发写同一构建目录。

~~~sh
sha256sum -c ../source.sha256
docker run --rm --network host \
  -v "$PWD:/workspace" -v /root/.m2:/root/.m2 \
  -v /usr/bin/docker:/usr/bin/docker:ro \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
  -e CM_AGENT_TEST_SANDBOX=true \
  maven:3.9.9-eclipse-temurin-21 mvn -B -pl cm-agent-server -am \
  -Dtest=HarnessSkillSandboxIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcm-agent.test.database=postgresql test
# 第二库将数据库属性改为 mysql，同一16项合同。
~~~

- `postgresql-accepted.log`：16通过、0失败/错误/跳过，BUILD SUCCESS；含实际在途停止修复。
- `mysql-accepted.log`：16通过、0失败/错误/跳过，BUILD SUCCESS；相同当前源码。双库32项。
- `docker-regression.log`：此前以 `-Dtest=HarnessSkillSandboxIntegrationTest,DockerSkillSandboxIntegrationTest,SkillArtifactDockerIntegrationTest` 执行；DockerSkillSandbox7与SkillArtifactDocker3全部通过，覆盖真实无网络/只读/非root、子进程超时销毁、输出/脱敏/缺镜像、DOCX与链接/传输拒绝。该合并轮集成夹具2项失败导致整体 BUILD FAILURE，不能称整轮通过；独立10项未受后续治理修复影响，集成已在最终两库重新全部通过。不同容器合同合计42项。
- 最终哈希证据 `source.sha256`、`hash-check-final.log`；基线bundle和覆盖清单仅存忽略目录，未提交。

## 实验差异与修正

1. 首次编译对 stop/releaseQualifying/activeRunId 等名称的假设不符合当前接口，改用实际 cancel/qualifiesRelease/租户与Agent查询；未修改生产 API。
2. 清理周期24h违反现有1h硬上限；改用合法1h。AgentScope2.0.2不接受空 Skill 正文；使用当前固定版本构造原生ID。
3. Maven容器缺 Docker CLI 导致 SKILL_SANDBOX_UNAVAILABLE；只读挂入 Rocky CLI 后真实执行。没有回退宿主Python或放宽子容器策略。
4. 重复模型调用没有向外抛异常；实测相同标识只留一次资源准备/文件，补充最终响应验证正常完成；直接网关防重既有单测仍通过。跨租户JWT夹具最初未建立对应租户导致严格审计外键失败503，补真实测试租户后鉴权拒绝404正确。
5. 增加实际在途停止：首轮16项中15通过、1错误，Future返回“技能沙箱执行已中断”且Run失败；这是生产分类缺陷，不是测试预期修正。治理边界最小修复后PostgreSQL/MySQL16项各全部通过。普通超时、附加关闭失败、取消审计及停止事实读取失败五项边界单测均通过。
6. 新单测曾缺少Mockito doThrow限定，编译失败；最终明确限定后本地与Rocky编译/测试通过。验证后没有执行逻辑改动，仅补中文JavaDoc。

## 差异与限制

实际5个已有文件：GovernedSkillAccessService、对应单测、adapter README、operations、release-notes；新7个文件：1个真实集成测试和本轮6份工作包。源码注释触发清单、空白差异和文档相对链接已核对。原30份Harness历史文档、root README/POM、application*.yml、console index、.gitignore及其他既有脏文件保持本轮前哈希；不涉及 .codex/.workbuddy/experiments 的修改。

未执行：真实 Provider、浏览器、强杀进程、多实例、跨主机 TTL、T5/T6，均不在本轮范围。

Rocky时钟约比客户端早7天，未修改。两库按各自宿主时间使用期限，不能证明跨主机TTL；文件/服务对象重建不代表进程强杀恢复。没有改API、配置键、迁移、依赖或默认react。T4已完成，直接后续为T5只读工作区上下文，需用户明确授权。

关联：[清单](../checklists/2026-10-11-harness-t4-checklist.md)、[提示词](../prompts/2026-10-11-harness-t4-prompt.md)、[设计](../specs/2026-10-11-harness-t4-design.md)、[计划](../plans/2026-10-11-harness-t4.md)、[实现](../implementation/2026-10-11-harness-t4-implementation-design.md)。
