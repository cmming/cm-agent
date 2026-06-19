# 运维说明

本文档说明 CM Agent 第一阶段运行后的健康检查、审计查询和日常运维关注点。

## 健康检查

服务端暴露 Spring Boot Actuator 健康端点：

```http
GET /actuator/health
```

本地示例：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

返回 `UP` 表示应用进程和基础 Spring 容器已就绪。生产环境可以将该端点接入负载均衡、容器编排平台或外部探活系统。

## 审计查询

审计事件查询端点：

```http
GET /api/audit-events
```

该端点用于查询登录、Agent 创建/运行、工具创建/授权、权限拒绝等关键动作的审计记录。除健康检查和登录接口外，业务 API 默认需要认证；调用审计查询前应先通过登录流程获取令牌，并在请求中携带 `Authorization: Bearer <token>`。

第一阶段默认审计存储仍为内存 store，适合验证事件结构和权限链路；进程重启后审计事件会丢失。生产试点前应接入持久化审计 repository，并确认审计写入失败告警、容量上限、查询分页和备份策略。

## 日志与告警

- 关注服务启动失败、JWT 密钥缺失、认证失败激增和运行链路异常。
- 生产日志中不得输出 JWT 密钥、模型 API Key、数据库密码或其他敏感凭据。
- 建议为健康检查失败、接口 5xx 增长、审计写入失败和数据库连接异常配置告警。

## 数据库运维

- MySQL 和 PostgreSQL 迁移脚本位于 `cm-agent-persistence/src/main/resources/db/migration`。
- 本地 `docker-compose.yml` 仅用于开发验证，不代表生产容量、备份或高可用方案。
- 默认服务端 REST API 尚未把 Agent、Tool、Grant 和 Audit 运行态数据写入这些数据库；生产试点前需要完成持久化服务层接入。
- 生产环境应启用定期备份、恢复演练、最小权限账号和连接池监控。
