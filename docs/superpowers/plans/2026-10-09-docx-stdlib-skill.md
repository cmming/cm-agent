# 标准库 DOCX 技能实施计划

R0；2026-10-09；mode=default。编号与清单一致；实际完成结果以账本为准。

1. T0：读取规范、POM、README 和配置；通过 CodeGraph/源码核实导入、stdin 和产物目录；确认系统，建立六份工作包。
2. T1（依赖 T0）：编写 `skill-packages/docx-stdlib/SKILL.md`、`scripts/main.py`、`references/input-format.md` 与 `examples/sample.json`。采用有界 JSON、XML 自动转义、标准库 ZIP、可信产物目录与排他写入。
3. T2（依赖 T1）：编写 `skill-packages/package_docx_stdlib.py`，显式清单打包到 `dist/skills/docx-stdlib-1.0.0.zip`；检查大小、UTF-8、CRC 和入口；用 JDK21 编译当前解析器源码并实际解析 ZIP，临时验证文件只放忽略目录。
4. T3（依赖 T2）：维护 `skill-packages/tests/test_docx_stdlib.py`，本地 `-I -S -B` 隔离标准库测试；在 Rocky 独立工作区匹配 HEAD/ZIP 摘要，用 Python 3.12 容器测试。生成单页中文示例，通过 Word COM 导出 PDF、Poppler PNG 与视觉检查。
5. T4（依赖 T3）：补齐 `skill-packages/README.md`、账本和交付入口。注明真实模型闭环未执行、不改配置和无关工作树、不提交。检查六份相互引用和仓库差异。

不运行全量 Java/双库测试：本次交付仅技能资源和打包器，没有平台 Java、数据库或 API 变更。若后续扩展平台，重新依据改动范围确定测试，不沿用此豁免。

2026-10-09 提交交付：用户后续授权本地提交。沿用 T4 收尾：同步六份提交记录 → 核对显式文件清单、暂存差异与归档内容 → 以“新增标准库 DOCX 生成技能包”提交 → 验证提交清单与剩余工作树。上方“不提交”为首次交付历史，按本次用户授权覆盖；不重复未变更代码的已通过验证，不推送或部署。

关联：[清单](../checklists/2026-10-09-docx-stdlib-skill-checklist.md)、[提示词](../prompts/2026-10-09-docx-stdlib-skill-prompt.md)、[设计](../specs/2026-10-09-docx-stdlib-skill-design.md)、[实现](../implementation/2026-10-09-docx-stdlib-skill-implementation-design.md)、[账本](../progress/2026-10-09-docx-stdlib-skill-ledger.md)。
