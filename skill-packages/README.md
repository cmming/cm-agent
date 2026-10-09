# 可导入技能包

## 标准库 DOCX 生成

`docx-stdlib` 面向 CM Agent 的单技能 ZIP 导入。生成器仅用 Python 3.10+ 标准库，在当前默认 `python:3.12-alpine` 镜像中通过验证，无需安装依赖。

打包与快速验证（仓库根目录）：

```powershell
python -I -S -B -m unittest discover -s skill-packages/tests -v
python -X utf8 skill-packages/package_docx_stdlib.py --output dist/skills/docx-stdlib-1.0.1.zip
```

归档仅包含入口、固定脚本、输入契约和 JSON 示例；不包含测试、缓存、二进制模板或示例 DOCX。`dist/` 是忽略的交付目录，源码与打包器保留在本目录。输入规范见 [契约](docx-stdlib/references/input-format.md)，模型操作见 [技能入口](docx-stdlib/SKILL.md)。

控制台使用流程：启用技能沙箱和文件产物收集 → 技能管理“导入新技能”上传 ZIP → 选择具有模型配置的真实 Agent 试运行 → 检查生成文件 → 发布 → 绑定 Agent。同名技能已存在时通过“上传新版本”导入，不重复创建。可用试运行输入：

> 使用 docx-stdlib 技能，生成名为“项目周报.docx”的中文周报，包含本周进展、两条任务清单和任务状态表格。实际调用 scripts/main.py 生成文件，并在运行成功后提供系统附件。

启用条件与安全配置沿用 [生产配置](../docs/configuration.md#技能沙箱文件产物)，本包不修改平台配置或授予权限。`CM_AGENT_ARTIFACT_DIR` 由服务端注入。输入限 32768 字节，生成文件限 4 MiB；实际部署可以设置更严格配额。脚本只报告生成状态，下载由系统按 Run 终态、主体权限和审计控制。

支持标题、段落、编号与项目符号列表、简单表格和分页；不支持图片、复杂公式、合并单元格、原文件修订或复杂模板。真实模型导入、TEST、发布、绑定及鉴权下载闭环尚未在本任务执行；不能用容器脚本验证代替该验收。

## 1.0.1 沙箱兼容修复

旧版直接读取 `sys.stdin.buffer`，与 CM Agent 文本沙箱注入的 `StringIO` 不兼容。1.0.1 同时支持文本流和二进制管道，均按 UTF-8 字节计量；stdout 编码配置也兼容没有 `reconfigure` 的文本流。新增四项回归，使用当前服务端原始引导程序验证文本与文件产物两种模式。

已有技能请选中 `docx-stdlib`，使用“上传新版本”导入 `docx-stdlib-1.0.1.zip`，再对新候选 TEST；旧版本的 TEST 不算新候选验证。当前平台对非零脚本退出统一返回 `SKILL_SANDBOX_FAILED`，可能隐藏脚本的具体 JSON 原因。若升级后仍出现此错误，先查已认证 `GET /api/skills/capabilities` 的 `sandboxEnabled` 与 `artifacts.enabled`。后者必须为 true；仅开启脚本沙箱不能提供文件收集目录。

1.0.1 在文件产物关闭时仍拒绝执行并返回非零退出码，不伪造成功、不使用任意宿主目录或 Base64 绕过交付。管理员需按生产配置启用独立文件产物、私有持久卷与密钥；本包不修改部署配置。
