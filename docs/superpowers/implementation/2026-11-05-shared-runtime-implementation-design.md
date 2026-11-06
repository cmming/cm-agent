# 共享运行环境 2026-11-05 变更实现

> 范围：服务端、本机客户端运行与局域网入口（跨页面）  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`

## 实际交付

- `scripts/manage-local-runtime.ps1`：Windows 服务端/客户端生命周期和构建。
- `scripts/manage-local-runtime.sh`：Linux 等价动作、进程检查、健康等待、PASSWORD PTY 和 `flock`。
- 服务端基础监听保持回环，`application-local.yml` 覆盖为 `0.0.0.0`。
- `docs/operations.md` 和 `docs/configuration.md` 同步运行与监听说明。

## 真实验证

- Windows：客户端 start/stop/restart、服务端幂等 start、默认 both restart、构建重启通过。
- Rocky Linux 9.3：JDK 21、MySQL 8.4、Flyway v28、设备 ACTIVE、挑战计数持续增长。
- 真实 DashScope `qwen-plus` Run 调用 `local_client_read_files` 成功。
- Run、授权、操作和工具调用状态完整；返回测试文件正文。
- 敏感日志扫描未发现口令、JWT、数据库密码或配置密钥。

## 当前监听状态

- PID `12488`
- Java `21.0.11`
- profile `mysql,local`
- 监听双栈 `8080`
- `127.0.0.1` 和 `192.168.0.159` 健康检查 HTTP 200
- 本机和 Rocky 访问 `chat.html`、`projects.html` HTTP 200

## 遗留

- 脚本不是系统服务，主机重启后需手动启动。
- 固定日志路径重启会覆盖。
- Linux 真实验收使用 Maven 3.6.3，不替代 Maven 3.9+ 矩阵。
- 隔离验收资源仍保留。
- 未提交。

## 关联文档

- [清单](../checklists/2026-11-05-shared-runtime-checklist.md)
- [设计](../specs/2026-11-05-shared-runtime-design.md)
- [计划](../plans/2026-11-05-shared-runtime.md)
- [提示词](../prompts/2026-11-05-shared-runtime-prompt.md)
- [账本](../progress/2026-11-05-shared-runtime-ledger.md)
