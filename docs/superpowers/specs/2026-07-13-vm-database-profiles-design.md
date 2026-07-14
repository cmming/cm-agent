# 内网虚拟机数据库 Profile 配置设计

## 目标

基于部署节点 `192.168.0.66:/data/cm-agent/docker-compose.yml` 中的数据库配置，为服务端补充两个可直接选择的 JDBC profile：

- `postgres`：连接 PostgreSQL `192.168.0.66:5432/cm_agent`，使用 `org.postgresql.Driver`。
- `mysql`：连接 MySQL `192.168.0.66:3306/cm_agent`，使用 `com.mysql.cj.jdbc.Driver`。

两个 profile 都通过现有 `cm-agent.config.*` 变量链路覆盖公共 `application.yml`，不新增配置绑定模型，不修改 Repository、Flyway migration 或数据库 schema。

## 配置结构

新增两个服务端资源文件：

- `cm-agent-server/src/main/resources/application-postgres.yml`
- `cm-agent-server/src/main/resources/application-mysql.yml`

每个文件只定义 `cm-agent.config.*` 变量：

- `persistence-mode: jdbc`
- `jdbc-url`
- `jdbc-username`
- `jdbc-password`
- `jdbc-driver-class-name`
- `allow-dev-jwt-fallback: false`
- `bootstrap-admin-enabled: false`

为了 profile 单独启动时满足现有 JWT 启动校验，两个内网虚拟机 profile 会配置长度足够的 VM 专用 `cm-agent.config.jwt-secret`。该密钥仅用于这套虚拟机联调环境，不作为生产安全方案。

## 数据库选择

`postgres` profile 使用 docker-compose 中的 PostgreSQL 配置：

- 数据库：`cm_agent`
- 账号：`cmagent`
- 密码：`cmagent`
- JDBC URL：`jdbc:postgresql://192.168.0.66:5432/cm_agent`

`mysql` profile 使用 docker-compose 中的 MySQL 配置：

- 数据库：`cm_agent`
- 账号：`root`
- 密码：`cmagent`
- JDBC URL：`jdbc:mysql://192.168.0.66:3306/cm_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`

`allowPublicKeyRetrieval=true` 和 MySQL `root` 账号只面向当前虚拟机联调配置；后续生产化仍应改为最小权限账号和 TLS。

## 启动方式

PostgreSQL：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=postgres"
```

MySQL：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=mysql"
```

两个 profile 都会启用 JDBC/Flyway，首次连接目标数据库时按现有 `classpath:db/migration` 自动迁移。

## 文档与测试

更新中文配置/部署文档，说明 `postgres` 和 `mysql` profile 的用途、启动方式以及它们与 `production`、`supabase` profile 的区别。

更新 `ApplicationProfileConfigurationTest`，覆盖两个新 profile 的变量映射，验证 JDBC URL、账号、密码、驱动、JWT secret、安全开关和实际 `cm-agent.persistence.jdbc.*` 绑定值。

## 边界

本次不修改数据库迁移，不新增表，不改变多租户、权限、审计或 Repository 行为。若虚拟机数据库不可达，本次只验证配置加载与单元测试；不把远端数据库连通性作为必须通过条件。
