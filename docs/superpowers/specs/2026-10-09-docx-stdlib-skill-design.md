# 标准库 DOCX 技能设计

R0；2026-10-09；mode=default。目标系统由用户明确为 CM Agent。当前平台已有固定 Python 沙箱和文件产物能力，适合不依赖 pip/Node.js 的单技能 ZIP。

方案：`SKILL.md` 指导模型整理 JSON；`run_skill_script` 使用当前技能标识、固定 `scripts/main.py`、JSON stdin 执行；脚本用 ElementTree 自动转义 XML，并用 zipfile 封装 WordprocessingML；文件只写可信 `CM_AGENT_ARTIFACT_DIR`。Run 终态、审计、存储、鉴权下载保持平台既有链路。

输入与布局自主取舍：标题、副标题、段落、一至三级标题、独立列表、简单矩形表格、分页；默认 A4、宋体 11 磅，可用 Letter/横向。拒绝重复键、未知字段、非法 XML、代码/路径注入、超预算输入及同名覆盖。输入硬限 32768 字节，生成 DOCX 硬限 4 MiB，遵循部署更紧限制。

非目标：图片、公式、图表、合并单元格、现有文档修订、模板套版、自动部署与平台代码修改。不提供自动执行任意 Python 的入口，不返回 Base64 或伪造下载 URL。

OOXML 采用正文、样式、编号、页脚及相应内容类型/关系七个部件。列表编号顺序与样式元素顺序遵循 OOXML；表格显式列宽、浅色表头及浅灰边框。参考 [Microsoft 文档结构](https://learn.microsoft.com/en-us/office/open-xml/word/structure-of-a-wordprocessingml-document)。

验收：当前 Java 导入解析器通过；仅标准库隔离执行；Windows/Python 3.12 下中文与特殊字符、表格、列表保真；失败有安全原因、错误码与编号，且不创建文件；Word 示例可打开且渲染无明显缺陷。真实模型闭环在部署时另验收，不以本次脚本验证代替。

当前配置存在用户修改，不能把配置文件字面值当成线上已启用状态。无凭据或运行中系统授权，不操作现有服务。

2026-10-09 提交交付：用户后续明确授权提交本任务修改。方案与验收不变，提交范围仅技能源码、测试、说明及六份工作包；ZIP 与预览保留在忽略目录。提交说明为“新增标准库 DOCX 生成技能包”，最终编号见 Git 记录；不推送、导入或发布。

关联：[清单](../checklists/2026-10-09-docx-stdlib-skill-checklist.md)、[提示词](../prompts/2026-10-09-docx-stdlib-skill-prompt.md)、[计划](../plans/2026-10-09-docx-stdlib-skill.md)、[实现](../implementation/2026-10-09-docx-stdlib-skill-implementation-design.md)、[账本](../progress/2026-10-09-docx-stdlib-skill-ledger.md)。
