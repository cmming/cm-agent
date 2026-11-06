-- 配对会话和操作队列只保存最小身份、状态及认证加密载荷；明文请求和结果不得进入数据库。
CREATE TABLE local_client_pairing_sessions (
    tenant_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    device_name VARCHAR(120) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    public_key VARCHAR(512) NOT NULL,
    nonce VARCHAR(44) NOT NULL,
    proof VARCHAR(88) NOT NULL,
    state VARCHAR(16) NOT NULL,
    device_id VARCHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    confirmed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CHECK (platform IN ('WINDOWS','MACOS','LINUX')),
    CHECK (state IN ('PENDING','CONFIRMED','EXPIRED')),
    CHECK ((state='CONFIRMED' AND device_id IS NOT NULL AND confirmed_at IS NOT NULL)
        OR (state<>'CONFIRMED' AND device_id IS NULL AND confirmed_at IS NULL))
);
COMMENT ON TABLE local_client_pairing_sessions IS '已认证主体确认的短期本机客户端配对会话';
COMMENT ON COLUMN local_client_pairing_sessions.tenant_id IS '浏览器认证主体所属可信租户';
COMMENT ON COLUMN local_client_pairing_sessions.id IS '客户端生成并签名的配对请求标识';
COMMENT ON COLUMN local_client_pairing_sessions.owner_id IS '确认配对的认证主体标识，查询须逐字匹配';
COMMENT ON COLUMN local_client_pairing_sessions.device_name IS '供用户核对的设备展示名称';
COMMENT ON COLUMN local_client_pairing_sessions.platform IS '客户端声明的操作系统类别';
COMMENT ON COLUMN local_client_pairing_sessions.public_key IS '设备 Ed25519 公钥的 Base64 编码';
COMMENT ON COLUMN local_client_pairing_sessions.nonce IS '客户端生成的三十二字节配对随机值';
COMMENT ON COLUMN local_client_pairing_sessions.proof IS '客户端对配对包的 Ed25519 签名';
COMMENT ON COLUMN local_client_pairing_sessions.state IS '待确认、已确认或已过期状态';
COMMENT ON COLUMN local_client_pairing_sessions.device_id IS '确认后生成的活动设备标识';
COMMENT ON COLUMN local_client_pairing_sessions.created_at IS '服务端接收配对包的时间';
COMMENT ON COLUMN local_client_pairing_sessions.expires_at IS '浏览器确认配对不可超过的排他期限';
COMMENT ON COLUMN local_client_pairing_sessions.confirmed_at IS '浏览器确认并创建活动设备的时间';
CREATE UNIQUE INDEX uq_local_client_pairing_id ON local_client_pairing_sessions(id);
CREATE INDEX idx_local_client_pairing_expiry ON local_client_pairing_sessions(state,expires_at);

CREATE TABLE local_client_operations (
    tenant_id VARCHAR(36) NOT NULL,
    id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    device_id VARCHAR(36) NOT NULL,
    agent_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    tool_id VARCHAR(36) NOT NULL,
    tool_call_id VARCHAR(200) NOT NULL,
    category VARCHAR(16) NOT NULL,
    request_ciphertext TEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    result_ciphertext TEXT NOT NULL,
    safe_error VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    dispatched_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,run_id,tool_call_id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES local_client_devices(tenant_id,id),
    CHECK (category IN ('READ_ONLY','FILE_CHANGE','NETWORK','COMMAND','UNKNOWN')),
    CHECK (state IN ('PENDING','DISPATCHED','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
    CHECK (version>=1)
);
COMMENT ON TABLE local_client_operations IS '本地客户端单向任务队列及认证加密结果';
COMMENT ON COLUMN local_client_operations.tenant_id IS '运行所属可信租户';
COMMENT ON COLUMN local_client_operations.id IS '服务端生成的本机操作标识';
COMMENT ON COLUMN local_client_operations.owner_id IS '发起当前运行的原认证主体';
COMMENT ON COLUMN local_client_operations.device_id IS '本轮唯一授权设备';
COMMENT ON COLUMN local_client_operations.agent_id IS '发起调用的智能体软引用';
COMMENT ON COLUMN local_client_operations.conversation_id IS '运行所属会话软引用';
COMMENT ON COLUMN local_client_operations.run_id IS '操作唯一适用的运行软引用';
COMMENT ON COLUMN local_client_operations.tool_id IS '固定本机客户端工具标识';
COMMENT ON COLUMN local_client_operations.tool_call_id IS 'Runtime 工具调用标识，用于幂等收口';
COMMENT ON COLUMN local_client_operations.category IS '服务端确认的文件、网络或命令类别';
COMMENT ON COLUMN local_client_operations.request_ciphertext IS '认证加密后的本机操作请求 JSON';
COMMENT ON COLUMN local_client_operations.state IS '待派发、已派发、成功、失败、未知或取消状态';
COMMENT ON COLUMN local_client_operations.result_ciphertext IS '认证加密后的本机操作结果 JSON';
COMMENT ON COLUMN local_client_operations.safe_error IS '可展示且已脱敏的失败原因';
COMMENT ON COLUMN local_client_operations.created_at IS '服务端登记操作的时间';
COMMENT ON COLUMN local_client_operations.expires_at IS '操作不可再领取且结果等待结束的排他期限';
COMMENT ON COLUMN local_client_operations.dispatched_at IS '客户端领取操作的唯一线性化时间';
COMMENT ON COLUMN local_client_operations.completed_at IS '成功、失败、未知或取消终态登记时间';
COMMENT ON COLUMN local_client_operations.version IS '操作条件更新版本，从一开始';
CREATE UNIQUE INDEX uq_local_client_operation_id ON local_client_operations(id);
CREATE INDEX idx_local_client_operation_pending
    ON local_client_operations(tenant_id,device_id,state,created_at);

