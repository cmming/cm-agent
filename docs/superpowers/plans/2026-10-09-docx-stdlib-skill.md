# 标准库 DOCX 技能实施计划

当前修订：R1；实际修订日：2026-10-09；mode=default。当前交付为 1.0.1，修复沙箱输入流兼容缺陷。下方 R0 和首次提交记录保留历史；当前范围与验证以 R1 为准。本轮提交记录见进度账本，历史未提交状态保留。

R0；2026-10-09；mode=default。编号与清单一致；实际完成结果以账本为准。

1. T0：读取规范、POM、README 和配置；通过 CodeGraph/源码核实导入、stdin 和产物目录；确认系统，建立六份工作包。
2. T1（依赖 T0）：编写 `skill-packages/docx-stdlib/SKILL.md`、`scripts/main.py`、`references/input-format.md` 与 `examples/sample.json`。采用有界 JSON、XML 自动转义、标准库 ZIP、可信产物目录与排他写入。
3. T2（依赖 T1）：编写 `skill-packages/package_docx_stdlib.py`，显式清单打包到 `dist/skills/docx-stdlib-1.0.0.zip`；检查大小、UTF-8、CRC 和入口；用 JDK21 编译当前解析器源码并实际解析 ZIP，临时验证文件只放忽略目录。
4. T3（依赖 T2）：维护 `skill-packages/tests/test_docx_stdlib.py`，本地 `-I -S -B` 隔离标准库测试；在 Rocky 独立工作区匹配 HEAD/ZIP 摘要，用 Python 3.12 容器测试。生成单页中文示例，通过 Word COM 导出 PDF、Poppler PNG 与视觉检查。
5. T4（依赖 T3）：补齐 `skill-packages/README.md`、账本和交付入口。注明真实模型闭环未执行、不改配置和无关工作树、不提交。检查六份相互引用和仓库差异。

不运行全量 Java/双库测试：本次交付仅技能资源和打包器，没有平台 Java、数据库或 API 变更。若后续扩展平台，重新依据改动范围确定测试，不沿用此豁免。

2026-10-09 提交交付：用户后续授权本地提交。沿用 T4 收尾：同步六份提交记录 → 核对显式文件清单、暂存差异与归档内容 → 以“新增标准库 DOCX 生成技能包”提交 → 验证提交清单与剩余工作树。上方“不提交”为首次交付历史，按本次用户授权覆盖；不重复未变更代码的已通过验证，不推送或部署。

关联：[清单](../checklists/2026-10-09-docx-stdlib-skill-checklist.md)、[提示词](../prompts/2026-10-09-docx-stdlib-skill-prompt.md)、[设计](../specs/2026-10-09-docx-stdlib-skill-design.md)、[实现](../implementation/2026-10-09-docx-stdlib-skill-implementation-design.md)、[账本](../progress/2026-10-09-docx-stdlib-skill-ledger.md)。


## R1 执行计划

T5：读取当前 DockerSkillSandbox/SkillArtifactProtocol 原始引导与错误映射；用 StringIO 和旧包复现。T6：仅修改固定 Python 入口的流读取，补四项输入流/输出流/UTF-8字节预算/未启用产物回归。T7：导出原始 Java 文本块（通过JDK21保持转义语义），在Rocky按平台隔离参数执行；检验CMA1协议中DOCX的CRC、XML与SHA256，再验证1.0.1归档。T8：交付新候选升级步骤；当前系统能力值与真实模型验收待部署使用阶段确认。

复用原六份路径、T0～T4历史和任务编号。新增验证器 skill-packages/tests/verify_cm_agent_bootstraps.py；只在Rocky调用Docker。更新README、SKILL.md元数据与输入契约，不修改Java、配置或数据库。版本ZIP保留dist忽略目录；旧包不覆盖以便复现。
