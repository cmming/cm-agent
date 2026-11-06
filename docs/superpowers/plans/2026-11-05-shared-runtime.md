# 共享运行环境 2026-11-05 变更计划

> 范围：服务端、本机客户端运行与局域网入口（跨页面）  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`

## 实施顺序

| 编号 | 操作 | 验证 |
|---|---|---|
| T0 | 实现 PowerShell 脚本 | 语法和客户端生命周期 |
| T1 | 实现服务端健康等待与幂等 start | 已有服务端不被重复创建 |
| T2 | 实现 stop/restart/both | 停止顺序和服务端健康 |
| T3 | 实现 `-Build` | fat JAR 和依赖安装 |
| T4 | 实现 Bash 等价脚本 | Rocky 隔离夹具 |
| T5 | 实现 PASSWORD PTY 包装 | 600 文件、日志不含口令 |
| T6 | 执行真实 Linux `restart --build` | MySQL、Flyway、配对和挑战轮询 |
| T7 | 执行真实模型本机读取 | Run、授权、操作和工具调用完整 |
| T8 | 配置和验证局域网监听 | 健康与页面 HTTP 200 |

## 完成标准

- Windows/Linux 脚本行为一致。
- 真实服务端、客户端和模型链路可用。
- 监听改动只影响 `local` profile。

## 关联文档

- [清单](../checklists/2026-11-05-shared-runtime-checklist.md)
- [设计](../specs/2026-11-05-shared-runtime-design.md)
- [提示词](../prompts/2026-11-05-shared-runtime-prompt.md)
- [实现说明](../implementation/2026-11-05-shared-runtime-implementation-design.md)
- [账本](../progress/2026-11-05-shared-runtime-ledger.md)
