-- 文件字节保存在受控持久卷；本表只承载归属、预算和跨实例资源租约。
CREATE TABLE skill_artifacts (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    principal_id VARCHAR(320) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    run_kind VARCHAR(16) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    call_id VARCHAR(160) NOT NULL,
    filename VARCHAR(240) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    lease_token CHAR(36) NULL,
    lease_until TIMESTAMP NULL,
    CONSTRAINT pk_skill_artifacts PRIMARY KEY (id),
    CONSTRAINT fk_skill_artifact_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ux_skill_artifact_call UNIQUE (tenant_id,run_id,call_id,filename)
);
CREATE INDEX idx_skill_artifact_run ON skill_artifacts(tenant_id,run_id,status);
CREATE INDEX idx_skill_artifact_cleanup ON skill_artifacts(status,expires_at,lease_until);
COMMENT ON TABLE skill_artifacts IS '技能沙箱加密文件产物元数据';
COMMENT ON COLUMN skill_artifacts.id IS '产物唯一标识';
COMMENT ON COLUMN skill_artifacts.tenant_id IS '产物归属租户';
COMMENT ON COLUMN skill_artifacts.principal_id IS '运行创建者标识';
COMMENT ON COLUMN skill_artifacts.agent_id IS 'Agent 软引用，历史产物不因停用自动删除';
COMMENT ON COLUMN skill_artifacts.run_id IS '运行软引用，下载必须核对成功终态';
COMMENT ON COLUMN skill_artifacts.run_kind IS '正式或技能 TEST 运行';
COMMENT ON COLUMN skill_artifacts.skill_id IS '技能软引用，保留历史归属';
COMMENT ON COLUMN skill_artifacts.version_id IS '固定技能版本软引用';
COMMENT ON COLUMN skill_artifacts.call_id IS '原模型调用标识';
COMMENT ON COLUMN skill_artifacts.filename IS '经校验的相对展示文件名，不作为磁盘路径';
COMMENT ON COLUMN skill_artifacts.media_type IS '受控附件媒体类型';
COMMENT ON COLUMN skill_artifacts.size_bytes IS '明文字节数，含暂存的配额占用';
COMMENT ON COLUMN skill_artifacts.sha256 IS '服务端计算的明文摘要，暂存时为空';
COMMENT ON COLUMN skill_artifacts.status IS 'STAGING PENDING READY DELETING DELETED';
COMMENT ON COLUMN skill_artifacts.created_at IS '元数据预留时间';
COMMENT ON COLUMN skill_artifacts.expires_at IS '下载有效期截止时间';
COMMENT ON COLUMN skill_artifacts.lease_token IS '资源读取或清理租约令牌';
COMMENT ON COLUMN skill_artifacts.lease_until IS '资源租约到期时间';
