# 标准库 DOCX 技能实际实现

当前修订：R1；实际修订日：2026-10-09；mode=default。当前交付为 1.0.1，修复沙箱输入流兼容缺陷。下方 R0 和首次提交记录保留历史；当前范围与验证以 R1 为准。本轮提交记录见进度账本，历史未提交状态保留。

R0；2026-10-09；mode=default。已完成 T0～T4；未提交。

源码交付：`skill-packages/docx-stdlib` 四个文本资源、`package_docx_stdlib.py` 显式清单打包器、`tests/test_docx_stdlib.py` 十二项测试与目录 README。ZIP 为 `dist/skills/docx-stdlib-1.0.0.zip`，不包含测试或生成二进制；示例与验证材料位于忽略的 `dist/skills/preview` 和 `dist/skills/validation`。

脚本将有界 UTF-8 JSON 转为七个 OOXML XML/关系部件。中文字体、层级标题、真正列表编号、矩形表格、页面参数和页码均写入相应元素。ElementTree 保证特殊字符转义，换行/Tab 显式使用专用元素。不同列表从 1 开始，编号定义按 OOXML 顺序排列。

数据不会形成执行代码：输入允许字段固定；文件名拒绝路径和 Windows 设备名；目录只读环境变量；排他创建避免覆盖；写入失败移除本次残缺文件。标准输出固定 UTF-8 JSON，成功只返回文件名、大小和摘要；失败返回中文原因、错误码与随机编号，不输出正文、路径、堆栈或二进制。未知异常用安全通用说明。

实际修正：首次 Windows 隔离测试暴露 GBK stdout 导致 JSON 解码失败，脚本显式设定 UTF-8 后十二项通过。临时 Java 校验最初误判 server JAR 含 BOOT-INF 依赖；改从已有 Surefire classpath 找到库，仅借其依赖位置，重新编译当前解析器源码，未使用历史测试结果作为本次通过证据。

当前解析器实际通过；Rocky Python 3.12 在无网络、非 root、只读根、内存/CPU/PID 限制环境通过。验证容器只读挂载测试输入，不代表生产沙箱新增宿主挂载。示例 Word 打开、PDF/PNG 单页视觉检查通过。未修改平台调用链、数据库、API、配置或权限。

未更新 release-notes：本次只新增独立技能包，平台发布行为与默认能力没有变化。真实导入、模型 TEST、发布、绑定和系统下载未执行。

## 2026-10-09 提交交付

用户后续授权提交本任务内容，上方“未提交”为首次交付时状态。本次提交包含七份技能源码/测试/说明文件与六份工作包；提交说明为“新增标准库 DOCX 生成技能包”，最终编号以对应 Git 记录为准，避免文档自引用编号。无功能变化，原验证仍适用。未将 ZIP、预览、临时 Java 校验或无关用户修改纳入提交；未推送、导入或部署。

关联：[清单](../checklists/2026-10-09-docx-stdlib-skill-checklist.md)、[提示词](../prompts/2026-10-09-docx-stdlib-skill-prompt.md)、[设计](../specs/2026-10-09-docx-stdlib-skill-design.md)、[计划](../plans/2026-10-09-docx-stdlib-skill.md)、[账本](../progress/2026-10-09-docx-stdlib-skill-ledger.md)。


## R1 实际修复

main() 对 stdout.reconfigure 做能力检测；stdin 有 buffer 时读二进制，没有时读文本并转UTF-8，统一检查字节上限。新增四项回归，保持所有错误非零退出。SKILL.md版本为1.0.1并加入最小JSON输入；输入契约和README同步。新增 verify_cm_agent_bootstraps.py 验证服务端原始引导与CMA1文件交付契约。

修复前在有效输出目录下StringIO输入导致 DOCX_INTERNAL_ERROR、退出2、零文件；修复后生成合法DOCX。原始文本引导在未设置产物目录时仍返回 DOCX_OUTPUT_NOT_READY；原始文件引导成功输出3589字节DOCX且摘要一致。当前生产源码未改动，文件内容与排版未改，不重复Word渲染；本轮增强的是启动与传输证据，未宣称用户线上失败已完全解决。

R1 的本次提交信息见进度账本。没有改部署配置、白名单、权限、审计或平台通用异常文案；因此未更新release-notes，发布行为未变化。新增文件与已有六文档续篇为本轮范围，R0提交dc541e7保留。
