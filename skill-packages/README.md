# 可导入技能包

## 标准库 DOCX 生成

`docx-stdlib` 面向 CM Agent 的单技能 ZIP 导入。生成器仅用 Python 3.10+ 标准库，在当前默认 `python:3.12-alpine` 镜像中通过验证，无需安装依赖。

打包与快速验证（仓库根目录）：

```powershell
python -I -S -B -m unittest discover -s skill-packages/tests -v
python -X utf8 skill-packages/package_docx_stdlib.py --output dist/skills/docx-stdlib-1.0.0.zip
```

归档仅包含入口、固定脚本、输入契约和 JSON 示例；不包含测试、缓存、二进制模板或示例 DOCX。`dist/` 是忽略的交付目录，源码与打包器保留在本目录。输入规范见 [契约](docx-stdlib/references/input-format.md)，模型操作见 [技能入口](docx-stdlib/SKILL.md)。

控制台使用流程：启用技能沙箱和文件产物收集 → 技能管理“导入新技能”上传 ZIP → 选择具有模型配置的真实 Agent 试运行 → 检查生成文件 → 发布 → 绑定 Agent。同名技能已存在时通过“上传新版本”导入，不重复创建。可用试运行输入：

> 使用 docx-stdlib 技能，生成名为“项目周报.docx”的中文周报，包含本周进展、两条任务清单和任务状态表格。实际调用 scripts/main.py 生成文件，并在运行成功后提供系统附件。

启用条件与安全配置沿用 [生产配置](../docs/configuration.md#技能沙箱文件产物)，本包不修改平台配置或授予权限。`CM_AGENT_ARTIFACT_DIR` 由服务端注入。输入限 32768 字节，生成文件限 4 MiB；实际部署可以设置更严格配额。脚本只报告生成状态，下载由系统按 Run 终态、主体权限和审计控制。

支持标题、段落、编号与项目符号列表、简单表格和分页；不支持图片、复杂公式、合并单元格、原文件修订或复杂模板。真实模型导入、TEST、发布、绑定及鉴权下载闭环尚未在本任务执行；不能用容器脚本验证代替该验收。
