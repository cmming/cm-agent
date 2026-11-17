# docs 在线化站点实现说明

- 日期：2026-11-17
- 主题：`docs-online-site`
- 修订：R0
- 工作模式：`mode=default`

## 实际改动

### 构建依赖

新增 `requirements-docs.txt`，固定 `mkdocs-material==9.8.1`。文件保持单行依赖清单，避免 Windows 当前区域设置使用 GBK 读取中文注释时导致 `pip -r` 解码失败。

### 站点配置

新增 `mkdocs.yml`：

- 直接使用 `docs` 作为 `docs_dir`。
- 构建到已忽略的 `dist/docs-site`。
- 以 Material 主题提供中文界面、导航分区、目录跟随、搜索建议和代码复制。
- 主导航列顶层生产文档、架构资料、产品设计资料和工作包索引。
- 开启 Unicode 标题 slug，保留现有中文锚点。
- 配置 Mermaid 自定义围栏。
- 使用严格模式，省略未列入导航页面的警告。
- 不配置 `repo_url`，避免 Material 为仓库角标自动请求 GitHub Release API；GitHub 入口仍保留在导航和站外链接中。

### 链接适配

新增 `scripts/mkdocs_docs_links.py`。该钩子在 Markdown 渲染前：

- 跳过围栏代码块。
- 将仓库内、`docs` 外的文件链接映射到 GitHub `blob`。
- 将 `docs` 外或无 `README.md`/`index.md` 的目录映射到 GitHub `tree`。
- 保留站点能解析的页面、锚点和外部 URL。
- 保留链接标签与可选标题，不修改源 Markdown。

### GitHub Pages

新增 `.github/workflows/docs.yml`：

- 在 `master` 的文档相关路径变化或手动触发时运行。
- 使用 Python 3.12 和固定依赖安装。
- 执行 `mkdocs build --strict`。
- 上传 `dist/docs-site`，通过 GitHub Pages 官方 Actions 部署。
- 仅授予 `contents: read`、`pages: write` 和 `id-token: write`。
- 使用当前主要版本 Actions：checkout v7、setup-python v7、configure-pages v6、upload-pages-artifact v5、deploy-pages v5。

### 工作包

新增本次六份中文工作包，不改写 `docs/superpowers/README.md` 或既有工作包。

## 与设计的差异

- 严格构建首次发现默认 MkDocs slug 会把中文标题生成为 `_1`、`_2`，导致 351 个现存锚点警告。实现增加 Unicode slug 配置，未修改现有文档标题或链接。
- 严格构建同时发现工作包目录入口没有站点索引页。实现把无索引目录链接统一映射到 GitHub 目录列表。
- 最初使用较旧 Pages Actions 主版本；核对官方最新发布后更新为当前主版本。
- 本地预览发现 Material 会自动请求 GitHub Release API，因仓库无 Release 产生一次控制台 404；后续移除非必要的仓库角标配置，保留源码链接入口。
- MkDocs 导入构建钩子时会生成 Python 缓存；因此在现有 `.gitignore` 末尾追加 `__pycache__/` 与 `*.py[cod]`，保留用户已有忽略规则。

## 未改动范围

- 现有 555 篇 Markdown 的正文、标题、目录和链接未批量修改。
- 未修改根 README、`docs/README.md` 或工作包索引。
- 未提交、推送、启用 GitHub Pages 或执行线上部署。

## 关联产物

- [清单](../checklists/2026-11-17-docs-online-site-checklist.md)
- [设计](../specs/2026-11-17-docs-online-site-design.md)
- [计划](../plans/2026-11-17-docs-online-site.md)
- [执行提示词](../prompts/2026-11-17-docs-online-site-prompt.md)
- [进度账本](../progress/2026-11-17-docs-online-site-ledger.md)

验证状态、命令结果和提交信息只记录在[进度账本](../progress/2026-11-17-docs-online-site-ledger.md)。
