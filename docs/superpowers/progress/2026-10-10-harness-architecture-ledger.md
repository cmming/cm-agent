# Harness 架构引入进度账本

日期：2026-10-10；主题：harness-architecture；修订：R1；修订日期：2026-10-10；模式：mode=default。

Git基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。R1按用户要求生成当前依赖报告与隔离草案，正式POM/代码未改；无业务实施、提交/推送/部署授权。

## 稳定任务状态

| 编号 | 状态 | 实际证据 | 未执行与继续条件 |
| --- | --- | --- | --- |
| T0 | 完成 | 设计E1–E10、下方核查与六文档检查 | 分析完成，业务未实施。 |
| T1 | 待做 | R1/T9已完成2.0.2候选依赖与API核查 | 未编译/运行，未选生产可用版本；继续二进制、状态/权限/生命周期合同。 |
| T2 | 待做 | 当前策略接口与工厂已定位 | 未实现执行器/模式/白名单/排空门禁；依赖T1。 |
| T3 | 待做 | 当前审批/状态/停止/会话源码已核查 | 新策略合同未运行；依赖T2。 |
| T4 | 待做 | 当前Skill/Python沙箱/产物边界已定位 | Harness工具覆盖与沙箱/下载回归未运行；依赖T2/T3。 |
| T5 | 待做 | 只读上下文来源/冻结/预算/隔离已设计 | 未实现工作区/输入验收；依赖T3/T4。 |
| T6 | 待做 | 已定位测试和运维入口 | 真实模型/浏览器/Rocky/回退未执行；依赖T5。 |
| T7 | 待做 | 仅后续压缩方向 | 首批范围外，T6后另行目标/授权。 |
| T8 | 待做 | 仅后续记忆/子Agent/计划/持续工作区 | 首批范围外，各项另行需求/授权。 |
| T9 | 完成 | 当前effective POM/依赖树、候选12个POM、对照JSON、2.0.2下载/API、示例树及R1六文档 | 仅依赖模型，不代替T1；正式POM和原文件保护，生成物Git忽略。 |

禁止代码修改是本轮范围，不将T1–T8伪标成技术阻塞。分析有足够证据可完成，未来兼容仍未验证。

## R0 实际核查记录，历史保留

| 操作/命令 | 本次结果与边界 |
| --- | --- |
| git status --short、git rev-parse HEAD、git diff --cached --stat | 基线如上，暂存区无变更，原脏文件保护。 |
| 读技能SKILL.md、artifact-contract.md、AGENTS、相关POM/README/configuration/operations | 确认六产物/default模式/授权/Java21/Rocky限制。 |
| codegraph explore：执行器、网关、RunExecutionService、会话与恢复 | 先定位关系和源码；第二次针对Executor/Adapter/Configuration，省略区间再补查。 |
| 定向rg、Get-Content关键源码/POM/配置键与测试 | 形成设计E1–E8；源码状态不当作线上启用证明。 |
| Context7 resolve AgentScope Java与三次query docs | 成功检索默认能力、状态、fromAgent；主库/agentscope-ai-java/agentscope-java，补查官方llms索引，版本需另验证。 |
| 官方Harness架构页、配置文档 | 成功，架构含较新Session Log/EVENT_LOG；最初/harness/overview访问失败后改正确路径，不影响最终来源。 |
| F:/java21/bin/javap.exe检查本机Harness2.0.0 JAR、Builder和-c fromAgent | 确认接口与复制配置/Toolkit；不是与Core2.0.2混用的编译运行证据。 |
| 本机Harness2.0.0 POM | 引用Core2.0.0，仅本地缓存，不声称最新版本。 |

一次PowerShell通配路径的rg返回路径语法错误，改已核实路径和rg --files定位测试；不据失败推断能力不存在。首次文档写入工具调用出现脚本语法错误，未写入任何文件，已改用多行补丁完成。未创建临时代码/日志。

## R0 文档交付检查，历史保留

2026-10-10 文档检查通过：

- PowerShell 校验六文件存在、日期/主题/R0/mode一致、围栏闭合、无模板占位符；31个相对链接均可解析，30个去重关联覆盖每份文档的另五份产物。
- 清单与账本逐项状态一致：T0完成，T1–T8待做；提示词与计划区分首批T1–T6和候选T7/T8。
- 逐文件执行 git -c core.autocrlf=false diff --no-index --check -- NUL <本次文件>，无空白诊断；no-index因新文件存在差异返回1属于预期，不当作测试失败。git diff --check -- docs/superpowers 同样无诊断。
- 五个原有脏文件（.gitignore、console index.html、server application-mysql.yml/application.yml/application-ok.yml）的写入前后SHA256完全一致；Git状态新增仅本组六文件，暂存区无变更，HEAD保持基线。
- 仅人工/文本检查敏感信息与范围；文档未包含真实凭据、配置正文或可用密钥。没有运行模型或业务测试，未提交。

上述检查只证明文档交付与原文件保护，不证明Harness业务能力已实现。

## R0 决定、缺口与修订，历史保留

D1受控渐进接入、D2只读工作区、D3排空切换为默认模式自主建议，未代表用户确认实施。A1框架理解假设；U1版本、U2默认行为/所有权、U3工作区/运行/部署均待验证。

原.gitignore、console index、server application*.yml、.codex/和.workbuddy/不属本包；不复制配置正文。当前核心结论已复核，历史资料只用于定位，不采用旧估算或测试为新验收。

R0：2026-10-10新建分析包，无旧包覆盖、无业务实现/回滚。连续需求修订复用本路径并递增R1/R2；独立实施建立新包引用本R0。

未运行Java/Maven、模型、浏览器、Rocky SSH、Docker、Testcontainers、JDBC/Flyway：仅文档无实现变更，未来T1–T6取得证据。未改release-notes：无发布行为变化。未提交。

## R1 修订与实际依赖核查

来源：用户“以当前项目为准，也可以生成项目依赖”。D4为用户明确约束；D1–D3架构范围保留。R1不是实际Harness接入，新增稳定任务T9，不覆盖T0/R0验收。

| 差异 | 类别 | R0 → R1 | 影响与采用 |
| --- | --- | --- | --- |
| R1-01 | 澄清 | 默认.m2旧缓存线索 → 当前有效POM/实际localRepository/完整树 | E11、D4、U1/T1；已采用 |
| R1-02 | 新增 | 无同版候选证据 → Harness2.0.2发布/下载/API/候选解析 | E12/E13、T9完成；T1运行待做；已采用 |
| R1-03 | 调整 | 可能增加yaml/SQLite → 项目已存在，仅新Harness坐标 | BOM覆盖二进制仍待验证，无无关升级；已采用 |
| R1-04 | 澄清 | 只六文档 → 可生成忽略目录内报告/隔离POM，正式POM未改 | 六文档R1与提示词范围同步，无业务授权；已采用 |

实际环境：JAVA_HOME=F:/java21，Microsoft OpenJDK21.0.11，Maven3.9.4；不是R0其他会话的JDK厂商推断。本地仓库由当前settings.localRepository解析为F:/mnt/f/java/maven-repository。

生成物目录：F:/java/cm-agent/cm-agent-server/target/harness-dependency-review-20261010。下表参数均为实际执行，REVIEW目录统一指该路径；日志不回传配置/凭据，报告不入Git。

| 实际命令 | 结果 |
| --- | --- |
| java -version；mvn -v | JDK21/Maven3.9.4确认。 |
| mvn -B -pl cm-agent-server help:effective-pom -Doutput=REVIEW/server-effective-pom.xml | BUILD SUCCESS，输出当前有效POM。 |
| mvn -B -pl cm-agent-server -am dependency:tree -Dverbose -DoutputType=text -DoutputFile=REVIEW/current-project-dependencies.txt -DappendOutput=true | 八个选中reactor项目成功，完整当前树。 |
| 直接HTTP读取Maven Central metadata和Harness2.0.2发布POM | 2.0.2存在且引用Core2.0.2；较新版存在不驱动升级。web读取器失败后直接HTTP成功，不视为发布阻塞。 |
| mvn -B -f REVIEW/candidate-reactor/pom.xml -pl cm-agent-server -am dependency:tree -Dverbose -DoutputType=text -DoutputFile=REVIEW/candidate-project-dependencies.txt -DappendOutput=true | 八个选中reactor项目成功，正式POM未变。 |
| 两棵树Server段剔除omitted记录，按含scope完整坐标去重对照 | 182→183；added仅io.agentscope:agentscope-harness:jar:2.0.2:compile；removed空，原版本无变化。 |
| mvn -q -N help:evaluate -Dexpression=settings.localRepository -DforceStdout | 当前实际仓库路径如上，旧默认.m2不是当前基准。 |
| mvn -B -N dependency:get -Dartifact=io.agentscope:agentscope-harness:2.0.2 -Dtransitive=false | BUILD SUCCESS，JAR下载到当前仓库。 |
| JDK21 javap读取上述2.0.2 JAR的HarnessAgent与Builder | fromAgent/Store/permission/streamEvents(ctx)/delegate/interrupt/close签名可见，disableWebTools未见；不证明行为。 |
| mvn -B -pl cm-agent-examples/dashscope-mcp-agent dependency:tree -Dverbose -Dincludes=io.agentscope:*,io.modelcontextprotocol.sdk:* -DoutputFile=REVIEW/example-mcp-dependencies.txt | BUILD SUCCESS，示例Core2.0.2与客户端0.17.0保持。 |

当前Server与草案：Core/Provider/Studio2.0.2、MCP core/json2.0.0（聚合mcp0.17.0仍存在）、Jackson2.19.0、compress1.27.1、SQLite3.49.1.0、Testcontainers1.21.0。发布POM的Jackson/SQLite被当前BOM覆盖，依赖解析可通过但需运行兼容测试。旧缓存2.0.0与新线上状态文档不能代替2.0.2契约。

初次2.0.2 javap误用默认.m2路径，找不到类；通过help:evaluate确定实际仓库后复查成功。没有以该路径错误判断API不存在，没有调整正式Maven设置。

R1交付检查通过：

- 六文件修订号R1、修订日期2026-10-10、mode=default一致；31个相对链接有效，围栏闭合。清单/账本T0、T9完成，T1–T8待做；六份均引用T9与当前范围。
- 候选XML可解析，根Harness版本引用现有agentscope.version，adapter为optional、server显式声明；对照JSON仅新增Harness2.0.2，未移除坐标。
- 对新文档逐个运行git -c core.autocrlf=false diff --no-index --check，无空白诊断；git diff --check -- docs/superpowers同样无诊断。
- 三份正式POM及五个原脏文件写入前后SHA256一致；HEAD保持基线，暂存区无变更，Git状态只新增原六文档，未跟踪任何报告或草案。
- git check-ignore确认dependency-comparison.json与candidate-reactor/pom.xml都被target规则忽略。报告中只交付依赖坐标与版本摘要，不将effective POM/日志复制为生产说明。

检查只证明依赖模型、草案和文档一致性，不证明运行兼容、审批或工具安全。

未执行compile/package/test、真实模型/浏览器/SSH/Docker/JDBC/Flyway；没有业务实现或数据库/容器操作。Maven依赖目标BUILD SUCCESS仅证明解析。无发布行为变更，release-notes未改；未提交。

## 关联产物

- [清单](../checklists/2026-10-10-harness-architecture-checklist.md)
- [提示词](../prompts/2026-10-10-harness-architecture-prompt.md)
- [设计](../specs/2026-10-10-harness-architecture-design.md)
- [计划](../plans/2026-10-10-harness-architecture.md)
- [实现说明](../implementation/2026-10-10-harness-architecture-implementation-design.md)
