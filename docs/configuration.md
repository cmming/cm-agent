# 配置说明

本文档说明 CM Agent 第一阶段常用配置项和敏感配置处理要求。

## 基础配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | 服务端监听端口 |
| `cm-agent.default-tenant-code` | `default` | 默认租户标识 |
| `cm-agent.fake-runtime-enabled` | `true` | 第一阶段使用 fake runtime，便于在未接入真实模型时验证 Agent 运行链路 |
| `cm-agent.security.jwt-secret` | 空 | JWT 签名密钥，生产环境必须由外部安全注入 |

## fake runtime

第一阶段默认使用：

```yaml
cm-agent:
  fake-runtime-enabled: true
```

fake runtime 用于验证 Agent 配置、权限、工具治理、审计和控制台体验。接入真实模型供应商前，可以保留该配置完成端到端联调；准备切换真实运行时时，应先完成模型配置、密钥托管、权限校验和审计策略。

## JWT 密钥

生产环境必须配置安全长度的 `cm-agent.security.jwt-secret`，推荐通过部署平台 Secret、环境变量或密钥管理服务注入。不要将密钥提交到 Git、写入镜像层或打印到日志。

本地开发可以临时使用命令行参数：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--cm-agent.security.jwt-secret=cm-agent-local-secret-with-at-least-32-bytes-2026"
```

## 模型供应商与 API Key

模型供应商配置和 API Key 属于敏感数据：

- API Key 不得明文返回给前端、日志、审计响应或普通查询接口。
- 持久化时应加密保存，或只保存密钥引用，由 secret provider 在运行时解析。
- 配置展示时应只返回脱敏摘要、状态、创建人、更新时间等非敏感字段。
- 轮换 API Key 时应记录审计事件，并避免中断正在执行的运行任务。
