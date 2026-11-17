# docs 在线化站点工作包清单

- 日期：2026-11-17
- 主题：`docs-online-site`
- 修订：R0
- 工作模式：`mode=default`
- 需求来源：用户要求使用 GitHub Pages 与 MkDocs Material 发布 `docs`，保持现有文档不迁移、不重写，先完成配置和本地构建验证，暂不推送。
- Git 基线：`8bba6aa`；工作树已有未提交修改，本工作包不覆盖或整理无关改动。

## 当前范围

目标是以现有 `docs` 目录作为唯一站点内容源，新增最小化构建配置、站外链接适配和 GitHub Pages 工作流，并在本机完成严格构建与预览验证。

当前包含：

- MkDocs Material 配置、导航、中文搜索和 Mermaid 支持。
- `docs` 外源码链接及无索引目录链接的构建期适配。
- GitHub Pages 构建与发布工作流。
- 本地构建、页面产物和预览验证。
- 本次独立任务的六份中文工作包。

当前不包含：

- 迁移、重写、批量格式化或重新组织现有 555 篇文档。
- 修改现有文档正文、入口导航或历史工作包状态。
- 提交、推送、启用 GitHub Pages、配置自定义域名或执行线上部署。
- Java 代码、数据库、运行时配置和接口行为变更。

## 任务与验收

| 编号 | 目标 | 涉及文件线索 | 依赖 | 可观察验收 | 验证方法 |
|---|---|---|---|---|---|
| T0 | 建立 MkDocs Material 基础配置 | `mkdocs.yml`、`requirements-docs.txt` | 无 | 使用现有 `docs` 目录；首页为 `docs/README.md`；产物位于被忽略的 `dist/docs-site`；中文搜索、导航和 Mermaid 已配置 | MkDocs 配置加载成功 |
| T1 | 保持现有链接可用 | `scripts/mkdocs_docs_links.py` | T0 | 构建期将 `docs` 外相对链接映射到 GitHub 源码或目录；无索引目录链接也映射到 GitHub；Markdown 源文件不改写；围栏代码块不处理 | 严格构建无链接警告；抽查生成 HTML |
| T2 | 建立 GitHub Pages 发布流程 | `.github/workflows/docs.yml` | T0、T1 | 在 `master` 的文档相关路径变化或手动触发时，执行依赖安装、严格构建、上传和发布；权限最小化；不包含推送动作 | YAML 解析及文件审查 |
| T3 | 完成本地严格构建 | `dist/docs-site` | T0、T1、T2 | `mkdocs build --strict` 成功且无警告；首页、全部 Markdown 页面和搜索索引生成 | 本地命令与产物计数 |
| T4 | 完成本地预览核验 | 本地 MkDocs 服务 | T3 | 首页 HTTP 可访问；中文导航、搜索资源和 Mermaid 输出存在；桌面与移动视口无明显布局异常 | 浏览器访问及截图检查 |

## 门禁

- 现有 `docs` 文件只能作为构建输入，不能因站点发布而迁移或批量改写。
- 站外链接必须在构建期适配，不能复制源码、脚本或根目录文档到 `docs`。
- 标题锚点必须与现有中文 GitHub 风格链接兼容。
- 构建使用固定版本依赖，并启用严格模式；出现警告不得标记验证通过。
- 工作流不得包含自动提交、强制推送或额外仓库写权限。
- 不得泄露真实 JWT secret、数据库密码、模型 API Key 或完整生产 JDBC URL。
- 用户未授权的推送、Pages 启用和线上部署不得执行。

## 关联产物

- [设计](../specs/2026-11-17-docs-online-site-design.md)
- [计划](../plans/2026-11-17-docs-online-site.md)
- [执行提示词](../prompts/2026-11-17-docs-online-site-prompt.md)
- [实现说明](../implementation/2026-11-17-docs-online-site-implementation-design.md)
- [进度账本](../progress/2026-11-17-docs-online-site-ledger.md)

当前状态、命令结果和验证证据只记录在[进度账本](../progress/2026-11-17-docs-online-site-ledger.md)。
