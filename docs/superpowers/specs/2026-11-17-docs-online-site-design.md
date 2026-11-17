# docs 在线化站点设计

- 日期：2026-11-17
- 主题：`docs-online-site`
- 修订：R0
- 工作模式：`mode=default`

## 背景

仓库为公开项目，`docs` 下已有统一的中文入口页和完整历史工作包，但尚未启用 GitHub Pages，也没有 MkDocs、Docusaurus 或其他站点配置。用户希望以最小改动将现有文档发布为在线文档，同时保持目录和正文不变。

当前事实：

- `docs` 包含 555 个 Markdown 文件，其中 10 个顶层生产文档、1 个 ADR、544 个 Superpowers 文档。
- `docs/README.md` 已提供按任务、事实来源和角色组织的入口。
- 现有文档没有 YAML front matter，包含 18 处 Mermaid 围栏和较多内嵌 HTML。
- 现有文档约有 94 个链接指向 `docs` 外的根目录文档、Java 源码、脚本或 `.agents` 资源。
- GitHub 仓库为公开仓库，未检测到已启用的 GitHub Pages 站点。
- 远端 `master` 尚未包含本地文档入口提交；本次不推送。

## 目标

- 直接以 `docs` 为 MkDocs `docs_dir`，不迁移文件。
- 使用 Material 主题提供中文导航、搜索、代码复制和 Mermaid 渲染。
- 保持现有中文标题锚点与文档间链接可用。
- 用一个构建钩子处理无法由站点承载的站外链接。
- 用 GitHub Actions 构建静态产物并通过 GitHub Pages 发布。
- 本地严格构建无警告，并提供可检查的本地预览。

## 非目标

- 不重写现有文档内容、标题、链接或目录结构。
- 不把根目录文档或源码复制进站点。
- 不新增自定义主题、复杂品牌页或文档版本切换。
- 不执行提交、推送、Pages 启用或线上部署。
- 不调整 Java 模块、数据库、运行时或安全配置。

## 方案

### 站点构建

MkDocs Material 直接读取 `docs`。`docs/README.md` 作为站点首页，主导航只列生产文档、架构资料和工作包索引；其余历史文档仍全部构建并进入搜索索引。构建产物写入已忽略的 `dist/docs-site`，不新增构建产物忽略规则。

### 中文锚点

MkDocs 默认 slug 会丢弃中文标题，只生成 `_1`、`_2` 等标识，与现有 GitHub 风格中文锚点不兼容。配置 `pymdownx.slugs.slugify(case=lower)`，保留中文并继续小写化英文。

### 链接适配

构建钩子在 Markdown 渲染前处理链接：

- 指向 `docs` 外的仓库文件映射到 GitHub `blob/master`。
- 指向 `docs` 外或站内没有首页的目录映射到 GitHub `tree/master`。
- 站点能够解析的站内页面、锚点、外部 URL 和代码块保持原样。
- 不修改源 Markdown，只影响构建结果。

### GitHub Pages

工作流在 `master` 的 `docs`、配置、依赖、钩子或工作流变化时触发，也支持手动触发。构建任务安装固定依赖并运行 `mkdocs build --strict`，发布任务通过 GitHub Pages 官方 Actions 上传并部署。工作流不包含自动提交或强制推送。

## 决定

| 编号 | 决定 | 原因与影响 |
|---|---|---|
| D1 | 使用 MkDocs Material 9.8.1 | 满足用户指定方案；固定版本保证本地与 CI 一致。Material 将于 2027-05-05 停止维护，后续需要单独评估 Zensical 迁移。 |
| D2 | 不迁移现有文档 | `docs` 已是完整内容源；避免历史链接、路径和过程记录失效。 |
| D3 | 构建到 `dist/docs-site` | `dist/` 已被忽略，不增加仓库产物污染和新忽略规则。 |
| D4 | 使用 Unicode 标题 slug | 恢复现有中文锚点兼容性，避免大量已有链接失效。 |
| D5 | 使用构建钩子改写站外链接 | 保持 Markdown 正文不变，同时让源码、脚本和根目录文档链接在站点中仍指向 GitHub。 |
| D6 | 导航只列生产入口与索引 | 544 篇历史文档仍可搜索和访问，避免主导航被历史工作包淹没。 |
| D7 | 使用 GitHub Pages 官方构建和部署 Actions | 使用最小仓库权限、环境部署 URL 和现代 Pages 产物流程，不执行 `gh-deploy` 强制推送。 |

## 约束

- 依赖安装需要访问 PyPI；GitHub Actions 需要仓库允许 Pages 和 Actions。
- 首次线上发布前，需要在仓库设置中将 Pages 来源设为 GitHub Actions。
- 远端文档入口提交尚未推送；线上站点要包含当前入口，需要后续另行授权推送。
- 部分历史验证截图链接指向被忽略的 `output` 目录，当前无法通过站点或 GitHub 自动恢复，保持既有历史事实。

## 验收

- `mkdocs build --strict` 成功且零警告。
- 站点页面数量与 Markdown 文档数量一致，首页和搜索索引存在。
- 站外相对链接在生成 HTML 中指向 GitHub，中文标题锚点可识别。
- Mermaid 围栏生成 `class="mermaid"`，代码复制和中文导航可用。
- 工作流 YAML 可解析，未执行推送或部署。
- 本地预览在桌面和移动视口可正常打开。

## 关联产物

- [清单](../checklists/2026-11-17-docs-online-site-checklist.md)
- [计划](../plans/2026-11-17-docs-online-site.md)
- [执行提示词](../prompts/2026-11-17-docs-online-site-prompt.md)
- [实现说明](../implementation/2026-11-17-docs-online-site-implementation-design.md)
- [进度账本](../progress/2026-11-17-docs-online-site-ledger.md)
