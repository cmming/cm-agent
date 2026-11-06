# 共享运行环境 2026-11-05 变更清单

> 范围：服务端、本机客户端运行与局域网入口（跨页面）  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`  
> Git 基线：`60d0fd2`

## 需求与范围

合并原 `local-runtime-script` 与 `lan-console-listening`，记录不专属于单一页面的运行管理、局域网监听和端到端验证。

范围：

- Windows PowerShell 与 Linux Bash 统一启停脚本。
- 服务端健康等待、客户端后台运行和配对身份保留。
- Linux PASSWORD 身份的安全口令输入。
- 基础回环默认与 `local` profile 的 `0.0.0.0` 覆盖。
- JDK 21、真实 MySQL/Flyway、Rocky 和真实模型本机读取证据。

非目标：

- 不创建 Windows 服务或 systemd 单元。
- 不自动安装 JDK/Maven。
- 不代替生产 TLS、反向代理或防火墙。

## 任务与验收

| 编号 | 状态 | 目标 | 验收 |
|---|---|---|---|
| T0 | 完成 | 统一动作和目标参数 | start/status/stop/restart 与 both/server/client |
| T1 | 完成 | 服务端健康等待和客户端启动 | 健康后启动、重复启动幂等 |
| T2 | 完成 | 状态、停止和重启 | PID/健康/配对可见，停止不删身份 |
| T3 | 完成 | 构建和运行包更新 | fat JAR 与服务端依赖构建成功 |
| T4 | 完成 | Linux Bash 等价能力 | Rocky 隔离夹具通过 |
| T5 | 完成 | PASSWORD 身份安全输入 | PTY 等待回显关闭，日志无口令 |
| T6 | 完成 | 真实 Linux 全链路 | MySQL 8.4、Flyway v28、设备 ACTIVE、客户端轮询 |
| T7 | 完成 | 真实模型本机工具 | Run SUCCEEDED，操作链完整，返回文件正文 |
| T8 | 完成 | 局域网监听 | 0.0.0.0 配置，本机和 Rocky HTTP 200 |

核心验收：

- A1：脚本动作和目标在两个平台等价。
- A2：服务端健康后再启动客户端。
- A3：停止顺序先客户端后服务端，配对不丢失。
- A4：脚本不输出 `.env`、JWT、数据库密码、模型 Key 或设备私钥。
- A5：Linux 运行目录 0700，口令文件 600。
- A6：基础配置仍默认回环，`local` profile 才使用 0.0.0.0。
- A7：局域网健康和控制台页面返回 200。

## 关联文档

- [设计](../specs/2026-11-05-shared-runtime-design.md)
- [计划](../plans/2026-11-05-shared-runtime.md)
- [提示词](../prompts/2026-11-05-shared-runtime-prompt.md)
- [实现说明](../implementation/2026-11-05-shared-runtime-implementation-design.md)
- [账本](../progress/2026-11-05-shared-runtime-ledger.md)
