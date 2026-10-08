# 技能沙箱文件产物需求清单

日期：2026-10-08｜主题：skill-artifacts｜修订：R0｜工作模式：mode=default

本工作包是独立新增需求，关联原技能沙箱工作包；本轮交付需求分析与六份文档，业务实施尚未执行。普通工程细节按默认模式自主取舍，所列方案是自主假设，不代表用户逐项确认。

[执行提示词](../prompts/2026-10-08-skill-artifacts-prompt.md) · [设计](../specs/2026-10-08-skill-artifacts-design.md) · [实施计划](../plans/2026-10-08-skill-artifacts.md) · [实现说明](../implementation/2026-10-08-skill-artifacts-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-artifacts-ledger.md)

## 需求与当前基线

用户提出：“新增一个需求支持增加文件产物收集、存储和带权限校验的下载功能”。目标是让技能脚本生成的 DOCX、PPTX、PDF 等文件在容器退出后仍可通过受控接口获取，而非仅返回文件路径。

Git 基线：`d0ee3a10dc8ec545e58ab36c0648c0c4b6cabc86`；当前分支 `codex/skills-version-console`；暂存区为空。本轮开始时已有 `application.yml`、`application-mysql.yml`、`application-ok.yml`、控制台根 `index.html` 以及 `.codex/`、`.workbuddy/` 修改或未跟踪项，保留原状。

## 当前证据

| 编号 | 当前事实 | 已核实位置 |
|---|---|---|
| E1 | 沙箱只执行固定技能版本的包内 Python 脚本，返回脱敏文本 | [DockerSkillSandbox](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/DockerSkillSandbox.java)、[SkillSandboxBackend](../../../cm-agent-core/src/main/java/com/cmagent/core/runtime/SkillSandboxBackend.java) |
| E2 | 容器使用 `--rm`，无宿主挂载，`/workspace` 与 `/tmp` 为各 16 MiB tmpfs | 同 E1；文件不得等容器退出后才尝试提取 |
| E3 | 执行完成后关闭句柄、复核授权、写严格审计，然后返回文本 | [GovernedSkillAccessService](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/GovernedSkillAccessService.java)、[ManagedSkillSandbox](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/ManagedSkillSandbox.java) |
| E4 | NORMAL/TEST 运行均有可信 tenant、principalId、runId；TEST 只允许创建者查询 | [RunRecord](../../../cm-agent-core/src/main/java/com/cmagent/core/domain/RunRecord.java)、[SkillTrialService](../../../cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillTrialService.java)、[SkillTrialController](../../../cm-agent-server/src/main/java/com/cmagent/server/web/SkillTrialController.java) |
| E5 | 正式运行查询已有 `agent:read`；产物权限不可仅复用租户内通用历史可见性 | [RunController](../../../cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java)、[PermissionEvaluator](../../../cm-agent-core/src/main/java/com/cmagent/core/security/PermissionEvaluator.java) |
| E6 | 当前生产源码未找到沙箱文件产物存储和下载 API；现有最新方言迁移为 V18 | 本轮 `rg` 与迁移文件清单复核，具体搜索见账本 |
| E7 | 用户日志提示检查点写入发生 MySQL 22001/1406；`encrypted_payload` 仍为 TEXT，高概率是载荷容量问题，具体列与真实载荷大小未确认 | [检查点写入](../../../cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcRuntimeCheckpointRepository.java)、[原迁移](../../../cm-agent-persistence/src/main/resources/db/migration/mysql/V11__add_tool_approvals.sql) |

CodeGraph 已优先查询，但结果未覆盖产物目标符号；随后直接复核上述当前源文件。既有沙箱历史验证不能证明文件回传与下载已经可用。

## 本轮采用范围

- 为 Python 沙箱增加指定输出目录、受限二进制文件回传；覆盖 LOCAL、SSH KEY、SSH PASSWORD、TLS。
- 采用服务端持久卷文件存储与 JDBC 元数据；提供存储扩展接口，首版不交付云对象存储后端。
- 绑定可信租户、主体、Agent、Run、技能版本、调用标识；以严格审计和运行终态控制可见性。
- 提供鉴权列表、下载；控制台技能 TEST、运行详情及聊天回复展示产物与下载入口。
- 提供过期清理、配额、崩溃补偿、下载完整性校验及错误诊断。
- 用专用包内 Python 测试技能生成可解析 DOCX，验证收集至下载链路。

不含：执行模型生成代码、开放 Node.js/Shell、直接适配原 Anthropic docx.zip、升级镜像依赖、文件上传、在线预览、匿名或永久公开链接、S3 实现、跨主体共享、管理员代取。原 ZIP 导入、模型读取、现有运行权限不因此扩大。检查点容量故障单独跟踪，详见 X1。

## 稳定任务与验收

| 编号 | 状态 | 目标与文件线索 | 依赖 | 验收条件 | 验证方法 |
|---|---|---|---|---|---|
| T0 | 完成 | 当前证据核对与六份工作包 | 无 | 六份齐全，链接与状态一致，未将业务标为完成 | 本轮文档检查，见账本 |
| T1 | 待做 | 产物领域对象、存储 SPI、配置、沙箱兼容契约；core/runtime、core/domain、server/config | T0 | 旧文本后端保持兼容；独立文件配额与 stdout 配额；能力接口返回真实策略；新增字段与 SPI 有中文 JavaDoc | core 单测、配置绑定与能力 API 测试 |
| T2 | 待做 | Docker 固定引导程序与有界流式收集；DockerSkillSandbox、DockerDaemonConnection、ManagedSkillSandbox | T1 | 退出前回传；只收集指定目录普通文件；拒绝越界、链接、设备、超限和非法帧；原隔离与固定端点策略保留 | 单测与 Rocky 本地/SSH/TLS 容器回归 |
| T3 | 待做 | 产物元数据 Repository、方言迁移、加密文件存储、配额与过期清理；core/repository、persistence、server/runtime/config | T1 | 服务重启可查可下载；PG/MySQL 字段注释齐全；并发配额不穿透；损坏、满盘和遗留临时文件可诊断与收敛 | 双库 Testcontainers、临时存储目录与故障注入 |
| T4 | 待做 | 治理、Run 终态和 TEST 生命周期接入；GovernedSkillAccessService、RunExecutionService、RunPersistenceService、SkillTrialService | T2、T3 | 运行未成功不开放下载；失败、撤销、审计/检查点错误不交付；审批恢复不重复产物；清理失败仍为失败 | 运行编排、审批、异常与幂等回归 |
| T5 | 待做 | 列表/下载 Controller、权限策略、审计和错误映射 | T3、T4 | 401/403/404/410 与稳定错误码对应；跨租户/跨主体不可读取；attachment、安全文件名、禁缓存；流失败有同编号日志 | MockMvc 安全矩阵、完整性与中断测试 |
| T6 | 待做 | 控制台 TEST/运行/聊天产物卡片及鉴权下载；skills.js、assets/app.js、skills/runs/chat.html | T5 | 文件名、类型、大小、有效期清楚；刷新/导航一致；下载失败可重试；登出和换租户撤销未完成请求与 Blob URL | Node、ConsoleResourceTest、真实浏览器桌面/移动测试 |
| T7 | 待做 | 端到端文件与隔离验收、原文本能力回归 | T2～T6 | Python 测试技能生成 DOCX 后下载字节/摘要相同且可解析；本地与 SSH/TLS 通过；失败不留下可下载产物 | Rocky Java 21 + PostgreSQL 16/MySQL 8.4 + Docker，浏览器下载 |
| T8 | 待做 | 更新生产配置/运维/发布说明，同步实施说明与账本 | T7 | 实际配置及备份/清理说明准确；命令、状态与阻塞有证据；未提交写“未提交” | docs 链接、差异、敏感信息自查 |

执行顺序：T1 → T2/T3 → T4 → T5 → T6 → T7 → T8；T2/T3 可独立推进，不强制启动子智能体。

## 验收门禁与现存问题

- 文件生成成功不等于 Run 成功或可下载；存储成功不等于权限与审计验收通过。
- 不能把路径字符串、模型声称“已生成”或 stdout Base64 当作文件交付证据。
- X1：真实模型 DOCX 场景已出现检查点持久化超限。此故障未修复、未复现具体字段；它可能阻断真实模型最终验收，但不阻止专用 Python 沙箱与下载 API 验证。不得为通过本需求关闭状态保存、截断状态或修改历史迁移。
- X2：真实模型、可信镜像依赖及远程连接的隔离测试条件本轮未探测。实施时在项目隔离环境核实，禁止操作用户运行中的服务。
- 本轮仅文档生成，不实施、不提交、不推送、不合并、不部署。
