# Rocky Harness 生产装配

这是一组只含非敏感配置的部署文件，接续[排空工作包](../../docs/superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md)。准备完成不等于生产启动成功，不能生成新密钥替代现有加密数据的主密钥。

部署目录为 `/home/cm-agent-production`，使用 `/home` 独立文件系统，避开仅剩约3GiB的根分区；app/config/scripts由root管理，容器UID/GID10001只读包和配置。artifacts及secrets由10001持有，目录700、Secret文件400或600。Docker组编号在运行时从socket读取。该应用是可信Docker编排服务，具备宿主控制权限；固定技能子容器不取得socket或文件密钥。不要把部署账号权限暴露给模型。

`application-production.properties.example`复制为远端 `config/application-production.properties`，仅激活production，显式MySQL驱动，禁用bootstrap admin、dev JWT fallback、fake、Studio和明文HTTP工具。首次默认react，监听127.0.0.1:18086；没有生产入口/TLS/外部JWT签发配置时不开放流量。

Secret目录需提供七个同名文件，不带引号、示例占位符或追加换行；数据库应用账号不得为root，密码至少20字节：

- `cm-agent.config.external-jwt-secret`：与受控外部身份签发系统一致的验证密钥，不能沿用开发JWT fallback。
- `cm-agent.config.external-jdbc-url`、`cm-agent.config.external-jdbc-username`、`cm-agent.config.external-jdbc-password`：目标MySQL连接与最小权限应用账号；当前root管理连接只用于部署排空检查，不配置为应用默认账号。
- `cm-agent.model-credentials.encryption-key`：沿用现有5条模型凭据的AES-256主密钥。
- `cm-agent.skills.sandbox.encryption-key`：沿用现有1条端点凭据的独立AES-256主密钥。
- `cm-agent.skills.sandbox.artifacts.encryption-key`：沿用5个产物密文的独立AES-256主密钥。

本轮只找到了现有密文文件，未供给或验证上述生产Secret。它们必须由部署管理员从现有受控来源注入，不能发到聊天、提交Git或放在CLI参数里。

运行前真实验证5条模型凭据解密、1条端点凭据解密、5个文件认证/摘要、成套备份恢复及全实例停机。再由执行者在远端config写入不含秘密的 `recovery-verification.json`，记录candidateSha256、modelsDecrypted=5、endpointsDecrypted=1、artifactsAuthenticated=5、backupRestoreVerified=true、allInstancesStopped=true及实际证据。该记录是可信管理员对实际证据的登记，不是自填true即可完成验收；preflight只核对声明，不能自行证明密钥对应旧数据。

`preflight.sh`缺材料时拒绝，不启动容器或修改模式。材料齐备后先 `run.sh react`，核对production安全约束、401/403、真实凭据与沙箱/产物，再在冻结入口和正常退出全部实例后 `run.sh harness`。启动仍以V21门禁核对，不直接UPDATE模式行。模式初始化可能先于其他Bean完成；启动失败时保持入口关闭并查询权威模式，不承诺自动回退。

初次容器不自动重启。正常停机用 `docker stop --time 120 cm-agent-production`，核对正常退出；超时可能触发Docker强杀，不把退出码137计为正常退出，停止后需重新核查副作用、状态和数据集。确认已正常退出后才能移除该专属容器并切换/回退；不删除卷、检查点或历史迁移。

可执行包固定为T6最终摘要 `b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12`；当前属于未提交源码覆盖包，不能称为已正式发布版本。验证容器使用既有Maven3.9.9/JDK21镜像，Python镜像固定3.12-alpine且不自动拉取；应用资源预算1CPU/1GiB是初次受控验证配置，不代表已做生产容量验收。
