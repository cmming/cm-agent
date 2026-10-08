# 技能沙箱文件产物进度账本

日期：2026-10-08｜主题：skill-artifacts｜修订：R0｜工作模式：mode=default

本工作包是独立新增需求，关联原技能沙箱工作包；本轮交付需求分析与六份文档，业务实施尚未执行。普通工程细节按默认模式自主取舍，所列方案是自主假设，不代表用户逐项确认。

[清单](../checklists/2026-10-08-skill-artifacts-checklist.md) · [执行提示词](../prompts/2026-10-08-skill-artifacts-prompt.md) · [设计](../specs/2026-10-08-skill-artifacts-design.md) · [实施计划](../plans/2026-10-08-skill-artifacts.md) · [实现说明](../implementation/2026-10-08-skill-artifacts-implementation-design.md)

## 基线与授权范围

- 2026-10-08，HEAD：`d0ee3a10dc8ec545e58ab36c0648c0c4b6cabc86`，分支：`codex/skills-version-console`。
- 用户本轮调用 requirement-workpack 新增文件产物需求；实际执行为分析与文档生成，生成实施提示词不等于执行其中命令。
- 原工作包：2026-09-30-skill-sandbox；本项独立新需求，不覆盖原 R0/R1/R2 历史。
- 原脏文件包含控制台根 index.html、application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/，本轮未改动。初始暂存区为空。
- 修订 R0，mode=default；A1～A7 为本次自主方案，非用户逐项已确认决定。

## 状态

| 编号 | 状态 | 实际证据与未执行验收 |
|---|---|---|
| T0 | 完成 | 已核对源码/规范/基线，并生成六份文件；本轮文档检查结果见下方 |
| T1 | 待做 | 未编写产物契约/配置/能力接口，兼容性和绑定测试未执行 |
| T2 | 待做 | 未修改固定引导程序或输出协议，容器文件回传测试未执行 |
| T3 | 待做 | 无新增产物表/存储/加密/配额/清理，双库与重启验证未执行 |
| T4 | 待做 | Run/TEST 发布和补偿未接入，审批/故障回归未执行 |
| T5 | 待做 | 无产物列表/下载 API，权限与流完整性验证未执行 |
| T6 | 待做 | 控制台未展示产物，Node/浏览器下载验证未执行 |
| T7 | 待做 | 固定 Python DOCX 夹具和端到端验收未执行 |
| T8 | 待做 | 无业务发布，生产配置/运维/release-notes 尚不写成现有功能 |

## 本轮实际取证

1. 读取 AGENTS.md、requirement-workpack/SKILL.md 与 references/artifact-contract.md；读取相关 core/adapter/server/persistence/console POM、README、生产配置及沙箱文档。
2. `git log -1 --format='%H %s'`、`git branch --show-current`、`git status --short`、`git diff --cached --stat`：确认上述基线/分支/脏文件，暂存区为空。
3. CodeGraph 优先查询 `SkillTrialController AgentRunResult PermissionEvaluator 文件下载`，返回已有运行/权限符号，没有覆盖产物实现；源文件补核未发现当前产物存储与下载接口。
4. `rg -n 'artifact|Artifact|StreamingResponseBody|Content-Disposition' cm-agent-server/src/main/java cm-agent-core/src/main/java -g '*.java'`：无匹配。此结果用于定位，结合实际 SkillSandboxBackend/DockerSkillSandbox 文本契约确认缺口，不以一次关键词检索证明整个仓库所有可能性。
5. `rg --files cm-agent-persistence/src/main/resources/db/migration`：核对最新方言脚本 V18。读取 RunRecord、SkillTrialController/Service、RunController、PermissionEvaluator 确认可信 principalId 与权限边界。
6. 核对沙箱 --rm、固定 Python、tmpfs、关闭顺序和 String 返回值；本轮未运行沙箱或修改服务。

## 本轮文档验证

实际运行 PowerShell 内嵌 `python -` 检查脚本：六文件存在，R0/date/topic/mode 一致，43 个相对链接全部可解析，清单与账本的 18 项任务状态检查通过，计划包含 T0～T8 共 9 项，执行提示词引用六个真实绝对路径，代码围栏完整，已知凭据模式未命中。脚本退出码 0，问题列表为空。

逐个运行 `git -c core.autocrlf=false diff --no-index --check -- NUL <本组文档绝对路径>`，六份均没有空白错误输出。单次 `-c` 只避免本机 LF/CRLF 转换警告，不改仓库 Git 配置；no-index 的差异退出码 1 是与空文件存在差异，不能直接判作格式失败，检查脚本同时核对输出为空与退出码不超过 1。首次检查把该差异退出码及转换警告误判为问题，调整检查判据后通过；未因此改写用户文件。另运行 `git diff --check`，无输出，退出码 0；该命令单独不足以验证未跟踪文档。

文档生成阶段未运行 Java、Docker、JDBC、Flyway、浏览器与真实模型业务验证：该阶段只生成文档，未修改业务源码，没有相应实现供验收。没有申请/读取/保存真实凭据，没有连接或修改用户服务。

## 外部条件与前置问题

| 编号 | 当前状态 | 影响 | 继续条件 |
|---|---|---|---|
| X1 | 已有日志证明检查点写入字段长度超限，最可能 encrypted_payload；具体字段与大小待复现，未修复 | 可能阻断原真实模型 DOCX 场景；不阻止直接固定脚本及下载接口验证 | 安全复现检查点容量并在独立已授权任务修复，保留真实模型验收缺口 |
| X2 | Rocky Docker/镜像/SSH/TLS 隔离夹具本轮未探测，非已确认环境阻塞 | 未来 T2/T7 集成条件未确认 | 实施时验证 Docker、JDK21、远程源码一致及项目专用连接 |
| X3 | 测试模型凭据及受控持久卷本轮未读取，非已确认环境阻塞 | 真模型验收和生产部署条件未确认 | 使用部署者提供的隔离测试配置，不写真实值到仓库/文档/日志 |

若后续发生实际连接/存储/浏览器错误，追加精确错误和受影响任务；不能把未尝试的环境写作已失败。对 X1 不通过关闭状态保存、截断数据或把文件塞入 AgentState 规避。

## Git 与交付

文档生成阶段实际变更范围仅本组六份文档；业务代码未实施，当时未提交、未推送、未合并、未部署。原用户改动保留。

## 2026-10-08 实施准备与提交记录

用户随后授权实施 T1～T8；已重新读取工作包并核对源码、Git 和环境，尚未开始业务代码修改时，执行被用户中断。T1～T8 继续保持待做，不把环境检查算作业务验收通过。

- 本机 `java -version`、`mvn -v`：当前默认 Java 17.0.13、Maven 3.9.4，尚未切换到项目要求的 JDK 21。
- `ssh rocky` 中只读检查：Docker Server 23.0.6；指定 `maven:3.9.9-eclipse-temurin-21` 临时验证容器报告 Maven 3.9.9、Java 21.0.7。未执行沙箱、数据库或模型业务测试，未操作用户服务。
- 原隔离目录 `/root/cm-agent-diagnostic-20261008` 的 HEAD 为 `5b881a8f0db8c0bf7cf2ce7d0f15c7c69a4c2443`，与当前本地基线不同；没有将该目录作为本需求的同版本验证证据，后续实施仍须同步并核对源码。
- X2 仅完成 Docker 与 Maven/JDK 基础可用性检查；SSH/TLS 项目夹具、镜像依赖、源码一致性与产物回传仍未验证。

用户最新指令“将当前修改提交”授权提交当前工作包。本次提交范围限定为本组六份文档，提交说明为“新增技能沙箱文件产物需求工作包”；提交编号以当前分支的 `git log -1` 为准，避免将提交前编号写成最终编号。没有将任何 T1～T8 标为完成。

提交前重新核对六份文件、43 个相对链接、任务状态、提示词路径、格式和已知敏感信息模式；通过后使用明确文件路径暂存，并检查 `git diff --cached --check` 与暂存文件清单。本次不推送、不合并、不部署；已有 application 配置、根 index.html、.codex/、.workbuddy/ 修改保持原状。
