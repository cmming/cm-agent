# 工作区目录选择需求设计（R2：可信Windows系统ACL兼容）

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

## R2规划阶段证据（历史，非当前实现状态）

1. HEAD为369418b3bdc08b096de7854f9d04c982ec8be050，暂存区为空，工作树存在大量既有业务/配置改动。只读AGENTS.md、相关POM/README/configuration/operations和六文档；存在.codegraph，先用codegraph explore定位，新增类未被索引覆盖时定向复核。生产源中WorkspaceContextFiles.requirePrivate仍拒绝所有非所有者ALLOW，R2尚未生效。当前最高迁移重查为V25；本轮无schema变化，不修改历史迁移。
2. WorkspaceContextFiles.read现只对根调用requirePrivate；Windows子文件可有独立安全描述符。WorkspaceContextService.failure已按Invalid、Limit、基础设施等分类并沿用runId作为errorId；R2应复用边界，不能把原始路径、ACL、账户列表或本地API消息放进日志/响应。
3. 本轮只读探针命令：F:/java21/bin/java.exe target/workspace-native-r1-remaining-20261028/WindowsPrincipalLookupProbe.java。当前Windows Java21对S-1-5-18、S-1-5-32-544调用公开lookupPrincipalByName均返回UserPrincipalNotFoundException。这只说明此环境不能直接将SID字符串当账户名查询，不代表其他Java实现或Windows API均不能解析。未改ACL、未读取资料正文。
4. Microsoft固定SID及文件安全描述符资料已核对；JNA上游提供Windows安全描述符/SID相关API，但本轮只作可行性源码核对，不代表选定依赖版本已经编译或通过真实运行。T16必须先确认实际依赖树和目标版本源码，再完成资源释放与Windows实测。
5. 本轮不运行业务测试、模型、数据库、系统窗口或完整Server；不把既有ACL拒绝探针、R0/R1测试数字或Linux跳过的Windows用例记成R2通过。T16–T18全部未实施、未验收；真实Windows用户环境保持不动。未提交。

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

## 实施顺序与文件线索

1. T15（本轮）：核实规范/工作包/当前ACL实现/官方SID；记录公开JDK探针结果；仅修订六文档并验证历史不变。
2. T16（已完成，保留实施方法）：以cm-agent-server/src/main/java/com/cmagent/server/runtime/WorkspaceContextFiles.java为接入点，拟定同包WindowsWorkspaceAclPolicy、WindowsSecurityDescriptorReader等最小类型（实施前重查，避免重复造类）。检查cm-agent-server/pom.xml实际依赖后决定是否需要JNA/jna-platform；固定实际可验证版本，不在core/adapter引入Windows依赖。根内路径校验与文件IO仍在事务外，审批恢复接入WorkspaceContextService原检查点链，不改数据库迁移或Run来源协议。
3. T17（已完成）：扩展WorkspaceContextServiceTest及两策略工作区合同；拟定WorkspaceWindowsAclPolicyTest覆盖纯决策，WindowsWorkspaceAclTest覆盖真实NTFS。负例同时断言模型未调用/工具无副作用；Windows专有case在Linux显式跳过并记录，不能计通过。错误消息、code、errorId/日志同号、无路径/资料/账户列表/Token泄漏必须断言。
4. T18（已完成）：Windows专用合成目录应用所有者+SYSTEM+Administrators ACL，再加入普通主体权限验证拒绝；只改测试自建目录、保留/恢复其权限，不修改用户reference/imports。核对实际服务的可信目录读取、审批恢复及POSIX兼容，更新README.md、docs/configuration.md、docs/operations.md、docs/release-notes.md及六文档，写清适用存储范围、SID身份、拒绝条件与回退边界。

建议快速命令在Java21确认后按真实测试类选择：mvn -q -pl cm-agent-server -am -Dtest=WorkspaceContextServiceTest,WorkspaceWindowsAclPolicyTest,WindowsWorkspaceAclTest -Dsurefire.failIfNoSpecifiedTests=false test。拟定类在实施前确认，不能把尚不存在的类或跳过当通过。原生依赖生命周期需纯单测+真实Windows资源回收验证。

JDBC/Flyway/Testcontainers或完整server若因集成影响需要执行，只能ssh rocky的maven:3.9.9-eclipse-temurin-21，PG16-alpine/MySQL8.4；先核对HEAD+实际脏补丁SHA256与解包时间戳。本轮不改schema，不为文档或纯ACL调整机械重跑双库；仍须保留R1未完成门禁。Windows ACL、中文/英文系统及真实浏览器若环境不足记录确切未验范围，不把Rocky结果替代。

## 核对来源

- [Windows固定SID定义](https://learn.microsoft.com/en-us/windows/win32/secauthz/well-known-sids)：SYSTEM和本机Administrators的稳定身份，不以界面显示名代替。
- [Windows文件安全与访问权](https://learn.microsoft.com/en-us/windows/win32/fileio/file-security-and-access-rights)：父目录和子文件安全描述符、实际OS读取权限。
- [JNA Windows工具上游源码](https://github.com/java-native-access/jna/blob/master/contrib/platform/src/com/sun/jna/platform/win32/Advapi32Util.java)：仅作原生描述符/SID适配可行性线索，目标版本实施时重新核实。

## 当前关联产物

- [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
- [执行提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
- [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
- [实际实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
- [进度账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)

## R1 历史原文（R2修订前全文，禁止作为当前实施提示）

# 工作区目录选择需求设计（R1：用户电脑原生文件夹）

日期：2026-10-27；主题：workspace-directory-selection-implementation；当前修订：R1；修订日期：2026-10-27；模式：mode=default。

## 2026-10-28 当前补充：T12/T13目录单选缺陷修复

本节记录用户报告的chat.html“可选目录选中后无法互相取消”缺陷，沿用2026-10-27/R1、mode=default与原T0–T14编号。本次为T12/T13局部修复，不新增需求修订、不宣布整个T12/T13或T14门禁完成。此前已经进入T11–T14实施，下面T10标题及“待做/未实施”文字保留为形成当时的阶段历史，不能据此否认已有在途代码，也不能据本次局部通过宣布R1整体完成。未提交。

根因：app.js的element()只处理className/text/type/value，选择器传入的name未落到DOM；默认、不使用及登记项因此不在同名单选组。已在bindWorkspaceChoice中显式设置radio.name="workspace"，恢复浏览器原生互斥与键盘分组语义。重复点击当前单选项仍保持选中；清除资料来源须选择“不使用工作区”并保存。点击取消/关闭只丢弃草稿，再打开恢复已保存来源。

共享app.js缓存版本由2.0.37升为2.0.38，九个v2页面与ConsoleResourceTest保持一致；不修改全局element()行为、业务API、数据库、配置、权限、上传或审批流程。保护已有配置、.gitignore、.workbuddy及无关脏改动。

验证：node --check app.js通过；node --test cm-agent-console/src/test/js/*.test.cjs共177项全部通过，含4项新增接线回归；Java21.0.11/Maven3.9.4执行mvn -q -pl cm-agent-console -am test，ConsoleResourceTest共16项全部通过。JSDOM26.1.0加载真实chat.html及真实element/bindWorkspaceChoice：修复前可同时选中DEFAULT、NONE、product，修复后始终唯一选中，NONE保存、取消恢复及搜索重建通过；这不是实际浏览器或原生窗口验收。当前localhost:8080的chat.html和app.js均HTTP200，已提供2.0.38引用和显式分组修复。

现场浏览器验证未执行：cua.getBrowser返回“Local browser use is disabled in Work settings”，没有改设置或经其他UI通道绕过。未对当前运行服务重启、登录或保存用户会话。detector扫描原chat.html得到9项提示（2 warning/7 advisory），涉及既有颜色、卡片伪元素条纹与阴影，本次无布局/CSS改动，不扩展处理。DOM验证不能替代当前用户页面、390px或键盘实测；这些保留为未验证项。后端/双库/Provider未因本次前端局部修复重跑，历史通过不替代R1整体门禁。

采用原生radio互斥模型，NONE是明确的不附加工作区资料状态；不引入多选、零选择或点击已选项反选的新协议。保留原来源选择语义、首发后锁定以及取消不保存约束。

## 2026-10-28当前实施状态（R1 T10）

当前采用D1–D6/B1–B8，mode=default；继续复用2026-10-27/R1六份路径，不生成新修订或回收任务编号。T0–T7保留R0完成历史，T8文档完成，T9存储基线完成，T10业务及对应验收完成，T11–T14待做。T9及原文档阶段记录保留，下方旧“未实施”只表示其形成当时状态，以本节为当前状态。

本轮按用户再次“继续下一步”实施T10：capabilities、明确确认后的manifest创建、原始正文逐文件上传、complete、GET、撤销，以及IMPORTED派生会话/Run/消息/审批/技能记录/停止入口的owner保护。列表在游标和LIMIT前过滤；私有资源对外复用原路由通用404 code/message，避免存在性探针，原三态权限保持原语义。所有身份只取认证JwtSession。源绑定仍失败关闭，READY的bindable及runtimeAvailable仍false；原生窗口、浏览器预览勾选和真实IMPORTED模型输入仍待T11–T14。

T10实现细化E1：为满足“创建响应丢失也能先GET核对”，新增requestId、GET by-request和双库V24；importId/fileId仍由Server生成，历史V22/V23不改。E2：采用有界原始正文而非multipart，5秒总等待、4个真实读取槽；E3：拒绝审计在外层短事务退出后完成，避免被回滚或独立事务自锁。上述为原需求内的工程细化，不是假称用户新增确认。

本机Java21定向52项，失败/错误0，跳过1项（Linux符号链接已由Rocky通过）；Rocky持久化全套70项无失败/错误/跳过，完整server 929项、失败/错误0、条件跳过41项，额外MySQL旧运行合同20项无失败/错误/跳过；新增拒绝回滚后审计PG/MySQL两项通过。旧历史数字不替代本轮证据，跳过不算通过，真实Provider及原生窗口未验。未提交、未推送、未部署。

### T10实际交付

- WorkspaceImportController/Service提供七类接口；POST创建要求displayName、随机UUID requestId、confirmed=true、files(relativePath/sizeBytes/sha256)，返回服务端生成的importId/fileId。新GET by-request只核对原tenant/owner/Agent，同确认重复POST409，不自动重试创建。
- WorkspaceImport增加可空requestId兼容T9构造器/旧记录，仓储在同租户锁内拒绝重复关联；V24增加关联列/唯一索引并令owner精确比较。查询包括回收tombstone，不因物理回收允许重复创建；真实标识、manifest和密文不接受客户端覆盖。
- HTTP用application/json和application/octet-stream，manifest16KiB/complete1KiB，文件实际声明长度加一读取并复核UTF-8/控制字符/摘要/预算；严格拒绝未知字段、重复JSON键、尾随JSON及非整数长度。WorkspaceImportBodyReader最多4个无队列线程，总等待5秒；不响应中断的流仍占实际槽。没有multipart临时落盘。
- 上传/complete持持久UPLOAD租约；完整核对实际密文后才READY。complete可核对“密文已落盘、接收确认未知”的对象，不重新上传或覆盖。JDBC成功状态与严格审计同短事务，文件I/O在事务外；memory审计/基础设施失败撤销已变更批次，不留下未审计可用READY。
- 新增WORKSPACE_IMPORT_*稳定失败，覆盖400/403/404/409/410/413/422/429/503/500。所有可失败阶段有同号errorId及脱敏日志，日志不带DTO、文件名、正文、Token、路径或原始异常消息。AuditAppender.appendControlled只上抛，由最终边界记录一次。外层事务内拒绝携带DeferredWorkspaceFailure，回滚释放锁后HTTP/SSE边界finish一次严格审计，拒绝审计不会随业务回滚消失。
- WorkspaceImportAccess和WorkspaceSelectionRepository共享可见性规则；ConversationService、RunPersistenceService、ToolApprovalService及Controller在列表/详情/消息/活动Run/审批历史/技能记录/停止等入口复核owner。查询与视图间再次复核固定Run，防止来源切换竞态。JDBC的listVisible在WHERE中完成过滤，memory在排序/LIMIT前过滤；原三态不增加owner条件。旧自定义仓储未实现新SPI时明确拒绝，不能截断后过滤或回退共享列表。
- 读取拒绝的HTTP code/message与原路由真正缺失时一致，后台以WORKSPACE_RESOURCE_NOT_FOUND分类且errorId同号；上传域的未知/跨tenant/owner/Agent统一WORKSPACE_IMPORT_NOT_FOUND/404。MySQL主体使用逐字比较，V24请求关联对大小写和尾随空格不误合并，PG语义一致。
- T10不开放绑定执行：WorkspaceSelectionService先核对导入归属/READY/期限，再明确拒绝尚未接线的运行来源，DRAFT409、不可见404，不回退DEFAULT。capabilities.runtimeAvailable及View.bindable固定false。现有v1/v2/审批/停止业务链保留；真实IMPORTED冻结/恢复仍归T11。
- README、configuration、operations、release-notes已更新API/结果未知/私有存储/审计/期限/配额/撤销/回退与阶段限制。密文TTL不代表manifest元数据自动擦除，已知清单/请求关联tombstone保留供历史及围栏；尚无自动清单元数据擦除。

## 2026-10-28 T9完成记录（历史阶段）

本节保留T9交付当时记录，当前T10状态以文首为准；需求仍采用D1–D6/B1–B8，工作包日期/修订保持2026-10-27/R1，mode=default。T0–T7历史完成、T8文档完成、T9业务及对应验收完成、T10–T14待做。最新最高迁移是V23，不修改V22/V23历史。

T9新增IMPORTED、不可变manifest、共享仓储规则、memory/JDBC配额/状态/租约、独立AES-256/GCM私有输入卷、补偿GC和双库V23/中文注释。WorkspaceSelectionService在make/resolve/view显式拒绝尚未接入的IMPORTED，不回退DEFAULT；HTTP409/WORKSPACE_UNAVAILABLE、errorId/日志/严格审计与模型未调用均已验证。共享JS保留不支持来源身份并阻止保存/发送，不显示为默认映射；原三态保持旧语义。

当前仍没有上传API、原生目录窗口、浏览器预览/确认上传、IMPORTED派生会话/Run接线；T10–T14仍须后续实施及验收。T13/T14整体门禁没有完成，R0的895/66/155不能替代。本机Java21定向测试44项，失败/错误0、跳过1项；Rocky持久化全套68项及server相关58项，失败/错误/跳过均0。命令与证据见账本。未提交。

### T9实际交付与工程取舍

- core新增WorkspaceImportScope/File/Import/Limits/Lease、WorkspaceImportRepository、AbstractWorkspaceImportRepository、WorkspaceImportStorage，并扩展WorkspaceSelection/Binding。领域模板统一memory/JDBC状态、配额和租约规则；manifest不可变，摘要以长度前缀编码绑定来源、tenant/owner/Agent/import/全部文件项。路径拒绝绝对路径、别名、Unicode/大小写碰撞、敏感名称及非既有文本类型。
- persistence新增JdbcWorkspaceImportRepository及双方言V23__add_workspace_imports.sql；锁真实tenants行，使跨Agent/实例的配额和租约原子化。新增workspace_imports/files/leases，manifest只插入一次，后续只改状态、确认集合、版本、期限和回收标记。V22原文不动；真实V22升级V23、旧三态和新增表字段原生中文注释由两库验证。
- server新增InMemoryWorkspaceImportRepository、FileSystemWorkspaceImportStorage、WorkspaceImportProperties/Configuration、WorkspaceImportCleanup；原WorkspaceContextProperties增加嵌套imports。未修改application配置。私有卷按tenant/import/file UUID寻址；AAD绑定manifest/fileId，实际读取声明字节加一并复核UTF-8/控制字符/长度/摘要。固定stage、文件锁、原子发布、持久删除标记防止半写/覆盖/GC后迟到写入；故障不附带底层异常消息。
- 输入流归调用方关闭，底层HTTP超时/请求并发由后续T10承担；不能直接公开存储SPI。默认DRAFT一小时、READY完成后固定最长30天，主体8/租户64批次和256KiB/2MiB预留，继承4文件/16KiB/32KiB预算及原硬界。配置收紧后旧草稿不能用旧预算发布。
- READ共享且每批最多64租约，UPLOAD/GC独占；租约1秒至5分钟。撤销/到期即拒绝新READ与续租，旧READ引用释放/到期前GC拒绝；迟到令牌不能确认上传或归还配额。GC先持租约、事务外删除、最后确认回收；删除失败/缺失卷保留容量并记录随机errorId与WORKSPACE_IMPORT_CLEANUP_FAILED，后续可重试。
- 保留零正文删除标记、锁文件与已知批次tombstone以实现围栏，不任意当作孤立对象清理。私有卷可位于服务账户独占子目录，禁止主目录自身/祖先、应用目录、登记根重叠、路径别名或网络卷；不自动扫描用户目录。多主机共享卷与其他存储适配尚未验收。
- README/configuration/operations/release-notes已补充T9分阶段边界、私有存储/密钥/期限/配额/清理及IMPORTED排空回退；没有宣称原生选择已发布。现有配置/.gitignore/.workbuddy及其他无关改动保持不动。

R1于2026-10-27完成T8文档修订；2026-10-28完成T9，用户再次要求“继续下一步”后实施T10，当前T10业务及对应验收完成。保留R0与T9历史，T11–T14仍待做；mode=default，不因读取提示词自动扩大范围。未提交、未推送、未部署。原分析包保留于 [原分析提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)。

## 需求来源与修订差异

用户明确提出的新增目标只有“需要支持用户电脑原生文件夹选择”。沿用 mode=default，自主采用下列保守方案；不把技术默认决定写成用户确认。当前采用方案从本节开始；文末 R0 引文只作历史，不再作为 R1 的有效非目标清单。

| 差异 | 类别 | R0 → R1 | 来源/依据 | 影响 | 采用状态 |
|---|---|---|---|---|---|
| D1 | 调整 | 原生文件夹选择原为非目标 → 必须提供用户电脑系统窗口入口 | 用户本轮明确指令 | A1/A3的仅服务端范围被扩展；T12/T14 | 已采用 |
| D2 | 新增 | 仅管理员目录 → 增加显式预览、勾选、确认上传与独立导入快照 | 远程Server不能直接读取用户电脑路径；B1/B2默认方案 | T9/T10/T12 | 已采用的工程假设 |
| D3 | 新增 | 三态 → 保留原三态并增加IMPORTED、不可变manifest和生命周期 | 不能把用户上传伪装成受信catalog | T9/T11/T13 | 已采用的工程假设 |
| D4 | 新增 | 无上传输入边界 → 有界文本、配额、加密存储、所有者及派生资源隔离 | 本机资料进入Server形成新的数据边界 | T9/T10/T13 | 已采用的工程假设 |
| D5 | 澄清 | 目录选择标签/原生HTML dialog → 真正操作系统选择器；普通文件上传/模拟input不替代原生验收 | MDN/WICG及现有源码 | T12/T14 | 已采用 |
| D6 | 调整 | R0通过即本任务完成 → R0保留完成，追加T8–T14与新验收；仅修订文档不等于实现 | 本技能迭代契约 | 六份文件/证据 | 已采用 |

## 2026-10-27修订时事实与证据（T9更新见上方）

- `cm-agent-core/src/main/java/com/cmagent/core/domain/WorkspaceSelection.java` 仅有 DEFAULT/SELECTED/NONE，SELECTED 的 UUID 来自登记配置；`WorkspaceBinding.java` 不存正文或宿主路径。
- `cm-agent-server/src/main/java/com/cmagent/server/runtime/WorkspaceSelectionService.java` 按可信tenant/Agent解析catalog；`WorkspaceContextService.java` 按Run冻结/恢复加密资料，已有v1/v2守卫。当前路径分支不能处理本机上传。
- `cm-agent-server/src/main/java/com/cmagent/server/config/WorkspaceContextProperties.java` 和 `WorkspaceContextResource.java`：默认4文件、单文件16KiB、总32KiB，硬上限8/32KiB/64KiB；文本只允许.md/.txt/.json/.csv。当前约束不代表可以上传整个开发项目。
- 真实前端为 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/chat.html`、共享 `assets/app.js` 和 `assets/console-core.js`。已有弹层是网页dialog，不是操作系统目录选择器；未发现showDirectoryPicker/webkitdirectory入口。
- JDBC选择元数据与V22已经存在；当前R0选择记录、审批和兼容路径须保留。`SkillArtifactStorage`/`FileSystemSkillArtifactStorage` 提供加密存储参考，但Skill产物的成功Run后下载授权不能直接用于Run前上传输入。
- 当前检索未发现桌面壳/本机辅助进程工程入口；本轮不以安装本机服务作为前置条件。CodeGraph精确查询部分返回相邻源码和截断，未覆盖处已定向核对，未假定索引覆盖全部新增未跟踪文件。

## 浏览器能力与明确边界

支持时，在用户点击处理函数中立即调用 `window.showDirectoryPicker({mode:'read'})`，不先await网络请求而丢失用户激活；capabilities提前读取。该API需要安全上下文及临时用户激活，兼容性有限，必须功能探测。依据：[MDN showDirectoryPicker](https://developer.mozilla.org/en-US/docs/Web/API/Window/showDirectoryPicker)、[WICG File System Access](https://wicg.github.io/file-system-access/#api-showdirectorypicker)。

不支持此API而支持目录input时，提供 `<input type="file" webkitdirectory multiple>` 的原生目录选择通道；由 `File.webkitRelativePath` 获取选定根下的相对层级，不把input.value或C:\fakepath视为真实路径。具体兼容目标实施时实测，不承诺所有旧浏览器/移动设备支持。依据：[MDN webkitdirectory](https://developer.mozilla.org/en-US/docs/Web/API/HTMLInputElement/webkitdirectory)、[MDN webkitRelativePath](https://developer.mozilla.org/en-US/docs/Web/API/File/webkitRelativePath)、[WICG Entries API](https://wicg.github.io/entries-api/#dom-htmlinputelement-webkitdirectory)。

两个通道都不可用时显示“当前浏览器不支持文件夹选择，请更换受支持浏览器或使用服务器登记目录”，不能改成手填本机绝对路径、单文件选择或假成功。showDirectoryPicker的取消/拒绝/安全上下文错误分别提示，不在用户取消后自动弹第二个选择框。对浏览器不能区分的取消/空目录情况保持原绑定并给重新选择提示，不编造精确原因。

本机handle和File只在当前页面内存持有，不写localStorage/IndexedDB、不发给Server、不记日志；刷新前未上传的草稿需重新选择。已完成导入从后端恢复，不重新授权本机目录。目录input可能已由浏览器展开整棵树，不能声称应用能在浏览器展开前剪枝；收到FileList后仍按有界规则处理。handle模式只按有界枚举检查元数据，禁止整树读取正文。

## 默认决定与假设（非用户逐项确认）

| 编号 | 默认采用方案 | 原因与可观察影响 |
|---|---|---|
| B1 | 纯Web、单文件夹、原生选择器优先且目录input降级，无本机helper | 用户在自己的电脑选择；不增加安装/远程控制信任面 |
| B2 | 本地预览勾选后明确“上传所选文件并用于会话”，再形成不可变导入 | 只选文件夹不向Server传文件名/manifest/正文；文件离开电脑前有清楚说明 |
| B3 | 只读参考输入，不持续同步、不写回、不挂载本机目录；本地改动需重选重导入 | 适配原Run冻结与远程部署，不能误称拥有持续本机工作区 |
| B4 | 沿用原有四种UTF-8文本与预算，不因目录选择扩大为代码/二进制/压缩包自动导入 | 可从目录中筛选资料；大型项目超限时需缩小范围，不能静默截断 |
| B5 | 新增IMPORTED来源，存储根、对象键及权限独立于受信catalog和Skill产物 | 用户文件永不成为服务器路径或自动获得工具/Skill执行权限 |
| B6 | 上传批次及其派生会话/Run仅原上传/会话创建者可读、运行及绑定，额外要求tenant/Agent与现有RBAC | 本机私有资料不因既有同租户列表权限自动分享；原DEFAULT/SELECTED/NONE权限不顺带重构 |
| B7 | 上传功能独立默认关闭，顶层工作区关闭时也禁用；取消/失败保留原绑定；写请求不自动重试 | 由部署者明确允许本机数据进入Server，结果未知时先GET核对 |
| B8 | DRAFT一小时、READY最长30天固定期限；小配额、运行租约及可撤销/回收 | 有界存储；过期会话显示不可用并引导新会话重导入，不能自动换资料 |

## 用户流程与界面合同

在现有“选择工作区参考目录”中提供“服务器登记目录 / 我的电脑”两个来源入口，保留旧DOM ID、审批/停止/产物入口和克制布局。我的电脑显示“本机文件夹 → 选定文件将上传到当前CM Agent Server，用作只读参考，不会修改你的文件”，先不创建会话或上传批次。

用户触发系统窗口后，本地展示文件夹名称、合格文件相对路径/大小、排除原因、选择数量/字节和当前预算；正文预览仅由用户显式点击且有界读取，不自动逐文件读正文。新选择文件默认不勾选，需用户选定。必须至少选一个有效文件且不超限，才能确认上传。被排除、超额或截断的枚举结果明确提示，不能悄悄取前N个。

本地枚举建议硬界：最多1000个条目、深度16、5秒处理预算（配置由服务端capabilities下发，可降低，实施验证后确定）。先排除.git、.ssh、.aws、.codex、.workbuddy、node_modules等目录；.env类、私钥/凭据类名称及不支持后缀不选入。文件名规则不能保证识别所有敏感正文，页面要求用户核对，不宣传自动脱敏/完全安全。目录input无法提前剪枝时超枚举预算拒绝此次导入并引导选择更小目录。

确认之后才发送manifest/正文；上传和校验进度可见。只有批次READY且空会话绑定PUT/创建成功，才更新活动选择为“我的电脑 · 名称 · 已导入快照”，不是“已连接本机磁盘”。取消、Esc、关闭、本机权限撤销、上传失败或绑定409均保留旧活动绑定。网络结果不确定先查询批次/会话，不能自动重试、重复complete或创建第二个会话掩盖失败。

切换Agent/会话/用户、退出或跨页导航递增请求代次/epoch；晚到的系统窗口结果、枚举/读取结果、进度、上传和PUT响应均不得影响新上下文。取消页面任务不声称能强行关闭操作系统原生窗口；忽略其后续结果。首发后绑定锁定，换本机文件夹/新导入需新会话；目录名用textContent，键盘/焦点/Esc/aria-live和390px保持可用。

## 输入、存储与权限安全

浏览器只是UX预检，所有manifest字段、字节数、类型、摘要、路径均不可信。Server使用认证tenant/principal与已校验Agent，生成importId/fileId及对象键；不接受客户端tenant或宿主路径。根目录名称仅为不可信展示文本；manifest保存**去除单个选择根后的相对路径**，Server校验非绝对路径、无盘符/反斜杠/控制字符/空段/./..，路径长度<=160，拒绝多根、重复及Unicode规范化/大小写等价碰撞，不用归一化消除越界后继续接收。

服务端拒绝禁用后缀/敏感名称、非法UTF-8、NUL、二进制伪装、空文件、实际超长、缺失/额外/重复文件。允许类型和最终数量/字节限额从同一配置导出capabilities，仍受WorkspaceContextResource硬上限；client声明hash不能替代Server重算。HTTP/multipart临时落盘、整体请求、每文件读取和并发必须有硬界，校验前不写入公开目录。正文只在用户勾选并确认后读取/上传，未勾选文件不得因扫描或调试请求上传。

新增导入独立领域/Repository/私有加密存储（名称为拟新增，不是已存在）。对象按tenant/import/file生成，私有目录或受控对象存储不以客户端文件名命名，不经静态资源/Web目录暴露。服务端校验后加密写入不可变对象，AAD或等效完整性约束绑定tenant/owner/Agent/import/manifest；普通元数据表不存明文。借鉴现有加密/租约/清理实践，不把上传输入塞入“成功Skill Run产物”的领域或复用其下载授权。

默认主体最多8个未过期批次、租户最多64个，DRAFT也计入，另有字节/请求并发配额；原子预留防并发穿透。DRAFT一小时无活跃上传租约后回收；READY有效期固定30天；完成时间和保留策略由Server决定。所有者可撤销，逻辑撤销立即阻止新Run/恢复，物理清理等当前读取/Run租约结束；不能删掉在途引用，也不能无限保留孤立对象。上传失败/事务失败/崩溃需补偿或可观测回收，清理失败不能吞掉。恢复需持有稳定密钥及持久存储，多实例路径或对象存储一致。

B6影响不仅是上传API：IMPORTED派生会话列表/get/messages/active-run/审批历史/Run列表和详情等都需复核owner，列表过滤应在查询/游标语义内完成，不能先返回标题再403或仅前端隐藏。原三态沿用原权限；引入此隔离是新增来源分支，不借机重构全部会话授权。普通租户管理员不自动获得资料正文；系统治理清理由受控服务执行，不能伪造用户权限。

## 接口与协议（T10 API已实施；运行绑定待T11）

| 接口 | 输入与状态 | 关键约束 |
|---|---|---|
| GET /api/agents/{agentId}/workspace-imports/capabilities | enabled、来源/类型/预算/配额/TTL等 | read+run权限；只读能力说明；不展示宿主路径 |
| POST /api/agents/{agentId}/workspace-imports | 展示名称、requestId、confirmed=true、选定文件manifest；返回DRAFT/id/version/Server文件ID | 用户明确确认后才请求；原子预留限额，manifest不含绝对路径或客户端tenant |
| POST /api/agents/{agentId}/workspace-imports/{importId}/files/{fileId} | 单个有界文件及expectedVersion | 校验owner/Agent/manifest、实长/hash；顺序上传，已接收文件不可覆盖；不自动重试 |
| POST /api/agents/{agentId}/workspace-imports/{importId}/complete | expectedVersion | 全文件完整校验及持久存储就绪后CAS为READY；失败不绑定、不调用模型 |
| GET /api/agents/{agentId}/workspace-imports/{importId} | 安全状态/版本/摘要/过期时间/可绑定性 | 只供原主体核对未知结果；跨tenant/owner/Agent统一404，不返回正文 |
| DELETE /api/agents/{agentId}/workspace-imports/{importId} | 撤销DRAFT/READY，返回安全撤销状态 | 先撤销后按租约清理；仅原主体；关闭上传后仍允许授权查询/删除 |
| GET /api/agents/{agentId}/workspace-imports/by-request/{requestId} | 原确认关联UUID；只读核对未知创建结果 | 仅原tenant/owner/Agent；不存在404，不重复创建；V24保存关联 |
| 原会话创建/绑定PUT、同步/SSE单轮及选择视图 | 新增IMPORTED；workspaceId携带importId | 保留DEFAULT/SELECTED/NONE合同；IMPORTED只引用READY且属当前主体/Agent的不可变批次 |

使用现有workspaceId字段携带importId是明确的拟定协议取舍：mode负责区分ID命名空间，不能混用或“先查catalog找不到再查import”。WorkspaceBinding及持久化校验同步扩展；不得只给枚举加值而保留resolve中落入DEFAULT的分支。T9已新增V23、T10新增V24，检查原三态CHECK约束并无损升级；不修改已作为历史的V22，所有新增表/字段需两库原生中文注释。

失败合同：参数/路径400；缺失或不可见404；无入口权限403；版本冲突/DRAFT不可用/功能关闭409；过期410；HTTP体过大413；内容/文本/预算不合格422；配额/并发繁忙429；审计/存储503；未知500。中文原因、code/errorId及日志同号，日志不含正文、绝对路径、Token或敏感文件名；已有R0资料读取状态保持兼容。SSE连通后用error事件，不伪改HTTP。

## Run、审批恢复与回退

选择本机目录 → 预览/勾选/明确确认 → 导入DRAFT/逐文件上传/READY → 持有同一会话控制锁/JDBC行锁进行owner+版本+空会话检查 → 固定IMPORTED批次及manifest身份到Run → 事务外读解密对象、有界构造workspaceContext → 原Run加密快照 → ReAct/Harness参考数据。新来源身份摘要需包含来源类型、importId、manifest及可信归属；同一ID不能换正文。

运行时不再访问浏览器handle。刷新/关闭浏览器/本机目录移动或删除不影响已持久导入；用户想用更新内容须重新选择生成新批次。审批恢复继续原快照，且复核原import未撤销/过期、owner/Agent、manifest及守卫；元数据/密文/密钥/正文缺失损坏时失败关闭，不能重新询问本机路径或回退DEFAULT。兼容R0 v1/v2守卫；新增来源若需v3须显式版本与负例。停止/清理仍只影响所属Run，不能删除其他会话共享引用的导入。

上传入口独立默认关闭，不能仅为支持原生API放宽旧受信根ACL/禁用路径；Server对自己受控加密对象的写入不是用户电脑目录写入。生产文档需说明HTTPS、数据上传去向/期限、配额/密钥/卷、撤销/清理、源文件变化语义及混跑限制。回退先禁用新导入和新IMPORTED绑定、排空活动/待审批Run、保留元数据/密文/密钥备份；不识别IMPORTED的旧实例不得接管，不能自动删除批次/表来回退。

## 本轮与未来验收边界

本轮只完成T8及文档一致性，不测试真实系统窗口、不上传任何本机文件，不运行Maven/数据库/真实模型。T13/T14须新证据：两通道原生窗口、取消/拒绝/不支持、HTTPS/远程服务、选前和确认前网络零文件数据、空/大/非文本/敏感路径、文件变更竞争、并发配额、同租户跨主体派生会话隔离、版本冲突、storage/audit/GC失败、双库进程重启、两策略真实Provider/审批/停止及旧三态回归。

Playwright的setInputFiles或模拟FileList只能证明JS/上传合同，不能代替实际操作系统窗口验收。Windows Chrome/Edge需创建无敏感测试目录并记录原生窗口出现/取消/选中；其他OS/浏览器未具备环境时明确记未验证。390px覆盖布局，不据此声称移动设备原生目录支持。实际浏览器版本、TLS/嵌入策略、部署持久卷及新密钥尚未验证，默认参数为拟定值。

## 关联产物

- [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
- [执行提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
- [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
- [实际实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
- [进度账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)

## R0 历史原文（仅历史，不作为当前执行命令）

下方保留本轮修订前全文，状态及限制仅在R0范围内有效。当前范围、待办和授权以本文上方R1为准。

> # 工作区目录选择实施设计
>
> 日期：2026-10-27；主题：workspace-directory-selection-implementation；修订：R0；模式：mode=default。
>
> 来源：[原分析执行提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)，保留原分析六份产物及 T0 历史，不以实施结果改写其“尚未实施”事实。Git 基线 `369418b3bdc08b096de7854f9d04c982ec8be050`。未提交、未推送、未部署。
>
> ## 目标及采用方案
>
> 采用原 A1–A6：使用者选择管理员部署登记的只读参考目录，会话首发后固定逻辑选择，正文按 Run 加密冻结。DEFAULT 兼容旧映射，SELECTED 必须指定登记 UUID，NONE 明确不注入资料。
>
> 配置增加 catalog，保留现有私有根、逐文件文本声明、预算和文件系统安全入口。目录展示只含已授权名称/相对位置，可信 tenant 和主体来自 JWT 过滤器的 JwtSession。列表需要 agent:read 与 agent:run；绑定读取沿用现有会话权限，修改仅创建者、空会话及匹配 expectedVersion。未知及跨租户/Agent 目录统一 404，版本或首发冲突 409，拒绝严格审计。
>
> 使用一个不可变 WorkspaceBinding 表示会话/Run 元数据，在独立仓储方法及两张表中区分语义，避免改变 Conversation/RunRecord 公共构造器。会话修改和首发共用控制锁及 conversations 的 JDBC 行锁；短事务提交后才读取文件、调用模型。memory 创建/首发失败显式补偿，不遗留活动 Run。Run 固定记录不随正文清理删除。
>
> SELECTED 每次执行和恢复复核原登记身份；DEFAULT 也冻结该 Run 实际映射；NONE 永不注入旧绑定。正文更新只影响新 Run。新增加密 v2 守卫要求长期选择记录仍存在，防止元数据丢失后误判为旧 DEFAULT；历史 v1 守卫仍兼容。停用、身份变化、过期/损坏或缺失快照失败关闭。
>
> 前端使用共享 console-core.js 状态机和现有 app.js 接线，避免新增未在登录/跨页入口加载的脚本。native dialog 提供键盘/Esc/焦点隔离，名称用 textContent；目录状态不可确认时阻止发送，写请求不自动重试。资源版本在 v2 入口统一更新。
>
> ## 证据推动的修正
>
> - MySQL TIMESTAMP 默认秒精度会影响快速改绑；V22 双方言统一 TIMESTAMP(6)，服务端不生成早于创建时间的 updatedAt，以版本作为并发依据。
> - 完整回归发现旧 profile 装配夹具缺少 RuntimeExecutionModeRepository，补入隔离 mock；旧 Run 审计故障夹具按动作排除独立目录选择审计，不降低生产严格性。
> - 读取资料原有 HTTP 错误映射保持兼容；新选择参数/目录/冲突按新 API 合同映射。v2 资料守卫与历史 v1 分开，不用新元数据要求阻止历史 DEFAULT 恢复。
>
> 验收范围沿用原清单，部署及回退见 README、docs/configuration.md、docs/operations.md；本任务不含提交、推送、正式发布或部署。
>
> ## 关联产物
>
> - [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
> - [实施提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
> - [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
> - [实际实现](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
> - [实施账本](../progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)
