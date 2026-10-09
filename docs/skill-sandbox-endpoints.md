# 技能沙箱端点部署与管理

默认执行仍关闭。R1 保留固定版本 Python、禁网、非 root、只读根文件系统、CPU/内存/PID/输入输出限制、Run 授权、预算、防重和 TEST 门禁；远程连接不会扩大技能可执行范围。

## 配置与优先级

完整属性名、默认值、环境变量、限额和账号密码 YAML 示例见[技能脚本沙箱与远程 Docker 配置](configuration.md#技能脚本沙箱与远程-docker)；本文侧重端点管理、连接操作和安全要求。

优先使用当前可信租户的默认端点；没有租户默认时使用部署连接。已选择端点连接失败直接报错，不回退其他端点或本地。每次调用固定端点、配置版本、凭据版本和连接，后续默认切换、凭据轮换不会改变当前调用的执行或清理主机。停用或删除原端点会使交付阶段失败。

部署配置位于 `cm-agent.skills.sandbox`，可显式组合环境 profile 和 `skill-sandbox` profile。以下仅为占位示例，不包含可用凭据：

```yaml
cm-agent:
  skills:
    sandbox:
      enabled: true
      backend: docker
      allowed-targets:
        - sandbox.example.invalid:22
        - sandbox-tls.example.invalid:2376
      encryption-key: ${CM_AGENT_SKILL_SANDBOX_ENCRYPTION_KEY:}
      connection:
        mode: SSH
        host: sandbox.example.invalid
        port: 22
        username: sandbox
        private-key-file: /run/secrets/sandbox-ssh-key
        known-hosts-file: /run/secrets/sandbox-known-hosts
```

`encryption-key` 是独立的32字节随机值的Base64编码，由部署的Secret注入；不要复用模型或JWT密钥。缺少或无效时允许服务按既有配置启动，但拒绝端点管理写入和远程凭据解密，读接口只报告未就绪。生产使用JDBC持久化；memory仅用于local/test。更换主密钥必须先用旧密钥解密并重新加密所有端点，当前没有跨主密钥自动重加密接口，直接替换会使已有材料不可用。

`LOCAL` 使用部署机Docker socket，host和username为空、port为0。SSH与TLS目标必须精确命中部署的 `allowed-targets`（host:port），解析结果不能为未指定、链路本地或组播地址；私网主机也必须显式登记。连接使用已核验的固定IP，TLS仍校验原host身份。此策略独立于业务HTTP工具的公网限制。

## SSH与双向TLS

Server需要Docker CLI；SSH私钥分支还需要OpenSSH客户端。SSH默认`ssh-auth-type=KEY`，使用独立私钥与严格known_hosts；可选择`PASSWORD`，通过JSch 2.28.7内存会话完成单次密码认证和固定Unix socket转发，无ASKPASS助手。两种方式均不调用用户SSH agent、不读取用户SSH配置、不执行远端任意命令。远端Linux账户必须具有访问 `/var/run/docker.sock` 和转发Unix socket的权限；密码分支还要求sshd允许password认证，不支持keyboard-interactive/MFA。known_hosts应由部署者通过可信渠道核实；两分支固定HostKeyAlias为原host（不附端口），条目须匹配该别名，不能改用解析IP或未经调整的[host]:port条目。哈希条目同样须针对该别名生成。信任只来自显式材料，禁止关闭主机身份检查。私钥不能需要交互口令。临时认证目录由当前用户独占，调用关闭时删除。

部署账号密码示例在上述SSH配置内将`private-key-file`移除，改为：

```yaml
ssh-auth-type: PASSWORD
password-file: /run/secrets/sandbox-ssh-password
known-hosts-file: /run/secrets/sandbox-known-hosts
```

`password-file`只接受部署者提供的Secret路径，控制台不能指定文件。文件内容原样作为密码，非空、UTF-8最多4096字节，不得含NUL/CR/LF或额外末尾换行；前后空格与中文不会trim。控制台密码通过`credentials.password`提交并AES/GCM加密，响应仅报告配置状态。材料总上限仍为128KiB。KEY/TLS禁止夹带密码，PASSWORD禁止同时提交私钥/TLS证书。不把密码送入Docker/SSH进程参数、子进程环境、URL、日志、审计或Web Storage，不生成密码明文文件；已有部署Secret由部署者管理。JVM中解密String仍具有GC生命周期，不承诺完整堆擦除。

TLS配置 `connection.mode=TLS`、host、port，以及 `private-key-file`、`ca-certificate-file`、`client-certificate-file`；username为空。TLS私钥必须是未加密PKCS#8 PEM（RSA或EC），客户端证书链与私钥必须匹配，服务端证书SAN必须包含配置host且未过期。信任仅来自明确CA，禁止跳过身份验证。TLS在内存建立仅回环监听的短期中继；材料不写入Docker全局配置。

所有连接（含R0本地兼容模式）均使用空私有Docker --config及固定--host，排除全局context和代理配置。显式LOCAL/SSH/TLS不继承DOCKER_HOST或SSH配置。mode留空只保留R0的本地unix/npipe环境兼容；旧远程DOCKER_HOST会明确拒绝并提示迁移为SSH/TLS，不能静默改变执行主机。远程镜像必须在实际daemon预拉取；执行不拉取镜像，镜像/runtime/限额始终来自部署配置。

## 租户控制台和API

v2技能管理页内切换“沙箱端点”：新建或编辑 → 保存 → 测试连接 → 设为默认。修改配置或轮换材料后成功探测版本失效，必须重新测试；默认端点不能直接删除，先切换或取消默认。失败保留普通表单，认证输入提交即清空；响应不提供明文或密文，只提供hasCredential、credentialVersion和探测版本等状态。测试连接检查Linux、资源配额支持、可信镜像及可选runtime，不等同于技能TEST或发布验收。

管理角色需要分别授予以下权限；旧令牌需要重新登录取得新增权限。端点管理权限不会授予Agent运行或业务工具权限。

| 权限 | 用途 |
|---|---|
| sandbox:read | 本租户列表和详情 |
| sandbox:write | 创建、更新和默认选择 |
| sandbox:credential:write | 提交/轮换认证材料，含转换为LOCAL时清除旧材料 |
| sandbox:test | 已保存端点的当前版本连接测试 |
| sandbox:delete | 删除非默认端点 |

接口前缀为 `/api/skill-sandbox-endpoints`。GET列出本租户投影；POST创建；PUT/{id}使用revision更新；DELETE/{id}?revision=执行逻辑删除；POST/{id}/probe提交revision；PUT/default提交endpointId和revision（endpointId为null取消默认）。tenant只能来自认证上下文，请求不能指定执行后端类、主密钥、文件路径、镜像或限额。SSH/TLS材料仅在credentials写入字段提交，省略表示保留，协议变化须重新提供材料。所有管理变更与严格审计在同一短事务完成；远程探测在事务外运行，回写使用原revision，冲突返回409。

常见失败包括目标拒绝403、端点不存在404、配置冲突409、配额429、身份失败502、不可用/清理/凭据失败503、超时504。页面显示中文原因、稳定code和errorId，维护者用同编号检索结构化脱敏日志；不回传客户端原始错误、堆栈或内部执行地址。

## 持久化、扩展与验证

V17为两库端点新增`ssh_auth_type`并更新材料列的中文原生注释，旧V16记录默认KEY。API请求新增`sshAuthType`（SSH允许KEY/PASSWORD，其他协议仅KEY），响应报告类型但不返回password。省略整个credentials仅在协议及认证类型相同的更新中保留；类型切换必须提交完整新材料，且需要`sandbox:credential:write`。只提交空密码不能轮换。旧四字段v1 JSON仍可解密；凭据格式及AAD不变。混合版本实例不能管理PASSWORD端点，应统一升级后启用。

V16增加skill_sandbox_endpoints和skill_sandbox_defaults，PostgreSQL/MySQL方言包含逐字段中文原生注释。默认表以tenant为主键，组合外键防止跨租户引用。CAS更新与tenant行锁保证多实例默认唯一；删除为软删除，现有调用持有原认证快照完成清理。凭据AES/GCM附加认证数据绑定tenant、endpointId、credentialVersion，禁止密文跨资源复制。MySQL使用MEDIUMTEXT承载加密后的128KiB材料上限。

通用扩展实现core的SkillSandboxBackend并通过Server Bean注册唯一backendId；配置只能选择注册标识，不能反射加载类。本轮只提供Docker后端，未实现HTTP/E2B/Kubernetes/任意SSH命令后端。句柄必须线程取消可控、close幂等且终止执行资源，清理无法确认时抛失败；verifyAccess允许在资源关闭后调用。ManagedSkillSandbox统一执行预算、并发、资源与输入输出限额及输出脱敏；扩展不能绕过外层Run/权限/审计治理。

Docker/JDBC/Flyway验证仅在ssh rocky的maven:3.9.9-eclipse-temurin-21容器执行，须核对HEAD与覆盖文件SHA256。CM_AGENT_TEST_SANDBOX=true启用R0真实隔离回归；CM_AGENT_TEST_REMOTE_SANDBOX=true启用项目一次性SSH/TLS网关夹具。可信Maven验证容器访问宿主socket以管理Testcontainers；技能容器没有宿主挂载，远程daemon夹具也不挂载宿主socket。夹具临时生成测试认证材料并自动清理，不使用生产凭据。SSH/TLS 使用项目专用 daemon；仅可信测试 daemon 夹具以 privileged 启动嵌套容器，不挂载宿主 Docker socket，不修改宿主时钟或生产身份策略。时钟/网络受限时，可用CM_AGENT_TEST_SSH_PACKAGE_DIR指定通过可信HTTPS下载的官方APK目录；夹具离线安装仍验证Alpine签名。

实际命令、结果、浏览器证据与未执行项见[本轮账本](superpowers/progress/2026-09-30-skill-sandbox-ledger.md)。断连后不能确认删除时该实例保留配额并返回失败，运维只检查本项目cm-agent-skill-*对象；没有跨实例自动接管或脚本重放。

## 端点执行后的文件交付

文件策略是独立部署配置 `cm-agent.skills.sandbox.artifacts`，不在租户端点表保存路径或文件密钥。LOCAL、SSH KEY、SSH PASSWORD、TLS 均在当前固定连接中完成执行、退出前回传和清理，禁止切换端点或回退本机。服务端持久卷不挂载到执行主机容器。

固定 Python 使用 `os.environ["CM_AGENT_ARTIFACT_DIR"]` 获取 `/workspace/output`，只写允许类型的普通文件。链接、特殊文件、逃逸路径、畸形帧和超限拒绝本次调用；有效文件先暂存，Run 及严格审计成功才公开。控制台 TEST、运行详情和聊天回复中的“生成文件”使用当前会话鉴权下载；显示文件名、类型、大小与有效期。端点后续停用不会变更历史文件归属，保留期和下载权限仍重新核验。配置与备份说明见 [配置文档](configuration.md#技能沙箱文件产物) 和 [运维文档](operations.md#文件产物持久卷与补偿)。