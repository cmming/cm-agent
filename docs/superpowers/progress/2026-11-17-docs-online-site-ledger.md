# docs 在线化站点进度账本

- 日期：2026-11-17
- 主题：`docs-online-site`
- 修订：R0
- 工作模式：`mode=default`
- Git 基线：`8bba6aa`

## 任务状态

| 编号 | 状态 | 当前证据 |
|---|---|---|
| T0 | 完成 | `mkdocs.yml`、`requirements-docs.txt` 已落地；最终严格构建成功 |
| T1 | 完成 | 链接钩子已落地；最终严格构建无链接警告，生成 HTML 中无残留相对 `.md` 链接 |
| T2 | 完成 | Pages 工作流已落地；YAML 解析成功，Actions 使用当前主要版本 |
| T3 | 完成 | 最终 `mkdocs build --strict` 退出码 0、警告数 0、页面数 561 |
| T4 | 完成 | 本地首页、搜索、中文锚点和 Mermaid 可访问；桌面与移动视口检查完成 |

## 实际验证

- 临时虚拟环境中安装 `mkdocs-material==9.8.1` 成功。
- 首次 `mkdocs build --strict` 因默认中文 slug 和工作包目录链接产生 351 个警告，随后调整配置与钩子。
- 调整后 `mkdocs build --strict` 退出码为 0，警告数为 0。
- 最终 `mkdocs build --strict` 退出码为 0，警告数为 0；本机构建耗时约 8 秒，不作为固定门禁。
- 产物包含 561 个 `index.html`，与现有 555 篇文档和本次新增 6 份工作包一致；首页和搜索索引存在。
- 生成 HTML 中的根 README 链接已映射到 GitHub，工作包目录链接已映射到 GitHub `tree`。
- 中文标题锚点已生成可读 ID，例如 `当前事实来源矩阵` 和 `profile-选择`。
- Mermaid 围栏已生成 `class="mermaid"`。
- 生成 HTML 中未发现残留的相对 `.md` 链接。
- `dist/docs-site/index.html` 命中现有忽略规则，未进入版本控制。
- 本地预览 `http://127.0.0.1:8123/cm-agent/` 返回 HTTP 200，页面标题为 `CM Agent 文档`。
- 配置锚点页面、搜索索引和 Harness Mermaid 页面均返回 HTTP 200。
- 浏览器搜索“部署”返回结果，搜索面板可用。
- 桌面 1440×1000 和移动 390×844 视口检查完成；移动端 `scrollWidth` 与 `innerWidth` 均为 390，无横向溢出。
- 截图保存在被忽略的 `output/playwright/docs-online-site/`：`home-desktop.png`、`home-mobile.png`、`home-mobile-viewport.png`、`search-desktop.png`。
- 移除仓库角标配置后，浏览器控制台无错误、无警告。

## 未执行项

- 未推送提交。
- 未启用 GitHub Pages。
- 未执行线上部署。
- 本次工作包六份文档加入后，严格构建、页面计数和本地预览已经最终复跑。

## 遗留与风险

- Material for MkDocs 官方提示将于 2027-05-05 停止维护；当前遵守用户指定方案继续使用，后续应独立评估 Zensical 迁移。
- 首次线上发布前，需要在仓库设置中将 Pages 来源设为 GitHub Actions。
- 远端 `master` 尚未包含本地文档入口提交；线上站点要包含当前入口，需要后续另行授权推送。
- 部分历史验证截图指向已被忽略的 `output` 目录，当前无法通过 GitHub 链接恢复，保留原历史事实。

## Git 与发布

- 主实现提交：`32d8654`，提交说明为 `docs: 增加 MkDocs Material 在线文档站点`。
- 已推送到 `master`。HTTPS 推送因网络连接重置失败后，改用已认证的 GitHub SSH 通道完成推送；GitHub API 与 SSH 通道可用。
- GitHub Pages 已启用，构建类型为 `workflow`，站点地址为 `https://cmming.github.io/cm-agent/`，已强制 HTTPS。
- 首次发布工作流：[Docs #44105339670](https://github.com/cmming/cm-agent/actions/runs/44105339670)；`build` 与 `deploy` 均成功。
- GitHub Pages 部署记录：部署 ID `7856433132`，环境 `github-pages`，引用 `master`，对应提交 `32d8654`。
- 线上首页、搜索索引、配置说明和 Harness 模块页面均返回 HTTP 200。
- 发布结果更新提交：`docs: 记录文档站点发布结果`；该提交的完整 SHA 由本次 Git 历史产生，最终以 `git log -1 --oneline` 为准。

## 关联产物

- [清单](../checklists/2026-11-17-docs-online-site-checklist.md)
- [设计](../specs/2026-11-17-docs-online-site-design.md)
- [计划](../plans/2026-11-17-docs-online-site.md)
- [执行提示词](../prompts/2026-11-17-docs-online-site-prompt.md)
- [实现说明](../implementation/2026-11-17-docs-online-site-implementation-design.md)
