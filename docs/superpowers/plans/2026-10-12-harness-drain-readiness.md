# MySQL 排空与迁移接续计划

日期：2026-10-12；主题：harness-drain-readiness；修订：R1；实际修订日期：2026-10-13；模式：mode=default。
当前状态：G11、G12、G13、G14完成；G5/G9/G10移出当前范围，保留历史阻塞，未完成Rocky生产部署。

## R1 当前实施顺序

| 编号 | 方法/涉及位置 | 完成标准 |
|---|---|---|
| G11 | 核对POM/README、CodeGraph、577份覆盖摘要和候选包；确认JDK21、端口、原密钥、原库排空及时钟 | 不采用未对应当前源码的旧包、不随机替换密钥；旧实例无活动入口 |
| G12 | 另持有SSH本机13306转发；显式mysql/JDBC/harness/fake=false/Studio=false/回环8080启动 | 正常启动门禁选择harness，JMX配置与健康/页面核实；保持两进程 |
| G13 | 密码/JWT仅内存；现有模型、临时无工具Agent真实运行；finally停用Agent；远端聚合复核 | 随机字符串匹配、成功且0工具；失败可关联、3Agent停用，Run/TEST/检查点/待审批无残留 |
| G14 | 前台脚本及CheckOnly；六文件R1、operations/release-notes、文件/链接/敏感信息/保护检查 | 原R0历史和移出项保留，不重做G4或生产Secret供给；未提交，正常停止/重启方法明确 |

不重新构建或重跑全量Maven：577份受验证覆盖文件与T6保持相同；本轮验证为实际启动/登录/模型及远端聚合，容器集成仍只能在Rocky执行。启动脚本是现有已验证候选包的本机复现入口，源码改变后必须重新构建核验，不能自动把旧摘要推广给新版本。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。

## R0 历史快照（2026-10-12）

以下为保留的首轮证据；其中当时的当前状态、react/170Run及未启用描述只适用于首轮，最新范围与状态以上方R1为准。

历史基线：R0，2026-10-12，mode=default。

| 编号 | 方法与涉及文件/环境 | 完成标准 |
|---|---|---|
| G0 | 用户确认目标，SSH检查时间、Docker和T6可执行包身份；读POM/运维/前轮工作包 | 目标、包摘要和JDK21证据明确 |
| G1 | CodeGraph定位当前停止/持久化合同，回读当前源码；原库只读查询逐Run状态、工具/审批/审计数量 | 有Run ID可关联证据，不读取正文/工具入参/凭据 |
| G2 | 私有mysqldump，临时内部Docker网络/MySQL8.4，实际还原后规范化全表数据比较 | 私有备份存在、全部表数据匹配；不影响其他容器 |
| G3 | 从T6候选JAR提取依赖，指定Maven3.9.9/JDK21容器运行临时探针；副本先验证，原库仅Flyway迁移 | 副本V21拒绝Harness且react不变；原库业务数据不变，迁移版本21 |
| G4 | 根据用户确认的中断测试，核对8个ID/归属/原状态/无治理关联；新V21备份；实际副本验证审计故障回滚与重复NOOP；现有仓储逐项收口，同事务严格审计；检查历史内容不变 | 8条FAILED、16新审计；其他Run/表/旧审计及目标输入输出消息保留；不重放或伪装主动取消 |
| G5 | G4结束后按运维流程重新排空、停止全部实例，核对配置/卷/密钥/沙箱与完整恢复，再受控验证 | 模式切换只由应用启动门禁；入口逐步恢复 |
| G6 | 六份文档、运维/发布说明、保护摘要检查；关闭专属副本/网络并保留私有备份 | 无无关修改、无凭据输出，未提交明确 |
| G7 | 按当前production安全绑定编写scripts/rocky-harness，私有/home目录暂存同包，bash -n和缺Secret负例 | 不创建容器或切换模式，模板不含凭据，缺材料拒绝 |
| G8 | 本机SafeConstructor读配置目录/环境别名，限定五ID密文ZIP；owner ACL，远端私有解包/摘要与权限复核 | 密文副本完整且私有，未读取主密钥或宣称认证成功 |
| G9 | 从管理员明确的受控来源供给7个Secret；验证现有密文和完整恢复，登记实际证据 | 无开发密钥/随机主密钥替换，最小权限DB应用账号、外部JWT可用 |
| G10 | 正常停止全部旧实例、冻结入口，先run.sh react做真实production验收；同样排空再run.sh harness | 健康/认证/模型/治理/审批/停止/文件/审计通过才开放入口，失败保持隔离查权威模式 |

临时探针不是生产API，源码/日志只在忽略目录和Rocky私有任务目录；新增Java生产代码为零。异常诊断留远端私有文件，只回传受控结果与失败类别。本轮只运行与迁移/还原有关的实证，不机械重跑T6真实模型/前端合同。

G4使用任务专属ReconcileInterruptedTests，密码由stdin传入；模式行/固定Run行锁和事务覆盖条件更新及16条审计。首次发现连接时拒绝，定位数据库TCP所有者为本机IDE项目JVM，通过JDK21本地JMX/Spring Admin正常关闭上下文，不强杀；仅在HTTP/DB连接释放且Admin注销后重试。G5已确定Rocky生产；残留IDE进程由正常IDE操作结束后再满足全部实例退出条件。生产Secret缺失时继续G7/G8等独立准备，不启动假凭据实例或开放端口。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[提示词](../prompts/2026-10-12-harness-drain-readiness-prompt.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。
