# 会话失败反馈进度与验证账本

## 当前修订 R1（2026-10-26，自主模式，已纳入本次本地提交）

### 2026-10-27 提交记录

- 用户授权提交本次修改；提交说明：`修复问答失败反馈并保留模型接口错误详情`。本段随同本次 Git 提交记录归档，哈希以包含本段的提交为准；未推送、未重启服务。
- 仅纳入本需求的 37 个文件。共享脚本、九个 HTML、资源测试、发布说明及 application.yml 按片段暂存；原有样式优化、页面锚点修改、本地 profile/凭据配置、`.codex/` 和 `.workbuddy/` 保留在工作区。
- 从暂存区导出独立副本执行验证，避免依赖其他未提交修改：JDK 21.0.11 / Maven 3.9.4，前述定向命令额外包含 `ConsoleResourceTest`，9 类共 167 项通过、失败/错误均为 0；Node 148 项通过。
- 提交副本比 R1 工作区验收少 1 项前端测试：此前已有的同页锚点测试不属于本需求，未纳入提交；R0/R1 的 149 项结果保留为当时工作区证据。
- 暂存清单、`git diff --cached --check`、新增内容敏感模式与无关差异检查通过；混合文件仅提交本任务片段，未覆盖工作区原内容。

用户追加后端接口失败说明返回与按钮作用解释，新增 T5～T6，保留以下 R0 历史证据。

| 编号 | 状态 | 当前证据 |
| --- | --- | --- |
| T5 | 已实现 | Provider HTTP 状态与结构化响应脱敏摘要；即时/历史派生错误元数据；安全日志与原始日志抑制 |
| T6 | 完成 | 最终定向 Java 152、console Node 149 通过；生产说明与六份工作包已同步 |

已核对本地 AgentScope 2.0.2 源码 JAR，确认 ModelHttpException、HttpTransportException、OpenAIException 与 DashScope 内部异常的访问方式及原始响应日志行为。未凭记忆推断框架能力。

第一次定向验证：ReAct RuntimeContract 31、Harness RuntimeContract 31、摘要单测 4、ModelFactory 6、OpenCodeGoRequest 8 全通过；后续加入 DashScope 独立异常与最终日志配置测试，需要以最终复测结果为准。Server RunControllerTest 34 与 core RunRecordTest 16 通过，其中新增用例覆盖同步、SSE 及历史 GET 的安全原因/errorCode/errorId 一致性。

最终验证：

- JDK 21 下执行 `mvn -q -pl cm-agent-server -am "-Dtest=ModelFailureSummaryTest,AgentScopeRuntimeContractTest,AgentScopeHarnessRuntimeContractTest,AgentScopeModelFactoryTest,OpenCodeGoRequestTest,RunRecordTest,RunControllerTest,ErrorDiagnosticLoggerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，退出码 0。8 个测试类分别为 5、31、31、6、8、16、34、21，共 152 项，失败与错误均为 0。
- 真实本地 HTTP Stub 返回 500 的结构化响应，含合成凭据回显、私有地址和额外 debug 字段；ReAct/Harness 均返回安全说明并验证日志只含相同 errorId/errorCode，不包含这些敏感标记。摘要单测另覆盖 DashScope 独立异常、401/429/5xx、损坏/HTML/过大响应、限长和纯文本脱敏。
- MockMvc 同时覆盖同步问答、SSE completed 与历史 Run GET：HTTP/SSE 本系统仍按既有完成协议返回 200，业务失败为 run.status=FAILED；上游 HTTP 429 是 errorMessage 中明确区分的模型状态，避免误当成本系统身份认证失败。无 assistant 正文；错误原因、RUNTIME_EXECUTION_FAILED 和 errorId=runId 在立即/历史结果中一致。
- `node --test cm-agent-console/src/test/js/*.test.cjs`：149 项全部通过，确认前端继续识别诊断字段及失败状态。
- 本次 adapter/core/Server 测试/生产文档范围 `git diff --check -- ...` 通过。原合同测试文件混合行尾保持原状，仅处理新增行的行尾检查；没有无关格式化。

未执行真实供应商模型、浏览器登录后的端到端或 JDBC/容器测试。后端新增 getter 不改变 Repository 写入字段、表结构或迁移；日志配置是必要变更，部署应保留这两个 logger 的安全级别。本需求改动已纳入 2026-10-27 本地提交，未推送。

实际使用 JDK 21.0.11 / Maven 3.9.4；没有执行数据库或容器测试，因为本轮不修改 schema/JDBC。没有调用真实模型或重启本机 8080 服务；JVM 仍需重启才能加载后端新类。按钮只查询旧 Run，旧泛化记录不能恢复原文。配置已有用户改动保留，本次只提交必要 logger 级别。

## R0 原进度与验收（历史）

日期：2026-10-26；修订 R0；模式：自主；提交状态：未提交。

| 编号 | 状态 | 证据 |
| --- | --- | --- |
| T1 | 完成 | CodeGraph 与当前源码核对 completed、runStatuses 及会话内 Run GET；理解并保留已有脏修改 |
| T2 | 完成 | 发送、轮询、审批恢复统一终态；失败消息反馈与按需 GET 原因已实现 |
| T3 | 完成，模拟验收 | Node 149/149、Java 资源 15/15；1366×768、390×844 浏览器模拟响应通过 |
| T4 | 完成 | 六份工作包、发布说明及共享脚本版本同步；未提交 |

## 实际验证

- 环境：显式令本轮 Maven 使用 `F:/java21`，`java -version` 为 Microsoft OpenJDK 21.0.11，`mvn -v` 为 Maven 3.9.4 / Java 21.0.11。未更改项目配置或系统环境变量。
- `node --test cm-agent-console/src/test/js/*.test.cjs`：149 项通过。新增覆盖空 assistant 失败、状态区分、已有错误码/编号、历史失败及部分输出、纯文本渲染、GET 详情失败、迟到隔离、会话切换、SSE error，以及轮询失败。
- `mvn -q -pl cm-agent-console -am test`：15 项通过。首次因资源断言还引用旧 JS 版本失败，同步版本后通过。
- 浏览器访问 `http://localhost:8080/console/v2/chat.html` 被重定向到登录页，因此未执行真实账户/模型失败验收。
- 本地模拟服务：`node cm-agent-console/src/test/js/chat-ux-preview.cjs`（18081）。Playwright 在现有失败会话查询原因，然后发送“模拟失败”，收到 FAILED、空 output、null assistant；消息区与表单均显示失败和运行编号，没有回答完成文案；发送按钮恢复可用。
- 1366×768 与 390×844：没有页面横向溢出，输入区与表单失败提示在视口内；刷新后历史失败仍可见并提供详情读取。截图：`output/playwright/chat-failure-desktop.png`、`output/playwright/chat-failure-mobile.png`（验收产物，不提交）。浏览器模拟服务仅有 favicon 404，无业务脚本异常。
- 只读获取 8080 的 app.js 2.0.35 与 console-core.js 2.0.17，确认当前返回脚本已含新增函数。此证据只确认资源更新，不等于真实登录与模型运行通过。
- 全仓 `git diff --check` 发现已有 `application-mysql.yml:19` 文件末尾空行，属于任务开始前配置改动，保持不动；限定 console、发布说明与本工作包的 `git diff --check -- ...` 通过。

## 遗留与限制

未运行后端全量、数据库和容器测试，因为本次只改控制台与文档。未改变服务端原因或日志关联合同；若后端仅提供“Agent 运行失败”，页面保留该说明与 runId，底层模型失败仍需依据运行编号排查。现有 DESIGN.md 与设计索引不同步，属于既有设计元数据漂移，本次不扩展处理。

关联：[清单](../checklists/2026-10-26-chat-failure-feedback-checklist.md)、[提示词](../prompts/2026-10-26-chat-failure-feedback-prompt.md)、[设计](../specs/2026-10-26-chat-failure-feedback-design.md)、[计划](../plans/2026-10-26-chat-failure-feedback.md)、[实现](../implementation/2026-10-26-chat-failure-feedback-implementation-design.md)。
