# 标准库 DOCX 技能执行提示词

当前修订：R1；实际修订日：2026-10-09；mode=default。当前交付为 1.0.1，修复沙箱输入流兼容缺陷。下方 R0 和首次提交记录保留历史；当前范围与验证以 R1 为准。本轮提交记录见进度账本，历史未提交状态保留。

R0；2026-10-09；mode=default。此提示词记录本任务范围；交付已完成。读取提示词不授权重做实现、导入、发布或部署。

提交交付更新：用户后续“将修改的内容提交”仅授权本任务源码和六份工作包的一次本地提交，提交说明为“新增标准库 DOCX 生成技能包”。下方原执行输入中的“不提交”保留为首次生成阶段历史；本次提交授权优先，不扩展到未来提交、推送或部署。最终编号见本主题路径的 Git 记录。

```text
仓库：F:\java\cm-agent。需求：生成给 CM Agent 使用的 Python 标准库 DOCX 技能 ZIP。
先读取当前 AGENTS.md 与下列清单、设计、计划、实现说明和账本；依据真实状态继续，完成项不机械重做。
清单：F:\java\cm-agent\docs\superpowers\checklists\2026-10-09-docx-stdlib-skill-checklist.md
设计：F:\java\cm-agent\docs\superpowers\specs\2026-10-09-docx-stdlib-skill-design.md
计划：F:\java\cm-agent\docs\superpowers\plans\2026-10-09-docx-stdlib-skill.md
实现：F:\java\cm-agent\docs\superpowers\implementation\2026-10-09-docx-stdlib-skill-implementation-design.md
账本：F:\java\cm-agent\docs\superpowers\progress\2026-10-09-docx-stdlib-skill-ledger.md
mode=default：依据当前证据自主取舍并记录假设，不逐步征询普通实现细节。
T0 核对单入口 ZIP、固定 Python、标准输入和产物目录契约；有 CodeGraph 先用它定位，缺失时复核源码。
T1 维护 skill-packages/docx-stdlib，仅通过固定 scripts/main.py 解析 JSON，不执行用户代码，不联网或安装依赖。
T2 使用 python -X utf8 skill-packages/package_docx_stdlib.py --output dist/skills/docx-stdlib-1.0.0.zip 打包四资源；用当前 SkillPackageParser 实际验证归档。
T3 运行 python -I -S -B -m unittest discover -s skill-packages/tests -v；覆盖内容保真、路径拒绝、错误分类与无残留文件。容器验证仅 ssh rocky，确认 Docker、Maven JDK21、Git HEAD和上传摘要；Python 3.12 在项目独立目录验证。示例通过 Word/LibreOffice 渲染并查看页面。
T4 同步 README 与六份工作包，记录实际命令、未执行的真实模型闭环和交付链接。
保护已有 application 配置、根控制台 index.html、.codex/、.workbuddy/。不修改 Java、数据库、生产安全或白名单以迁就包；不提交、推送、部署或自行导入发布。
外部环境不可用时记录确切原因，不将未执行验证标记成功。只生成技能 ZIP，不承诺真实模型、鉴权下载闭环已通过。
按仓库最终格式交付摘要、验证、影响、限制和直接相关下一步，附 ZIP 与当前提示词链接。
```

关联：[清单](../checklists/2026-10-09-docx-stdlib-skill-checklist.md)、[设计](../specs/2026-10-09-docx-stdlib-skill-design.md)、[计划](../plans/2026-10-09-docx-stdlib-skill.md)、[实现](../implementation/2026-10-09-docx-stdlib-skill-implementation-design.md)、[账本](../progress/2026-10-09-docx-stdlib-skill-ledger.md)。


## R1 当前修复提示词

以下为当前有效范围；上方R0输入保留形成过程，不能用旧测试证明新候选成功。

```text
仓库 F:\java\cm-agent；当前修订R1，mode=default。
先读取本组六份原路径文档，以R1续篇为当前范围，保留R0与提交dc541e7历史。
T5复现StringIO输入缺陷；T6兼容文本/二进制输入和无reconfigure输出，保持UTF-8字节限额与非零失败；T7在Rocky验证当前服务端原始文本/文件引导和CMA1 DOCX字节、摘要、XML，发布1.0.1 ZIP；这些任务已完成，不机械重做。
T8为部署使用阶段：核实当前用户系统 GET /api/skills/capabilities 的sandboxEnabled、artifacts.enabled；上传新候选1.0.1并真实TEST、检查附件。未取得值不得猜测或以模型声称成功代替验收。
只读问答不执行实施命令。进一步修改依用户明确要求；不自行动平台配置、权限、审计、Java、数据库，不提交、推送、导入或发布。保留无关application配置、index.html、.codex/、.workbuddy/。
打包命令 python -X utf8 skill-packages/package_docx_stdlib.py --output dist/skills/docx-stdlib-1.0.1.zip。
测试命令 python -I -S -B -m unittest discover -s skill-packages/tests -v；Docker测试仅ssh rocky；同步最新摘要、结果、缺失环境及六份文档，不以旧12项替代本轮16项与真实引导检查。
```
