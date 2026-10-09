# 标准库 DOCX 技能进度账本

当前修订：R1；实际修订日：2026-10-09；mode=default。当前交付为 1.0.1，修复沙箱输入流兼容缺陷。下方 R0 和首次提交记录保留历史；当前范围与验证以 R1 为准。本轮提交记录见进度账本，历史未提交状态保留。

R0；2026-10-09；mode=default。Git 基线：`c8e7a90a78915703cbbf5443de54280ba96af548`；未提交、未推送、未导入/发布/部署。

| 编号 | 状态 | 本次实际证据 |
|---|---|---|
| T0 | 完成 | 用户确认 CM Agent；README、POM、SkillPackageParser、SkillProperties、执行桥与产物校验源码已复核；工作包六份齐全 |
| T1 | 完成 | 固定标准库脚本、输入契约、入口和示例；AST 检查 imports 均为标准库，隔离运行通过 |
| T2 | 完成 | 四文件 ZIP CRC 通过；quick_validate 返回 Skill is valid；当前解析器源码编译后返回 CM_AGENT_PARSER_OK name=docx-stdlib resources=3 |
| T3 | 完成 | 本地十二项通过；Rocky Python 3.12 十二项通过及 PYTHON312_SANDBOX_DOCX_OK；Word 打开/导出成功；page-1.png 已查看 |
| T4 | 完成 | README、工作包与交付说明齐全；六份链接校验；差异与摘要检查，未提交 |

## 实际验证

- `python -I -S -B -m unittest discover -s skill-packages/tests -v`：12 项通过。首次运行 stdout 编码失败，修正后重新执行通过。
- `python -X utf8 skill-packages/package_docx_stdlib.py --output dist/skills/docx-stdlib-1.0.0.zip`：归档生成与 CRC 校验通过。
- `python -X utf8 C:\Users\chmi\.codex\skills\.system\skill-creator\scripts\quick_validate.py skill-packages/docx-stdlib`：Skill is valid。
- 本地 `java -version` 与 `mvn -v` 在显式 JAVA_HOME 下确认 Temurin 21.0.11、Maven 3.9.4。`javac` 编译当前 SkillPackageParser、ParsedSkillPackage、SkillPackageLimits 与临时 VerifySkillZip；随后 `java ... VerifySkillZip dist/skills/docx-stdlib-1.0.0.zip`：实际解析通过。临时 harness 位于 `dist/skills/validation/VerifySkillZip.java`。
- `ssh rocky`：Docker 23.0.6；`docker run --rm --network none maven:3.9.9-eclipse-temurin-21 mvn -v` 确认 Maven 3.9.9/JDK21.0.7。
- 独立远程工作区 `/root/cm-agent-docx-stdlib-c8e7a90-20261009` HEAD 与本地基线相同，通过增量 bundle 建立；传输目录为同名 `-transfer`。初探 `/root/cm-agent` 不存在，改用已存在基线仓库共享克隆后 fetch 本次 bundle，没有改其工作树。
- Rocky `docker run --rm --name cm-agent-docx-stdlib-20261009 --network none --read-only --user 65534:65534 --memory 128m --cpus 0.5 --pids-limit 32 --tmpfs /workspace:rw,size=16m,mode=1777 --tmpfs /tmp:rw,size=16m,mode=1777 --mount type=bind,src=/root/cm-agent-docx-stdlib-c8e7a90-20261009-transfer,dst=/input,readonly python:3.12-alpine python -I -S -B /input/container_check.py`：十二项通过，生成 3589 字节合法 DOCX，输出 PYTHON312_SANDBOX_DOCX_OK；容器自动删除。
- ZIP 本地/远程 SHA-256 一致：`d97827a4870ae04a46b90f04bfab77a5c1ca85c27c0075b71d0de99123c723c5`。ZIP 内文本才是技能，远程输入只读挂载仅用于验证。
- 示例由已解析的捆绑 Python 运行固定脚本生成；Word COM 打开并导出 PDF，Poppler 输出单页 PNG。转换报告 Symbol/ArialUnicode display font 提示，但实际 page-1.png 中文、符号、列表、表格及页码显示正常；不声称已验证任意长文档的所有分页。

## 限制与未执行

未运行全量 Maven、数据库或 Testcontainers 测试：没有 Java/API/数据库行为变更。临时 Java harness 是本次真实包校验，不代替持久化或运行 API 验收。

未在运行中的 CM Agent 上传、模型 TEST、发布、绑定或鉴权下载：本次仅生成 ZIP，未取得该环境状态和凭据，不自动改变用户服务。部署者需启用技能沙箱与独立文件产物能力，按真实模型闭环验证。没有把源码配置中的用户修改解释为线上已就绪。

保留用户原有 index.html、application.yml、application-mysql.yml、application-ok.yml、.codex/、.workbuddy/ 修改。交付新增仅 `skill-packages/` 与本组六份文档；生成文件都在忽略目录。没有修改记忆。

## 2026-10-09 提交交付记录

用户后续指令“将修改的内容提交”授权本任务的本地提交，覆盖首次交付时不提交的限制。上方“未提交”保留为历史状态。T0～T4 实现与验收不变，本轮仅同步提交记录，源码未变更。

提交说明：“新增标准库 DOCX 生成技能包”。范围为 `skill-packages` 的七份源码、测试和说明以及本组六份工作包，共十三份文件。最终提交编号通过 `git log -1 --format="%h %s" -- skill-packages` 查询，避免同一提交文档自引用编号。

本轮检查：提交前暂存区为空；显式文件清单纳入暂存；核对暂存差异、空白与敏感信息；检查六份链接及 ZIP 字节与源码一致。此前 Windows/Rocky 十二项、Java 包解析与 Word 视觉验证仍适用于未变更源码，未重复运行；没有平台改动，未运行全量 Maven/数据库测试。ZIP 与预览仍为本地忽略产物，未加入版本控制。

提交后核对提交文件清单与剩余工作树；用户原有控制台、application 配置、.codex/、.workbuddy/ 保持原状。未推送、未导入、未发布或部署。

关联：[清单](../checklists/2026-10-09-docx-stdlib-skill-checklist.md)、[提示词](../prompts/2026-10-09-docx-stdlib-skill-prompt.md)、[设计](../specs/2026-10-09-docx-stdlib-skill-design.md)、[计划](../plans/2026-10-09-docx-stdlib-skill.md)、[实现](../implementation/2026-10-09-docx-stdlib-skill-implementation-design.md)。


## R1 实际验证与遗留

日期2026-10-09；mode=default；本轮基线 dc541e7ddc59022411a9ed7b3a9727f421d8c073；本轮未提交，R0已提交。用户错误码 SKILL_SANDBOX_FAILED，errorId=4d252c56-8272-4f0e-8af1-bd4669324ec6；当前仓库没有 logs 目录，未查到该线上日志。通用文案本身不能证明具体根因，以下是本轮独立复现证据。

- T5完成：旧main.py在StringIO输入、有效输出目录下退出2，DOCX_INTERNAL_ERROR，零文件。当前DockerSkillSandbox文本引导确定使用StringIO；文件产物引导采用子进程管道。
- T6完成：固定脚本兼容两类输入及无reconfigure输出；新增四项回归；python -I -S -B -m unittest discover -s skill-packages/tests -v：16项通过。
- T7完成：quick_validate为Skill is valid；当前Java解析器 VerifySkillZip dist/skills/docx-stdlib-1.0.1.zip 返回 CM_AGENT_PARSER_OK resources=3。
- T7远程：Rocky Docker23.0.6；Maven3.9.9/JDK21.0.7；独立工作区HEAD与本地dc541e7相同，通过增量bundle同步。修复的未提交技能以ZIP传输，摘要校验，不伪称已进入Git基线。
- 服务端引导通过临时 ExportSandboxBootstrap.java 从当前Java源文本块导出；java21保持转义语义。远程 verify_cm_agent_bootstraps.py --zip .../docx-stdlib-1.0.1.zip --previous-zip .../docx-stdlib-1.0.0.zip --text-bootstrap .../text-bootstrap.py --artifact-bootstrap .../artifact-bootstrap.py：PREVIOUS_STRINGIO_FAILURE_REPRODUCED、CURRENT_TEXT_BOOTSTRAP_OK、ARTIFACTS_DISABLED_CONFIGURATION_FAILURE_CONFIRMED、CURRENT_ARTIFACT_BOOTSTRAP_CMA1_DOCX_OK bytes=3589。
- Rocky `docker run ... python:3.12-alpine python -I -S -B /input/container_check_101.py`：16项全部通过，随后原始双引导检查再次通过。容器只操作项目验证目录，无生产挂载/全局清理，不沿用旧12项。
- 最终ZIP SHA256：9a0afabbc9634f6503e03a03ca521569e828fe8c837742dcc6b8942e5219b9fd；本地与远程核对。初版修复ZIP的8502cd2c摘要在更新包内说明后已失效，不作为最终交付摘要。
- T8待做（部署使用阶段）：当前系统 sandboxEnabled、artifacts.enabled 未提供；真实输入、上传候选/TEST/下载尚未执行，不能将隔离成功冒充线上闭环。已请求用户仅提供公开能力值，不请求令牌或凭据。

源码没有修改OOXML正文布局，原Word单页视觉证据继续适用；没有Java/API/数据库行为修改，未运行全量Maven/双库测试。平台对非零脚本退出仍隐藏stdout并返回通用SKILL_SANDBOX_FAILED；需要配置就绪和新候选TEST来判定当前使用者的问题。用户无关修改保留。当前范围为T5～T7实现与T8环境待确认。


## 本次授权提交记录

用户再次明确要求“将修改的内容提交”，本轮将 DOCX 1.0.1 输入流兼容修复、Server Lombok 编译支持和 Windows 固定沙箱引导参数修复合并为一次本地提交；对应六份工作包一并交付。上文未提交状态是验证阶段历史记录，不改变已记录证据。

提交说明：`修复 DOCX 技能兼容性与 Windows 沙箱引导`。提交编号通过 `git log -1 --format="%h %s" --grep="修复 DOCX 技能兼容性与 Windows 沙箱引导"` 查询，避免同一提交文档自引用编号。未推送、未导入技能、未部署或重启用户服务。

只暂存相关源码、测试、说明和工作包；保留用户已有的控制台首页、application 配置、SkillArtifactProtocol 诊断日志及排版、.codex/、.workbuddy/。Lombok 的 provided 依赖是编译支持所必需，和注解处理器一并纳入。

本次重新执行 `python -m unittest discover -s skill-packages/tests -p test_*.py -v`：16 项通过；同一会话的附件前端测试 5 项通过。已有 Windows 9 项、Rocky 18 项和 Lombok 编译/打包证据仍对应功能未变的修复，未重复创建已清理夹具或重跑全量 Maven/数据库测试。最终暂存空白、文本资源、工作包链接和敏感信息检查通过。用户现场 TEST 与下载闭环仍需部署后复验。
