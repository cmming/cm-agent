-- 设备身份、短期挑战与单轮授权均绑定可信租户；外部命令和文件正文不保存在这些表中。
CREATE TABLE local_client_devices (
    tenant_id VARCHAR(36) NOT NULL COMMENT '认证上下文中的可信租户',
    id VARCHAR(36) NOT NULL COMMENT '服务端生成的配对设备标识',
    owner_id VARCHAR(200) NOT NULL COMMENT '完成配对的原主体，查询须逐字匹配',
    display_name VARCHAR(120) NOT NULL COMMENT '供用户核对的设备名称，不作为授权凭据',
    platform VARCHAR(16) NOT NULL COMMENT '客户端声明并展示的操作系统类别',
    public_key VARCHAR(512) NOT NULL COMMENT '设备 Ed25519 公钥的 Base64 编码，不保存私钥',
    state VARCHAR(16) NOT NULL COMMENT '活动或撤销状态，撤销后拒绝新挑战',
    version BIGINT NOT NULL COMMENT '设备条件更新版本，从一开始',
    created_at TIMESTAMP(6) NOT NULL COMMENT '服务端记录的配对完成时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '设备状态最近更新时间',
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CHECK (platform IN ('WINDOWS','MACOS','LINUX')),
    CHECK (state IN ('ACTIVE','REVOKED')),
    CHECK (version>=1)
) COMMENT='原主体确认配对的本地客户端设备，仅保存公钥';

CREATE TABLE local_client_challenges (
    tenant_id VARCHAR(36) NOT NULL COMMENT '设备所属可信租户',
    id VARCHAR(36) NOT NULL COMMENT '服务端生成的单次挑战标识',
    owner_id VARCHAR(200) NOT NULL COMMENT '与配对设备一致的原主体',
    device_id VARCHAR(36) NOT NULL COMMENT '必须证明持钥的已配对设备',
    nonce VARCHAR(44) NOT NULL COMMENT '服务端生成的三十二字节随机值的 Base64 编码',
    created_at TIMESTAMP(6) NOT NULL COMMENT '服务端生成挑战的时间',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '挑战不可再使用的排他时间上限',
    consumed_at TIMESTAMP(6) NULL COMMENT '单次消费时间，空值表示尚未消费',
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES local_client_devices(tenant_id,id),
    CHECK (CHAR_LENGTH(nonce)=44)
) COMMENT='设备持钥证明的短期一次性挑战，不保存签名或私钥';

CREATE TABLE local_client_run_grants (
    tenant_id VARCHAR(36) NOT NULL COMMENT '运行所属可信租户',
    id VARCHAR(36) NOT NULL COMMENT '服务端生成的单轮授权标识',
    owner_id VARCHAR(200) NOT NULL COMMENT '明确确认授权的原主体',
    device_id VARCHAR(36) NOT NULL COMMENT '本轮唯一授权的配对设备',
    agent_id VARCHAR(36) NOT NULL COMMENT '运行固定的智能体软引用，由服务核验',
    conversation_id VARCHAR(36) NOT NULL COMMENT '运行固定的会话软引用，由服务核验',
    run_id VARCHAR(36) NOT NULL COMMENT '授权唯一适用的运行软引用，由服务核验',
    mode VARCHAR(24) NOT NULL COMMENT '本轮操作前询问、风险询问或完全访问方式',
    categories VARCHAR(160) NOT NULL COMMENT '服务端确定的本轮操作类别集合',
    policy_version BIGINT NOT NULL COMMENT '本轮固定的服务端策略版本',
    granted_at TIMESTAMP(6) NOT NULL COMMENT '服务端记录的授权时间',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '授权不可再使用的排他时间上限',
    revoked_at TIMESTAMP(6) NULL COMMENT '用户或系统撤销时间，空值表示尚未撤销',
    version BIGINT NOT NULL COMMENT '授权状态条件更新版本，从一开始',
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,run_id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES local_client_devices(tenant_id,id),
    CHECK (mode IN ('ASK_ON_EFFECT','ASK_ON_RISK','FULL_ACCESS')),
    CHECK (policy_version>=1 AND version>=1)
) COMMENT='原主体对指定设备及运行确认的单轮操作授权';

CREATE INDEX idx_local_client_devices_owner ON local_client_devices(tenant_id,owner_id,state,id);
CREATE INDEX idx_local_client_challenges_device ON local_client_challenges(tenant_id,device_id,expires_at);
CREATE INDEX idx_local_client_grants_device ON local_client_run_grants(tenant_id,device_id,expires_at);
