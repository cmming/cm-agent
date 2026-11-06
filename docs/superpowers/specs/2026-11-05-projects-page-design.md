# Projects 页 2026-11-05 变更设计

> 页面：`/console/v2/projects.html`  
> 日期：2026-11-05  
> 修订：`R1`（2026-11-05）
> 模式：`mode=default`

## 页面目标

- 管理操作不抢占主任务空间。
- 审批在输入前可见。
- 宽屏布局不过度拉伸，窄屏无整体横向溢出。
- 在需要受控局域网项目访问时提供显式高风险开关。

## 布局方案

| 区域 | 方案 |
|---|---|
| 项目选择 | 保留原服务端状态 |
| 项目管理 | 使用原生 details 按需展开；归档项目隐藏入口 |
| 审批 | 位于任务输入之前 |
| 会话 | 最大宽度 960px |
| Agent 选择 | 最大宽度 340px，窄屏按容器撑开 |
| 文件工具 | 按钮允许换行 |

## 非回环开关

新增：

```yaml
cm-agent:
  local-projects:
    allow-non-loopback: false
```

安全语义：

- 默认关闭，保持回环部署。
- 开启后只放宽地址条件。
- Host、Origin、`X-CM-Agent-Local-Project` 和 Fetch Metadata 继续生效。
- `*`、`0.0.0.0`、`::` 不能作为浏览器来源。
- 生产/prod/supabase 仍拒绝本地项目。

当前 `local` profile：

```yaml
cm-agent:
  local-projects:
    enabled: true
    allow-non-loopback: true
    allowed-origins:
      - http://127.0.0.1:8080
      - http://localhost:8080
      - http://192.168.0.159:8080
```

## 安全边界

远端持有有效权限的用户操作的是服务端电脑项目目录，不是访问者电脑。当前为局域网 HTTP，必须配合来源防火墙，生产环境应使用 HTTPS。

## R1 HTTP 局域网兼容

浏览器只在安全上下文中提供 `crypto.randomUUID()`。通过局域网 HTTP 访问 Projects 页时，`crypto` 存在但 `randomUUID` 可能不存在。

目录请求编号采用如下顺序：

1. 优先调用 `crypto.randomUUID()`。
2. 不可用时使用 `crypto.getRandomValues()` 生成 UUID v4。
3. 两者都不可用时明确失败，不使用 `Math.random()` 生成目录选择编号。

`local-projects.js` 资源版本更新为 `1.2.0`，避免浏览器继续使用旧缓存。

## 关联文档

- [清单](../checklists/2026-11-05-projects-page-checklist.md)
- [计划](../plans/2026-11-05-projects-page.md)
- [提示词](../prompts/2026-11-05-projects-page-prompt.md)
- [实现说明](../implementation/2026-11-05-projects-page-implementation-design.md)
- [账本](../progress/2026-11-05-projects-page-ledger.md)
