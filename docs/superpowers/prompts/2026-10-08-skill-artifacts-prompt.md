# 技能沙箱文件产物执行提示词

日期：2026-10-08｜主题：skill-artifacts｜修订：R0｜工作模式：mode=default

本工作包是独立新增需求，关联原技能沙箱工作包；本轮交付需求分析与六份文档，业务实施尚未执行。普通工程细节按默认模式自主取舍，所列方案是自主假设，不代表用户逐项确认。

[清单](../checklists/2026-10-08-skill-artifacts-checklist.md) · [设计](../specs/2026-10-08-skill-artifacts-design.md) · [实施计划](../plans/2026-10-08-skill-artifacts.md) · [实现说明](../implementation/2026-10-08-skill-artifacts-implementation-design.md) · [进度账本](../progress/2026-10-08-skill-artifacts-ledger.md)

本提示词已经生成，但本轮未执行其中的代码实施命令。它是可供用户后续明确授权实施的输入，不授予提交、推送、合并、部署或修改运行中服务的权限。

```text
在 F:/java/cm-agent 中实施 skill-artifacts 工作包 R0：为技能沙箱增加文件产物收集、持久化及带权限校验的下载，并接入控制台。

先读取：
F:/java/cm-agent/AGENTS.md
F:/java/cm-agent/docs/superpowers/checklists/2026-10-08-skill-artifacts-checklist.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-08-skill-artifacts-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-08-skill-artifacts.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-08-skill-artifacts-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-08-skill-artifacts-ledger.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-08-skill-artifacts-prompt.md
以及关联的原沙箱工作包、POM、README、配置与当前代码。

工作模式 mode=default：普通工程细节自主处理并记录假设，不主动弹普通方案确认；用户后续切换模式时从其新指令。先核对 Git HEAD/分支/暂存区/脏文件、已有实现和实际测试证据。使用 codex/ 分支，保护无关 application*.yml、根 index.html、.codex/、.workbuddy/；不得覆盖用户改动。存在 .codegraph 时先 CodeGraph，缺失或不匹配再源文件复核。

T0 已完成文档分析，复核即可，不重复当作实现；按依赖实际实施 T1～T8并补充必要测试，同步维护本组六份文档。原 LOCAL/SSH KEY/SSH PASSWORD/TLS Docker、固定包内 Python、禁网、非 root、无宿主挂载、限流、授权、读取预算、审计、审批恢复与发布门禁均保留。

实现固定 /workspace/output 与 CM_AGENT_ARTIFACT_DIR；只有包内固定 Python 可以执行。容器退出前用有界二进制协议收集指定目录普通文件，保留 --rm；禁止等待退出后从 tmpfs docker cp，禁止宿主挂载、从模型提交代码/命令/URL/路径。容器传回的帧、名称、长度、内容均不可信，校验数量、类型、实际字节、目录逃逸、软硬链接、特殊文件、协议截断与超时。同一连接负责执行、传输、清理，无回退。

采用服务端持久卷文件存储与 JDBC 元数据，提供存储 SPI，不实现 S3。独立 AES/GCM 二进制加密与独立部署密钥；文件不能进入 DB TEXT、模型上下文、检查点或日志。按设计的独立文件/调用/Run/租户配额实现跨实例预留、暂存原子发布、崩溃补偿、幂等、过期清理及下载/清理租约。新增双库方言迁移，先查下一个未占用版本；不修改旧迁移，每表每字段有原生中文注释。

治理调用只写 PENDING；Run 成功持久化和严格审计成功后再 READY。审批等待不下载；失败、超时、拒绝、撤销、检查点/数据库/审计/清理失败不得交付，执行补偿。产物字段只能从可信 tenant/principal/Run/skill/version/call 构造，不信任模型提供归属，二进制不返回给模型。

增加正式/会话 Run、TEST列表与统一下载 API。下载需当前认证、可信 tenant、Run owner、agent:read；TEST 另需 skill:read 和原 TEST owner，会话检查原 owner 边界。跨租户/主体404、未认证401、缺权限403、已授权过期410；列表只显示可见文件。下载前完成完整解密认证、大小/摘要校验和严格审计，attachment、安全中文名称、nosniff、private/no-store。不得公开静态路径、URL令牌或未经鉴权外链，首版不做预览、Range与共享。记录稳定 errorCode/errorId 和唯一诊断日志，流中断记录同编号与失败/中断审计，不输出内容或密钥。

控制台技能 TEST、运行详情和聊天回复展示文件名、类型、大小、有效期和鉴权下载。复用当前会话请求，fetch/Blob 安全保存，不把令牌放URL，不从模型Markdown提取链接。处理重复点击、过期、下载失败、登出/换租户/导航销毁及Blob URL释放；刷新、跨页导航和后退一致，移动端不溢出。

使用专用固定 Python 测试技能生成合法 DOCX，验证回传、重启存储、鉴权下载及文件结构和摘要；原 Anthropic docx.zip 的 Node.js 动态脚本兼容不在范围。当前 X1 检查点长度超限可能阻断真实模型验收，它不在本工作包的修复授权内，先记录/核实并独立推进可验证项，不关闭状态保存、截断数据或宣称现有 docx.zip 已适配。

Docker、JDBC、Flyway和容器测试只在 ssh rocky 执行，使用 maven:3.9.9-eclipse-temurin-21、PostgreSQL16-alpine、MySQL8.4。运行前核对Docker、Maven JDK21、远程HEAD和当前代码SHA-256；仅项目隔离夹具，不修改用户运行服务，不全局清理，不复制或输出真实凭据。完成本地快速单测、双库/四种连接容器回归、控制台Node/资源测试和真实浏览器下载；测试条件不足则记录实际阻塞与未执行验收，历史通过不得替代新验收。

新增 Java 的领域字段、枚举、SPI、生命周期、安全和异常分支按 AGENTS.md 落地中文注释/JavaDoc。实施后对齐 README、配置/运维/沙箱说明和 release-notes，并更新本组六份原路径的状态与证据。未通过项不能标完成。记录实际命令/结果、相关源码位置、提交状态，未提交写“未提交”。

本轮不提交、不推送、不合并、不部署。最终按 AGENTS.md 简洁报告实际变更、验证结果、T1～T8状态、风险和确切剩余阻塞，持续推进可独立工作直到验收或明确外部阻塞。
```
