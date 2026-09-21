CREATE TABLE skill_definitions (
    id CHAR(36) NOT NULL COMMENT '技能稳定标识',
    tenant_id CHAR(36) NOT NULL COMMENT '技能归属租户标识',
    name VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '租户内唯一技能名称',
    current_version_id CHAR(36) NOT NULL COMMENT '当前生效版本软引用',
    enabled BOOLEAN NOT NULL COMMENT '技能是否允许新运行使用',
    access_epoch BIGINT NOT NULL COMMENT '撤销旧运行读取权限的访问纪元',
    created_by VARCHAR(160) NOT NULL COMMENT '创建主体标识',
    updated_by VARCHAR(160) NOT NULL COMMENT '最近更新主体标识',
    created_at TIMESTAMP NOT NULL COMMENT '技能创建时间',
    updated_at TIMESTAMP NOT NULL COMMENT '技能最近更新时间',
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_definitions_tenant_identity UNIQUE (id, tenant_id),
    CONSTRAINT ux_skill_definitions_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT fk_skill_definitions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) COMMENT='租户内技能稳定定义';

CREATE TABLE skill_versions (
    id CHAR(36) NOT NULL COMMENT '技能版本标识',
    tenant_id CHAR(36) NOT NULL COMMENT '版本归属租户标识',
    skill_id CHAR(36) NOT NULL COMMENT '所属技能稳定标识',
    version_no INTEGER NOT NULL COMMENT '技能内递增版本号',
    description VARCHAR(500) NOT NULL COMMENT '供模型选择技能的简短描述',
    metadata_json LONGTEXT NOT NULL COMMENT '受限技能元数据 JSON',
    skill_content LONGTEXT NOT NULL COMMENT 'SKILL.md 指令正文',
    sha256 CHAR(64) NOT NULL COMMENT '版本规范内容 SHA-256 摘要',
    created_by VARCHAR(160) NOT NULL COMMENT '版本创建主体标识',
    created_at TIMESTAMP NOT NULL COMMENT '版本创建时间',
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_versions_tenant_number UNIQUE (tenant_id, skill_id, version_no),
    CONSTRAINT ux_skill_versions_tenant_identity UNIQUE (tenant_id, skill_id, id),
    CONSTRAINT fk_skill_versions_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id)
) COMMENT='技能不可变版本正文与元数据';

CREATE TABLE skill_resources (
    tenant_id CHAR(36) NOT NULL COMMENT '资源归属租户标识',
    skill_id CHAR(36) NOT NULL COMMENT '所属技能稳定标识',
    version_id CHAR(36) NOT NULL COMMENT '所属不可变版本标识',
    path VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '包内规范化相对路径',
    media_type VARCHAR(120) NOT NULL COMMENT '文本资源媒体类型',
    content LONGTEXT NOT NULL COMMENT 'UTF-8 文本正文',
    byte_length INTEGER NOT NULL COMMENT '正文 UTF-8 字节数',
    sha256 CHAR(64) NOT NULL COMMENT '资源正文 SHA-256 摘要',
    CONSTRAINT ux_skill_resources_tenant_path UNIQUE (tenant_id, version_id, path),
    CONSTRAINT fk_skill_resources_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
) COMMENT='技能版本附带的受控文本资源';

CREATE TABLE agent_skill_bindings (
    id CHAR(36) NOT NULL COMMENT '绑定标识',
    tenant_id CHAR(36) NOT NULL COMMENT '绑定归属租户标识',
    agent_id CHAR(36) NOT NULL COMMENT '被绑定的 Agent 标识',
    skill_id CHAR(36) NOT NULL COMMENT '被绑定的技能标识',
    bound_by VARCHAR(160) NOT NULL COMMENT '执行绑定的主体标识',
    created_at TIMESTAMP NOT NULL COMMENT '绑定创建时间',
    PRIMARY KEY (id),
    CONSTRAINT ux_agent_skill_bindings_tenant_agent_skill UNIQUE (tenant_id, agent_id, skill_id),
    CONSTRAINT fk_agent_skill_bindings_agent FOREIGN KEY (agent_id, tenant_id)
        REFERENCES agent_definitions (id, tenant_id),
    CONSTRAINT fk_agent_skill_bindings_skill FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id)
) COMMENT='Agent 与技能的一次授权绑定';

CREATE TABLE run_skill_snapshots (
    tenant_id CHAR(36) NOT NULL COMMENT '快照归属租户标识',
    run_id CHAR(36) NOT NULL COMMENT '所属 Run 标识',
    agent_id CHAR(36) NOT NULL COMMENT '执行 Run 的 Agent 标识',
    format_version INTEGER NOT NULL COMMENT '快照结构格式版本',
    skills_json LONGTEXT NOT NULL COMMENT '固定版本绑定和访问纪元引用列表',
    created_at TIMESTAMP NOT NULL COMMENT '快照创建时间',
    CONSTRAINT ux_run_skill_snapshots_tenant_run UNIQUE (tenant_id, run_id),
    CONSTRAINT fk_run_skill_snapshots_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id)
) COMMENT='Run 创建时固定的技能授权快照';

CREATE TABLE skill_load_records (
    id CHAR(36) NOT NULL COMMENT '读取记录标识',
    tenant_id CHAR(36) NOT NULL COMMENT '读取记录归属租户标识',
    run_id CHAR(36) NOT NULL COMMENT '所属 Run 标识',
    model_call_id VARCHAR(255) NOT NULL COMMENT '模型侧读取调用幂等标识',
    attempt_no INTEGER NOT NULL COMMENT 'Run 内累计尝试序号',
    skill_id CHAR(36) NULL COMMENT '已解析技能标识，非法请求可为空',
    version_id CHAR(36) NULL COMMENT '已解析版本标识，非法请求可为空',
    path VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '已校验资源路径，非法请求可为空',
    status VARCHAR(32) NOT NULL COMMENT '读取成功失败或拒绝状态',
    delivered_bytes INTEGER NOT NULL COMMENT '实际交付模型的 UTF-8 字节数',
    duration_millis BIGINT NOT NULL COMMENT '受控读取耗时毫秒数',
    error_code VARCHAR(80) NULL COMMENT '失败或拒绝的稳定错误码',
    error_id VARCHAR(160) NULL COMMENT '关联安全响应与后台日志的错误编号',
    created_at TIMESTAMP NOT NULL COMMENT '读取记录创建时间',
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_load_records_tenant_call UNIQUE (tenant_id, run_id, model_call_id),
    CONSTRAINT fk_skill_load_records_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id)
) COMMENT='Run 内技能文本读取尝试记录';

CREATE INDEX idx_skill_definitions_tenant_updated ON skill_definitions (tenant_id, updated_at, id);
CREATE INDEX idx_skill_versions_tenant_skill ON skill_versions (tenant_id, skill_id, version_no);
CREATE INDEX idx_agent_skill_bindings_tenant_skill ON agent_skill_bindings (tenant_id, skill_id);
CREATE INDEX idx_agent_skill_bindings_tenant_agent ON agent_skill_bindings (tenant_id, agent_id);
CREATE INDEX idx_skill_load_records_tenant_run_time ON skill_load_records (tenant_id, run_id, created_at, id);

INSERT INTO run_skill_snapshots (tenant_id, run_id, agent_id, format_version, skills_json, created_at)
SELECT tenant_id, id, agent_id, 1, '[]', started_at FROM runs;
