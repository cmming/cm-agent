# Harness 2.0.2 运行兼容实验账本

日期：2026-10-10；主题：harness-202-compatibility；修订：R0；模式：mode=default。

基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。

## 授权和基线

来源：[原分析 R1 提示词](../prompts/2026-10-10-harness-architecture-prompt.md)。用户最初授权 2.0.4 运行实验，随后要求全部 AgentScope 升级 2.0.4，最终明确改为统一保持 2.0.2；以最后决定为准。正式 POM 未发生升级，旧分析包保留历史。本包独立记录已授权运行实验，继续原 T1，不扩大到生产接入。

“一致 2.0.2”适用于 io.agentscope 构件，不能把其他厂商依赖也改成此版本。Server MCP core/json 保留 2.0.0；独立 MCP 示例客户端保留 0.17.0。Jackson 2.19.0、SQLite 3.49.1.0 沿用项目 BOM 覆盖。

| 编号 | 状态 | 目标 | 依赖 | 验收与证据 |
| --- | --- | --- | --- | --- |
| C0 | 完成 | 核对授权、规范、基线和保护已有修改 | 无 | 正式 POM/源码/配置及原分析六文档 SHA256 不变，HEAD/暂存区未变 |
| C1 | 完成 | AgentScope 同版 2.0.2 依赖核查 | C0 | 十二项目 reactor dependency:tree 成功；Core/两种 Provider/Studio/Redis 2.0.2；实验 Harness 同版 |
| C2 | 完成 | 隔离编译与模型 Stub 运行合同 | C1 | clean test：104 项，103 通过、1 条件跳过；其中 Harness 新增 13 项通过 |
| C3 | 完成 | 记录准入失败与宿主生命周期边界 | C2 | G1/G2 已复现并保留特征测试；不宣称生产准入通过 |
| C4 | 完成 | 实验工程、六份工作包与一致性检查 | C3 | 实验不加入根 reactor，原分析包不覆盖，文档链接/差异检查通过 |

## 当前实际验证

~~~powershell
$env:JAVA_HOME='F:/java21'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
java -version
mvn -v
mvn -B -f experiments/harness-compatibility-2.0.2/pom.xml clean test
mvn -B dependency:tree "-Dincludes=io.agentscope:*,io.modelcontextprotocol.sdk:*" -Dverbose
mvn -B -f experiments/harness-compatibility-2.0.2/pom.xml dependency:tree -Dverbose
~~~

- java -version/mvn -v：JDK21、Maven3.9.4。
- 根 reactor dependency:tree：十二项目 BUILD SUCCESS，Core/OpenAI/DashScope/Studio/Redis 均 2.0.2；Server MCP2.0.0、示例0.17.0。
- 实验 dependency:tree：BUILD SUCCESS；Harness2.0.2、Core2.0.2、两 Provider2.0.2；Jackson2.19.0、SQLite3.49.1.0。
- 实验 clean test：BUILD SUCCESS；104 tests，0 failures/errors，1 skipped。Harness13通过；其余现有91项中90通过，外部 AgentScopeExternalStopTest 需要 CM_AGENT_EXTERNAL_STOP_TEST=true/外部凭据，本轮未启用。
- 原首轮失败与最终缺陷特征见实现说明，不把改写特征断言算作业务修复。
- 当前 tracked 文件及原分析六文档 SHA256 与实验前一致，HEAD6752bf88保持，暂存区为空。
- 新六文档与实验 README 相对链接/模式检查通过；新增实验 POM/Java/文档逐个 diff --no-index --check 无诊断。全仓 git diff --check 仍报告原 application-mysql.yml 第19行已有空行，已用哈希确认未改变该用户文件；不修复无关改动。
- target 下日志、依赖树、测试报告已确认被 Git 忽略；新实验 Git 可见文件仅 POM、README 和一份测试源码。

## 证据位置

报告统一位于 `F:/java/cm-agent/cm-agent-server/target/harness-202-compatibility`：`clean-test.log`、`project-agent-dependencies.txt`、`project-dependencies.log`、`experiment-dependencies.log`、`before-hashes.json`、`protection-check.json`。

实验 `target/surefire-reports/TEST-com.cmagent.agentscope.Harness202ContractTest.xml` 与 `target/dependencies.txt` 可复查。实际2.0.2发布源码来自当前 Maven 仓库 sources.jar；CodeGraph 首先定位，未覆盖区间再读源文件。下载缓存不进入 Git。

## 准入失败与未执行验收

同版编译及限定 Stub 路径可运行，**生产准入未通过**。实验完成不等于原分析 T1 的全部安全验收通过，更不等于 T2–T6 已实施。

- G1：仅使用 Builder 禁用配方仍留下 `wait_async_results`。实验在模型调用前移除源 Toolkit 白名单外工具，并保留未过滤特征测试；正式执行器尚无此逻辑。
- G2：HIGH 的显式 ASK 规则优先于 `DONT_ASK` 默认决策，无会话请求仍产生 `RequireUserConfirmEvent`。最终绿色测试记录该缺陷特征，不把它视为业务验收通过；此前“无会话不暂停”的断言确实失败。
- G3：`close()` 不删除状态槽；`disableSessionPersistence()` 配方保留显式 Store 的 ASK/恢复。终态删除仍由 CM Agent 宿主负责，实验使用 InMemory Store，不验证数据库加密/TTL。
- G4：Harness 2.0.2 没有带 RuntimeContext 的 interrupt 重载，实验通过 `getDelegate().interrupt(context)` 实现本轮中断。
- G5：默认消息总线/Inbox 中间件仍存在；关闭默认工作区 Skill 也不等于停止共享 skills 目录扫描。当前只使用每例私有空临时目录；未经验证的共享工作区、收件箱注入与保留期不能直接上线。

本次环境：Microsoft OpenJDK 21.0.11、Maven 3.9.4；实际 Maven 仓库 `F:/mnt/f/java/maven-repository`。模型仅为 127.0.0.1 合成 SSE 服务，凭据为无效测试占位；没有访问用户模型配置。

未执行真实模型、DashScope 网络调用、Studio 发送、MCP 联网、浏览器、服务端 API、SSH/Rocky、Docker、Testcontainers、JDBC/Flyway 或 Python 沙箱。当前实验不改变这些层，相关验证属于后续 T2–T6。

原 T1 安全门禁未全部满足；原 T2–T6 未实施，G1/G2 待正式修复。未更新发布说明，因为没有生产代码/依赖行为变化。未提交。

## 关联产物

- [清单](../checklists/2026-10-10-harness-202-compatibility-checklist.md)
- [提示词](../prompts/2026-10-10-harness-202-compatibility-prompt.md)
- [设计](../specs/2026-10-10-harness-202-compatibility-design.md)
- [计划](../plans/2026-10-10-harness-202-compatibility.md)
- [实现说明](../implementation/2026-10-10-harness-202-compatibility-implementation-design.md)
