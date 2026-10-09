# 配置说明

本文档说明服务端的 profile、真实 AgentScope Runtime、技能脚本沙箱、持久化、安全和敏感信息配置。`local`/`test` 可显式使用 fake runtime；生产 profile 必须关闭 fake runtime、启用 AgentScope Runtime，并使用外部签发、受控验证的 JWT。

## Profile 选择

公共 `application.yml` 通过 `spring.profiles.active` 选择环境，也兼容使用 `CM_AGENT_PROFILE` 作为 profile 选择器。部署时优先使用 `spring.profiles.active`，例如：

```powershell
mvn -pl cm-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=local"
```

`application.yml` 没有把 `local` 设为默认 profile。未显式设置 `spring.profiles.active` 且未设置 `CM_AGENT_PROFILE` 时，不会自动加载 `application-local.yml`；服务将使用公共配置中的默认值。

| Profile | 用途 | 持久化与安全边界 |
| --- | --- | --- |
| `local` | 本地开发和演示 | 可以使用 memory、fake runtime 和 bootstrap admin；凭据仅限本地 |
| `test` | 自动化测试 | 可以使用 memory 和测试 bootstrap admin；测试凭据由代码/CI 注入 |
| `postgres`、`mysql` | Rocky VM 集成验证 | 使用 JDBC/Flyway；仅限联调，不是生产凭据来源 |
| `prod`、`production` | 生产或类生产 | 必须使用 JDBC，关闭 fake runtime，禁用 bootstrap admin 和开发 JWT fallback |
| `supabase` | Supabase PostgreSQL | 必须使用 JDBC，关闭 fake runtime，禁用 bootstrap admin 和开发 JWT fallback |
| `skill-sandbox` | 可选技能脚本沙箱配置 | 与环境 profile 显式组合；该 profile 本身仍默认关闭执行，不能替代环境安全配置 |

`prod` profile 通过 Spring profile group 复用 `production` 配置。生产 profile 启动时，如果缺少安全 JWT secret、误用 memory、启用 bootstrap admin 或混入不允许的 profile，服务应启动失败。

## 基础配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `8080` | 服务端监听端口 |
| `spring.profiles.active` | 空 | 运行环境选择器；推荐显式设置 |
| `CM_AGENT_PROFILE` | 空 | profile 兼容选择器；仅在未通过 Spring 配置选择时使用 |
| `cm-agent.fake-runtime-enabled` | `false` | fake runtime 开关；仅 `local`/`test` 可按需设为 `true`，strict profile 必须为 `false` |
| `cm-agent.agentscope.enabled` | `false` | AgentScope 真实 Runtime 开关；生产 profile 必须为 `true`，并与 fake runtime 互斥 |
| `cm-agent.agentscope.model-timeout` | `60s` | 单次模型阶段超时时间，必须为正数 |
| `cm-agent.agentscope.tool-timeout` | `30s` | AgentScope 工具执行超时时间，必须为正数 |
| `cm-agent.agentscope.model-max-attempts` | `2` | 模型最大尝试次数，范围为 1 到 5 |
| `cm-agent.agentscope.studio.enabled` | `false` | 是否启用 AgentScope Studio 开发期消息转发；`production`、`prod`、`supabase` 禁止启用 |
| `cm-agent.agentscope.studio.url` | `http://localhost:8000` | Studio HTTP/WebSocket 地址；仅接受不含用户信息、查询串和片段的 HTTP(S) 绝对地址 |
| `cm-agent.agentscope.studio.project` | `cm-agent` | Studio 中归集当前服务实例的项目名称 |
| `cm-agent.agentscope.studio.run-name` | `cm-agent` | Studio 中当前服务实例的 Run 名称，不对应单个 CM Agent `runId` |
| `cm-agent.persistence.mode` | `memory` | `memory` 只允许本地开发和测试；生产 profile 必须为 `jdbc` |
| `cm-agent.skills.enabled` | `false` | 是否允许创建、更新、启用和绑定文本型 Skill；关闭后保留历史读取、停用与解绑，便于受控停写 |
| `cm-agent.skills.allowed-resource-types` | `.md,.txt,.json,.yaml,.yml,.csv,.html,.js,.cjs,.sh` | Skill ZIP 内允许的文本资源扩展名白名单；入口文件 `SKILL.md` 始终保留 |
| `cm-agent.default-tenant-code` | `default` | 默认租户标识 |
| `cm-agent.mcp.enabled` | `false` | 是否注册无状态 MCP Streamable HTTP 端点；生产启用前必须同时配置来源和主机白名单 |
| `cm-agent.mcp.endpoint` | `/mcp` | MCP 端点的单一路径，不能包含查询串、片段、通配符或结尾斜杠 |
| `cm-agent.http-tools.enabled` | `false` | 是否允许动态 HTTP 工具出站执行；生产环境必须显式开启 |
| `cm-agent.http-tools.allow-http` | `false` | 是否允许明文 HTTP；生产环境应保持 `false` 并使用 HTTPS |
| `cm-agent.http-tools.allowed-hosts` | 空 | HTTP 工具允许访问的主机白名单；为空时所有出站请求都会被拒绝 |
| `cm-agent.http-tools.min-timeout` | `100ms` | 单工具最小请求超时 |
| `cm-agent.http-tools.max-timeout` | `30s` | 单工具最大总超时；请求、重定向和 Secret 解析共用该时限 |
| `cm-agent.http-tools.max-response-bytes` | `262144` | 脱敏前后均受约束的响应上限，超过上限返回固定受控失败 |
| `cm-agent.http-tools.max-redirects` | `3` | 同源重定向最大次数；每跳均重新校验协议、主机与地址安全性 |
| `cm-agent.model-catalog-discovery.allow-http` | `false` | 是否允许模型目录发现使用明文 HTTP；生产应保持 `false` |
| `cm-agent.model-catalog-discovery.allowed-hosts` | OpenAI 与 DashScope 官方主机 | 模型目录发现的精确主机白名单；自建网关必须显式加入 |
| `cm-agent.model-catalog-discovery.timeout` | `5s` | 单次目录请求的连接与读取超时，必须为正数 |
| `cm-agent.model-catalog-discovery.max-response-bytes` | `131072` | 目录响应体上限，超过后返回受控失败 |
| `cm-agent.model-catalog-discovery.max-models` | `200` | 单次返回给控制台的模型名称上限 |

## Skill 配置与权限

Skill 只接受 ZIP 中的 `SKILL.md` 和文本资源；不支持二进制、网络地址或写入型资源。资源扩展名由 `cm-agent.skills.allowed-resource-types` 配置，默认开放 `.md`、`.txt`、`.json`、`.yaml`、`.yml`、`.csv`、`.html`、`.js`、`.cjs` 和 `.sh`。扩展名控制导入范围，不授予执行权限；沙箱默认关闭。启用 `cm-agent.skills.sandbox.enabled` 后，白名单自动补入 `.py`，通过受治理的 `run_skill_script` 执行当前 Run 快照中的 Python 脚本；其他语言仍作为文本资源。`cm-agent.skills.enabled` 默认关闭，启用前应先在 `test` 或隔离环境验证包结构、容量和模型上下文成本。服务端公开能力接口会返回当前允许扩展名、归档/解压大小、文件数、单资源/指令上限和单 Agent 绑定上限；浏览器不得自行放宽这些限制。

读取技能需要 `skill:read`，上传、版本更新和启停需要 `skill:write`。Agent 绑定同时要求 `agent:write` 与 `skill:read`，运行本身仍需要既有 `agent:run`；绑定不会授予任何业务 Tool 权限。生产变更权限后，应重新签发 JWT 或等待身份系统的短时令牌刷新，不能依赖前端隐藏按钮作为安全边界。

启用开关后，部署者仍应先备份 JDBC 数据库并完成 Flyway 迁移。新 Run 固定当前版本快照；更新技能不会修改旧 Run。停用、解绑或访问纪元变化会阻止旧 Run 后续读取和审批恢复，页面应提示重新发起，而不是自动重放消息或审批。

技能 `description` 最多包含 1024 个 Unicode 字符。JDBC 环境需应用 V18，将 `skill_versions.description` 从历史的 `VARCHAR(500)` 扩为 `VARCHAR(1024)`；否则长描述包可能解析成功后在落库时失败。该迁移保留既有内容、摘要和版本指针，不授予资源执行权限；原版文档技能仍须满足资源白名单、容量和运行依赖要求。

## 技能脚本沙箱与远程 Docker

### 技能容量与运行预算

以下数值取自 `SkillProperties`，单位为 UTF-8 字节或文件/尝试次数；部署覆盖值必须在 1 到硬上限之间。

| 配置项 | 默认值 | 硬上限 | 作用阶段 |
|---|---:|---:|---|
| `cm-agent.skills.max-zip-bytes` | 4194304（4 MiB） | 16777216（16 MiB） | 上传 ZIP 压缩大小 |
| `cm-agent.skills.max-expanded-bytes` | 4194304（4 MiB） | 4194304（4 MiB） | 导入时全部文件解压总大小 |
| `cm-agent.skills.max-files` | 256 | 512 | 导入文件数量 |
| `cm-agent.skills.max-instruction-bytes` | 32768（32 KiB） | 32768（32 KiB） | `SKILL.md` 正文大小 |
| `cm-agent.skills.max-resource-bytes` | 262144（256 KiB） | 1048576（1 MiB） | 导入时单个资源大小 |
| `cm-agent.skills.max-path-length` | 240 | 240 | 资源相对路径长度 |
| `cm-agent.skills.max-bound-skills` | 20 | 20 | 单 Agent 技能绑定数量 |
| `cm-agent.skills.max-run-bytes` | 8388608（8 MiB） | 8388608（8 MiB） | 单 Run 固定技能快照准备大小 |
| `cm-agent.skills.max-load-attempts` | 32 | 32 | 单 Run 累计读取/准备尝试次数 |
| `cm-agent.skills.max-loaded-bytes` | 16777216（16 MiB） | 16777216（16 MiB） | 单 Run 累计读取与沙箱资源准备预算 |

导入容量与运行预算分别检查：包能导入不代表可以无限次读取或执行。沙箱每次准备会将固定版本的全部资源和 `SKILL.md` 一起预留到累计预算中；之前的模型读取也计入同一 Run 预算。次数用尽或新资源超过剩余字节预算均返回 `413/SKILL_LOAD_LIMIT_EXCEEDED`，默认 32 次仍保持，不应在同一 Run 盲目重试。新 Run 重新计量。

这些属性不属于 `cm-agent.skills.sandbox`。独立解析器的 `SkillPackageLimits.defaults()` 保留历史保守值，服务端导入使用 `SkillProperties.toPackageLimits()` 的配置快照。修改源码或部署配置后需按发布流程构建/重启才能生效；实际配置覆盖可能比这里的默认值更严格。

HTTP 上传还受 `spring.servlet.multipart.max-file-size` 和 `spring.servlet.multipart.max-request-size` 限制。扩大 ZIP 配置并不会自动扩大 multipart 限制，部署者需同时核对两者。

### 沙箱执行与连接配置

以下配置均属于 `cm-agent.skills.sandbox`，默认值以当前 Server 配置类为准，与 `application-skill-sandbox.yml` 一致。表中的环境变量是该可选 profile 显式提供的占位符；未加载它时，这些自定义别名不会自动映射到对应属性。也可以直接通过外部 YAML 或 Spring 配置设置完整属性名。`—` 表示该 profile 未提供专用环境变量占位符。

### 执行策略与凭据管理

| 完整配置项 | 默认值 | 环境变量（加载 `skill-sandbox`） | 用途与约束 |
| --- | --- | --- | --- |
| `cm-agent.skills.sandbox.enabled` | `false` | `CM_AGENT_SKILL_SANDBOX_ENABLED` | 技能脚本执行开关；加载 profile 不会自动开启。技能管理开关 `cm-agent.skills.enabled` 单独配置 |
| `cm-agent.skills.sandbox.image` | `python:3.12-alpine` | `CM_AGENT_SKILL_SANDBOX_IMAGE` | 部署者指定的可信 Python 镜像，须在实际 Docker daemon 预拉取；生产建议固定 digest，执行时不自动拉取 |
| `cm-agent.skills.sandbox.runtime` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_RUNTIME` | 可选强化 OCI runtime；为空使用 Docker 默认值，明确指定后不可用则失败，不自动回退 |
| `cm-agent.skills.sandbox.timeout` | `15s` | — | 整次容器启动、输入与执行预算，允许 `1s`～`20s`；应小于 AgentScope 工具超时 |
| `cm-agent.skills.sandbox.max-input-bytes` | `32768` | — | 脚本 stdin 的 UTF-8 字节上限，允许 `1`～`32768` |
| `cm-agent.skills.sandbox.max-output-bytes` | `32768` | — | stdout 与 stderr 合计原始字节上限，允许 `1`～`32768` |
| `cm-agent.skills.sandbox.max-concurrent` | `2` | — | 单实例执行并发上限，允许 `1`～`2`；不是租户或集群总配额 |
| `cm-agent.skills.sandbox.backend` | `docker` | — | 已注册的后端标识；当前仅交付 Docker 实现，不能填写实现类或任意服务 URL |
| `cm-agent.skills.sandbox.allowed-targets` | 空列表 | `CM_AGENT_SKILL_SANDBOX_ALLOWED_TARGETS` | 部署者控制的远程 `host:port` 精确允许列表；空列表拒绝全部远程目标，不影响 LOCAL。推荐使用 YAML 列表，不接受通配符、CIDR 或 URL |
| `cm-agent.skills.sandbox.encryption-key` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_ENCRYPTION_KEY` | 控制台凭据加密的独立 32 字节随机主密钥，以 Base64 编码并由部署 Secret 注入；不得复用 JWT 或模型密钥 |

镜像与 runtime 名称在启动期校验，时间、输入输出及并发配置只能在上述范围内调整。禁网、非 root、只读根文件系统和 CPU/内存/PID 等隔离策略由执行器固定约束，本轮没有新增可放宽这些边界的配置项。导入、TEST、发布、绑定及 Run 授权要求继续生效。

主密钥缺失或无效时不会提供默认密钥：服务可按既有配置启动，但端点管理写入和已存远程凭据解密被拒绝，读接口只返回配置状态。此密钥用于租户端点凭据，部署默认连接的文件材料由部署者提供。更换主密钥前须安排旧材料解密与重新加密；当前没有自动跨主密钥轮换接口。生产端点使用 JDBC 持久化并完成 V16、V17 迁移，memory 仅限 local/test。

### 部署默认连接

| 完整配置项 | 默认值 | 环境变量（加载 `skill-sandbox`） | 用途与约束 |
| --- | --- | --- | --- |
| `cm-agent.skills.sandbox.connection.mode` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_CONNECTION_MODE` | 空值保留 R0 本地环境兼容；显式模式为 `LOCAL`、`SSH`、`TLS` |
| `cm-agent.skills.sandbox.connection.host` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_HOST` | SSH/TLS 主机名或 IP，不含协议、用户信息和路径；须精确登记在允许列表 |
| `cm-agent.skills.sandbox.connection.port` | `0` | `CM_AGENT_SKILL_SANDBOX_PORT` | LOCAL 必须为 `0`；SSH/TLS 必须显式指定 `1`～`65535`，不会自动使用 `22` 或 `2376` |
| `cm-agent.skills.sandbox.connection.username` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_USERNAME` | SSH 主机账号；LOCAL/TLS 必须为空 |
| `cm-agent.skills.sandbox.connection.ssh-auth-type` | `KEY` | `CM_AGENT_SKILL_SANDBOX_SSH_AUTH_TYPE` | 本轮账号密码需求新增；SSH 可选 `KEY`、`PASSWORD`，LOCAL/TLS 仅接受 `KEY`，不会自动尝试其他认证方式 |
| `cm-agent.skills.sandbox.connection.password-file` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_PASSWORD_FILE` | 本轮账号密码需求新增；仅 SSH PASSWORD 使用的部署 Secret 文件路径，不能直接填写密码 |
| `cm-agent.skills.sandbox.connection.private-key-file` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_PRIVATE_KEY_FILE` | SSH KEY 私钥或双向 TLS 客户端私钥文件；PASSWORD 必须清空 |
| `cm-agent.skills.sandbox.connection.known-hosts-file` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_KNOWN_HOSTS_FILE` | SSH 两种认证都必需的可信 known_hosts 文件；主机条目按原 host 别名匹配，不附端口 |
| `cm-agent.skills.sandbox.connection.ca-certificate-file` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_CA_CERTIFICATE_FILE` | 双向 TLS 的可信 CA 证书文件；PASSWORD 必须清空 |
| `cm-agent.skills.sandbox.connection.client-certificate-file` | 空字符串 | `CM_AGENT_SKILL_SANDBOX_CLIENT_CERTIFICATE_FILE` | 双向 TLS 的客户端证书链文件；PASSWORD 必须清空 |

| 连接方式 | 必需参数与材料 | 部署要求 |
| --- | --- | --- |
| LOCAL | `mode: LOCAL`，host/username 为空、port 为 0 | 本机 socket；Server 需要 Docker CLI |
| SSH KEY | host、port、username、私钥和 known_hosts；`ssh-auth-type: KEY` | Server 需要 Docker CLI 与 OpenSSH 客户端；私钥不能依赖交互口令 |
| SSH PASSWORD | host、port、username、密码文件和 known_hosts；`ssh-auth-type: PASSWORD` | Server 需要 Docker CLI；通过 JSch 内存认证，远端 sshd 须允许 password 认证，不支持 keyboard-interactive/MFA |
| TLS | host、port、CA、客户端证书链与私钥；username 为空 | Server 需要 Docker CLI；私钥为未加密 PKCS#8 PEM（RSA/EC），证书与私钥匹配，服务端 SAN 覆盖配置 host |

SSH 账号须能访问远端 Linux 的 `/var/run/docker.sock` 并允许 Unix socket 转发。账号密码指远端 SSH 主机认证，不是 Registry 登录或 Docker HTTP Basic。密码文件内容原样读取，不 trim：须非空，UTF-8 长度不超过 4096 字节，不含 NUL/CR/LF，也不能带末尾换行；总认证材料不超过 128KiB。KEY/TLS 不接受密码，PASSWORD 不接受私钥或 TLS 证书。部署文件由部署者管理，控制台不能提交这些文件路径；密码不进入 CLI 参数、子进程环境、日志、审计或浏览器存储。

远程目标的每个 DNS 解析地址均须通过安全校验，拒绝未指定、链路本地和组播地址；私网和回环目标也必须在允许列表中显式登记。连接固定已校验 IP，SSH 严格验证原 host 的 known_hosts，TLS 严格验证原 host 证书身份。显式 LOCAL/SSH/TLS 不继承全局 Docker context、DOCKER_HOST 或用户 SSH 配置。mode 留空只兼容本地 unix/npipe 的 DOCKER_HOST；旧远程 DOCKER_HOST 被拒绝，须迁移为 SSH/TLS。

### SSH 账号密码示例与运行期选择

可以在部署的外部配置中使用下面的占位示例，或加载 `test,skill-sandbox` / `production,skill-sandbox` 等环境组合并设置上表的环境变量。生产组合仍须满足已有 JDBC、JWT、真实 Runtime 等要求；不能把 local/test 混入生产组合。示例没有可用凭据，Secret 文件和主密钥由部署者提供。

```yaml
cm-agent:
  skills:
    enabled: true
    sandbox:
      enabled: true
      backend: docker
      image: python:3.12-alpine
      runtime: ""
      timeout: 15s
      max-input-bytes: 32768
      max-output-bytes: 32768
      max-concurrent: 2
      allowed-targets:
        - sandbox.example.invalid:22
      encryption-key: ${CM_AGENT_SKILL_SANDBOX_ENCRYPTION_KEY:}
      connection:
        mode: SSH
        host: sandbox.example.invalid
        port: 22
        username: sandbox
        ssh-auth-type: PASSWORD
        password-file: /run/secrets/sandbox-ssh-password
        known-hosts-file: /run/secrets/sandbox-known-hosts
        private-key-file: ""
        ca-certificate-file: ""
        client-certificate-file: ""
```

执行优先使用当前可信租户的默认端点；没有租户默认时使用上述部署连接。选择后的失败不会回退其他端点或本地。每次调用固定端点及配置、凭据和连接版本，运行期切换默认值不会改动已经执行中的连接。

控制台“技能管理 → 沙箱端点”管理的是租户端点元数据和加密凭据，不修改这些部署配置。API 的 `sshAuthType`、`credentials.password` 是请求字段，不是新增 YAML 配置；控制台只写密码并只读配置状态，不能提交主密钥、宿主文件路径、镜像或资源限额。权限、API、迁移和完整连接操作见[沙箱端点部署与管理](skill-sandbox-endpoints.md)。

## 模型配置管理

`/api/model-configs` 提供当前租户模型元数据的列表、详情、创建、更新和删除接口。读取需要 `model:read`，创建与更新需要 `model:write`，删除需要 `model:delete`。请求体只包含：

```json
{
  "providerType": "OPENAI_COMPATIBLE",
  "displayName": "业务模型",
  "baseUrl": "https://models.example.com/v1",
  "modelName": "qwen-plus",
  "enabled": true
}
```

`baseUrl` 必须是没有用户信息和 URL 片段的 HTTP(S) 绝对地址。创建请求必须包含 `apiKey`，更新请求省略该字段或传 `null` 时保留原值，提供非空值时轮换密钥。API Key 在进入仓储前以 AES/GCM 加密写入 `encrypted_api_key`，响应、列表、详情、审计和日志均不包含 API Key 或密文。仍被 Agent 引用的模型配置不能删除，接口会返回明确的 `409 Conflict`；启动初始化器维护的固定系统默认配置也不能删除，但可以停用或更新。

## 模型目录发现

v2 模型配置表单提供“获取模型列表”。它只调用 CM Agent 的同源接口，浏览器不会向供应商域名发送 API Key。创建前或编辑时更改了 Provider、基础地址的草稿，使用 `POST /api/model-configs/discover-models` 并只在本次服务端调用期间使用请求中的 API Key；未修改 Provider 和基础地址的已保存配置，使用 `POST /api/model-configs/{id}/discover-models`，服务端只会在当前 tenant 内读取已保存密文。两者均需要 `model:write`。

响应为 `{ "items": ["model-a", "model-b"] }`；服务端只接收并返回 `data[].id`，会去重、排序并限制到 `max-models`。第一版仅读取供应商返回的首个目录响应，不自动追页、不持久化或缓存模型目录。`404`、`405` 或目录结构不兼容会返回可手工回退的稳定错误码；页面不会覆盖当前已填模型名。

目录发现独立使用 `cm-agent.model-catalog-discovery.*`，避免继承动态 HTTP 工具的“默认拒绝所有主机”语义。默认仅允许 `api.openai.com`、`dashscope.aliyuncs.com` 和 `dashscope-intl.aliyuncs.com`；其他 OpenAI Compatible 网关应由部署者显式配置精确主机名，例如：

```yaml
cm-agent:
  model-catalog-discovery:
    allowed-hosts:
      - models.example.com
    timeout: 5s
    max-response-bytes: 131072
    max-models: 200
```

请求不跟随重定向，地址仍经过协议、主机和解析地址安全校验。不得在此配置、日志、审计或浏览器中放置 API Key；目录发现失败时使用 `errorCode` 与 `errorId` 关联日志，并改为手工填写模型名称。

## 动态 HTTP 工具

动态 HTTP 工具由 `POST /api/tools` 创建，`type` 为 `HTTP`，需要 `tool:grant` 权限。工具定义与 HTTP 配置在同一事务内保存；同一租户的工具名称唯一。新版 HTTP 配置包含 `method`（`GET` 或 `POST`）、`urlTemplate`、扁平 `parameters`、`secretHeaders` 与 `timeoutMillis`。输入 JSON Schema 由服务端生成，不需要调用方重复维护。示例仅展示结构，不含可用凭据：

```json
{
  "name": "order_lookup",
  "description": "查询订单摘要",
  "type": "HTTP",
  "riskLevel": "MEDIUM",
  "httpConfig": {
    "method": "POST",
    "urlTemplate": "https://api.example.test/orders/{orderId}",
    "parameters": [
      { "id": "orderId", "name": "orderId", "dataType": "STRING", "requestLocation": "PATH", "required": true },
      { "id": "filter", "name": "filter", "dataType": "OBJECT", "requestLocation": "BODY", "required": false },
      { "id": "status", "parentId": "filter", "name": "status", "dataType": "STRING", "required": false, "defaultValue": "OPEN" }
    ],
    "secretHeaders": { "X-Service-Token": "secret/integration/order-service" },
    "timeoutMillis": 3000
  }
}
```

每个参数以 `id` 唯一标识，嵌套参数只需用 `parentId` 指向父节点。只有顶层参数填写 `requestLocation`；字段 `name` 会直接作为 PATH、QUERY、HEADER 或 BODY 字段名，不再需要 `sourcePointer`、`targetPointer` 或 `nodeRole`。`dataType` 支持 `STRING`、`INTEGER`、`NUMBER`、`BOOLEAN`、`OBJECT` 和 `ARRAY`。ARRAY 必须有且只有一个直接匿名子节点（`name` 为空）来描述元素类型，子节点仍用 `parentId` 定位。

顶层位置支持 `PATH`、`QUERY`、`HEADER`、`BODY` 和 `BODY_ROOT`。`BODY_ROOT` 会把该命名 Tool 输入字段的值直接作为整个请求体，因此可表达 `[{"p1":"v1"}]` 这类根数组请求；它不能与 `BODY` 并用。PATH 必须是必填标量且与 URL `{placeholder}` 完整同名；QUERY 允许标量或标量数组；HEADER 仅允许非敏感标量；GET 不允许 BODY/BODY_ROOT。缺失输入或显式 `null` 会尝试应用参数的 `defaultValue`。

HTTP 工具配置只接受 `parameters`。`inputSchema` 由服务端根据参数定义生成，并保存在通用工具定义中供模型和 MCP 使用；客户端不能提交或编辑 HTTP 配置中的 Schema 和 JSON Pointer 映射。

`secretHeaders` 只允许 `secret/...` 格式的引用，不保存密钥值，也不向创建响应、审计、日志、调试或 MCP 响应返回值。部署方必须提供受控 `SecretProvider`，并确保其自身 I/O 超时与中断响应；缺失、超时或失败的解析只产生固定受控错误。

HTTP 工具默认不能出站。启用后，URL 必须匹配 `allowed-hosts`，并由服务端拒绝回环、链路本地、私有/保留地址及其他 SSRF 风险地址。重定向只允许同源，且每次跳转再次执行 DNS/地址校验；总超时覆盖 Secret 解析、请求和重定向。响应采用固定大小上限并经脱敏后才返回。JDK HTTP 客户端无法在域名解析后将已校验 IP 与 TLS SNI 原子绑定，生产网络仍必须配置 egress 防火墙、受控 DNS 或代理，作为 DNS TOCTOU 的纵深防御。

控制台使用树形编辑器录入参数：顶层节点直接添加，OBJECT/ARRAY 在节点内添加子参数；页面保存时自动转换为扁平 `parameters[]`。Secret 配置仍只接受 JSON 对象和 `secret/...` 引用。调试入口仅支持 HTTP/LOCAL 工具，需要 `tool:debug`；HIGH 风险工具必须提交完全一致的工具名称。调试不创建 Agent 或 Run，仍经过同一治理、审计和输出脱敏链路。

## MCP Streamable HTTP

MCP 端点默认关闭。启用时，除 `cm-agent.mcp.enabled=true` 外，必须显式配置非空的 `cm-agent.mcp.allowed-origins` 和 `cm-agent.mcp.allowed-hosts`；任一白名单缺失都会使服务启动失败。可使用部署环境的等价环境变量或受控外部 YAML 提供列表，示例中的值仅用于说明：

```yaml
cm-agent:
  mcp:
    enabled: true
    endpoint: /mcp
    allowed-origins:
      - https://mcp-client.example.test
    allowed-hosts:
      - mcp.example.test
```

`POST /mcp` 使用 MCP Java SDK 2.0 的无状态 Streamable HTTP transport。`GET` 固定返回 `405`；关闭开关时端点不存在并返回 `404`。端点仍受 JWT 认证保护，不会因为启用 MCP 而变为公开接口；调用方还需要 `tool:mcp:invoke` 权限。每个 HTTP 请求都依据认证主体重新构建当前租户的工具目录，每次调用还会重新读取发布记录和工具定义，因此取消发布、禁用或配置漂移会即时生效。

只会公开已发布且启用的 HTTP/LOCAL 工具。创建工具时 `mcpPublished=true` 或通过 `PUT /api/tools/{id}/mcp-publication` 发布，取消发布使用 `DELETE /api/tools/{id}/mcp-publication`，两者均需要 `tool:grant`；控制台操作完成后会重新加载当前工具状态。HTTP 工具的端点必须与受治理配置一致，LOCAL 工具必须与当前注册快照一致。调用使用 `MCP` 来源进入统一受治理执行入口，不创建 Agent 或 Run。输入按 MCP Schema 校验；调用失败仅返回受控 MCP 错误文本，不返回密钥、URL、Cookie、异常栈或底层错误。

通过 `cm-agent.config.*` 可以覆盖公共 YAML 中对应的 `cm-agent.*` 属性。生产配置应放在受控外部 YAML 或由 secret manager 生成并挂载的配置文件中：

```yaml
cm-agent:
  config:
    jwt-secret: <externally-controlled-jwt-verification-key>
    persistence-mode: jdbc
    jdbc-url: <controlled-jdbc-url>
    jdbc-username: <least-privilege-db-user>
    jdbc-password: <secret-manager-db-password>
    jdbc-driver-class-name: org.postgresql.Driver
    fake-runtime-enabled: false
    agentscope-enabled: true
  model-credentials:
    encryption-key: ${CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY}
```

不要把上面的占位符替换后的值提交到 Git、镜像层、日志、审计消息或 API 响应。敏感配置不应通过文档中的固定值传播。

## AgentScope 真实 Runtime

阶段3使用 AgentScope Java `2.0.2`，支持 `OPENAI_COMPATIBLE` 和 `DASHSCOPE_NATIVE` 两种 Provider，分别由 AgentScope OpenAI 与 DashScope 扩展提供。启用真实 Runtime 必须满足：

```yaml
cm-agent:
  fake-runtime-enabled: false
  agentscope:
    enabled: true
    model-timeout: 60s
    tool-timeout: 30s
    model-max-attempts: 2
    permission-enabled: true
    approval-ttl: 15m
  model-credentials:
    encryption-key: ${CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY}
```

默认 `DatabaseModelCredentialProvider` 以 `tenantId + modelConfigId` 复合键读取数据库密文，并仅在当前模型调用期间解密。`CM_AGENT_MODEL_CREDENTIAL_ENCRYPTION_KEY` 必须是 Base64 编码的 256 位 AES 主密钥；缺失、格式错误或无法解密时，调用只得到受控的“模型凭据不可用”结果。若部署平台使用 secret manager，可提供自定义 `ModelCredentialProvider` Bean。

`model_configs` 表保存模型 Provider、`baseUrl`、`modelName`、启用状态与 API Key 密文。明文 API Key 不得进入 DTO、日志、审计或异常，也不支持接口回显。

### OpenCode Go 会话路由

OpenCode Go 的基础地址使用 `https://opencode.ai/zen/go/v1` 时，供应商要求聊天请求携带 `x-opencode-session` 才能完成路由。CM Agent 对主机名精确为 `opencode.ai` 的 `OPENAI_COMPATIBLE` 配置自动使用本次服务端已创建的 `runId` 作为该 Header 值；同一次运行的重试复用同一个值。此 Header 不是模型配置字段，不能由浏览器、提示词或 API 请求指定，且不会附加到其他 OpenAI Compatible 网关。更新部署包后重启服务即可生效，无需重新保存 API Key。

AgentScope 2.0.0 在绑定可信 `RuntimeContext` 前可能探测无用户标识的默认状态槽。CM Agent 将这种只读探测视为未命中；任何实际状态读取、写入或删除仍要求 `tenantId:principalId` 格式的可信运行身份，不能因该兼容处理跨租户访问检查点。

真实运行同时提供兼容的单轮 Run API 和持久化会话 API。会话接口位于 `/api/agents/{agentId}/conversations`；续聊时固定读取最近 40 条、最多 60,000 字符的完整消息历史，不做自动摘要。该限制当前不是外部配置项，避免不同实例产生不一致的上下文边界。会话流式接口发送最终回答文本增量、受控执行进度及审批事件，不发送工具原始参数或工具原始输出。

`permission-enabled` 默认 `false`，用于保持已有工具放行策略的兼容升级；只有明确设为 `true` 时，HIGH 工具才按每次具体调用进入 `WAITING_APPROVAL`。`approval-ttl` 默认 `15m`，必须大于 0 且不超过 24 小时。审批请求保存脱敏摘要及原工具 ID、调用 ID、输入哈希，AgentScope 状态通过 AES/GCM 加密写入 `runtime_checkpoints`，终态或过期处理后删除。第一版只允许原运行发起人且同时拥有 `agent:run`、`agent:approve` 的主体决定，不支持独立审批人、长期预授权规则或无人值守暂停。模式固定在线 DEFAULT、无会话 DONT_ASK，没有 `permission.*` 嵌套配置。

## 审批主动过期扫描

一次遍历的截止时间保持不变，空页后才重置并纳入新到期候选；持续积压不会阻止回头重试先前失败项。积压期间刚到期请求可能需要等待下一次遍历，不能将扫描间隔视为严格完成时限。

以下配置由服务端绑定，生产默认开启，不需要在应用 YAML 中重复写入默认值：

```yaml
cm-agent:
  approval-expiry:
    enabled: true
    interval: 30s
    batch-size: 50
```

`interval` 为每轮结束后的等待时间，范围 1 秒～1 小时；`batch-size` 范围 1～100，非法配置启动失败。扫描独立于 `agentscope.permission-enabled`：关闭新审批不会停止历史审批收口。`enabled=false` 只关闭扫描，人工提交过期审批仍收口，权威查询保持只读。每轮读取一批，达到 10 秒后不再开始新项；单项 JDBC 事务有 10 秒超时，依赖连接建立仍应由部署的数据源超时约束。

候选跨租户发现只用于服务端系统任务，按到期时间及审批 ID 升序分页；每项仍验证可信 tenant、Agent、scope、会话/Run、版本和到期时间。截止时间相等即过期，实例应保持系统时钟同步。V14 添加 `(status, expires_at, id)` 扫描索引；无字段变更。单项失败推进游标，遍历到底后下一轮从头重试，关闭开关期间积压不会自动删除。

JDBC 的审批 EXPIRED、等待 Run DENIED、该 Run 检查点删除、关联 TEST FAILED 及严格审计共享短事务。memory 只保证单行竞争，不具备跨仓储回滚或重启恢复能力。扫描不授予工具权限、不调用 Runtime，也不接管已批准但结果未知的运行。

## 会话审批历史查询

会话审批历史无新增配置项，复用当前持久化模式和 V11 审批表。`GET /api/agents/{agentId}/conversations/{conversationId}/approvals/history?limit=20` 使用 `agent:read` 和会话归属校验；`limit` 范围为 1～100，后续请求的 `cursor` 使用响应 `nextCursor` 原值。响应 `{items, nextCursor}` 中仅有终态的脱敏视图；`nextCursor=null` 表示本次查询没有更早页。非法参数为 400/VALIDATION_FAILED，持久化不可用为 503/PERSISTENCE_UNAVAILABLE，并返回可关联日志的 `errorId`。历史读取不依赖开关当前是否开启，不会触发运行或自动过期处理。

控制台默认最新 20 条；“加载更早审批”追加旧页，“刷新记录”重新读取最新页。JDBC 才能跨服务重启保留历史；memory 仅用于本地与测试，刷新浏览器不清除进程内数据，但重启进程会丢失。完整操作见 [README](../README.md)。

## AgentScope Studio 本地调试

Studio 仅用于开发期可视化调试与链路回放，不替代 CM Agent 的运行记录、工具审计、权限或租户隔离。启动本地 Studio 服务后，在 `local`、`test`、`postgres` 或 `mysql` 等非严格 profile 显式启用：

```yaml
cm-agent:
  agentscope:
    studio:
      enabled: true
      url: http://localhost:8000
      project: cm-agent-local
      run-name: cm-agent-local
```

AgentScope 2.0.2 的 Studio 初始化会注册进程级系统消息 Hook；因此一个服务 JVM 只能使用一套 Studio 地址、项目和 Run 名称。所有该 JVM 内的 AgentScope 消息汇集到同一个 Studio Run，不能把 `run-name` 设为每个并发 CM Agent 请求的 `runId`。如果同一 JVM 已由其他初始化器用不同参数连接 Studio，服务会启动失败而不是覆盖全局连接。

严格 profile（`production`、`prod`、`supabase`）会拒绝 `enabled=true`，即使配置被外部覆盖也不会连接 Studio。Studio 转发的消息可能包含业务输入或模型输出，开发环境同样应避免输入不应离开本机或测试边界的敏感内容。

## JDBC 与 Flyway

| 覆盖项 | 实际绑定项 | 说明 |
| --- | --- | --- |
| `cm-agent.config.persistence-mode` | `cm-agent.persistence.mode` | `memory` 或 `jdbc` |
| `cm-agent.config.jdbc-url` | `cm-agent.persistence.jdbc.url` | 启用 JDBC 时必须配置 |
| `cm-agent.config.jdbc-username` | `cm-agent.persistence.jdbc.username` | 使用最小权限账号 |
| `cm-agent.config.jdbc-password` | `cm-agent.persistence.jdbc.password` | 仅从受控外部 YAML 或 secret manager 注入 |
| `cm-agent.config.jdbc-driver-class-name` | `cm-agent.persistence.jdbc.driver-class-name` | PostgreSQL 或 MySQL 驱动 |

JDBC 模式创建 DataSource，并在启动时由 Flyway 执行迁移。`CmAgentFlyway` 只扫描 `classpath:db/migration/*.sql` 中的公共迁移，并依据 JDBC 元数据加载 `db/migration/postgresql` 或 `db/migration/mysql` 中的当前数据库方言迁移，避免同时解析两种不兼容的注释 DDL。已发布的 `V1__init_schema.sql` 不修改；V8 为全部 18 张业务表和 135 个字段写入中文数据库原生注释。生产环境需先核对 Flyway 历史，再按发布流程应用新迁移。

V20 修复较大问答状态写入 MySQL 时的 `22001/1406` 错误：将 `runtime_checkpoints.encrypted_payload` 从 `TEXT` 扩为 `LONGTEXT`；PostgreSQL 保持 `TEXT` 并同步中文字段注释。状态会经过 JSON 序列化、AES/GCM 加密及 Base64 编码，旧 MySQL 字段的 65,535 字节容量可能在文件生成后保存运行状态时耗尽，不能按 TXT 文件本身的大小判断。迁移保留既有密文、状态槽和失效时间，不需要更换加密主密钥；修改技能文件大小配置不能替代 V20。更新服务端与 persistence 依赖后，按发布流程执行启动迁移并核对 Flyway V20 成功记录，再重试原问答。应用数据库 DDL 前应评估表规模和维护窗口；扩容仍受数据库通信包及应用既有限额约束，不表示允许无限状态。

## 安全配置

| 配置项 | 说明 |
| --- | --- |
| `cm-agent.config.jwt-secret` | 生产和类生产必须使用由外部身份系统/secret manager 或受控外部 YAML 管理的 JWT 验证密钥 |
| `cm-agent.config.bootstrap-admin-enabled` | `local`/`test` 可按需启用；`prod`、`production`、`supabase` 必须为 `false` |
| `cm-agent.config.bootstrap-admin-password` | 不写入生产配置仓库、镜像、文档或日志；测试凭据由代码/CI 注入 |
| `cm-agent.config.public-api-docs-enabled` | 生产 profile 默认关闭公开 API 文档 |

生产 profile 不提供开发 JWT fallback。缺少 JWT secret 或违反 profile 安全约束时，服务应拒绝启动，而不是生成或回退到开发密钥。

### 生产认证边界

生产和类生产服务使用外部身份系统或受控认证服务签发的 Bearer JWT。服务只从受控外部配置取得验证密钥，用于校验外部签发的令牌；不得在 Git、镜像、命令行、日志或审计消息中保存密钥或令牌。

`/api/auth/login` 是 bootstrap admin 登录入口，仅供显式启用 bootstrap admin 的 `local` profile 使用。`production`、`prod` 和 `supabase` 必须关闭 bootstrap admin，因此生产调用该入口应被拒绝；生产调用方应携带外部签发的 Bearer JWT。`test` 的 bootstrap 凭据也必须由测试代码、CI 或受控外部配置注入。

## 审计与错误语义

阶段2审计写入是严格路径。登录、权限拒绝、Agent 变更、工具治理和运行生命周期等关键动作写入失败时，不吞掉异常，不把请求伪装成成功；API 返回 HTTP `503 Service Unavailable`，日志只记录脱敏后的上下文。生产告警应区分审计失败、数据库连接失败和普通业务失败。

Run、ToolCall、Conversation 与 Message 使用当前认证主体的 tenant 条件读写；Conversation 还固定校验所属 Agent，消息使用会话内 sequence 正序分页。Run、Conversation 和 Audit 列表采用有界 cursor 分页，避免跨租户或无界扫描。消息数据库保存的是脱敏文本与受治理工具摘要，但仍属于敏感业务数据；生产数据库账号、备份、导出和保留策略必须按敏感数据管理。返回内容和日志中的输入、输出、错误消息经过敏感信息脱敏。

## 运行边界

- `memory` 仅限开发和测试，进程重启会丢失运行、工具调用和审计状态。
- JDBC 模式已接通 Run、ToolCall、Conversation、Message、Audit 的持久化与查询；`local`/`test` 的运行执行器可以由 fake runtime 提供结果。
- 真实 Runtime 的每次工具调用都通过治理网关重新读取定义并授权；工具定义中的 endpoint 只是元数据，不会被 Adapter 自动联网执行。
- 模型 timeout 或 Provider 故障会把运行收口为失败；授权拒绝优先映射为拒绝；审计失败保持严格语义并传播。AgentScope 2.0.2 的工具层只暴露通用取消信号，系统仅根据其明确生成的超时结果判定工具 timeout，不能把该信号视为通用手动取消能力。
- 工具可能产生外部副作用。超时、中断或 Provider 重试不能证明外部系统已经回滚，工具实现与下游接口必须使用 `runId`、`toolCallId` 或业务幂等键实现去重。
- metrics、集中式日志/追踪、备份治理和 CI/CD 不应在当前配置文档中被视为已交付能力，对应工作列入[中文路线图](roadmap.md)的阶段4-5。

## 技能沙箱文件产物

配置前缀为 `cm-agent.skills.sandbox.artifacts`，绑定 `SkillSandboxProperties#getArtifacts()` 与 `SkillArtifactProperties`。文件开关默认关闭，启用沙箱不等于启用文件产物。显式加载 `skill-sandbox` profile 时可使用 `CM_AGENT_SKILL_ARTIFACTS_ENABLED`、`CM_AGENT_SKILL_ARTIFACTS_ROOT_DIRECTORY`、`CM_AGENT_SKILL_ARTIFACTS_ENCRYPTION_KEY` 注入对应配置；其他 profile 使用标准 Spring 属性绑定。密钥必须是独立的 32 字节 Base64 Secret，不能复用 JWT、模型或沙箱端点密钥；目录必须是仓库与静态资源之外、服务账户独占的持久卷。

| 属性 | 默认值 | 约束/语义 |
|---|---|---|
| enabled | false | 文件收集与下载开关 |
| storage | filesystem | 首版实现；存储 SPI 可由部署代码替换，不提供 S3 |
| root-directory | 空 | 显式私有持久卷，不挂载进技能容器 |
| encryption-key | 空 | 独立 AES-256 密钥，不提供可用默认值 |
| allowed-types | .docx,.pptx,.pdf,.txt,.csv,.json | 只能收紧；与脚本资源白名单独立 |
| max-files-per-call | 8 | 1～8 |
| max-file-bytes | 4194304 | 1～4 MiB，明文字节 |
| max-total-bytes-per-call | 8388608 | 不小于单文件，不超过 8 MiB |
| max-total-bytes-per-run | 33554432 | 不小于调用预算，不超过 32 MiB；补偿删除不返还 Run 累计预算 |
| max-stored-bytes-per-tenant | 268435456 | 不小于 Run 预算，不超过 1 GiB；实际删除才归还租户容量 |
| max-files-per-run | 64 | 不小于调用数量，不超过 64；含删除历史 |
| max-files-per-tenant | 4096 | 不小于 Run 数量，不超过 4096；未实际删除都占用 |
| retention | 7d | 1 分钟～30 天；成功发布时重新计算 |
| cleanup-interval | 5m | 10 秒～1 小时；每批最多 100 个候选 |
| collection-timeout | 5s | 大于 0 且不超过 5 秒，原脚本超时不会延长 |

启用示例（Secret 由部署系统注入）：

```yaml
cm-agent:
  skills:
    sandbox:
      artifacts:
        enabled: true
        root-directory: ${CM_AGENT_SKILL_ARTIFACTS_ROOT_DIRECTORY}
        encryption-key: ${CM_AGENT_SKILL_ARTIFACTS_ENCRYPTION_KEY}
```

生产还需启用技能和沙箱、配置 JDBC/Flyway。调用固定包内 Python 将附件写入 `/workspace/output`（环境变量 `CM_AGENT_ARTIFACT_DIR`）；容器退出前回传，stdout 仍受原 32 KiB 限额。二进制只保存在独立 AES/GCM 文件，不进入数据库 TEXT、模型上下文或检查点。DOCX/PPTX 有界检查 ZIP 结构并拒绝宏；PDF 检查格式标记，不保证任意文件安全或复杂文档兼容。首版只有固定 Python，原 Anthropic docx.zip 的 Node.js 动态脚本不在兼容范围。

正式/会话 Run 列表为 `GET /api/agents/{agentId}/runs/{runId}/artifacts`；TEST 为 `GET /api/skills/{skillId}/trials/{runId}/artifacts`；统一下载为 `GET /api/skill-artifacts/{artifactId}/content`。需当前认证、Run owner 和 `agent:read`；TEST 额外需要 `skill:read` 与原 TEST owner，会话复核原会话 owner。等待审批或失败不下载。响应仅附件下载，禁缓存，无 URL 令牌、预览、Range 和共享。
TEST恢复入口为 GET /api/skills/{skillId}/trials/latest?versionId={versionId}，仅返回当前主体当前版本最近记录，无记录204；需要 skill:read 和 agent:read，不自动执行或替用户发布。控制台最多并行下载两个文件，服务端每实例最多四项完整解密下载，单文件仍受4MiB硬限额。下载租约5分钟，补偿扫描每批100项；同一文件被占用时返回可重试503。