# 服务端 YAML 配置变量覆盖设计

## 目标

将服务端公共配置集中到 `cm-agent-server/src/main/resources/application.yml`，使 profile 文件只提供环境差异变量。数据库、JWT 密钥和其他敏感值仍由受控外部 YAML 覆盖，不再依赖 `CM_AGENT_JDBC_*` 环境变量。

## 配置结构

`application.yml` 保留完整的实际配置树：`server`、`spring`、`management` 和 `cm-agent`。其中环境相关的值通过 `cm-agent.config.*` 占位符解析，例如：

```yaml
cm-agent:
  security:
    jwt-secret: ${cm-agent.config.jwt-secret:}
  persistence:
    mode: ${cm-agent.config.persistence-mode:memory}
    jdbc:
      url: ${cm-agent.config.jdbc-url:}
```

`application-local.yml`、`application-test.yml`、`application-supabase.yml` 和新增的 `application-production.yml` 仅定义 `cm-agent.config.*` 变量，不重复 `cm-agent.security`、`cm-agent.persistence` 等完整配置结构。

`prod` 通过 `spring.profiles.group.prod=production` 复用生产变量，避免维护两份生产 profile 配置。

## 环境覆盖

本地和测试 profile 可以定义仅限开发或测试使用的 JWT 密钥、bootstrap admin 凭据及 memory 持久化变量。Supabase profile 定义 JDBC 模式、PostgreSQL 驱动、关闭 bootstrap admin 和关闭公开 API 文档的变量。生产 profile 定义 JDBC 模式与安全开关，但不定义 JWT 密钥、数据库 URL、用户名或密码。

生产部署通过 `--spring.profiles.active=production` 或 `--spring.profiles.active=prod` 选择 profile，并使用 `--spring.config.additional-location=file:<配置目录>/` 加载外部 YAML。外部 YAML 以相同的 `cm-agent.config.*` 变量提供密钥和数据库连接信息，覆盖内置 profile 变量。

## 安全与兼容性

保留现有 `production`、`prod`、`supabase` profile 的安全校验：必须启用 JDBC、必须提供 JWT 密钥、禁止 bootstrap admin，并禁止与 `test` profile 混用。配置重组不修改 Java 配置绑定和验证逻辑。

删除当前 `application.yml` 顶层无效的 `CM_AGENT_*` 数据库条目，避免在仓库中保留连接信息。不会把实际生产凭据写入 YAML、测试或文档。

## 验证

更新 `ApplicationProfileConfigurationTest`，覆盖 local、test、supabase、production 和 prod profile 的变量解析与生产保护。执行服务端配置测试，并使用 YAML 解析、`git diff --check` 和敏感配置扫描验证配置文件与文档。
