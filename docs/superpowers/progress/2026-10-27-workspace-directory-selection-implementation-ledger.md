# 工作区目录选择进度账本（R2：可信Windows系统ACL兼容）

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

### 最终文档和改动范围验收

执行python target/workspace-acl-r2-implementation-20261028/validate_final.py通过：6份当前R2文档、108个相对链接及6个当前提示词绝对路径有效，R0/R1归档原字节不变；1196个基线文件中仅15个明确范围内既有文件变化，另新增2个生产类和2个测试类，范围外变化为0。108个最终源码文件与Rocky清单逐一匹配，JNA已进入可执行JAR；暂存区为空。git diff --check对本次涉及的已跟踪代码/生产文档通过；新类和六文档新增部分单独检查无尾随空白。既有application-mysql.yml末尾空行未为本次修复而修改。结果见同目录validation.json；此为提交前验证记录，代码现已本地提交。

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

## R2需求修订时验证记录（历史）

已执行：git rev-parse HEAD、git diff --cached --stat、git status --short；CodeGraph相关符号探索；源码/六文档/POM/生产说明只读核对；Microsoft Learn与JNA上游源码核对；Java21固定SID公开查询探针。探针两个UserPrincipalNotFoundException是可行性证据，不是业务失败或功能测试通过。

文档写入由target/workspace-acl-r2-20261028/revise_workpack.py完成，检查由check_workpack.py执行：六份当前R2/日期/mode和T15–T18状态一致、相对链接/当前提示词绝对路径存在、代码围栏配对；归档R1全文/R0历史SHA256不变，六文档之外仓库已跟踪/非忽略文件哈希不变。结果保存在同目录validation.json，属于忽略的本机证据，不加入版本控制。

本轮无业务实现，因此未执行Maven业务测试、Windows ACL写入测试、真实Run、浏览器、Provider或Rocky双库。T16–T18仍待做，不能标记完成。生产README/configuration/operations/release-notes未改：运行行为未变化，避免提前宣传支持，更新明确安排T18。未来若SID解析依赖或真实Windows环境不可用，先留下失败证据和受影响范围，不能以“跳过”替代验收。

本轮文档校验补充：git diff --check报告cm-agent-server/src/main/resources/application-mysql.yml:22有既有文件末尾空行；该文件在修订前后SHA256一致，属于原脏改动，未为消除提示而修改。六文档新增前缀单独检查无尾随空白；当前提示词六个绝对路径按CRLF规范化后核对存在，校验脚本首轮因换行正则未匹配已修正，不影响文档内容。

## 核对来源

- [Windows固定SID定义](https://learn.microsoft.com/en-us/windows/win32/secauthz/well-known-sids)：SYSTEM和本机Administrators的稳定身份，不以界面显示名代替。
- [Windows文件安全与访问权](https://learn.microsoft.com/en-us/windows/win32/fileio/file-security-and-access-rights)：父目录和子文件安全描述符、实际OS读取权限。
- [JNA Windows工具上游源码](https://github.com/java-native-access/jna/blob/master/contrib/platform/src/com/sun/jna/platform/win32/Advapi32Util.java)：仅作原生描述符/SID适配可行性线索，目标版本实施时重新核实。

## 当前关联产物

- [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
- [执行提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
- [需求设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
- [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
- [实际实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)

## R1 历史原文（R2修订前全文，禁止作为当前实施提示）

# 工作区目录选择进度账本（R1：用户电脑原生文件夹）

日期：2026-10-27；主题：workspace-directory-selection-implementation；当前修订：R1；修订日期：2026-10-27；模式：mode=default。

## 2026-10-28 当前补充：T12/T13目录单选缺陷修复

本节记录用户报告的chat.html“可选目录选中后无法互相取消”缺陷，沿用2026-10-27/R1、mode=default与原T0–T14编号。本次为T12/T13局部修复，不新增需求修订、不宣布整个T12/T13或T14门禁完成。此前已经进入T11–T14实施，下面T10标题及“待做/未实施”文字保留为形成当时的阶段历史，不能据此否认已有在途代码，也不能据本次局部通过宣布R1整体完成。未提交。

根因：app.js的element()只处理className/text/type/value，选择器传入的name未落到DOM；默认、不使用及登记项因此不在同名单选组。已在bindWorkspaceChoice中显式设置radio.name="workspace"，恢复浏览器原生互斥与键盘分组语义。重复点击当前单选项仍保持选中；清除资料来源须选择“不使用工作区”并保存。点击取消/关闭只丢弃草稿，再打开恢复已保存来源。

共享app.js缓存版本由2.0.37升为2.0.38，九个v2页面与ConsoleResourceTest保持一致；不修改全局element()行为、业务API、数据库、配置、权限、上传或审批流程。保护已有配置、.gitignore、.workbuddy及无关脏改动。

验证：node --check app.js通过；node --test cm-agent-console/src/test/js/*.test.cjs共177项全部通过，含4项新增接线回归；Java21.0.11/Maven3.9.4执行mvn -q -pl cm-agent-console -am test，ConsoleResourceTest共16项全部通过。JSDOM26.1.0加载真实chat.html及真实element/bindWorkspaceChoice：修复前可同时选中DEFAULT、NONE、product，修复后始终唯一选中，NONE保存、取消恢复及搜索重建通过；这不是实际浏览器或原生窗口验收。当前localhost:8080的chat.html和app.js均HTTP200，已提供2.0.38引用和显式分组修复。

现场浏览器验证未执行：cua.getBrowser返回“Local browser use is disabled in Work settings”，没有改设置或经其他UI通道绕过。未对当前运行服务重启、登录或保存用户会话。detector扫描原chat.html得到9项提示（2 warning/7 advisory），涉及既有颜色、卡片伪元素条纹与阴影，本次无布局/CSS改动，不扩展处理。DOM验证不能替代当前用户页面、390px或键盘实测；这些保留为未验证项。后端/双库/Provider未因本次前端局部修复重跑，历史通过不替代R1整体门禁。

实际证据位于target/workspace-radio-fix-20261028：js-tests.log、console-resources.log、dom-check.cjs、dom-result.json、detector.json。HEAD仍369418b3bdc08b096de7854f9d04c982ec8be050，暂存区为空，未提交、未推送、未合并、未部署。

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

### T10命令、环境及实际证据

- 基线HEAD仍369418b3bdc08b096de7854f9d04c982ec8be050，暂存区为空。开始读取AGENTS.md、相关POM/README/生产说明及六份当前R1文档；CodeGraph优先查询旧符号，未覆盖新增文件时定向复核。用户配置/.gitignore/.workbuddy及无关UI改动不覆盖。
- 本机JAVA_HOME=F:/java21，Microsoft OpenJDK21.0.11；Maven3.9.4运行同一JDK21。mvn -q -pl cm-agent-server -am -DskipTests compile通过。
- 本机最终命令：mvn -q -pl cm-agent-server -am "-Dtest=WorkspaceImportControllerTest,WorkspaceImportBodyReaderTest,WorkspaceImportRepositoryTest,WorkspaceImportConfigurationTest,WorkspaceImportTest,WorkspaceImportStorageTest,WorkspaceImportCleanupTest,DeferredWorkspaceFailureTest,RunControllerTest#未知导入来源统一隐藏且诊断同号不降级运行" -Dcm-agent.workspace-context.enabled=false -Dsurefire.failIfNoSpecifiedTests=false test。52项，失败/错误0，跳过1项；其中API用例10项，各包含多边界断言。日志target/workspace-import-t10-20261028/fast-final.log。测试显式覆盖用户本地配置，不修改配置本身。
- 初次测试编译发现JdbcConversationMessageRepository需要ObjectMapper/TransactionTemplate，已按实际构造器补齐；两库旧runs时间精度以秒对齐夹具，消息追加后更新时间不当作身份断言。没有改生产历史迁移来修测试。
- ssh rocky确认Docker23.0.6，镜像maven:3.9.9-eclipse-temurin-21内Maven3.9.9/JDK21.0.7。隔离目录/tmp/cm-agent-workspace-import-t10-20261028-4147f8，HEAD与本地一致；实际脏源码88文件SHA256核对，清单摘要d88e91e16fdb5941d88ad097f6070c6853c0dba70549c9476c7a01839ecac381。仅传必要源码/测试/控制台，不传用户application配置/.gitignore/.workbuddy；解包后逐一touch再编译，测试前后校验源内容。
- 初轮mvn -q -pl cm-agent-persistence test：70项，失败/错误/跳过0，含两库V24/native中文注释、请求关联、大小写/尾随空格、owner游标分页、原选择/配额/新JVM恢复。最终core/api/persistence/console内容与该轮逐文件一致，不重复无关数据库套件。
- 初轮完整server926项、失败/错误0、条件跳过61项；随后发现并补上外层事务拒绝审计回滚窗口，故不能仅用该轮替代最终实现。最终mvn -q -pl cm-agent-server -am -DskipTests install后，先mvn -q -pl cm-agent-server -Dtest=WorkspaceImportJdbcAuditTest test：PG/MySQL两项通过，验证回滚后审计保留且finish仅执行一次。
- 最终以CM_AGENT_TEST_WORKSPACE_JDBC=true在指定Rocky容器执行mvn -q -pl cm-agent-server test：929项，失败/错误0，条件跳过41项；包含PG旧工作区JDBC合同、当前API/权限/日志/新双库拒绝审计及其余完整server回归。跳过项受原环境开关控制，不计通过，不代表真实Provider/外部浏览器已验。
- 同一容器环境执行mvn -q -pl cm-agent-server -Dtest=HarnessWorkspaceContextJdbcFlowTest -Dcm-agent.test.database=mysql test：20项，失败/错误/跳过0。与全套重复类不重复累计为新的总数。没有本机Docker、全局清理、部署或触碰原8080服务。
- 新增分支验证包含401/403、未知/跨主体/租户/Agent404、DRAFT绑定/版本/重复409、期限410、HTTP体413、文本/实长/hash/预算422、配额/真实读取槽429、超时/存储/审计503、未知500；errorId前后端同号、脱敏日志/堆栈、memory撤销补偿、SQL拒绝回滚后审计保留。
- 当前JS文件与T9相同，本轮未重跑Node/浏览器；T9的157不计本轮新增通过。T11–T14的真实导入Run/冻结/审批/停止、原生窗口/取消/远程HTTPS/确认前零文件网络/Provider等仍待实施和验收。
- 最终证据target/workspace-import-t10-20261028/remote-summary.json、远程target/t10-final.log/t10-mysql.log以及source-check日志；均为忽略的验证产物，不纳入版本控制。六份当前文档/R0历史/链接/空白检查后交付；未提交。

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

### T9实际命令、结果及修复

1. 本机设置JAVA_HOME=F:/java21；java -version为Microsoft OpenJDK21.0.11，mvn -v为Maven3.9.4、同一JDK21；未修改原Java17默认环境。
2. mvn -q -pl cm-agent-server -am -DskipTests compile通过。
3. 本机最终命令：mvn -q -pl cm-agent-server -am "-Dtest=WorkspaceImportTest,WorkspaceImportRepositoryTest,WorkspaceImportStorageTest,WorkspaceImportCleanupTest,WorkspaceImportConfigurationTest,WorkspaceContextConfigurationTest,RunControllerTest#尚未接入的导入来源明确拒绝且诊断同号不降级运行" -Dcm-agent.workspace-context.enabled=false -Dsurefire.failIfNoSpecifiedTests=false test。44项，失败/错误0、跳过1项；报告位于各模块target/surefire-reports及target/workspace-import-t9-fast.log。
4. 本机MockMvc首次被用户既有脏配置开启workspace-context与合成Runtime不支持的启动门禁拒绝；仅在测试命令中显式关闭该配置，不改用户配置或生产门禁。首次测试编译的Container通配导入歧义已改显式导入。
5. ssh rocky确认Docker23.0.6；指定maven:3.9.9-eclipse-temurin-21内Maven3.9.9/JDK21.0.7。隔离目录/tmp/cm-agent-workspace-import-t9-20261028-27e62b；本地/远程HEAD均369418b3bdc08b096de7854f9d04c982ec8be050。没有本机Docker、全局清理或部署操作。
6. 最终68个实际脏源码/测试/控制台文件（含必要R0改动，不含用户application配置/.gitignore/.workbuddy）逐文件SHA256核对，清单摘要38dba1c4f3fffd02edb4c43abb1a233d4e5c56b8b1056a425fe41fdceaab760a；解包后逐一touch再编译，测试前后均验证哈希。首次证据清单CRLF使sha256sum把CR当文件名，已仅将清单转LF重验，没有改源码换行或Git配置。
7. Rocky容器执行mvn -q -pl cm-agent-server -am -DskipTests install通过；mvn -q -pl cm-agent-persistence test共68项、失败/错误/跳过0。含PG16-alpine/MySQL8.4真实V22升级V23、全表/字段原生中文注释、20并发仅8项预留、tenant/owner/Agent隔离、共享读取阻止GC、配额回收、IMPORTED元数据及新JVM读取。
8. 首次持久化68项中，旧JdbcRuntimeCheckpointRepositoryTest两项仍断言新增3个迁移而失败；已更新为4并同步说明V23，最终完整重跑通过，V22历史未修改。
9. Rocky容器执行mvn -q -pl cm-agent-server -Dcm-agent.workspace-context.enabled=false -Dtest=WorkspaceImportRepositoryTest,WorkspaceImportStorageTest,WorkspaceImportCleanupTest,WorkspaceImportConfigurationTest,WorkspaceContextConfigurationTest,RunControllerTest test，配合下述存储补验最终覆盖58项、失败/错误/跳过0。包含原RunController完整回归与新拒绝分支、密文错配/实例重建/新JVM、实际有界读取、GC补偿和日志脱敏。
10. 完整server套件、真实Provider及原生窗口未执行，属于T10–T14后续门禁，不用本次定向结果替代。远程证据/tmp/cm-agent-workspace-import-t9-20261028-27e62b/target/t9-final.log，本机脚本/哈希/结构化结果位于忽略目录target/workspace-import-t9-20261028-27e62b/；不加入版本控制。未提交。

补充验收：最后增强存储ACL/对象链接/等长摘要错配断言后，本机和Rocky均执行mvn -q -pl cm-agent-server -am -Dtest=WorkspaceImportStorageTest -Dsurefire.failIfNoSpecifiedTests=false test。Windows为6项中1项Linux符号链接用例跳过，Rocky为6/6通过；不以Windows未创建链接冒充通过。对应补验日志target/workspace-import-t9-storage-final.log与远程target/t9-storage-final.log。两次server测试按最终各类报告汇总覆盖，不重复累计同一测试。

node --check共享console-core.js通过；node --test cm-agent-console/src/test/js/*.test.cjs当前157/157通过（非R0历史155），包含两个不支持来源身份保留及零写入行为回归。没有新增原生UI。

R1于2026-10-27完成T8文档修订；2026-10-28完成T9，用户再次要求“继续下一步”后实施T10，当前T10业务及对应验收完成。保留R0与T9历史，T11–T14仍待做；mode=default，不因读取提示词自动扩大范围。未提交、未推送、未部署。原分析包保留于 [原分析提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)。

## 2026-10-27文档修订时基线

HEAD仍为 `369418b3bdc08b096de7854f9d04c982ec8be050`；暂存区为空。R0实现及用户配置等全部在原工作树中，当前不能因HEAD未变化而把业务源码误当干净基线。本轮只更新本组六份文件，不读取/打印配置凭据，不改业务源码、生产说明、迁移、.gitignore或.workbuddy。未提交。

本轮明确授权是需求优化和工作包修订；只完成T8。当前原生文件夹/上传/IMPORTED功能仍不存在，当时T9–T14全部待做；2026-10-28已完成T9，对应状态见上方。原有R0“无已知失败”只说明旧登记目录功能，不代表新需求已经支持。

## 修订差异与采用记录

| 差异 | 类别 | 调整与依据 | 影响任务 | 当前采用 |
|---|---|---|---|---|
| D1 | 调整 | 用户要求原生本机文件夹入口，移出旧“原生选择为非目标”限制 | T12/T14 | 用户明确要求，已采用 |
| D2 | 新增 | 本地预览/用户勾选确认后上传受控快照，解决远程Server无本机路径访问能力 | T9/T10/T12 | 默认B1/B2，不冒充用户逐项确认 |
| D3 | 新增 | 增加IMPORTED及manifest身份，旧三态不变；新迁移不修改V22 | T9/T11/T13 | 默认B3–B5 |
| D4 | 新增 | 有界文本/加密存储/期限/配额/租约与导入及派生会话owner隔离 | T9/T10/T13 | 默认B4–B8 |
| D5 | 澄清 | 真正OS选择框需实际验收；目录input是文件夹通道，网页dialog/普通文件框/setInputFiles不替代 | T12/T14 | 已采用，浏览器目标待实测 |
| D6 | 调整 | 复用原六路径R1，保留T0–T7完成历史，新增T8–T14 | 全部文档 | 已采用，生成提示词不实施 |

R0的A1/A3“仅服务端目录”范围由D1/D2扩展，A2只读保留；A4首发后固定保留，A5三态兼容扩展为保留三态+IMPORTED，A6按Run冻结保留。原分析包自身的R0描述不覆盖，作为形成历史引用。

## 任务与证据适用性

| 编号 | 状态 | 本轮结果/未执行项 |
|---|---|---|
| T0–T7 | 完成 | 保留R0原实现及历史通过；本轮未重跑，不能证明本机选择/导入/新ACL |
| T8 | 完成 | 规范、源码/工作树、官方API调研；六份R1文档、来源/默认决定/差异/历史保留检查 |
| T9 | 完成 | 2026-10-28领域/双仓储/加密卷/V23/租约GC及对应验收完成，见本次实施记录 |
| T10 | 完成 | API、V24关联、可见分页/入口owner、脱敏诊断和回滚后严格审计已实施，见T10证据 |
| T11 | 待做 | 导入资料实际运行/冻结/审批/停止与新守卫未实施 |
| T12 | 待做 | 原生API/目录input、预览/确认上传、UI状态机未实施 |
| T13 | 待做 | 新自动化、Rocky双库及进程/密文恢复未执行 |
| T14 | 待做 | 实际OS窗口、远程部署、真实Provider、更新生产说明未执行 |

## 2026-10-27文档修订检查

- `git rev-parse HEAD`、`git status --short`、`git diff --cached --stat`：核对上述基线、空暂存和原有脏文件；没有提交或恢复文件。
- 读取当前AGENTS.md、requirement-workpack/SKILL.md及artifact-contract、六份R0产物、相关POM/README/配置说明；未读取配置文件中的秘密值。
- `.codegraph`存在，先`codegraph explore` Workspace选择/快照和Skill加密存储；部分结果相邻/截断，后定向Get-Content/rg复核WorkspaceSelection/Binding/Resource/Properties、真实chat.html与app.js/console-core.js、当前迁移。
- 官方资料核对：MDN showDirectoryPicker、webkitdirectory、webkitRelativePath及WICG两份规范，核对日期2026-10-27，链接见设计。首次批量网页open连接失败，改搜索与规范open取得内容；没有把请求失败写成已获取资料。
- 文档验证通过：六文件当前R1/2026-10-27/mode=default一致；T0–T7保留完成、T8完成、T9–T14待做；当前提示词仅一个有效text代码块，六个绝对引用存在；共78个本地相对链接有效；六份R0引文去除引用前缀后与修订前全文一致。
- 使用 `git -c core.autocrlf=false -c core.whitespace=trailing-space,space-before-tab,cr-at-eol diff --no-index --check -- NUL <本轮单个文档>` 逐文件通过；六文件均无行末空白。首次校验脚本遇Windows默认GBK子进程解码及CRLF被误判空白，已改为UTF-8解码和显式cr-at-eol重新检查，未修改Git配置或批量转换其他文件。
- 对照R0已保存源码清单，45个业务/测试/控制台文件SHA256全部一致，未发现本轮业务源码漂移；暂存区仍为空。检查备份与结构化结果仅存于忽略的 `target/workspace-directory-selection-r1/`，不新增版本控制产物；本轮交付仍为原路径六份文档。

没有运行Maven/Node业务测试、JDK构建、Docker/数据库/SSH、真实模型或浏览器原生窗口测试：本轮仅文档修订。R0旧通过数字保留在历史区，不当本轮证明；没有枚举/读取/上传用户本机资料，也没有申请本机文件系统权限。

## 2026-10-27修订时未验证边界

B1–B8为默认方案，不是用户逐项确认；具体浏览器版本/OS/安全上下文/嵌入策略、上传存储卷/密钥/配额、双库约束迁移和新权限覆盖仍需T9–T14实际验证。当前无已复现外部阻塞，不把“尚未验证”写成“不可用”；若无法提供某OS/浏览器，按设计说明未验范围。上传限额和TTL均为拟定配置值，未生效。

生产说明/发布说明本轮未更新，因为还没有业务行为变化；更新安排在T14，避免把新需求写成已发布能力。需求修订不自动执行提示词、提交、推送、部署、回滚R0或安装本机helper。下一步须用户明确授权T9–T14实施。

## 关联产物

- [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
- [执行提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
- [需求设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
- [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
- [实际实现说明](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)

## R0 历史原文（仅历史，不作为当前执行命令）

下方保留本轮修订前全文，状态及限制仅在R0范围内有效。当前范围、待办和授权以本文上方R1为准。

> # 工作区目录选择实施账本
>
> 日期：2026-10-27；主题：workspace-directory-selection-implementation；修订：R0；模式：mode=default。
>
> 来源：[原分析执行提示词](../prompts/2026-10-27-workspace-directory-selection-prompt.md)。基线 `369418b3bdc08b096de7854f9d04c982ec8be050`，接续中断后已有相关草稿；无关配置、.gitignore、.workbuddy、发布说明改动保留。未提交。
>
> ## 当前结果与提交边界
>
> T0–T7 实施与本需求验收完成。未提交、未推送、未部署；未触碰原 8080 服务或已有数据库。既有 .gitignore、application.yml、application-mysql.yml、application-ok.yml、.workbuddy 和发布说明中的原改动保留，未将其作为验证补丁传输。
>
> ## 任务状态
>
> | 编号 | 状态 | 证据 |
> |---|---|---|
> | T0 | 完成 | 读取规范/技能、原分析六份文件、POM/README/配置及 Git；CodeGraph 优先查询，未覆盖部分定向复核 |
> | T1 | 完成 | catalog 登记/边界及旧配置负例，WorkspaceContextConfigurationTest 6 项通过 |
> | T2 | 完成 | 两库 V22、CAS 与 tenant/Agent 隔离、表字段中文注释；MigrationTest 及 JDBC 仓储通过；双库独立 JVM 写入退出后新 JVM 恢复选择与 Run 元数据 2 项通过 |
> | T3 | 完成 | 真实 MockMvc、权限/创建者/版本/冲突/目录失效、同步/SSE兼容与脱敏同号；RunControllerTest 34 项通过，完整服务端通过 |
> | T4 | 完成 | Harness/ReAct 各 20 项共同合同；PostgreSQL/MySQL JDBC 各 20 项，包括元数据缺失拒绝、故障收口和时间精度/时钟偏差回归 |
> | T5 | 完成 | 155 项 JS 行为回归、两份脚本语法和 ConsoleResourceTest 15 项通过；真实页面操作见下 |
> | T6 | 完成 | Rocky 指定 Maven21 容器完整服务端、双库持久化与定向 MySQL 通过；明确跳过项不算通过 |
> | T7 | 完成 | 真实 DashScope 两策略各 1 个测试、内部各 3 个 Run；浏览器桌面/390px、刷新、Esc、旧快照审批及停止；生产说明/六份产物完成 |
>
> ## 实际环境和源码一致性
>
> 本机原默认是 Java17；构建显式使用 `JAVA_HOME=F:/java21`，已核对 `java -version` 为 Microsoft OpenJDK 21.0.11，Maven 3.9.4 使用同一 JDK。未在本机运行 Docker/Testcontainers/JDBC/Flyway。
>
> 远程通过 `ssh rocky`，Docker Server 23.0.6，镜像 `maven:3.9.9-eclipse-temurin-21` 内 Maven3.9.9/JDK21.0.7；临时数据库为 PostgreSQL16-alpine、MySQL8.4。隔离验证仓库：`/tmp/cm-agent-workspace-selection-20261027`。从本地 HEAD bundle 克隆，远程 HEAD 与本地均为 `369418b3bdc08b096de7854f9d04c982ec8be050`。45 个业务/测试/控制台文件逐一 SHA256 一致；最终清单摘要 `4e08fdf4eecb491234d760f56aa5b3c81c18eaf7f22198eefb5e506e23c0d39f`。正文和用户配置不在补丁内。最终同步修正枚举中文说明及 record JavaDoc 标签逐行格式后，再次核对哈希并执行快速配置回归。
>
> 开始曾使用零时间戳解包以避免远端时钟警告，导致 Maven 增量编译复用旧 class；已改为校验后刷新所有清单内源文件时间戳再编译，后续结果才作为当前证明。未用只比 HEAD 的方式声称脏补丁一致。
>
> ## 命令及最终结果
>
> | 实际命令/操作 | 最终结果与证据 |
> |---|---|
> | `mvn -q -pl cm-agent-core,cm-agent-agentscope-adapter,cm-agent-console -am test`（本机 Java21） | core 97 项、adapter 157 项（3 项环境条件跳过）、console 15 项，均无失败；`target/workspace-selection-fast-tests.log`、模块 Surefire 报告 |
> | `node --test cm-agent-console/src/test/js/*.test.cjs` | 155/155 通过；含 6 项目录状态机与跨页组件清理回归，`target/workspace-selection-js-tests.log` |
> | `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`、`node --check cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js` | 均通过 |
> | `mvn -q -pl cm-agent-server -am -Dtest=WorkspaceContextServiceTest,WorkspaceContextConfigurationTest,HarnessWorkspaceContextFlowTest,ReActWorkspaceSelectionFlowTest,RunControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | 定向合同通过；后续新增时间精度回归在 Rocky 最终20项共同合同验证，不冒充本机旧19项结果；`target/workspace-selection-final-contract-tests.log` |
> | `CM_AGENT_TEST_WORKSPACE_LIVE=true`，`mvn -q -pl cm-agent-server -am -Dtest=WorkspaceSelectionLiveProviderTest -Dsurefire.failIfNoSpecifiedTests=false -Dcm-agent.test.workspace.live.mode=react test` | 退出0；真实 DashScope OpenAI兼容协议，所选 A/B 各返回各自随机资料，NONE 返回无参考资料；`target/workspace-selection-live-react.log` |
> | 同上，`-Dcm-agent.test.workspace.live.mode=harness` | 退出0；同样3个真实 Run 通过；`target/workspace-selection-live-harness.log`。Key仅使用现有环境，不打印/写入本工作包 |
> | Rocky 容器 `mvn -q -pl cm-agent-server -am -DskipTests install` | 为独立 server 测试安装同一待验模块，退出0；`remote-install.log` |
> | Rocky 容器 `CM_AGENT_TEST_WORKSPACE_JDBC=true mvn -q -pl cm-agent-server -Dtest=HarnessWorkspaceContextJdbcFlowTest -Dcm-agent.test.database=mysql test` | 20项，无失败/跳过；`remote-mysql-final.log` |
> | Rocky 容器 `CM_AGENT_TEST_WORKSPACE_JDBC=true mvn -q -pl cm-agent-server test` | 895项、0失败、0错误、41项环境条件跳过；包含 PostgreSQL 工作区20项和修正后的 profile/JDBC审计回归；`remote-server-final.log` |
> | Rocky 容器 `mvn -q -pl cm-agent-persistence test` | 66项、0失败、0错误、0跳过；包含双库迁移、原生中文表/字段注释、CAS及两个新JVM恢复用例；`remote-persistence-final.log` |
> | 最终 scoped `git diff --check`、工作包链接检查 | 本次源码范围空白检查通过，六份文件42个链接有效。全工作树另有原 application-mysql.yml 的末尾空行，未改用户配置 |
>
> 远程报告的跳过用例受原测试环境开关控制，未计入通过；本需求真实 Provider 门禁在本机另行显式开启执行，工作区 JDBC 门禁在 Rocky 显式开启。自动化通过不代表所有厂商、模型、真实设备或所有角色的完整认证。
>
> ## 浏览器实际证据
>
> Playwright CLI 运行真实 Chrome。独立端口18081，默认 memory，随机私有合成目录，不使用当前部署资料。先用合成 Runtime 验证普通操作，再使用实际 AgentScope Harness + 本机回环模型、无副作用 HIGH 工具验证真实审批和停止链；这些不是公网模型证明，真实模型结果见上表。
>
> - 桌面1440×1000与窄屏390×844：选择弹层、长中文名称、筛选、取消、Esc关闭及焦点返回；390px时 `documentElement.scrollWidth == innerWidth == 390`，弹层352px，无页面级横向溢出。
> - 首发前选择甲目录，经 ensureConversation 自动创建时携带 SELECTED；回答出现甲目录独有资料。首发后选择锁定，刷新仍从后端恢复甲目录。
> - 新会话换目录选择乙目录，回答出现乙目录独有资料；运行详情展示该 Run 的 reference-b 及固定名称。
> - 503目录网络错误注入时等待前端显示同一合成errorId，发送受锁定；移除拦截后显式重新读取恢复，不自动重试写请求。
> - 在实际 HIGH 待审批时改写合成源文件，再点允许并继续；恢复回答仍为“审批恢复完成 · 目录甲独有标记”，不是改写后的资料，选择仍为甲目录。
> - 发送延迟回环任务后“停止本轮”；页面显示“本轮已停止，已保留输出内容”，发送重新启用，选择仍为甲目录。
> - 已验证跨页运行详情与 returnTo 登录恢复；v1共享脚本资源/行为回归通过。切到v1后按其旧登录模型返回登录，不声称v1与v2共享登录态。
>
> 截图：[桌面弹层](../../../output/playwright/workspace-selection/desktop-picker.png)、[390px弹层](../../../output/playwright/workspace-selection/mobile-picker.png)、[甲目录首发锁定](../../../output/playwright/workspace-selection/mobile-bound.png)、[乙目录长名称](../../../output/playwright/workspace-selection/mobile-second-bound.png)、[运行详情](../../../output/playwright/workspace-selection/mobile-run-detail.png)、[桌面锁定](../../../output/playwright/workspace-selection/desktop-bound.png)。操作快照保存在仓库忽略的 `.playwright-cli/`；临时日志及测试产物不加入版本控制。
>
> ## 已解决问题与方案差异
>
> 初始相关草稿尚未接入运行；本任务接续而非覆盖。编译/合同阶段修正 PermissionEvaluator 的方法名、JwtSession 主体解析、登记列表 null 检查与路径参数绑定、兼容守卫和 memory 收口。浏览器修正忙碌状态结束后重新读取按钮未恢复。回归夹具修正迁移数量、生产 profile 部署门禁依赖，以及 Run 生命周期故障注入不应被独立目录审计提前触发；生产严格性未降低。
>
> MySQL 的秒精度及细小时钟偏差可能导致空会话快速改绑被领域时间不变量拒绝；新增 V22 双方言使用 TIMESTAMP(6)，生成更新元数据时不早于原创建时间，版本继续作为CAS依据。添加故意未来创建时间的 HTTP 回归并在两库验证。没有修改任何已发布历史迁移。
>
> 采用统一 WorkspaceBinding 加两组仓储方法，替代分析阶段拟定的两个 record 名称；新增独立会话 Run 选择视图，原 Run 详情附加 workspaceSelection。资料读取原有 HTTP 错误映射保持兼容。新选择元数据不含正文或宿主绝对路径。
>
> ## 剩余边界
>
> 没有待修复的本需求已知失败；未提交、未发布、未部署。上生产前仍需部署者按配置说明登记真实 tenant/Agent/资料卷、保留加密密钥并执行既有发布流程。回退必须先排空 SELECTED 活动/待审批 Run，不自动删除新表或反向执行迁移。完整server进程重建的所有运维场景不由本次两个JVM元数据探针全部代表，测试范围如上明确。专用18081验收服务、浏览器及三个已核验随机资料目录均已清理；原8080服务进程9424保持运行，远程隔离源码和测试证据保留。
>
> ## 关联产物
>
> - [实施清单](../checklists/2026-10-27-workspace-directory-selection-implementation-checklist.md)
> - [实施提示词](../prompts/2026-10-27-workspace-directory-selection-implementation-prompt.md)
> - [实施设计](../specs/2026-10-27-workspace-directory-selection-implementation-design.md)
> - [实施计划](../plans/2026-10-27-workspace-directory-selection-implementation.md)
> - [实际实现](../implementation/2026-10-27-workspace-directory-selection-implementation-implementation-design.md)
