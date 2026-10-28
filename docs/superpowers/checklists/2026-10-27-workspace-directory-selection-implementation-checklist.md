# 工作区目录选择实施清单（R2：可信Windows系统ACL兼容）

日期：2026-10-27；主题：workspace-directory-selection-implementation；当前修订：R2；修订日期：2026-10-28；模式：mode=default。

## 2026-10-28 本地提交记录

用户明确要求“将修改的内容提交”。已确认代码与工作包主提交：`cc441dd585f36fd165d2071232690109a64aed90`，主题“实现工作区参考资料选择与受控导入并兼容Windows可信ACL”，共124个相关文件。本节由随后文档提交保存；原分析六文档亦随主提交首次纳入Git，其“未提交”等字样保留为形成时历史。未推送、未合并、未部署。

提交范围为目录选择、受控导入、单选修复、R2可信Windows ACL及相应测试/生产说明/工作包；不包含用户application.yml/application-mysql.yml/application-ok.yml、.gitignore、.workbuddy、.env或target生成物。逐项核对主提交的108个源码文件与最终已验证补丁一致（仅Git换行规范化），没有重复运行业务套件；Windows80项全通过、Rocky993项无失败/错误且97条件跳过的证据适用。R1 T11–T14剩余门禁仍未全部完成，本地提交不等同正式发布。

暂存检查只发现V22两方言历史迁移末尾空行提示，按不可修改历史迁移的约束保留原文；其他暂存空白和高置信敏感信息扫描通过。未修改真实ACL或重启8080，主提交后的无关脏文件哈希保持不变。后续问答或实施先读当前记录，旧HEAD369418b及“未提交”均为历史/验证基线，不应当作当前Git状态。

## 2026-10-28 R2实施交付与当前验收

用户在R2文档交付后明确要求“继续后面的步骤”，已按mode=default实施T16–T18；当前R2增量完成，已本地提交（见文首提交记录）、未推送、未合并、未部署。未重启用户8080服务，未修改用户reference/imports、YAML、.env、.gitignore、.workbuddy或无关脏改动。R1 T11–T14整体门禁仍未完成，不能用本次ACL通过替代原生窗口、真实Provider或原IMPORTED双库门禁。

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

未修改现有浏览器设置，未重新操作用户聊天页或原生文件夹窗口；前端未变，不重跑JS/视觉套件。外部Provider未调用，其他Windows版本/语言与32位/ARM未实测，不将本机和Rocky测试扩展成这些环境通过。R1 T11–T14尚未完整收口；后续先核对其未验清单，不重复已完成T16–T18。已本地提交（见文首提交记录）、未推送、未合并、未部署。

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

## 本轮清单与完成定义

T15保留文档完成历史。现T16已实现新依赖/可信SID/固定根基线和失败关闭，T17已取得真实Windows及错误脱敏证据，T18已完成两策略回环运行和生产说明；真实用户目录ACL未变。外部环境阻塞逐项记录，不把跳过当通过。

新增验收影响R0已完成的路径/ACL部分，但旧T1/T4不重写为未完成；差异由T16–T18补验。R1原生选择、两库、Provider与浏览器门禁仍有效，ACL兼容不替代这些验收，也不要求重新生成原生选择设计。

## R2差异与采用决定

| 编号 | 类别 | 调整前→调整后及来源 | 影响 | 采用状态 |
|---|---|---|---|---|
| D7 | 调整 | Windows仅允许所有者→额外允许固定SID的SYSTEM、本机Administrators；用户明确新增要求 | R0 T1/T4与R1 T13/T14验收扩展，新增T16–T18 | 用户明确要求，已实施；见本次验收 |
| D8 | 澄清 | “私有目录”容易被理解为所有存储统一放宽→只调整服务器登记参考资料链 | DEFAULT/SELECTED；不影响B5/B6和IMPORTED/Skill/Harness | 默认保守范围B9，已采用，不冒称用户逐项确认 |
| D9 | 新增 | 账户显示名/原根ACL判断→稳定SID、固定信任基线和选中文件链校验 | T16/T17；防同名冒充、子文件ACL绕过、恢复时ACL变更 | 默认工程细化B10，已采用 |
| D10 | 澄清 | 只读讨论/文档≠已改代码→需求修订时T15文档完成、T16–T18待做，后续已授权实施；旧“必须删SYSTEM/Administrators”只保留为旧实现历史 | 六份当前文档/未来生产说明 | 已采用，原历史不删 |

| 假设 | 默认工程取舍 | 原因及未采用方案 |
|---|---|---|
| B9 | 不增加新运行配置键；Windows固定兼容可信系统身份，POSIX不变；不开放普通组只读 | 用户只要求SYSTEM/Administrators兼容；不扩大为任意白名单或ACL bypass |
| B10 | server.runtime内部拟建最小Windows SID/安全描述符适配；优先核实JNA相关API后读取原生SID，类型名实施前复核 | 当前JDK公开查询探针不能直接解析SID；禁止反射sun.*、增加--add-opens、账户名字符串匹配或PowerShell/cmd/helper降级。需新增依赖时仅server、固定核实版本并记录原因，不升级无关依赖 |
| B11 | 继续使用WORKSPACE_CONTEXT_INVALID描述受控ACL拒绝，SID/ACL读取基础设施失败使用WORKSPACE_CONTEXT_UNAVAILABLE；固定中文原因与同号诊断 | 不扩张公共API/数据库/配置协议；未来若需新错误码须同步HTTP/日志/测试，不得返回原始系统消息 |

## 方案细节与验收矩阵

Windows适配结果只包含策略所需的规范化SID、所有权、DACL存在状态、ACE类型/权限/继承标志；不得把底层描述符、资料内容或路径通过toString输出。原生内存、句柄由适配器创建并在每次读取后关闭，不能缓存资源跨Run；非Windows不加载原生库。可缓存固定身份常量，不缓存目录ACL授权结果。ACL前后漂移与文件身份复核结合，不能为了兼容权限移除原链接和读取竞争检查；不声称消除所有宿主文件系统竞争。

可信集合只新增两个固定SID；基线根所有者来自部署端文件系统状态，不接受HTTP/模型传入主体。检查根内选中路径时保持这套集合，禁止随子文件所有者改变而扩大。Windows OS仍决定服务进程是否有实际读取权限；管理员/SYSTEM出现在ACL里不等于给当前进程额外权限。

| 场景 | 预期与证据 |
|---|---|
| 仅所有者ALLOW | 继续成功，兼容旧目录 |
| 所有者+SYSTEM/本机Administrators，显式及继承项 | 成功，普通运行能读取合成资料；无需删除正常管理权限 |
| 中文/英文显示名、同名自建主体、域中同名组 | 按SID识别；只有两个固定系统SID获兼容，名字相似不放行 |
| Everyone/Users/Authenticated Users或未知SID的ALLOW | 保守拒绝；写入、删除、改权限均有负例，未授权读取也不顺带开放 |
| 可信根但选中子目录/文件对普通主体开放，或子文件不可信所有者 | 拒绝，不能只验证根；不扫描未登记文件 |
| NULL/空DACL、未知ACE、描述符不可读、原生解析/资源关闭失败 | 失败关闭，按受控拒绝/基础设施准确分类，同号errorId和脱敏日志 |
| 真实OS DENY阻止服务读取 | 不绕过；返回安全的实际读取失败原因 |
| WAITING_APPROVAL后ACL被改成不可信，随后恢复 | 阻止恢复和工具副作用；不能使用新文件重建原快照 |
| 两个策略、POSIX、IMPORTED/Skill/Harness原私有策略 | 原边界保持；Linux通过不算Windows ACL实测通过 |

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

- [执行提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
- [需求设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
- [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
- [实际实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
- [进度账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)

## R1 历史原文（R2修订前全文，禁止作为当前实施提示）

# 工作区目录选择实施清单（R1：用户电脑原生文件夹）

日期：2026-10-27；主题：workspace-directory-selection-implementation；当前修订：R1；修订日期：2026-10-27；模式：mode=default。

## 2026-10-28 当前补充：T12/T13目录单选缺陷修复

本节记录用户报告的chat.html“可选目录选中后无法互相取消”缺陷，沿用2026-10-27/R1、mode=default与原T0–T14编号。本次为T12/T13局部修复，不新增需求修订、不宣布整个T12/T13或T14门禁完成。此前已经进入T11–T14实施，下面T10标题及“待做/未实施”文字保留为形成当时的阶段历史，不能据此否认已有在途代码，也不能据本次局部通过宣布R1整体完成。未提交。

根因：app.js的element()只处理className/text/type/value，选择器传入的name未落到DOM；默认、不使用及登记项因此不在同名单选组。已在bindWorkspaceChoice中显式设置radio.name="workspace"，恢复浏览器原生互斥与键盘分组语义。重复点击当前单选项仍保持选中；清除资料来源须选择“不使用工作区”并保存。点击取消/关闭只丢弃草稿，再打开恢复已保存来源。

共享app.js缓存版本由2.0.37升为2.0.38，九个v2页面与ConsoleResourceTest保持一致；不修改全局element()行为、业务API、数据库、配置、权限、上传或审批流程。保护已有配置、.gitignore、.workbuddy及无关脏改动。

验证：node --check app.js通过；node --test cm-agent-console/src/test/js/*.test.cjs共177项全部通过，含4项新增接线回归；Java21.0.11/Maven3.9.4执行mvn -q -pl cm-agent-console -am test，ConsoleResourceTest共16项全部通过。JSDOM26.1.0加载真实chat.html及真实element/bindWorkspaceChoice：修复前可同时选中DEFAULT、NONE、product，修复后始终唯一选中，NONE保存、取消恢复及搜索重建通过；这不是实际浏览器或原生窗口验收。当前localhost:8080的chat.html和app.js均HTTP200，已提供2.0.38引用和显式分组修复。

现场浏览器验证未执行：cua.getBrowser返回“Local browser use is disabled in Work settings”，没有改设置或经其他UI通道绕过。未对当前运行服务重启、登录或保存用户会话。detector扫描原chat.html得到9项提示（2 warning/7 advisory），涉及既有颜色、卡片伪元素条纹与阴影，本次无布局/CSS改动，不扩展处理。DOM验证不能替代当前用户页面、390px或键盘实测；这些保留为未验证项。后端/双库/Provider未因本次前端局部修复重跑，历史通过不替代R1整体门禁。

本次验收范围：单选项必须同组；选择登记项后旧项由浏览器取消；NONE提交workspaceId=null；取消不写入；搜索不丢草稿；缓存版本一致。自动化完成，现场浏览器待验证。

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

## 当前需求与采用范围

用户明确要求系统能够调起**用户电脑的操作系统文件夹选择框**。R1 新增“从我的电脑选择文件夹”入口，与 R0“服务器登记目录”并存。默认方案：原生选择 → 浏览器本地列出候选 → 用户勾选并明确确认“上传所选文件并用于会话” → Server 生成归属当前主体的不可变只读导入快照 → 绑定空会话并实际进入模型参考资料。

远程 Server 不获得用户电脑绝对路径、文件系统句柄或持续访问权限。本轮不增加本机辅助程序、持续同步、目录写入、Shell、通用项目IDE或整个项目无界上传；导入仍沿用当前有限文本资料预算。上传快照、保存期限、所有者隔离等为默认工程取舍 B1–B8，详见设计，不是用户逐项确认。

## 2026-10-27修订时证据与历史适用性（T9更新见上方）

HEAD `369418b3bdc08b096de7854f9d04c982ec8be050`，工作树已有 R0 实现及配置等脏文件，暂存区为空。当前 WorkspaceSelection 只有 DEFAULT/SELECTED/NONE；chat.html 仅展示登记目录；WorkspaceContextFiles 读取服务端受信根；未发现原生目录选择或导入 API。最高迁移目前为 V22，后续实施重新核查。

R0 的服务端895项（41条件跳过）、持久化66项、JS155项、真实Provider/浏览器证明仅适用于R0；本轮未重跑，不能证明原生窗口、导入、IMPORTED、上传配额或新权限分支。

## 稳定任务编号

| 编号 | 状态 | 当前范围 | R1影响 |
|---|---|---|---|
| T0 | 完成 | R0规范/基线/分析 | 保留历史，T8复核新需求 |
| T1 | 完成 | R0登记catalog/路径边界 | 保留，不用登记catalog假冒本机目录 |
| T2 | 完成 | R0三态元数据及V22 | 验收变化由T9/T13追加，不重写旧完成记录 |
| T3 | 完成 | R0目录/会话/Run接口 | 新来源与所有者隔离另由T10覆盖 |
| T4 | 完成 | R0冻结/恢复/清理 | IMPORTED及新守卫另由T11/T13覆盖 |
| T5 | 完成 | R0选择弹层及运行详情 | 原生入口/本地预览由T12追加 |
| T6 | 完成 | R0自动化 | 历史结果不可替代T13 |
| T7 | 完成 | R0运行/浏览器/生产说明 | 原生系统窗口、导入及新运维由T14追加 |

| 编号 | 状态 | 目标与影响文件 | 依赖 | 验收条件 | 验证方式 |
|---|---|---|---|---|---|
| T8 | 完成 | 原生文件夹能力、浏览器/服务端边界调研；本组六份 R1 文档及差异/历史检查 | R0 | 原生选择纳入范围；默认取舍显式标为假设；T9–T14 不冒充实现 | 文档、源码、官方资料及限定差异检查 |
| T9 | 完成 | IMPORTED 协议、导入领域/仓储/受控加密存储、配额、租约、清理、后续双库迁移 | T8 | 旧三态/V22记录保持兼容；文件对象按可信身份生成；原子预留和有界生命周期 | core/memory/JDBC合同、双库迁移及中文表字段注释、崩溃与并发 |
| T10 | 完成 | 导入 capabilities、manifest/上传/complete/查询/撤销 API，来源所有者及派生会话/Run访问隔离 | T9 | 未确认不传文件；DRAFT不可绑定；跨租户/主体/Agent统一404；准确HTTP/code/errorId和严格审计 | MockMvc/流式错误、故障日志同号脱敏、游标列表与越权负例 |
| T11 | 待做 | 会话及同步/SSE运行接入 IMPORTED，来源固定、原加密快照/审批/停止链与版本守卫 | T9、T10 | 上传A/B真实改变资料；刷新/新JVM不依赖客户端句柄；撤销/缺失拒绝；不回退DEFAULT | ReAct/Harness输入、首发并发、审批源文件变化/缺失、停止及GC租约 |
| T12 | 待做 | v2 两类来源、系统原生选择、只读预览勾选、确认上传及导入绑定，JS状态机及跨页依赖 | T10、T11 | showDirectoryPicker与目录input双通道；取消无写入；不支持不伪装；慢响应/冲突不串台 | JS行为/语法、v1/登录/审批/停止/产物回归、桌面/390px交互 |
| T13 | 待做 | 本轮自动化与Rocky两库/新JVM/存储重建/失败补偿；增量注释与日志验收 | T9–T12 | 新失败分支均验证；旧模式不受影响；实际源码哈希一致；不用R0通过替代 | Java21、node测试、ssh rocky指定Maven21容器、PG16/MySQL8.4 |
| T14 | 待做 | 原生系统窗口、双浏览器/降级、真实Provider两策略、远程部署及生产说明/回退 | T13 | 选的是用户电脑；确认前网络无文件数据；实际输入可区分；原生弹框与模拟测试分开举证 | Windows Chrome/Edge实际系统窗口、其他环境实测或明确未验证、真实模型与运行证据 |

## 完成定义

T8 仅表示本次文档修订完成。2026-10-28授权的下一步T9已实施及验收完成；当前T10亦已实施并通过对应验收，后续范围为T11–T14，顺序依赖如表。取消“用户电脑原生文件夹选择”作为非目标的旧限制，不取消或回滚 R0 已交付代码。用户目录持续挂载和可写工具依然不在本轮。R1 差异 D1–D6、采用假设和未验证项以设计及账本为准。

## 关联产物

- [执行提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
- [需求设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
- [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
- [实际实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
- [进度账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)

## R0 历史原文（仅历史，不作为当前执行命令）

下方保留本轮修订前全文，状态及限制仅在R0范围内有效。当前范围、待办和授权以本文上方R1为准。

> # 工作区目录选择实施清单
>
> 日期：2026-10-27；主题：workspace-directory-selection-implementation；修订：R0；模式：mode=default。
>
> 来源：[原分析执行提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)，保留原分析六份产物及 T0 历史，不以实施结果改写其“尚未实施”事实。Git 基线 `369418b3bdc08b096de7854f9d04c982ec8be050`。未提交、未推送、未部署。
>
> ## 范围及状态
>
> 本任务接续中断后已有领域/仓储/catalog 草稿，完成原 T1–T7 的前后端实施。保留 .gitignore、application.yml、application-mysql.yml、application-ok.yml、.workbuddy 及发布说明既有改动。范围为服务端登记只读目录；不开放浏览器本机路径、任意磁盘树、写文件、Shell 或 Skill 宿主挂载。
>
> | 编号 | 状态 | 实际交付与验收 | 依赖 |
> |---|---|---|---|
> | T0 | 完成 | 读取规范、原分析包、POM/生产说明；使用 CodeGraph 并定向补查未覆盖源码 | 无 |
> | T1 | 完成 | catalog 有界登记、UUID 身份、名称及路径校验，tenant/Agent 筛选、重叠拒绝、旧 bindings 兼容 | T0 |
> | T2 | 完成 | WorkspaceSelection/WorkspaceBinding、memory/JDBC、CAS、会话行锁、V22 双方言及中文表字段注释；两库新 JVM 恢复验证通过 | T1 |
> | T3 | 完成 | 目录/绑定/安全 Run 视图、可选创建与同步/SSE 参数；权限、创建者、404/409、同号脱敏诊断 | T1、T2 |
> | T4 | 完成 | 首发固定选择、真实 workspaceContext 注入、NONE 隔离、v2 守卫、审批恢复及严格清理；两策略合同通过 | T2、T3 |
> | T5 | 完成 | v2 摘要/弹层/筛选、保存、重新读取、首发锁定、新会话、运行详情；代次及会话 epoch 隔离 | T3、T4 |
> | T6 | 完成 | 本机快速/JS通过；Rocky完整服务端895项、双库持久化66项及MySQL20项无失败 | T1–T5 |
> | T7 | 完成 | 真实 DashScope 两策略、桌面/390px、刷新、Esc、审批旧快照、停止及双库新JVM证明通过，生产说明齐全 | T6 |
>
> 验收证据和命令以账本为准。代码完成、单项验证通过与最终发布分开；本轮最终回归已结束；环境条件跳过项、未提交和未部署边界见账本。
>
> ## 关联产物
>
> - [实施提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
> - [实施设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
> - [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
> - [实际实现](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
> - [实施账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)
