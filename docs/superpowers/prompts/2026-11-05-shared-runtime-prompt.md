# 共享运行环境 2026-11-05 变更提示词

> 范围：服务端、本机客户端运行与局域网入口（跨页面）  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`

```text
仓库：F:\java\cm-agent
主题：2026-11-05 共享运行环境、启停脚本和局域网监听

先读取本组六份文档。维护以下约束：
1. Windows 和 Linux 脚本保持 start/status/stop/restart 与 both/server/client 等价语义。
2. 服务端健康后再启动客户端；停止先客户端后服务端；不删除配对身份。
3. .env 不得输出或执行，运行目录 0700，口令文件 600，PTY 等待回显关闭。
4. 服务端使用 JDK 21；基础默认监听 127.0.0.1，只有 local profile 覆盖 0.0.0.0。
5. 局域网访问验证使用绕过代理的 TCP/HTTP 测试。
6. 不改变页面业务授权，不把运行脚本成功当作真实项目操作验收。
7. 记录真实 MySQL/Flyway、配对、挑战轮询和模型本机读取证据。
8. 更新本组文档；未提交明确记录，不获得额外提交、推送或部署授权。
```

## 关联文档

- [清单](../checklists/2026-11-05-shared-runtime-checklist.md)
- [设计](../specs/2026-11-05-shared-runtime-design.md)
- [计划](../plans/2026-11-05-shared-runtime.md)
- [实现说明](../implementation/2026-11-05-shared-runtime-implementation-design.md)
- [账本](../progress/2026-11-05-shared-runtime-ledger.md)
