# 共享运行环境 2026-11-05 变更账本

> 范围：服务端、本机客户端运行与局域网入口（跨页面）  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`  
> Git 基线：`60d0fd2`  
> 提交状态：未提交

## 任务状态

| 编号 | 状态 | 证据 |
|---|---|---|
| T0 | 完成 | PowerShell 脚本语法和客户端生命周期 |
| T1 | 完成 | 健康等待和幂等服务端启动 |
| T2 | 完成 | status/stop/restart/both 实测 |
| T3 | 完成 | `-Build` 和 fat JAR 更新 |
| T4 | 完成 | Rocky 隔离夹具全部预期分支通过 |
| T5 | 完成 | PASSWORD PTY、600 文件和敏感日志扫描 |
| T6 | 完成 | MySQL 8.4、Flyway v28、ACTIVE 设备和挑战轮询 |
| T7 | 完成 | 真实模型 Run 调用本机文件读取并返回正文 |
| T8 | 完成 | 0.0.0.0 监听、本机和 Rocky 页面 HTTP 200 |

## 关键命令

```powershell
.\scripts\manage-local-runtime.ps1 status
.\scripts\manage-local-runtime.ps1 start -Target client
.\scripts\manage-local-runtime.ps1 restart -Target server `
  -JavaHome F:\java21 `
  -MavenExecutable F:\java\apache-maven-3.9.4\bin\mvn.cmd
```

```bash
scripts/manage-local-runtime.sh restart --build \
  --java-home /usr/lib/jvm/java-21 \
  --maven /usr/bin/mvn \
  --port 18080 \
  --client-state-dir "$base/client-state" \
  --client-password-file "$base/config/client.pass"
```

## 当前状态

- 服务端 PID `12488`，HTTP 200，监听双栈 8080。
- 本机和 Rocky 访问健康检查、`chat.html`、`projects.html` 均为 200。
- 未认证项目能力接口返回 401。

## 未执行

- 未创建系统服务。
- 未完成 Maven 3.9+ Linux 矩阵。
- 未清理远端隔离验收资源。
- 未提交、推送或部署。

## 关联文档

- [清单](../checklists/2026-11-05-shared-runtime-checklist.md)
- [设计](../specs/2026-11-05-shared-runtime-design.md)
- [计划](../plans/2026-11-05-shared-runtime.md)
- [提示词](../prompts/2026-11-05-shared-runtime-prompt.md)
- [实现说明](../implementation/2026-11-05-shared-runtime-implementation-design.md)
