# 会话停止本轮实施：进度账本

日期：2026-10-10；主题：`chat-stop-delivery`；修订：R0；模式：`mode=default`。基线 `8cd22b3f031c5887cba86d1ba3f4222d9a057184`；本次已按用户指令纳入本地提交，未推送、未部署。

## 授权与状态

D1～D3 继承原设计的实际用户选择。D4 用户选择使用现有测试模型并补充 `https://dashscope.aliyuncs.com/compatible-mode/v1`、`qwen3.8-27b`；既有 DASHSCOPE_API_KEY 环境变量可用，未读取或复制用户配置里的凭据。

| 编号 | 状态 | 实际交付与证据 |
|---|---|---|
| T0 | 完成 | 原六份读取、CodeGraph/源码/POM/配置复核；保护无关脏树 |
| T1 | 完成 | core 控制/不变量/SPI、RunCancellationTest，Java 21 定向回归 |
| T2 | 完成 | adapter 本地 HTTP、模型等待停止、忽略中断和迟到基础设施失败；真实 Provider 专项通过 |
| T3 | 完成 | memory/JDBC CAS、审批取消/恢复/回滚；Rocky PG19 + MySQL19 均零失败 |
| T4 | 完成 | ChatStopFlowTest 4 项及审批/运行控制器回归；403/404/409/errorId 同号日志/停止审计失败关闭 |
| T5 | 完成 | 78 项 JS；真实服务端 fixture + Edge 桌面/手机、刷新、审批停止/批准后停止、断网不自动重试 |
| T6 | 完成 | 六份及生产说明已落地；最终回归/浏览器/core全套通过；六份33链接有效、范围差异检查通过 |

## 本次实际命令与结果

所有本机 Maven 先设置 JAVA_HOME=F:/java21 并把其 bin 放在 PATH 前面。java -version 与 mvn -v 已核对 Java 21；全量 `mvn test` 未执行，避免本机触发仓库全部 Testcontainers。以下均为本次执行，不引用历史绿色测试代替验收。

1. `mvn -q -pl cm-agent-server -am -Dtest=RunControlRegistryTest,RunControllerTest,ToolApprovalControllerTest,SkillTrialControllerTest,SkillTrialServiceTest,RunPersistenceServiceTest,InMemoryConversationStoreTest,InMemoryToolApprovalRepositoryTest,ChatStopFlowTest,ToolApprovalServiceTest,ApprovalExpiryScannerTest,ApprovalExpiryFlowTest,ConversationPromptComposerTest,RunCancellationTest,AgentScope*Test,ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false test`：最终退出0，包含句柄身份释放修复。服务端97项、Adapter82项、Console14项和当时Core2项通过，外部专项按普通运行默认跳过1项；日志 `output/chat-stop-acceptance-final.log`。
2. `CM_AGENT_EXTERNAL_STOP_TEST=true; mvn -q -pl cm-agent-agentscope-adapter -am -Dtest=AgentScope*Test,RunCancellationTest -Dsurefire.failIfNoSpecifiedTests=false test`：退出 0，外部专项 1 项零失败零跳过；首轮真实正文出现后 CANCELLED，下一轮 SUCCEEDED、非空正文、零工具。环境凭据未存储或打印；不记录模型正文。后续普通测试该专项默认跳过。日志 `output/chat-stop-adapter-final.log`。
3. `node --test cm-agent-console/src/test/js/console-core.test.cjs cm-agent-console/src/test/js/chat-stop.test.cjs`：78 项全部通过；日志 `output/chat-stop-js-final.log`。
4. `node scripts/verify-chat-stop-browser.cjs`（JAVA_HOME=F:/java21，NODE_PATH 为本机 bundled Node dependencies）：最后句柄修复后再次退出0，真实 Spring/登录/REST/SSE，Edge 1440×1000、390×844；无 pageerror/横向溢出，图标40px。两轮发送、活跃刷新找回原Run、PENDING取消、批准后恢复再停止、POST断网只发生一次及手动GET恢复通过；日志 `output/chat-stop-browser-final.log`。
5. 通过 `ssh rocky` 在项目隔离目录运行 Maven 容器的 `mvn -q -pl cm-agent-server -am -Dtest=ApprovalExpiryJdbcPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test`：PG16 19 项，0 失败/错误。添加 `-Dcm-agent.test.database=mysql`：MySQL8.4 19 项，0 失败/错误。
6. `git -c core.autocrlf=false -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check -- . ':!cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillArtifactProtocol.java' ':!cm-agent-server/src/main/resources/application-mysql.yml'`：最终退出0。合同测试原文件已有混合/重复CR，精确保留历史行，只新增74行；本次比较禁用自动换行归一化而未修改Git配置。无关用户文件的原有空白保持不动。
7. `mvn -q -pl cm-agent-core -am test`：最终退出0，Core96项全通过，无跳过。包含新增停止状态、结束时间、恢复身份与只读信号副本测试；日志 `output/chat-stop-core-final.log`。本机Maven3.9.4/Microsoft JDK21.0.11。
8. 本包6份文档共33个相对链接全部存在，日期/主题/任务与状态一致；新增关键源文件敏感模式检查未发现凭据。暂存区为空；自己的18094验收fixture已关闭，原8080服务未操作。

## Rocky 验证快照

远端 `/root/cm-agent-chat-stop-8cd22b3-20261010/repo`。已核对 Docker 23.0.6、Maven 3.9.9、JDK 21.0.7 和同 HEAD。容器镜像 `maven:3.9.9-eclipse-temurin-21`、数据库镜像 PG16-alpine/MySQL8.4。未使用本机 Docker、未清理无关容器/卷。

未提交源覆盖归档 `output/chat-stop-overlay-final.tgz`，本机/远端 SHA-256 都为 `4dd15d1035d65b13d89c94bc2030d24e28841418027ef8fe3b3c7df6cf987aae`。覆盖相关模块 src，不传 application 配置，测试用基线配置与受控属性。远端日志在上述项目父目录 `jdbc-postgres-acceptance.log` 和 `jdbc-mysql-acceptance.log`。归档早于最后句柄身份释放和 Adapter 严格失败发布顺序修复；这两项本机回归/浏览器单独验证，不声称该归档包含最终全部改动。JDBC 仓储及 19 项数据库测试源码没有后续语义修改。

## 浏览器证据

本地 `output/playwright/`：
`chat-stop-ready-desktop.png`、`chat-stop-cancelling-desktop.png`、`chat-stop-cancelled-desktop.png`、`chat-stop-cancelled-mobile.png`、`chat-stop-approval-cancelled.png`、`chat-stop-approved-resume.png`、`chat-stop-network-failure.png`。已实际查看桌面与手机截图，40px 查询图标和收口文案已复验。脚本结束时关闭自有浏览器/fixture，不修改或停止原 8080 服务。该脚本不调用真实 Provider，不证明外部副作用回滚；真实模型专项是独立证据。

## 失败修复与边界

- 初轮服务器回归因旧 mock 没有真实 RunControlRegistry 失败，补可复用测试构造器后通过，没有弱化生产校验。
- 首轮 PostgreSQL 定向失败暴露消息表名误用，改为现有 `messages`，然后双库通过；没有新增迁移绕过错误。
- 外部专项初次编译错误使用 result() 而非既有 run()，修复后实测通过；编译失败时没有发起外部模型调用。
- 严格失败记录必须在退出许可释放前发布；新增迟到失败测试通过。正常 timeout 合同仍保持，不借此改变旧超时语义。
- 最后审查发现上一审批片段 finally 可能删掉新片段句柄，新增身份匹配释放与回归。
- Core全套首次发现两条旧RUNNING异常文案精确断言失败，将错误原因按状态名生成以保持旧合同；修复后96项全部通过。
- 生成输出、日志、归档、截图位于 output/，不加入版本控制；真实密钥、JWT、模型正文不写入交付文档。
- 全量套件、跨实例/进程重启执行接管、供应商计费结束、真实有副作用工具撤销和生产部署未验证，不作承诺。

## 提交记录

- 2026-10-10 用户明确要求“将修改内容提交”；本次仅创建本地提交，不推送或部署。
- 分支：`master`；提交说明：`实现会话问答停止本轮与审批安全收口`；完整提交标识以 Git 中该提交说明的记录为准。
- 范围：本次代码、测试、浏览器验收脚本、图标及许可、生产说明、原 chat-stop 分析包与本 chat-stop-delivery 交付包。原分析包的“未提交/业务待做”保留为形成需求时的历史，不代表本交付当前状态。
- 排除已有 application 配置、SkillArtifactProtocol、index.html、.codex/.workbuddy 和 output/ 生成物；逐路径暂存，不使用 `git add -A`。
- 提交阶段只更新文档元信息，不重跑付费模型或数据库验收；前述业务验证证据仍适用。
- 提交前再次运行两份 JavaScript 合同测试，78项全部通过，日志 `output/chat-stop-commit-js.log`；暂存差异和文档链接检查通过，未检测到新增真实凭据。

## 关联产物

- [清单](../checklists/2026-10-10-chat-stop-delivery-checklist.md)
- [提示词](../prompts/2026-10-10-chat-stop-delivery-prompt.md)
- [设计](../specs/2026-10-10-chat-stop-delivery-design.md)
- [计划](../plans/2026-10-10-chat-stop-delivery.md)
- [实现说明](../implementation/2026-10-10-chat-stop-delivery-implementation-design.md)
