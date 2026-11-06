# 共享运行环境 2026-11-05 变更设计

> 范围：服务端、本机客户端运行与局域网入口（跨页面）  
> 日期：2026-11-05  
> 修订：`R0`  
> 模式：`mode=default`

## 背景

手工查找服务端和客户端 PID、检查 8080、分别启停容易重复启动。跨平台运行还需要处理 Java 21、Maven 路径、设备身份口令和运行目录权限。

## 统一脚本设计

共同动作：

- `start`
- `status`
- `stop`
- `restart`

共同目标：

- `both`
- `server`
- `client`

启动顺序：

1. 检查端口与已有服务端。
2. 启动 Maven `spring-boot:run`。
3. 等待 `/actuator/health` 返回 200。
4. 健康通过后启动客户端。

停止顺序：

1. 先停止客户端。
2. 再停止服务端 Java 和 Maven 父进程。
3. 保留设备身份和配对关系。

## 安全设计

- `.env` 只写入进程环境，不显示值。
- Linux 不开 shell 执行 `.env`。
- 运行目录使用 0700，口令文件必须 600。
- PASSWORD 身份通过 Python PTY 等待 Java 关闭回显后写入单行口令。
- 日志不复制身份秘密、JWT、数据库密码或模型 Key。

## 监听设计

- `application.yml` 默认 `${cm-agent.config.server-address:127.0.0.1}`。
- `application-local.yml` 覆盖为 `0.0.0.0`。
- 局域网暴露当前配合本地项目非回环开关和精确 Origin。

## 跨页面边界

运行环境服务于聊天页、Projects 页和本机客户端页，但不改变这些页面的业务授权或数据契约。

## 关联文档

- [清单](../checklists/2026-11-05-shared-runtime-checklist.md)
- [计划](../plans/2026-11-05-shared-runtime.md)
- [提示词](../prompts/2026-11-05-shared-runtime-prompt.md)
- [实现说明](../implementation/2026-11-05-shared-runtime-implementation-design.md)
- [账本](../progress/2026-11-05-shared-runtime-ledger.md)
