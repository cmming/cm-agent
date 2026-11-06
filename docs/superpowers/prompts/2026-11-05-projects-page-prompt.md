# Projects 页 2026-11-05 变更提示词

> 页面：`/console/v2/projects.html`  
> 日期：2026-11-05  
> 修订：`R1`（2026-11-05）
> 模式：`mode=default`

```text
仓库：F:\java\cm-agent
页面：/console/v2/projects.html
主题：2026-11-05 Projects 页布局与非回环安全开关

先读取本组六份文档。维护以下边界：
1. 管理折叠、审批顺序、会话宽度和 Agent 选择宽度保持现有验收。
2. allow-non-loopback 默认 false；不得取消精确 Host、Origin、行动头和 Fetch Metadata 检查。
3. 不允许 *、0.0.0.0、:: 作为浏览器来源；生产 profile 继续拒绝本地项目。
4. 当前 local profile 使用三个精确来源，远端操作的是服务端电脑项目目录。
5. 使用 JDK 21 验证 LocalProjectConfigurationTest、LocalProjectRequestBoundaryTest、LocalProjectControllerTest。
6. 页面资源测试使用 Node 和 ConsoleResourceTest。
7. 更新本组文档，未提交时明确记录，不获得额外提交、推送或部署授权。
8. HTTP 局域网下 randomUUID 不可用时使用 getRandomValues 生成 UUID v4；禁止用 Math.random 代替安全随机数。
9. 修改 local-projects.js 后同步提升所有加载页面的缓存版本并运行 Node/ConsoleResourceTest。
```

## 关联文档

- [清单](../checklists/2026-11-05-projects-page-checklist.md)
- [设计](../specs/2026-11-05-projects-page-design.md)
- [计划](../plans/2026-11-05-projects-page.md)
- [实现说明](../implementation/2026-11-05-projects-page-implementation-design.md)
- [账本](../progress/2026-11-05-projects-page-ledger.md)
