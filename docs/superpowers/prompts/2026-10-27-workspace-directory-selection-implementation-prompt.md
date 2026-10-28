# 工作区目录选择执行提示词（R2：可信Windows系统ACL兼容）

日期：2026-10-27；主题：workspace-directory-selection-implementation；当前修订：R2；修订日期：2026-10-28；模式：mode=default。

## 2026-10-28 R2实施交付与当前验收

用户在R2文档交付后明确要求“继续后面的步骤”，已按mode=default实施T16–T18；当前R2增量完成，未提交、未推送、未合并、未部署。未重启用户8080服务，未修改用户reference/imports、YAML、.env、.gitignore、.workbuddy或无关脏改动。R1 T11–T14整体门禁仍未完成，不能用本次ACL通过替代原生窗口、真实Provider或原IMPORTED双库门禁。

### 实际实现

- `WorkspaceContextFiles`：Windows登记资料改为固定根所有者+SYSTEM/本机Administrators的SID策略，覆盖根、实际选中中间目录和文件；读取前后权限指纹复核，保留路径/文件身份和有界读取检查。POSIX仍走原所有者私有根；未将Windows策略共享给IMPORTED、Skill或Harness。
- `WindowsWorkspaceAclPolicy`：纯SID决策、NULL/缺失/空DACL与未知ACE拒绝、固定根信任集合、每轮重新核验；不按账户名匹配，不任意信任子文件所有者，其他ALLOW仍拒绝。所有record组件、枚举常量和安全/资源/异常逻辑已落地中文注释。
- `WindowsSecurityDescriptorReader`：仅Server新增`net.java.dev.jna:jna:5.17.0`核心依赖，不引入jna-platform。最小只读GetFileSecurityW等映射取得原生SID；缓冲区最多128KiB、ACE最多4096，Memory由try-with-resources关闭、SID字符串由LocalFree释放，DLL函数表延迟加载。生产接口无写ACL、Shell、提权或辅助进程。
- `WorkspaceContextService`：准备/恢复交付前、ASK保留前检查Windows原路径权限；仍使用原加密正文。受控拒绝沿用WORKSPACE_CONTEXT_INVALID；原生组件、身份解析、结构、资源释放或超时的基础设施失败沿用WORKSPACE_CONTEXT_UNAVAILABLE，具体固定中文原因和稳定reason与同号errorId日志关联；原始系统消息、路径、正文、完整ACL/SID列表不进入响应/诊断。
- 两个新测试类为`WorkspaceWindowsAclPolicyTest`与`WindowsWorkspaceAclTest`；扩展`WorkspaceContextServiceTest`及`HarnessWorkspaceContextFlowTest`，ReAct继承同一真实合同。只在自建随机根写合成ACL并恢复/清理，不操作用户真实目录。README、configuration、operations、release-notes已说明适用范围、部署JNA/重启、保留可信系统权限、普通组仍拒绝和仅回退R2的边界。

### 实施细化（不新增需求修订）

| 编号 | 采用与依据 |
|---|---|
| E4 | SID解析采用JNA核心5.17.0+最小只读Win32映射，而不是jna-platform、账户名或Shell。实际依赖树只新增这一项；重打包后BOOT-INF/lib包含jna-5.17.0.jar |
| E5 | 源文件删除仍保留R0原快照恢复合同：恢复不读正文，只检查仍存在的原选中路径及存活祖先；根缺失/不可信或仍存在对象ACL无法核验则拒绝。删除不是将无法解析的现存安全描述符当成成功；不读取替代文件 |
| E6 | 旧Windows合成夹具仅设根私有、无OI/CI继承，新增逐文件检查暴露默认子ACL；仅修正测试根继承。真实继承夹具使用测试专用SetNamedSecurityInfoW按Windows规则传播，不在生产暴露写接口 |
| E7 | 本机脏application已启用imports并使用占位符，旧Harness夹具无法解析CM_AGENT_WORKSPACE_IMPORT_ROOT；改为独占随机空输入卷/内存随机密钥并关闭该夹具导入，结束清理，不修改用户配置 |
| E8 | 正文读取与恢复/交付前权限复核各阶段使用现有read-timeout，在原生调用前后及循环检查；不把模型/审计等待算成文件读取预算，不能抢占已经进入内核的单次调用；超时结果不交付，无后台重试。仅验证当前Windows/JDK21组合，未声称所有OS/语言/架构均实测 |

### 最终验证与证据

| 环境/命令 | 实际结果 |
|---|---|
| 本机`java -version`、`mvn -v` | Microsoft JDK21.0.11，Maven3.9.4；JVM报告Windows10/amd64 |
| `mvn -q -pl cm-agent-server dependency:tree -Dincludes=net.java.dev.jna:*` | 最终解析JNA5.17.0，未引入jna-platform；POM仅server变化 |
| Java21定向：`mvn -q -pl cm-agent-server -am -Dtest=WorkspaceWindowsAclPolicyTest,WindowsWorkspaceAclTest,WorkspaceContextServiceTest,HarnessWorkspaceContextFlowTest,ReActWorkspaceSelectionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test` | 80项，失败/错误/跳过均0；分别10/8/18/22/22项，PowerShell实际调用时含点或逗号的-D参数整体加引号 |
| 真实Windows NTFS/原生API | 所有者+SYSTEM+Administrators及真实继承成功；普通组在根/子目录/文件的权限拒绝；NULL/空DACL拒绝；OS DENY不绕过；128次读取后移动文件成功；模拟释放失败实际先释放并准确分类 |
| 两策略真实Spring/AgentScope+回环模型 | SELECTED首发及原快照审批成功；审批后普通主体获得写权限时，恢复失败、模型请求不增加、工具副作用为0；并非真实外部Provider验收 |
| `ssh rocky`指定Maven3.9.9/JDK21.0.7容器定向 | 80项，失败/错误0，Windows条件跳过15项（65执行通过）；不计作Windows通过 |
| 同容器`mvn -q -pl cm-agent-server test` | 993项，失败/错误0，条件跳过97项（896执行通过）；未开启R1显式live/JDBC门禁，不能替代T13/T14未验部分 |
| 同容器`mvn -q -pl cm-agent-server -DskipTests package spring-boot:repackage` | 成功；可执行JAR确认包含JNA5.17.0。单独执行repackage因未在同生命周期执行package失败，已按正确命令重做；未改变全局打包插件 |

Rocky目录`/tmp/cm-agent-workspace-acl-r2-20261028-8c21ef`，HEAD为`369418b3bdc08b096de7854f9d04c982ec8be050`；108个实际脏源码文件，最终清单SHA256为`b2c369a7fd4e6eb3f562251b5fdad0db7a5a2ea55fe2529c8574f506f00f48a4`，解包后逐一touch，UTC时间`2026-10-28T08:26:19Z`，测试/打包后逐文件内容哈希复核一致。只传必要源码，未传用户YAML/.env/.workbuddy。中间完整Server992项/跳过96项通过后新增具体错误分类，故重新验证最终Windows80项和Rocky993项，不拿中间快照替代最终补丁。

本机忽略目录`target/workspace-acl-r2-implementation-20261028`保存baseline、windows-final.log、windows-summary.json、remote-summary.json、两次源清单和校验脚本；不提交日志或生成物。早期命令有PowerShell未整体引用-D点号参数造成Maven解析失败，已修正；最初Windows夹具继承断言和旧子ACL失败、Spring imports占位符失败均如上修正，失败轮不计为通过。

### 影响、限制与直接后续

无新增API字段、schema、迁移或配置键；当前最高迁移仍V25，历史迁移未改。实际服务使用新代码前须重新加载Maven依赖/构建产物并重启Server；本次未替用户重启或部署。保留SYSTEM/Administrators不表示Everyone/Users/Authenticated Users的ALLOW也可保留；仍需部署者核对自己的专用目录权限，应用不会自动修复ACL。

未修改现有浏览器设置，未重新操作用户聊天页或原生文件夹窗口；前端未变，不重跑JS/视觉套件。外部Provider未调用，其他Windows版本/语言与32位/ARM未实测，不将本机和Rocky测试扩展成这些环境通过。R1 T11–T14尚未完整收口；后续先核对其未验清单，不重复已完成T16–T18。未提交、未推送、未合并、未部署。

## 当前修订与授权边界

用户新增优化点：“允许可信的 SYSTEM、Administrators 权限”。需求修订后用户已明确要求继续，现已实施并验收R2 T16–T18；真实目录ACL仍未修改；后续读取提示词不额外授予修改、提交、推送、合并、部署或外部消息权限。当前采用D1–D6/B1–B8及新增D7–D10/B9–B11；mode=default，普通工程取舍按证据自主记录，用户后续显式模式切换优先。

复用2026-10-27/workspace-directory-selection-implementation原六路径，当前修订为R2，实际修订日期2026-10-28。R0/R1全文在本文件历史区逐字保留，其中“当前”“待做”“已完成”“最高迁移”等仅代表形成时刻，不能覆盖本节。T0–T14不删除、不回收，R2新增T15–T18。T0–T7保留R0历史，T8–T10保留原完成证据；T11–T14已进入实施、有部分代码或验证记录，但整体验收未完成，本轮不重新核定通过。T12/T13单选缺陷回归177项JS/16项控制台资源是R1局部历史，不能替代新ACL门禁。

## R2有效范围与安全约束

- 仅调整Windows服务器登记参考资料读取链：DEFAULT的bindings、SELECTED的catalog以及该资料链的审批恢复。读取及恢复均复核当前ACL；权限变得不可信后必须阻止继续，不回退DEFAULT/NONE。原Run审批仍只使用原正文快照。
- 保留可信部署目录所有者的现有权限；额外认可Windows固定SID的LocalSystem（S-1-5-18）和本机内置Administrators（S-1-5-32-544）。显式/继承的可信权限项都不能仅因不是所有者而被拒绝；真实读取仍受Windows访问控制约束，不能绕过DENY或提权。
- 其他主体的ALLOW继续按原策略拒绝，尤其Everyone、Users、Authenticated Users的写入/修改/删除/改ACL权限。仅放开两个明确的可信系统身份，不顺带开放普通用户只读，不增加任意信任账户名单或跳过校验开关；同名自建/域账户、名称后缀、大小写及本地化名称不能冒充固定SID。
- Windows检查受信根、根内实际选中的中间目录及明确登记文件，不能只看根就假定子文件继承正确。信任基线固定为受信根所有者及两个固定SID，不能把每个子文件的任意所有者自动加入信任集。不遍历未选文件，不要求盘符根/应用外祖先也变成所有者私有；所有检查保持当前运行读取预算、有界执行和资源释放。
- 读取仍存在对象的ACL/安全描述符或SID失败、未知ACE语义、缺失安全视图时失败关闭，不按账户显示名降级；NULL DACL的开放含义和空DACL的拒绝含义必须区分，不得因没有枚举到ALLOW而直接认定安全。不实现完整Windows有效权限计算器，不依赖DENY抵消未知ALLOW来放行。
- POSIX策略不变；IMPORTED私有输入密文、Skill产物存储及Harness临时隔离目录不随本轮放宽。原路径越界/链接/网络卷、文件显式清单、类型/UTF-8/预算、租户及owner/RBAC、加密快照/审批/停止边界全部保留。不创建Shell/helper能力，不自动修改任何生产ACL或要求关闭继承才能运行。

## 稳定任务状态

| 编号 | 当前状态 | 交付/依赖与验收 |
|---|---|---|
| T0–T10 | 完成（历史范围） | 仅保留原对应证据；ACL变化关联T16–T18，不能拿旧通过替代 |
| T11–T14 | 进行中（整体验收未完成） | 延续R1在途实现及未验门禁；本次不重做已完成部分，也不声称全部未实施 |
| T15 | 完成 | R2源码/规范/官方资料核对、差异与六文档修订、历史/链接/范围检查；仅文档完成 |
| T16 | 完成 | 依赖T15：Windows可信SID解析与有界ACL策略接入DEFAULT/SELECTED读取/恢复，所有权与资源生命周期明确 |
| T17 | 完成 | 依赖T16：纯策略/Windows真实NTFS/错误脱敏及原边界回归，验证普通主体仍被拒绝 |
| T18 | 完成 | 依赖T17：Windows实际运行与审批ACL变更证据、生产配置/运维/README/发布说明及回退说明；更新六文档 |

## 当前有效执行提示词（R2）

下方text代码块是唯一当前有效实施输入；历史区所有R0/R1提示词只供追溯。读取或生成本提示词不等于授权执行。用户后续已授权实施，T16–T18现已完成；以下为当前交付合同和后续核对输入，不重复实施。

```text
仓库：F:/java/cm-agent；工作包：2026-10-27 / workspace-directory-selection-implementation / R2；mode=default。
开始先读AGENTS.md、相关POM/README/生产说明与以下六份当前文档：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-27-workspace-directory-selection-implementation-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-27-workspace-directory-selection-implementation.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-27-workspace-directory-selection-implementation-ledger.md

用户已授权并完成R2 T16–T18；T15文档完成，T0–T10历史保持。R2当前无未实施任务，不能重复按历史R1/R2规划块实施或把旧owner-only规则当成新目标。读取提示词进入问答不自动授权修改、重启、提交或部署；后续用户明确要求实施时依据新指令。mode=default自主核实并记录普通工程取舍，用户后续模式切换优先。

当前交付：Windows DEFAULT/SELECTED只读参考根所有者、固定SID S-1-5-18的SYSTEM及S-1-5-32-544的本机内置Administrators允许；其他ALLOW保守拒绝。JNA核心5.17.0只在Server，原生描述符/ACE/SID有硬界并关闭资源；不按账户名匹配、不增加Shell/helper或任意白名单、不修改真实ACL。POSIX和IMPORTED/Skill/Harness存储策略不变。Windows实际选中路径ACL读前读后及恢复/ASK核验；删除源文件保留原快照，只检查仍存在的原路径及存活祖先，不重读或替代正文。

保持原路径/链接/文件清单/类型/UTF-8/预算、tenant/owner/RBAC、加密快照和审批/停止约束。受控ACL拒绝WORKSPACE_CONTEXT_INVALID，原生基础设施失败WORKSPACE_CONTEXT_UNAVAILABLE，具体固定中文原因、稳定reason及同号errorId；不输出原始路径、正文、完整ACL、账户列表或凭据。所有record组件/枚举、安全与资源分支保持实际中文注释。

当前验证：本机Java21定向80项全部通过；Rocky指定Maven21容器定向80项中15项Windows条件跳过，完整Server993项中97项条件跳过，失败/错误0；最终108文件补丁哈希和打包JNA已核对，见账本。跳过不算通过，回环模型不等于外部Provider；新证据不能替代R1 T13/T14原生窗口、显式JDBC及真实Provider门禁。无schema变化，不修改历史V22–V25。

如果用户继续原工作包：先核对R1 T11–T14实际在途代码和缺口，不把它们重置成全未实施或全已完成；保留R2和目录单选修复，针对未验项推进。JDBC/Flyway/Testcontainers及完整Server仍只ssh rocky+maven:3.9.9-eclipse-temurin-21、PG16-alpine/MySQL8.4，核对HEAD+实际脏补丁SHA256和解包时间。真实Windows/浏览器未具备环境时如实记录，不绕过Work的浏览器访问设置，不把setInputFiles或Linux跳过当实际系统窗口/NTFS通过。

保护配置/.env/.gitignore/.workbuddy和无关脏改动。不要用删除检查点、放宽ACL或重建会话掩盖失败；不要修改用户真实reference/imports。没有用户明确要求不重启8080、不提交、推送、合并、部署或外发消息。更新同一六份文档并保留R0/R1历史，未提交写“未提交”，按变更、实际验证、影响、风险和直接后续交付。
```

## R2规划阶段证据（历史，非当前实现状态）

1. HEAD为369418b3bdc08b096de7854f9d04c982ec8be050，暂存区为空，工作树存在大量既有业务/配置改动。只读AGENTS.md、相关POM/README/configuration/operations和六文档；存在.codegraph，先用codegraph explore定位，新增类未被索引覆盖时定向复核。生产源中WorkspaceContextFiles.requirePrivate仍拒绝所有非所有者ALLOW，R2尚未生效。当前最高迁移重查为V25；本轮无schema变化，不修改历史迁移。
2. WorkspaceContextFiles.read现只对根调用requirePrivate；Windows子文件可有独立安全描述符。WorkspaceContextService.failure已按Invalid、Limit、基础设施等分类并沿用runId作为errorId；R2应复用边界，不能把原始路径、ACL、账户列表或本地API消息放进日志/响应。
3. 本轮只读探针命令：F:/java21/bin/java.exe target/workspace-native-r1-remaining-20261028/WindowsPrincipalLookupProbe.java。当前Windows Java21对S-1-5-18、S-1-5-32-544调用公开lookupPrincipalByName均返回UserPrincipalNotFoundException。这只说明此环境不能直接将SID字符串当账户名查询，不代表其他Java实现或Windows API均不能解析。未改ACL、未读取资料正文。
4. Microsoft固定SID及文件安全描述符资料已核对；JNA上游提供Windows安全描述符/SID相关API，但本轮只作可行性源码核对，不代表选定依赖版本已经编译或通过真实运行。T16必须先确认实际依赖树和目标版本源码，再完成资源释放与Windows实测。
5. 本轮不运行业务测试、模型、数据库、系统窗口或完整Server；不把既有ACL拒绝探针、R0/R1测试数字或Linux跳过的Windows用例记成R2通过。T16–T18全部未实施、未验收；真实Windows用户环境保持不动。未提交。

## 核对来源

- [Windows固定SID定义](https://learn.microsoft.com/en-us/windows/win32/secauthz/well-known-sids)：SYSTEM和本机Administrators的稳定身份，不以界面显示名代替。
- [Windows文件安全与访问权](https://learn.microsoft.com/en-us/windows/win32/fileio/file-security-and-access-rights)：父目录和子文件安全描述符、实际OS读取权限。
- [JNA Windows工具上游源码](https://github.com/java-native-access/jna/blob/master/contrib/platform/src/com/sun/jna/platform/win32/Advapi32Util.java)：仅作原生描述符/SID适配可行性线索，目标版本实施时重新核实。

## 当前关联产物

- [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
- [需求设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
- [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
- [实际实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
- [进度账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)

## R1 历史原文（R2修订前全文，禁止作为当前实施提示）

# 工作区目录选择执行提示词（R1：用户电脑原生文件夹）

日期：2026-10-27；主题：workspace-directory-selection-implementation；当前修订：R1；修订日期：2026-10-27；模式：mode=default。

## 2026-10-28 当前补充：T12/T13目录单选缺陷修复

本节记录用户报告的chat.html“可选目录选中后无法互相取消”缺陷，沿用2026-10-27/R1、mode=default与原T0–T14编号。本次为T12/T13局部修复，不新增需求修订、不宣布整个T12/T13或T14门禁完成。此前已经进入T11–T14实施，下面T10标题及“待做/未实施”文字保留为形成当时的阶段历史，不能据此否认已有在途代码，也不能据本次局部通过宣布R1整体完成。未提交。

根因：app.js的element()只处理className/text/type/value，选择器传入的name未落到DOM；默认、不使用及登记项因此不在同名单选组。已在bindWorkspaceChoice中显式设置radio.name="workspace"，恢复浏览器原生互斥与键盘分组语义。重复点击当前单选项仍保持选中；清除资料来源须选择“不使用工作区”并保存。点击取消/关闭只丢弃草稿，再打开恢复已保存来源。

共享app.js缓存版本由2.0.37升为2.0.38，九个v2页面与ConsoleResourceTest保持一致；不修改全局element()行为、业务API、数据库、配置、权限、上传或审批流程。保护已有配置、.gitignore、.workbuddy及无关脏改动。

验证：node --check app.js通过；node --test cm-agent-console/src/test/js/*.test.cjs共177项全部通过，含4项新增接线回归；Java21.0.11/Maven3.9.4执行mvn -q -pl cm-agent-console -am test，ConsoleResourceTest共16项全部通过。JSDOM26.1.0加载真实chat.html及真实element/bindWorkspaceChoice：修复前可同时选中DEFAULT、NONE、product，修复后始终唯一选中，NONE保存、取消恢复及搜索重建通过；这不是实际浏览器或原生窗口验收。当前localhost:8080的chat.html和app.js均HTTP200，已提供2.0.38引用和显式分组修复。

现场浏览器验证未执行：cua.getBrowser返回“Local browser use is disabled in Work settings”，没有改设置或经其他UI通道绕过。未对当前运行服务重启、登录或保存用户会话。detector扫描原chat.html得到9项提示（2 warning/7 advisory），涉及既有颜色、卡片伪元素条纹与阴影，本次无布局/CSS改动，不扩展处理。DOM验证不能替代当前用户页面、390px或键盘实测；这些保留为未验证项。后端/双库/Provider未因本次前端局部修复重跑，历史通过不替代R1整体门禁。

后续执行者先读取本节与账本，当前单选缺陷已修复，不重复实施。针对现场验证，确认页面使用app.js?v=2.0.38，再验证DEFAULT→登记项→NONE仅一项选中、取消恢复及键盘切换；浏览器访问仍关闭时如实记录，不绕过设置。本提示词不额外授予修改配置、提交、推送或部署权限。

## 2026-10-28当前实施状态（R1 T10）

当前采用D1–D6/B1–B8，mode=default；继续复用2026-10-27/R1六份路径，不生成新修订或回收任务编号。T0–T7保留R0完成历史，T8文档完成，T9存储基线完成，T10业务及对应验收完成，T11–T14待做。T9及原文档阶段记录保留，下方旧“未实施”只表示其形成当时状态，以本节为当前状态。

本轮按用户再次“继续下一步”实施T10：capabilities、明确确认后的manifest创建、原始正文逐文件上传、complete、GET、撤销，以及IMPORTED派生会话/Run/消息/审批/技能记录/停止入口的owner保护。列表在游标和LIMIT前过滤；私有资源对外复用原路由通用404 code/message，避免存在性探针，原三态权限保持原语义。所有身份只取认证JwtSession。源绑定仍失败关闭，READY的bindable及runtimeAvailable仍false；原生窗口、浏览器预览勾选和真实IMPORTED模型输入仍待T11–T14。

T10实现细化E1：为满足“创建响应丢失也能先GET核对”，新增requestId、GET by-request和双库V24；importId/fileId仍由Server生成，历史V22/V23不改。E2：采用有界原始正文而非multipart，5秒总等待、4个真实读取槽；E3：拒绝审计在外层短事务退出后完成，避免被回滚或独立事务自锁。上述为原需求内的工程细化，不是假称用户新增确认。

本机Java21定向52项，失败/错误0，跳过1项（Linux符号链接已由Rocky通过）；Rocky持久化全套70项无失败/错误/跳过，完整server 929项、失败/错误0、条件跳过41项，额外MySQL旧运行合同20项无失败/错误/跳过；新增拒绝回滚后审计PG/MySQL两项通过。旧历史数字不替代本轮证据，跳过不算通过，真实Provider及原生窗口未验。未提交、未推送、未部署。

## 2026-10-28 T9完成记录（历史阶段）

本节保留T9交付当时记录，当前T10状态以文首为准；需求仍采用D1–D6/B1–B8，工作包日期/修订保持2026-10-27/R1，mode=default。T0–T7历史完成、T8文档完成、T9业务及对应验收完成、T10–T14待做。最新最高迁移是V23，不修改V22/V23历史。

T9新增IMPORTED、不可变manifest、共享仓储规则、memory/JDBC配额/状态/租约、独立AES-256/GCM私有输入卷、补偿GC和双库V23/中文注释。WorkspaceSelectionService在make/resolve/view显式拒绝尚未接入的IMPORTED，不回退DEFAULT；HTTP409/WORKSPACE_UNAVAILABLE、errorId/日志/严格审计与模型未调用均已验证。共享JS保留不支持来源身份并阻止保存/发送，不显示为默认映射；原三态保持旧语义。

当前仍没有上传API、原生目录窗口、浏览器预览/确认上传、IMPORTED派生会话/Run接线；T10–T14仍须后续实施及验收。T13/T14整体门禁没有完成，R0的895/66/155不能替代。本机Java21定向测试44项，失败/错误0、跳过1项；Rocky持久化全套68项及server相关58项，失败/错误/跳过均0。命令与证据见账本。未提交。

R1于2026-10-27完成T8文档修订；2026-10-28完成T9，用户再次要求“继续下一步”后实施T10，当前T10业务及对应验收完成。保留R0与T9历史，T11–T14仍待做；mode=default，不因读取提示词自动扩大范围。未提交、未推送、未部署。原分析包保留于 [原分析提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)。

本文件当前有效执行输入为下方R1代码块。读取/问答不会自动实施；2026-10-28已获授权实施的下一步T9已完成。文末R0引文只保留历史，不作为当前执行命令，不得把R0的“无新增失败/不含本机选择”覆盖当前R1范围。

```text
仓库：F:/java/cm-agent；工作包：2026-10-27 / workspace-directory-selection-implementation / R1；mode=default。
用户新增要求：支持用户电脑操作系统原生文件夹选择。
开始先读AGENTS.md、相关POM/README/生产配置说明，以及以下六份当前文档：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-27-workspace-directory-selection-implementation-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-27-workspace-directory-selection-implementation.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-27-workspace-directory-selection-implementation-ledger.md
T0–T7是R0已完成历史；T8为本轮文档修订完成；2026-10-28连续实施后，T9/T10已完成；T11–T14待做，当前后续从T11开始。生成提示词不授予实施权限；后续按用户实际指令推进。普通工程取舍自主核实并记录，用户后续显式模式切换优先。

采用当前设计D1–D6和B1–B8：提供“服务器登记目录 / 我的电脑”两入口。支持时由真实点击同步调用showDirectoryPicker({mode:'read'})，不支持则以webkitdirectory目录input打开系统目录框；都不支持则明确提示。不能以网页dialog、手填路径、单文件框或setInputFiles冒充原生选择。capabilities预加载；不在用户激活前等待网络；取消不自动弹另一窗口。

原生选择后仅浏览器内有界列出候选，文件默认不勾选；用户勾选并明确确认上传前，不向Server发送文件名、manifest或正文。保持现有.md/.txt/.json/.csv和4文件/16KiB单文件/32KiB总量默认及硬界；超限明确提示，不静默截断。句柄只在当前页面内存，不持久化或发往Server。服务端远程不接收本机绝对路径，不读取浏览器电脑磁盘；首版不做helper、同步、写回、Shell或整项目上传。

T9领域/仓储/受控加密存储与V23迁移已完成，不重复实施；T10已完成API和owner隔离（含requestId/GET by-request及V24），不重复实施；后续从T11真实Run/加密快照/审批/停止 → T12前端原生流程 → T13自动化双库 → T14真实窗口/Provider及生产说明推进。
T9：新增IMPORTED，workspaceId显式作为importId，由mode隔离命名空间；扩展所有switch/校验/元数据，不能回退DEFAULT。manifest和加密对象不可变。DRAFT/READY/REVOKED/EXPIRED、原子配额、期限、租约/GC/补偿按设计落地；客户端路径/摘要/长度全部复核，实际读取有界。最高迁移现为V24，后续重查新版本，禁止改历史V22/V23/V24；两库同语义及中文表字段原生注释。
T10（已完成合同保留）：按设计表提供capabilities、创建manifest、Server fileId上传、complete、GET核对、DELETE撤销；DRAFT不可绑定。可信tenant/principal只取认证JwtSession，导入及IMPORTED派生会话/Run仅原主体可见；列表/消息/审批历史/详情所有入口校验，跨tenant/owner/Agent统一404，原三态权限保持。新增失败状态/code/errorId、严格审计和同号脱敏日志均测试。
T11：首发与改绑沿用同一控制锁/JDBC行锁与短事务，文件/模型在事务外。Run冻结来源/批次/manifest，读取专用加密输入存储，不向原WorkspaceContextFiles传用户路径。审批恢复只原快照、复核撤销/期限/缺失，兼容v1/v2；需要新守卫明确版本，不把IMPORTED当旧记录。停止和GC不破坏其他在途引用。
T12：真实chat.html与共享app.js/console-core.js，保留受控工作台及旧DOM；来源、预览/排除/预算/确认上传/进度/READY绑定/刷新恢复均可操作。取消失败保留旧绑定；会话首发后锁定；结果未知先GET，写请求不自动重试，不重复创建会话掩盖失败。sessionEpoch及代次覆盖系统窗口、枚举、读取、上传和保存迟到响应。键盘/Esc/焦点、aria-live和390px验收，跨页/登录/v1/审批/停止/产物回归。
T13：Java21快速/MockMvc/两策略/JS行为/错误脱敏；JDBC/Flyway/Testcontainers与完整server只ssh rocky + maven:3.9.9-eclipse-temurin-21，PG16-alpine/MySQL8.4。核对HEAD+实际脏补丁SHA256和解包时间戳；两库迁移/列注释、配额并发、密文及新JVM恢复通过。每个record组件和枚举、安全/事务/资源/异常逻辑实际中文注释。
T14：Windows Chrome/Edge实际原生窗口和取消/选择，API不支持的目录input降级、双方不支持提示、HTTPS/远程Server与用户电脑不同机，确认前零文件数据网络证明。Playwright setInputFiles不等于系统窗口通过；其他OS/手机环境未测就如实记未验证。真实Provider两策略能区分上传A/B、审批和停止冻结语义。更新README/configuration/operations/release-notes，说明文件离开电脑、私有存储/密钥/期限/配额/撤销及IMPORTED排空回退。

全程保护已有配置/.gitignore/.workbuddy及所有无关脏改动；存在.codegraph先用CodeGraph，缺失再定向读源码；新增路径按现有模块复核。资料/密钥/Token不得打印、写日志或提交。R0的895/66/155等历史结果不能替代R1门禁。遇外部环境阻塞写确切原因和未验范围，继续独立工作，不伪造通过。
保持六份当前R1文档和稳定任务编号一致，保留R0历史与本轮差异，记录实际命令和业务/验证状态；未提交写“未提交”。最终按变更摘要、验证命令/结果、影响、风险、直接相关下一步交付；不自行提交、推送、合并、部署或外部发消息。
```

## 关联产物

- [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
- [需求设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
- [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
- [实际实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
- [进度账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)

## R0 历史原文（仅历史，不作为当前执行命令）

下方保留本轮修订前全文，状态及限制仅在R0范围内有效。当前范围、待办和授权以本文上方R1为准。

> # 工作区目录选择实施提示词
>
> 日期：2026-10-27；主题：workspace-directory-selection-implementation；修订：R0；模式：mode=default。
>
> 来源：[原分析执行提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)，保留原分析六份产物及 T0 历史，不以实施结果改写其“尚未实施”事实。Git 基线 `369418b3bdc08b096de7854f9d04c982ec8be050`。未提交、未推送、未部署。
>
> 本文件是当前实施记录的后续执行输入；生成/读取它不授予提交、推送或部署权限。
>
> ```text
> 仓库：F:/java/cm-agent；模式：mode=default。
> 先读取本组六份实施文档和原分析提示词 docs/superpowers/prompts/2026-10-27-workspace-directory-selection-prompt.md。
> 清单：docs/superpowers/checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md。
> 账本：docs/superpowers/progress/2026-10-27-workspace-directory-selection-implementation-ledger.md。
> T0–T7 已完成本轮实施及验收，当前没有未处理已知失败；先核实账本证据，不重复实施。后续仅处理用户明确提出的本需求问题，保持未提交/未部署边界。
> 保持原 A1–A6、DEFAULT/SELECTED/NONE、可信tenant/权限/审计、会话首发控制锁与JDBC行锁、Run身份及加密正文冻结、v1守卫兼容及v2元数据丢失拒绝。不得开放任意宿主路径、写文件、Shell或Skill宿主挂载。
> 保护原配置/.gitignore/.workbuddy及发布说明既有改动。存在 .codegraph 时先用CodeGraph，未覆盖再定向查源码。新Java安全/事务/资源/异常逻辑与record逐组件按规范落实中文注释。
> Java21验证；JDBC/Flyway/Testcontainers只允许ssh rocky，镜像maven:3.9.9-eclipse-temurin-21、PostgreSQL16和MySQL8.4。远程HEAD及源码补丁逐文件SHA256一致后验证。解包时间戳不得导致Maven复用旧class；不使用本机Docker或清理无关容器。
> 真实Key仅用于验证，不打印/落文档/提交。区分协议桩、真实Provider、浏览器与新JVM恢复证据。错误HTTP/code/errorId和后台同号脱敏日志必须一致，写请求不自动重试。
> 同步六份文档中的实际结果与遗留；外部阻塞记录确切原因，不伪造通过。未提交写“未提交”。按仓库规定输出变更摘要、验证、影响、风险和直接相关下一步。不得自行提交、推送、合并、部署或外部发消息。
> ```
>
> ## 关联产物
>
> - [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
> - [实施设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
> - [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
> - [实际实现](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
> - [实施账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)
