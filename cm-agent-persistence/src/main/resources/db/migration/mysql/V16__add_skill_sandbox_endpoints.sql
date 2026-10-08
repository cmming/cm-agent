-- 新端点与默认选择使用组合租户外键，防止跨租户引用；逻辑删除保留已开始调用的清理材料。
CREATE TABLE skill_sandbox_endpoints (
    id CHAR(36) NOT NULL COMMENT '端点唯一标识',
    tenant_id CHAR(36) NOT NULL COMMENT '端点所属可信租户',
    display_name VARCHAR(160) NOT NULL COMMENT '控制台显示名称',
    backend VARCHAR(64) NOT NULL COMMENT '服务端注册的执行后端',
    connection_mode VARCHAR(16) NOT NULL COMMENT '本地SSH或双向TLS连接方式',
    host VARCHAR(255) NOT NULL COMMENT '经部署策略校验的主机',
    port INTEGER NOT NULL COMMENT '远程端口本地为零',
    username VARCHAR(80) NOT NULL COMMENT 'SSH登录用户其他协议为空',
    enabled BOOLEAN NOT NULL COMMENT '是否允许新调用',
    encrypted_credential MEDIUMTEXT NOT NULL COMMENT '私钥证书及信任材料的认证密文',
    credential_version BIGINT NOT NULL COMMENT '加密材料版本',
    revision BIGINT NOT NULL COMMENT '配置乐观并发版本',
    probe_revision BIGINT NOT NULL COMMENT '成功探测对应配置版本',
    probed_at TIMESTAMP NULL COMMENT '最后探测时间',
    probe_status VARCHAR(40) NOT NULL COMMENT '安全探测结果不含原始响应',
    deleted BOOLEAN NOT NULL COMMENT '逻辑删除保留执行清理材料',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_sandbox_endpoint_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) COMMENT='租户沙箱端点与加密认证材料';
CREATE TABLE skill_sandbox_defaults (
    tenant_id CHAR(36) NOT NULL COMMENT '默认选择所属租户',
    endpoint_id CHAR(36) NOT NULL COMMENT '该租户默认沙箱端点',
    created_at TIMESTAMP NOT NULL COMMENT '首次设置默认端点的时间',
    updated_at TIMESTAMP NOT NULL COMMENT '最近切换默认端点的时间',
    PRIMARY KEY (tenant_id),
    CONSTRAINT fk_sandbox_default_endpoint FOREIGN KEY (endpoint_id, tenant_id) REFERENCES skill_sandbox_endpoints(id, tenant_id)
) COMMENT='每租户唯一的默认沙箱端点';
CREATE INDEX idx_sandbox_endpoint_tenant ON skill_sandbox_endpoints(tenant_id, deleted);
INSERT INTO permissions(code, description) VALUES ('sandbox:read', '查看本租户沙箱端点');
INSERT INTO permissions(code, description) VALUES ('sandbox:write', '维护本租户沙箱端点及默认选择');
INSERT INTO permissions(code, description) VALUES ('sandbox:delete', '删除本租户沙箱端点');
INSERT INTO permissions(code, description) VALUES ('sandbox:test', '探测本租户沙箱连接');
INSERT INTO permissions(code, description) VALUES ('sandbox:credential:write', '配置或轮换本租户沙箱凭据');
