# docs 在线化站点实施计划

- 日期：2026-11-17
- 主题：`docs-online-site`
- 修订：R0
- 工作模式：`mode=default`

## 实施顺序

| 顺序 | 任务 | 操作 | 涉及文件 | 依赖 | 验证方法 |
|---|---|---|---|---|---|
| 1 | T0 | 固定 MkDocs Material 版本，建立站点元数据、导航、中文搜索、Mermaid 和严格校验配置 | `requirements-docs.txt`、`mkdocs.yml` | 无 | 配置加载成功 |
| 2 | T1 | 增加构建钩子，识别仓库外文件、无索引目录和站内可承载链接 | `scripts/mkdocs_docs_links.py` | T0 | 抽查生成 HTML；严格构建无警告 |
| 3 | T2 | 增加 GitHub Pages 构建和部署工作流 | `.github/workflows/docs.yml` | T0、T1 | YAML 解析；审查权限和触发条件 |
| 4 | T3 | 在独立临时虚拟环境安装固定依赖并执行严格构建 | `dist/docs-site` | T0、T1、T2 | 命令退出码、警告数和产物计数 |
| 5 | T4 | 启动本地预览，检查首页、搜索、Mermaid 和响应式布局 | 本地预览服务、`output/playwright/` | T3 | HTTP 状态、浏览器截图和页面结构检查 |

## 实施约束

- 只新增站点配置、依赖、钩子、工作流和本次工作包；不修改现有文档内容。
- 站外链接适配只发生在 MkDocs 构建阶段。
- 使用临时虚拟环境安装依赖，不在仓库中创建虚拟环境或 `__pycache__`。
- 构建产物只写入已忽略的 `dist/docs-site`。
- 工作流使用 GitHub 官方 Actions，不包含自动提交、推送或额外仓库写权限。
- 推送、Pages 启用和线上部署不在本次执行范围。

## 验证命令

```powershell
python -c "import ast, pathlib; ast.parse(pathlib.Path('scripts/mkdocs_docs_links.py').read_text(encoding='utf-8')); print('Python 语法检查通过')"

$venv = Join-Path $env:TEMP 'cm-agent-mkdocs-venv'
python -m venv $venv
& (Join-Path $venv 'Scripts\python.exe') -m pip install -r requirements-docs.txt

$env:PYTHONUTF8 = '1'
& (Join-Path $venv 'Scripts\python.exe') -m mkdocs build --strict

& (Join-Path $venv 'Scripts\python.exe') -m mkdocs serve --dev-addr 127.0.0.1:8123
```

## 回退方式

站点内容源与现有文档相同。如果需要撤销站点能力，只需删除或回退新增的 `mkdocs.yml`、`requirements-docs.txt`、构建钩子和 Pages 工作流；现有文档目录无需迁移或恢复。

## 关联产物

- [清单](../checklists/2026-11-17-docs-online-site-checklist.md)
- [设计](../specs/2026-11-17-docs-online-site-design.md)
- [执行提示词](../prompts/2026-11-17-docs-online-site-prompt.md)
- [实现说明](../implementation/2026-11-17-docs-online-site-implementation-design.md)
- [进度账本](../progress/2026-11-17-docs-online-site-ledger.md)
