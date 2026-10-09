-- 文件字节保存在受控持久卷；本表只承载归属、预算和跨实例资源租约。
CREATE TABLE skill_artifacts (
    id CHAR(36) NOT NULL COMMENT '产物唯一标识',
    tenant_id CHAR(36) NOT NULL COMMENT '产物归属租户',
    principal_id VARCHAR(320) NOT NULL COMMENT '运行创建者标识',
    agent_id CHAR(36) NOT NULL COMMENT 'Agent 软引用，历史产物不因停用自动删除',
    run_id CHAR(36) NOT NULL COMMENT '运行软引用，下载必须核对成功终态',
    run_kind VARCHAR(16) NOT NULL COMMENT '正式或技能 TEST 运行',
    skill_id CHAR(36) NOT NULL COMMENT '技能软引用，保留历史归属',
    version_id CHAR(36) NOT NULL COMMENT '固定技能版本软引用',
    call_id VARCHAR(160) NOT NULL COMMENT '原模型调用标识',
    filename VARCHAR(240) NOT NULL COMMENT '经校验的相对展示文件名，不作为磁盘路径',
    media_type VARCHAR(160) NOT NULL COMMENT '受控附件媒体类型',
    size_bytes BIGINT NOT NULL COMMENT '明文字节数，含暂存的配额占用',
    sha256 VARCHAR(64) NOT NULL COMMENT '服务端计算的明文摘要，暂存时为空',
    status VARCHAR(16) NOT NULL COMMENT 'STAGING PENDING READY DELETING DELETED',
    created_at TIMESTAMP NOT NULL COMMENT '元数据预留时间',
    expires_at TIMESTAMP NOT NULL COMMENT '下载有效期截止时间',
    lease_token CHAR(36) NULL COMMENT '资源读取或清理租约令牌',
    lease_until TIMESTAMP NULL COMMENT '资源租约到期时间',
    CONSTRAINT pk_skill_artifacts PRIMARY KEY (id),
    CONSTRAINT fk_skill_artifact_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ux_skill_artifact_call UNIQUE (tenant_id,run_id,call_id,filename)
) CHARACTER SET utf8mb4 COMMENT='技能沙箱加密文件产物元数据';
CREATE INDEX idx_skill_artifact_run ON skill_artifacts(tenant_id,run_id,status);
CREATE INDEX idx_skill_artifact_cleanup ON skill_artifacts(status,expires_at,lease_until);
