# 技能沙箱设计

日期沿用任务启动日 2026-09-30；验证跨日到 2026-10-01。初始 HEAD 为 `a72f9228eb07242f303d5e033adaf8b1a56e6241`，任务期间 HEAD 前移到 `5c60ad53372f231f004bf7057b96c92ad07cc441`，已重新核对并在远端采用相同 HEAD。用户于 2026-10-01 授权本地提交，本任务代码、测试及六份工作包纳入同一次提交；提交编号以本文件对应的 Git 历史为准，未推送、未部署。

用户已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、`.codex/` 和 `.workbuddy/` 改动保持不动。远程采用 HEAD 配置加本任务明确文件覆盖，不复制这些未提交配置或凭据。CodeGraph 索引无法定位当前 Skill 类，已先调用并回退源码核对。

## 目标和边界

首版只执行导入技能包内的 Python 3 脚本和同版本文本资源，模型不能提交代码、命令、镜像、runtime、环境变量或宿主路径。Docker 参数固定为禁网、非 root、只读根文件系统、无宿主挂载、无额外 capabilities、no-new-privileges、CPU 0.5 核、内存与交换内存各 128 MiB、PID 32、nofile 64；私有 /workspace 与 /tmp 各 16 MiB。默认执行 15 秒、stdin 与原始合并输出各 32768 字节、单实例最多 2 个容器；只允许收紧。沙箱默认关闭，开启后 .py 自动进入有效导入白名单，既有资源类型不改变。

非目标：宿主 Shell 执行、联网安装、任意语言、修改业务工具授权、上传二进制、持久化脚本工作目录。没有新增数据库表或字段，V15 更新 status 的原生注释。

## 调用链

模型 → AgentScopeSkillExecutionBridge → 当前 Run 公平门控 → SkillAccessGateway.execute → GovernedSkillAccessService → DockerSkillSandbox → 后置授权与严格审计 → 脱敏结果。

Core 默认方法使原函数式读取实现保持只读。只有技能和沙箱开关同时开启，适配器才注册 run_skill_script；原生 run_skill_code 始终关闭。模型输入只解析到目录中的固定技能 ID 和已登记 .py 路径，可信 tenant、主体、Agent、Run、版本从领域请求取值。

准备阶段在短工作单元内核对有效 RUNNING、快照、纪元、正式绑定或 TEST 授权，并检查现有读取预算。在快照锁下插入唯一 modelCallId 的 SANDBOX_PREPARED，执行 PREPARED 严格审计后提交。该记录 deliveredBytes 为 0，按不可变版本资源实际大小预留累计预算；不满足 TEST 成功读取门禁。相同调用标识不再执行，正常读取也不能重放此准备凭据。

STARTED 审计成功后在事务外创建一次性容器，通过 stdin 发送固定资源与输入；资源仅在容器 tmpfs 写入。并行虚拟线程处理管道，输出超限、超时、中断与异常均清理唯一容器。清理无法确认时返回失败并保留并发名额。成功后复核 Run、技能与授权，SUCCEEDED 严格审计提交后才交付脱敏输出。受控失败附稳定错误码与同一 errorId，审计/持久化故障保持独立错误分类与致命传播。

## 验收与兼容

T1 至 T4 验收见清单。能力接口仅增加字段，不暴露 daemon、镜像或 runtime。旧读取网关默认关闭执行。SANDBOX_PREPARED 为新增持久化状态，旧服务无法解析，写入后混用旧版本实例与回退需评估；V15 不修改历史迁移。生产应使用可信 digest 镜像和强化 runtime 或专用执行主机；故障退出后孤立容器由部署运维检查，不声称虚拟机级隔离或跨实例自动接管。

设计细化：初始计划复用成功读取记录作为准备凭据；实施审查后改为独立 SANDBOX_PREPARED，增加 V15 双方言注释与中文状态，避免放行发布门禁。此变化不扩大代码执行范围。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)
