-- 新端点与默认选择使用组合租户外键，防止跨租户引用；逻辑删除保留已开始调用的清理材料。
CREATE TABLE skill_sandbox_endpoints (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    backend VARCHAR(64) NOT NULL,
    connection_mode VARCHAR(16) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    username VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL,
    encrypted_credential TEXT NOT NULL,
    credential_version BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    probe_revision BIGINT NOT NULL,
    probed_at TIMESTAMP NULL,
    probe_status VARCHAR(40) NOT NULL,
    deleted BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_sandbox_endpoint_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);
COMMENT ON TABLE skill_sandbox_endpoints IS '租户沙箱端点与加密认证材料';
COMMENT ON COLUMN skill_sandbox_endpoints.id IS '端点唯一标识';
COMMENT ON COLUMN skill_sandbox_endpoints.tenant_id IS '端点所属可信租户';
COMMENT ON COLUMN skill_sandbox_endpoints.display_name IS '控制台显示名称';
COMMENT ON COLUMN skill_sandbox_endpoints.backend IS '服务端注册的执行后端';
COMMENT ON COLUMN skill_sandbox_endpoints.connection_mode IS '本地SSH或双向TLS连接方式';
COMMENT ON COLUMN skill_sandbox_endpoints.host IS '经部署策略校验的主机';
COMMENT ON COLUMN skill_sandbox_endpoints.port IS '远程端口本地为零';
COMMENT ON COLUMN skill_sandbox_endpoints.username IS 'SSH登录用户其他协议为空';
COMMENT ON COLUMN skill_sandbox_endpoints.enabled IS '是否允许新调用';
COMMENT ON COLUMN skill_sandbox_endpoints.encrypted_credential IS '私钥证书及信任材料的认证密文';
COMMENT ON COLUMN skill_sandbox_endpoints.credential_version IS '加密材料版本';
COMMENT ON COLUMN skill_sandbox_endpoints.revision IS '配置乐观并发版本';
COMMENT ON COLUMN skill_sandbox_endpoints.probe_revision IS '成功探测对应配置版本';
COMMENT ON COLUMN skill_sandbox_endpoints.probed_at IS '最后探测时间';
COMMENT ON COLUMN skill_sandbox_endpoints.probe_status IS '安全探测结果不含原始响应';
COMMENT ON COLUMN skill_sandbox_endpoints.deleted IS '逻辑删除保留执行清理材料';
COMMENT ON COLUMN skill_sandbox_endpoints.created_at IS '创建时间';
COMMENT ON COLUMN skill_sandbox_endpoints.updated_at IS '更新时间';
CREATE TABLE skill_sandbox_defaults (
    tenant_id CHAR(36) NOT NULL,
    endpoint_id CHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id),
    CONSTRAINT fk_sandbox_default_endpoint FOREIGN KEY (endpoint_id, tenant_id) REFERENCES skill_sandbox_endpoints(id, tenant_id)
);
COMMENT ON TABLE skill_sandbox_defaults IS '每租户唯一的默认沙箱端点';
COMMENT ON COLUMN skill_sandbox_defaults.tenant_id IS '默认选择所属租户';
COMMENT ON COLUMN skill_sandbox_defaults.endpoint_id IS '该租户默认沙箱端点';
COMMENT ON COLUMN skill_sandbox_defaults.created_at IS '首次设置默认端点的时间';
COMMENT ON COLUMN skill_sandbox_defaults.updated_at IS '最近切换默认端点的时间';
CREATE INDEX idx_sandbox_endpoint_tenant ON skill_sandbox_endpoints(tenant_id, deleted);
INSERT INTO permissions(code, description) VALUES ('sandbox:read', '查看本租户沙箱端点');
INSERT INTO permissions(code, description) VALUES ('sandbox:write', '维护本租户沙箱端点及默认选择');
INSERT INTO permissions(code, description) VALUES ('sandbox:delete', '删除本租户沙箱端点');
INSERT INTO permissions(code, description) VALUES ('sandbox:test', '探测本租户沙箱连接');
INSERT INTO permissions(code, description) VALUES ('sandbox:credential:write', '配置或轮换本租户沙箱凭据');
