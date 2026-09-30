# A 批次进度与验收账本

关联：[设计](../specs/2026-09-30-approval-expiry-a-design.md)、[计划](../plans/2026-09-30-approval-expiry-a.md)、[实现](../implementation/2026-09-30-approval-expiry-a-implementation-design.md)。本账本记录实际执行，不改写形成清单的四份 next-iteration-analysis 历史文档。

## 基线与交付状态

- 原仓库：`F:/java/cm-agent`，基线 `b7c7280d4e542c565cf11c482a4c53efd1e99516`。原有 application*.yml、.codex/、.workbuddy/ 及清单/历史文档保持不动，未操作用户运行中的服务。
- 实施工作树：`F:/java/cm-agent-approval-expiry-a`，分支 `codex/approval-expiry-a`。CodeGraph 先查询 ToolApprovalService，但返回不匹配的 MCP Servlet 等源码；已直接复核当前实现和测试，未重建索引。
- 隔离验证提交：eb38920、66fc9fb、067b16e、bfc73dc、ddc2ad6、ed2576e；最终代码快照 `2d85c22f54e634bf5cae7c58f6eaa799256e7358`。仅显式纳入本轮文件，未使用 git add -A。
- 最终文档更新**未提交**；验证提交仅用于同步 Rocky 测试，不等于已提交到用户原分支。未推送、未合并、未部署。
- 本轮只执行 A，未实现 B/C/D、执行租约、自动重放、独立审批中心或新业务功能，未升级依赖。

## T0～T8 状态

| 任务 | 状态 | 实际证据 |
| --- | --- | --- |
| T0 | 完成 | 已读取适用 AGENTS、模块 POM、README、configuration、operations、清单；独立工作树；本轮四份文档定义身份、时间、事务、竞争和失败重试 |
| T1 | 完成 | ApprovalExpiryPage、Repository 双实现、V14 索引；ApprovalExpiryRepositoryTest 3 项覆盖范围、同时间分页、边界、归属和竞争；双库迁移及索引断言 |
| T2 | 完成 | ToolApprovalService 统一 CAS；等待 Run 条件更新、原主体检查点清理、TEST FAILED、严格审计同事务；ApprovalExpiryJdbcPersistenceTest 双库 16 项及真实 memory 工作单元回归 |
| T3 | 完成 | ApprovalExpiryProperties 校验、有界调度、固定遍历截止时间、防故障项饥饿；ApprovalExpiryScannerTest 7 项验证开关、非法值、两种范围、诊断脱敏、故障继续/恢复和连续到期候选 |
| T4 | 完成 | Node 85/85；桌面与 390×844 实际聊天/运行/TEST 页面；刷新移除旧决定、断线只查询、历史可查、v1 入口保留；截图见下表 |
| T5 | 完成 | Java 21 快速模块 186 项；最终 server 非容器显式列表 65 类、573 项；最终重点 23 项；Node 85 项；全量补充参数绑定/认证/权限及既有租户、撤权、停用、快照和发布门禁回归 |
| T6 | 完成 | 最终 2d85c22 快照 Rocky 全量 126 报告、892 项；同快照 MySQL 专项 29 项；零失败/错误/跳过；双库事务回滚、双扫描/扫描决定竞争、完整 JVM 重启及迁移注释已核对 |
| T7 | 完成 | 真实浏览器与可控 Runtime：PASSED 发布、PINNED v1/FOLLOW v2/回滚 v1 实际加载、同 Run 审批恢复、主动过期、NOT_TRIGGERED 拒绝、必需依赖撤销阻断、响应丢失恰好一次提交；不是实际模型验收 |
| T8 | 完成 | README/configuration/operations/release-notes 和四份本轮文档已更新；相关 diff、中文注释与敏感内容复核；git diff --check 通过；原工作树 HEAD/脏文件未变；文档未提交、未推送/合并/部署 |

## 本机实际验证

构建前发现默认 Java 17；仅设置当前进程 `JAVA_HOME=F:/java/temurin21/jdk-21.0.11+10` 并前置 bin，重新执行 `java -version`、`mvn -v`，确认 Java 21.0.11 与 Maven 3.9.4，未更改全局环境。

| 命令 | 结果与证据（工作树根目录或 target 下的忽略生成物） |
| --- | --- |
| `mvn -q -pl cm-agent-server -am -DskipTests package` | 通过 |
| `mvn -q -pl cm-agent-core,cm-agent-agentscope-adapter,cm-agent-console -am test` | 186 项通过（core 91、adapter 81、console 14），fast-regression.log |
| `mvn -q -pl cm-agent-server -am -Dtest=<核实后的非容器类列表> -Dsurefire.failIfNoSpecifiedTests=false test` | 早期 63 份报告、560 项；最终快照 65 类、573 项，零失败/错误/跳过。准确 selector 保存在 cm-agent-server/target/a-noncontainer-selection.json，汇总 a-noncontainer-summary.json，日志 final-noncontainer.log |
| `mvn -q -pl cm-agent-server -am -Dtest=ApprovalExpiryScannerTest,ApprovalExpiryFlowTest,SkillControllerParameterBindingTest,SkillTrialServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 最终代码 23 项通过，final-focused.log |
| `node --test cm-agent-console/src/test/js/skills.test.cjs cm-agent-console/src/test/js/console-core.test.cjs` | 最终重跑 85/85；包含 NOT_TRIGGERED/WAITING 清除旧发布入口和只读恢复回归；cm-agent-server/target/final-node.log |
| `mvn -q -pl cm-agent-server -am -DskipTests install` | 通过；用于隔离 fixture 引入当前 console 资源，无部署 |

中间失败均已排查，不算通过：初次使用 `*Test,!*Jdbc*Test` 误包含 MigrationTest，发生本机 Docker 探测失败、未启动容器；随后按源码构造非容器列表。新增诊断测试曾遗漏 ObjectMapper 参数，修复后通过。fixture 占用本轮 Maven 安装产物曾导致本机 install 文件锁，停止本轮 fixture 后重跑通过。

最终非容器选择与实际 Maven 调用（PowerShell，Java 环境已按上文设置）：

```powershell
$files = rg --files cm-agent-server/src/test/java -g '*Test.java'
$names = @($files | Where-Object {
    -not (Select-String -LiteralPath $_ -Pattern 'org\.testcontainers|@Testcontainers' -Quiet)
} | ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_) })
$selector = $names -join ','
mvn -q -pl cm-agent-server -am "-Dtest=$selector" '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## Rocky 实际验证

统一由主智能体操作 `ssh rocky`。已核对 Docker 23.0.6、镜像 `maven:3.9.9-eclipse-temurin-21` 内 Maven 3.9.9/JDK 21.0.7、Git HEAD 与本地快照一致；使用 PostgreSQL `16-alpine` 和 MySQL `8.4`。隔离目录位于 `/tmp/cm-agent-approval-a-*`，挂载 Docker socket 仅供当前项目 Testcontainers；未全局清理或操作无关容器/卷。

公共 Maven 缓存 `/tmp/cm-agent-skills-m2`。远程系统日期为 2026-09-22，与本机 2026-09-30 不一致，示例模块下载依赖遇到 `PKIX path validation failed`（证书有效期）；未调整主机时钟、未关闭 TLS 验证。仅复制本机已解析的公开依赖缓存，不包含 settings/凭据；用无认证的隔离 Maven settings 声明缓存既有 repository/pluginRepository 来源并 `-o` 离线验证。

1. `/tmp/cm-agent-approval-a-eb38920`：PG 专项 16 项通过。MySQL 首次审批专项通过，但快速技能 Run 返回 400；定位为既有 TIMESTAMP 精度舍入使完成时间早于数据库开始时间，局部修复 JdbcRunRepository.complete 并补双库回归。
2. `/tmp/cm-agent-approval-a-bfc73dc`：主模块全量通过，示例模块被上述 TLS 日期问题阻断；补公开缓存后 `mvn -o -q -s validation-offline-settings.xml -rf :dashscope-mcp-agent test` 通过。累计结构化 XML 核对 126 份报告、891 项，零失败/错误/跳过；这是全量主模块加示例续跑，不伪装为原始单条命令一次通过。
3. `/tmp/cm-agent-approval-a-ddc2ad6`：`mvn -q -pl cm-agent-server -am -Dcm-agent.test.database=mysql -Dtest=ApprovalExpiryJdbcPersistenceTest,SkillRuntimeJdbcPersistenceTest,SkillManagementJdbcPersistenceTest,ApprovalExpiryFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`，22 项通过，日志 `/tmp/cm-agent-approval-a-v5-mysql.log`。包含 16 项审批故障/竞争/时间精度/JVM 重启验证。
4. `/tmp/cm-agent-approval-a-final`（ed2576e）：完整离线 `mvn -o -q -s cm-agent-server/target/validation-offline-settings.xml test` 退出 0，日志 `/tmp/cm-agent-approval-a-final.log`；固定截止时间改动后的最终快照另行全量回归，见下一项。
5. `/tmp/cm-agent-approval-a-sweep`（2d85c22）：最终完整离线 `mvn -o -q -s cm-agent-server/target/validation-offline-settings.xml test` 退出 0；126 份报告、892 项，零失败/错误/跳过，日志 `/tmp/cm-agent-approval-a-sweep.log`。同快照再运行 `mvn -o -q -s cm-agent-server/target/validation-offline-settings.xml -pl cm-agent-server -am -Dcm-agent.test.database=mysql -Dtest=ApprovalExpiryJdbcPersistenceTest,ApprovalExpiryScannerTest,ApprovalExpiryFlowTest,SkillRuntimeJdbcPersistenceTest,SkillManagementJdbcPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test`，29 项，零失败/错误/跳过，日志 `/tmp/cm-agent-approval-a-sweep-mysql.log`。

最终结构化汇总已复制到本机实施工作树 `cm-agent-server/target/a-full-summary.json` 和 `a-mysql-summary.json`，不包含请求、stdout、SQL 或凭据。全量汇总在 MySQL 专项覆盖同名 XML 之前生成留存；全量与专项不相加为一次执行的数量。

容器公共参数：`--add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v /var/run/docker.sock:/var/run/docker.sock -v /tmp/cm-agent-skills-m2:/root/.m2 -v <隔离目录>:/workspace -w /workspace`。报告在各隔离目录的模块 `target/surefire-reports/`；迁移测试同时核对两个数据库的表/字段中文原生注释与 V14 索引列序。

## 浏览器验收证据

隔离 fixture：`ApprovalExpiryBrowserFixture`，仅 `127.0.0.1:18093`、test profile、进程内随机 JWT/AES、临时环境变量密码；可控 Runtime 使用真实 SkillAccessGateway 读取固定版本，仅产生授权工具 ASK，不调用真实模型/外部工具。fixture 开启 HTTP 定义只供依赖就绪检查，明文 HTTP 禁用。生产默认值未放宽。Playwright 会话 `approval-a`，桌面 1440×1000、移动 390×844。

下列路径均相对实施工作树 `cm-agent-server/target/`，属于忽略生成物；脚本和截图留存本机，不加入 Git。

| 验收 | 日志/截图与判定 |
| --- | --- |
| NOT_TRIGGERED 与同 Run 恢复 | a-browser-trials.log；NOT_TRIGGERED 发布 409 SKILL_TRIAL_REQUIRED；82593ced-7908-48da-8c19-dc5c9c8408cd WAITING→PASSED；a-browser-resumed.png |
| TEST 主动过期与刷新 | 同日志；d457d3ec-292b-4abb-b200-e95fadb5cb48 主动 FAILED，Run DENIED，原因“工具审批已过期”；旧提交入口 0、移动横向溢出 false；a-browser-expired-mobile.png |
| 决定响应丢失 | a-browser-disconnect.log；服务端已处理后丢弃响应，只有 1 次 POST，GET 恢复同 Run PASSED，未自动重提；发布指针前后 null、正式会话前后为空；a-browser-response-lost.png |
| 发布、绑定和回滚 | a-browser-release.log、a-browser-bindings.log、a-browser-agent-binding.log；实际 NORMAL Run 技能加载版本依次 1、2、1，覆盖 PINNED/FOLLOW/回滚，TEST 后正式会话仍为空；a-browser-passed.png、a-browser-release-rollback.png、a-browser-agent-binding.png |
| 必需依赖撤销 | a-browser-required.log；先 PASSED/READY，撤销 grant 后 GRANT_MISSING；页面 TEST 409 SKILL_RELEASE_FAILED，页面与响应 errorId 同为 3b5fff7f-8f96-4752-93b2-1b8e7471918c；a-browser-required-revoked.png |
| 会话、运行详情、v1 | a-browser-chat-final.log；正式会话只在 TEST 无污染证据完成后专门创建。954b7fe6-3249-4b00-a91b-b2b2c40c1716 无提交主动过期；刷新历史 EXPIRED、system:approval-expiry，旧 radio 0，Run DENIED，移动无溢出，v1 标题正常；a-browser-chat-expired.png、a-browser-chat-expired-mobile.png、a-browser-run-expired.png、a-browser-run-expired-mobile.png、a-browser-v1.png |

浏览器脚本：browser-seed.js、browser-trials.js、browser-disconnect.js、browser-release.js、browser-required.js、browser-chat-run.js。出现的浏览器 409/断线错误是门禁与故障注入的预期结果，不以零控制台错误冒充验收标准。JWT 到期按原规则重新登录，没有放宽认证。

浏览器完成后仅停止经进程命令行核对的本轮 fixture（PID 23488），关闭 Playwright 会话 approval-a；用户服务未动。浏览器验收之后的最终代码修改仅固定扫描遍历截止时间，已通过本机扫描回归与 Rocky 最终双库/JVM 门禁；未将较早浏览器记录冒称为重新执行的最终快照浏览器记录。

## 复核与保留边界

- 真实浏览器发现 memory Trial 必须通过 SkillUnitOfWork 暂存写入；已接入并用真实仓储/JVM 回归，而非只依赖 Mockito。
- 发布/回滚曾因无编译参数名元数据无法绑定 skillId；已显式命名路径参数，6 项 MockMvc 在禁用参数名发现器时核对认证、权限和绑定，不调整全局编译器。
- 新 TEST 和权威刷新清除旧 PASSED 发布入口，避免 NOT_TRIGGERED/等待状态沿用旧按钮；Node 回归通过。
- 最后审查发现移动截止时间会使持续新增候选拖延故障项重试；改为一次完整遍历固定截止时间，空页后重新开始，7 项扫描测试包含故障恢复与连续新增回归。
- JDBC 承诺每项短事务原子性；memory 仅开发/测试，不承诺数据库级回滚。扫描间隔不是严格到期 SLA；积压、数据库连接和单项超时影响延迟。
- 批准不等于执行成功。Runtime 不在审批数据库短事务内；已批准但结果不确定的 Run 不自动重放。跨实例执行租约、fencing 和外部副作用恰好一次未实现，仍保留运维限制。
- 本次没有安全专用真实模型配置，未调用真实模型或有外部副作用工具；T7 是受控端到端验收，不是实际模型验收。
- `git diff b7c7280 --check` 通过；检查全部 36 个本轮文件，中文安全/事务/并发/失败注释已落地，token/私钥格式扫描未发现候选，application*.yml、POM、.codex/、.workbuddy/ 和历史分析文档无本轮 diff。最终原仓库 HEAD 仍为 b7c7280，脏文件清单与开始一致。
- T0～T8 必需验收全部通过，A 批次完成；无剩余实现或必需验证阻塞。远程时钟与在线 Maven TLS 的环境问题仍需环境维护者处理，当前离线验证已完成，并未弱化 TLS。最终文档更新未提交，验证分支保留，不推送、不合并、不部署。
