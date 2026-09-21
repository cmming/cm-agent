CREATE TABLE skill_definitions (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    current_version_id CHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL,
    access_epoch BIGINT NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    updated_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_definitions_tenant_identity UNIQUE (id, tenant_id),
    CONSTRAINT ux_skill_definitions_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT fk_skill_definitions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE skill_versions (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    version_no INTEGER NOT NULL,
    description VARCHAR(500) NOT NULL,
    metadata_json TEXT NOT NULL,
    skill_content TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_versions_tenant_number UNIQUE (tenant_id, skill_id, version_no),
    CONSTRAINT ux_skill_versions_tenant_identity UNIQUE (tenant_id, skill_id, id),
    CONSTRAINT fk_skill_versions_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id)
);

CREATE TABLE skill_resources (
    tenant_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    path VARCHAR(500) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    byte_length INTEGER NOT NULL,
    sha256 CHAR(64) NOT NULL,
    CONSTRAINT ux_skill_resources_tenant_path UNIQUE (tenant_id, version_id, path),
    CONSTRAINT fk_skill_resources_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
);

CREATE TABLE agent_skill_bindings (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    bound_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_agent_skill_bindings_tenant_agent_skill UNIQUE (tenant_id, agent_id, skill_id),
    CONSTRAINT fk_agent_skill_bindings_agent FOREIGN KEY (agent_id, tenant_id)
        REFERENCES agent_definitions (id, tenant_id),
    CONSTRAINT fk_agent_skill_bindings_skill FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id)
);

CREATE TABLE run_skill_snapshots (
    tenant_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    format_version INTEGER NOT NULL,
    skills_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ux_run_skill_snapshots_tenant_run UNIQUE (tenant_id, run_id),
    CONSTRAINT fk_run_skill_snapshots_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id)
);

CREATE TABLE skill_load_records (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    model_call_id VARCHAR(255) NOT NULL,
    attempt_no INTEGER NOT NULL,
    skill_id CHAR(36),
    version_id CHAR(36),
    path VARCHAR(500),
    status VARCHAR(32) NOT NULL,
    delivered_bytes INTEGER NOT NULL,
    duration_millis BIGINT NOT NULL,
    error_code VARCHAR(80),
    error_id VARCHAR(160),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_load_records_tenant_call UNIQUE (tenant_id, run_id, model_call_id),
    CONSTRAINT fk_skill_load_records_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id)
);

CREATE INDEX idx_skill_definitions_tenant_updated ON skill_definitions (tenant_id, updated_at, id);
CREATE INDEX idx_skill_versions_tenant_skill ON skill_versions (tenant_id, skill_id, version_no);
CREATE INDEX idx_agent_skill_bindings_tenant_skill ON agent_skill_bindings (tenant_id, skill_id);
CREATE INDEX idx_agent_skill_bindings_tenant_agent ON agent_skill_bindings (tenant_id, agent_id);
CREATE INDEX idx_skill_load_records_tenant_run_time ON skill_load_records (tenant_id, run_id, created_at, id);

INSERT INTO run_skill_snapshots (tenant_id, run_id, agent_id, format_version, skills_json, created_at)
SELECT tenant_id, id, agent_id, 1, '[]', started_at FROM runs;

COMMENT ON TABLE skill_definitions IS '租户内技能稳定定义';
COMMENT ON COLUMN skill_definitions.id IS '技能稳定标识';
COMMENT ON COLUMN skill_definitions.tenant_id IS '技能归属租户标识';
COMMENT ON COLUMN skill_definitions.name IS '租户内唯一技能名称';
COMMENT ON COLUMN skill_definitions.current_version_id IS '当前生效版本软引用';
COMMENT ON COLUMN skill_definitions.enabled IS '技能是否允许新运行使用';
COMMENT ON COLUMN skill_definitions.access_epoch IS '撤销旧运行读取权限的访问纪元';
COMMENT ON COLUMN skill_definitions.created_by IS '创建主体标识';
COMMENT ON COLUMN skill_definitions.updated_by IS '最近更新主体标识';
COMMENT ON COLUMN skill_definitions.created_at IS '技能创建时间';
COMMENT ON COLUMN skill_definitions.updated_at IS '技能最近更新时间';
COMMENT ON TABLE skill_versions IS '技能不可变版本正文与元数据';
COMMENT ON COLUMN skill_versions.id IS '技能版本标识';
COMMENT ON COLUMN skill_versions.tenant_id IS '版本归属租户标识';
COMMENT ON COLUMN skill_versions.skill_id IS '所属技能稳定标识';
COMMENT ON COLUMN skill_versions.version_no IS '技能内递增版本号';
COMMENT ON COLUMN skill_versions.description IS '供模型选择技能的简短描述';
COMMENT ON COLUMN skill_versions.metadata_json IS '受限技能元数据 JSON';
COMMENT ON COLUMN skill_versions.skill_content IS 'SKILL.md 指令正文';
COMMENT ON COLUMN skill_versions.sha256 IS '版本规范内容 SHA-256 摘要';
COMMENT ON COLUMN skill_versions.created_by IS '版本创建主体标识';
COMMENT ON COLUMN skill_versions.created_at IS '版本创建时间';
COMMENT ON TABLE skill_resources IS '技能版本附带的受控文本资源';
COMMENT ON COLUMN skill_resources.tenant_id IS '资源归属租户标识';
COMMENT ON COLUMN skill_resources.skill_id IS '所属技能稳定标识';
COMMENT ON COLUMN skill_resources.version_id IS '所属不可变版本标识';
COMMENT ON COLUMN skill_resources.path IS '包内规范化相对路径';
COMMENT ON COLUMN skill_resources.media_type IS '文本资源媒体类型';
COMMENT ON COLUMN skill_resources.content IS 'UTF-8 文本正文';
COMMENT ON COLUMN skill_resources.byte_length IS '正文 UTF-8 字节数';
COMMENT ON COLUMN skill_resources.sha256 IS '资源正文 SHA-256 摘要';
COMMENT ON TABLE agent_skill_bindings IS 'Agent 与技能的一次授权绑定';
COMMENT ON COLUMN agent_skill_bindings.id IS '绑定标识';
COMMENT ON COLUMN agent_skill_bindings.tenant_id IS '绑定归属租户标识';
COMMENT ON COLUMN agent_skill_bindings.agent_id IS '被绑定的 Agent 标识';
COMMENT ON COLUMN agent_skill_bindings.skill_id IS '被绑定的技能标识';
COMMENT ON COLUMN agent_skill_bindings.bound_by IS '执行绑定的主体标识';
COMMENT ON COLUMN agent_skill_bindings.created_at IS '绑定创建时间';
COMMENT ON TABLE run_skill_snapshots IS 'Run 创建时固定的技能授权快照';
COMMENT ON COLUMN run_skill_snapshots.tenant_id IS '快照归属租户标识';
COMMENT ON COLUMN run_skill_snapshots.run_id IS '所属 Run 标识';
COMMENT ON COLUMN run_skill_snapshots.agent_id IS '执行 Run 的 Agent 标识';
COMMENT ON COLUMN run_skill_snapshots.format_version IS '快照结构格式版本';
COMMENT ON COLUMN run_skill_snapshots.skills_json IS '固定版本绑定和访问纪元引用列表';
COMMENT ON COLUMN run_skill_snapshots.created_at IS '快照创建时间';
COMMENT ON TABLE skill_load_records IS 'Run 内技能文本读取尝试记录';
COMMENT ON COLUMN skill_load_records.id IS '读取记录标识';
COMMENT ON COLUMN skill_load_records.tenant_id IS '读取记录归属租户标识';
COMMENT ON COLUMN skill_load_records.run_id IS '所属 Run 标识';
COMMENT ON COLUMN skill_load_records.model_call_id IS '模型侧读取调用幂等标识';
COMMENT ON COLUMN skill_load_records.attempt_no IS 'Run 内累计尝试序号';
COMMENT ON COLUMN skill_load_records.skill_id IS '已解析技能标识，非法请求可为空';
COMMENT ON COLUMN skill_load_records.version_id IS '已解析版本标识，非法请求可为空';
COMMENT ON COLUMN skill_load_records.path IS '已校验资源路径，非法请求可为空';
COMMENT ON COLUMN skill_load_records.status IS '读取成功失败或拒绝状态';
COMMENT ON COLUMN skill_load_records.delivered_bytes IS '实际交付模型的 UTF-8 字节数';
COMMENT ON COLUMN skill_load_records.duration_millis IS '受控读取耗时毫秒数';
COMMENT ON COLUMN skill_load_records.error_code IS '失败或拒绝的稳定错误码';
COMMENT ON COLUMN skill_load_records.error_id IS '关联安全响应与后台日志的错误编号';
COMMENT ON COLUMN skill_load_records.created_at IS '读取记录创建时间';
