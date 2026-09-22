ALTER TABLE skill_definitions
    ADD COLUMN candidate_version_id CHAR(36) NULL COMMENT '当前唯一候选版本标识，没有候选时为空',
    ADD COLUMN published_version_id CHAR(36) NULL COMMENT '当前正式发布版本标识，从未发布时为空',
    ADD COLUMN dependency_mapping_revision BIGINT NOT NULL COMMENT '技能级依赖映射集合修订号' AFTER access_epoch;

UPDATE skill_definitions
SET dependency_mapping_revision = 0,
    candidate_version_id = current_version_id,
    published_version_id = current_version_id
WHERE current_version_id IS NOT NULL;
ALTER TABLE skill_definitions DROP COLUMN current_version_id;

CREATE TABLE skill_dependencies (
    tenant_id CHAR(36) NOT NULL COMMENT '依赖声明归属租户标识',
    skill_id CHAR(36) NOT NULL COMMENT '依赖声明所属技能标识',
    version_id CHAR(36) NOT NULL COMMENT '依赖声明所属不可变版本标识',
    logical_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '技能包内的稳定逻辑工具键',
    required BOOLEAN NOT NULL COMMENT '是否为阻断运行和发布的必需依赖',
    description VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '给管理员的依赖用途说明',
    position INTEGER NOT NULL COMMENT '技能包声明顺序，从零开始',
    CONSTRAINT ux_skill_dependencies_version_key UNIQUE (tenant_id, version_id, logical_key),
    CONSTRAINT fk_skill_dependencies_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_dependencies_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
) COMMENT='技能版本声明的不可变逻辑依赖';

CREATE INDEX idx_skill_dependencies_tenant_version
    ON skill_dependencies (tenant_id, version_id, position);

CREATE TABLE skill_dependency_mappings (
    tenant_id CHAR(36) NOT NULL COMMENT '映射归属租户标识',
    skill_id CHAR(36) NOT NULL COMMENT '映射所属技能标识',
    logical_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '技能包声明的逻辑工具键',
    tool_id CHAR(36) NOT NULL COMMENT '管理员选择的租户工具标识',
    updated_by VARCHAR(160) NOT NULL COMMENT '最近修改映射的可信主体标识',
    updated_at TIMESTAMP NOT NULL COMMENT '最近修改映射的时间',
    PRIMARY KEY (tenant_id, skill_id, logical_key),
    CONSTRAINT fk_skill_dependency_mappings_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_dependency_mappings_tool FOREIGN KEY (tool_id, tenant_id)
        REFERENCES tool_definitions (id, tenant_id)
) COMMENT='技能逻辑依赖到租户工具映射';

CREATE TABLE skill_releases (
    id CHAR(36) NOT NULL COMMENT '发布事实标识，基线发布使用技能标识',
    tenant_id CHAR(36) NOT NULL COMMENT '发布事实归属租户标识',
    skill_id CHAR(36) NOT NULL COMMENT '发布事实所属技能标识',
    release_no BIGINT NOT NULL COMMENT '技能内从 1 开始的发布序号',
    action VARCHAR(32) NOT NULL COMMENT '发布、回滚或迁移基线动作',
    previous_version_id CHAR(36) NULL COMMENT '动作前已发布版本，首次发布或基线为空',
    version_id CHAR(36) NOT NULL COMMENT '动作最终生效的版本标识',
    preflight_id CHAR(36) NULL COMMENT '发布时采用的即时预检标识，基线为空',
    trial_run_id CHAR(36) NULL COMMENT '候选发布采用的合格试运行标识，基线或回滚为空',
    created_by VARCHAR(160) NOT NULL COMMENT '执行动作的可信主体标识',
    created_at TIMESTAMP NOT NULL COMMENT '动作发生时间',
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_releases_tenant_release_no UNIQUE (tenant_id, skill_id, release_no),
    CONSTRAINT fk_skill_releases_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_releases_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id),
    CONSTRAINT fk_skill_releases_previous FOREIGN KEY (tenant_id, skill_id, previous_version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
) COMMENT='追加式技能发布事实';

CREATE TABLE skill_preflight_checks (
    id CHAR(36) NOT NULL COMMENT '预检标识',
    tenant_id CHAR(36) NOT NULL COMMENT '预检归属租户标识',
    skill_id CHAR(36) NOT NULL COMMENT '目标技能标识',
    version_id CHAR(36) NOT NULL COMMENT '目标不可变版本标识',
    mapping_revision BIGINT NOT NULL COMMENT '预检时使用的映射修订号',
    scope VARCHAR(32) NOT NULL COMMENT '预检治理入口',
    agent_id CHAR(36) NULL COMMENT '单 Agent 预检目标，结构或发布可为空',
    status VARCHAR(32) NOT NULL COMMENT '预检汇总状态',
    created_by VARCHAR(160) NOT NULL COMMENT '发起预检的可信主体标识',
    created_at TIMESTAMP NOT NULL COMMENT '预检完成时间',
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_preflight_checks_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_skill_preflight_checks_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_preflight_checks_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
) COMMENT='技能依赖预检汇总';
CREATE INDEX idx_skill_preflight_checks_tenant_version
    ON skill_preflight_checks (tenant_id, skill_id, version_id, created_at DESC, id DESC);

CREATE TABLE skill_preflight_items (
    tenant_id CHAR(36) NOT NULL COMMENT '预检明细归属租户标识',
    check_id CHAR(36) NOT NULL COMMENT '所属预检标识',
    agent_key CHAR(36) NOT NULL COMMENT '明细键中用于区分绑定和结构项的归属键',
    agent_id CHAR(36) NULL COMMENT '受影响 Agent 标识，纯结构检查为空',
    logical_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '技能版本声明的逻辑工具键',
    required BOOLEAN NOT NULL COMMENT '是否为必需依赖',
    tool_id CHAR(36) NULL COMMENT '已解析的租户工具标识，未映射时为空',
    status VARCHAR(32) NOT NULL COMMENT '明细检查状态',
    error_code VARCHAR(80) NULL COMMENT '未就绪依赖的稳定错误码，就绪项为空',
    message VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '已脱敏的中文结果说明',
    error_id VARCHAR(160) NULL COMMENT '需要后台定位时使用的关联编号',
    CONSTRAINT ux_skill_preflight_items_tenant_check UNIQUE (tenant_id, check_id, agent_key, logical_key),
    CONSTRAINT fk_skill_preflight_items_check FOREIGN KEY (tenant_id, check_id)
        REFERENCES skill_preflight_checks (tenant_id, id),
    CONSTRAINT fk_skill_preflight_items_tool FOREIGN KEY (tool_id, tenant_id)
        REFERENCES tool_definitions (id, tenant_id)
) COMMENT='一次预检中针对逻辑依赖或 Agent 的明细';
CREATE INDEX idx_skill_preflight_items_tenant_check
    ON skill_preflight_items (tenant_id, check_id, agent_key, logical_key);

CREATE TABLE skill_trials (
    run_id CHAR(36) NOT NULL COMMENT '复用真实运行链路的 TEST Run 标识',
    tenant_id CHAR(36) NOT NULL COMMENT '试运行归属租户标识',
    skill_id CHAR(36) NOT NULL COMMENT '被临时注入的技能标识',
    version_id CHAR(36) NOT NULL COMMENT '被临时注入的不可变版本标识',
    agent_id CHAR(36) NOT NULL COMMENT '执行真实模型调用的 Agent 标识',
    mapping_revision BIGINT NOT NULL COMMENT '试运行固定的映射修订号',
    status VARCHAR(32) NOT NULL COMMENT '试运行状态',
    qualifies_release BOOLEAN NOT NULL COMMENT '是否满足候选发布依据要求',
    created_by VARCHAR(160) NOT NULL COMMENT '发起试运行的可信主体标识',
    created_at TIMESTAMP NOT NULL COMMENT '试运行创建时间',
    updated_at TIMESTAMP NOT NULL COMMENT '试运行最近更新时间',
    CONSTRAINT ux_skill_trials_tenant_run UNIQUE (tenant_id, run_id),
    CONSTRAINT fk_skill_trials_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id),
    CONSTRAINT fk_skill_trials_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_trials_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
) COMMENT='指定技能版本试运行的治理状态';
CREATE INDEX idx_skill_trials_tenant_skill
    ON skill_trials (tenant_id, skill_id, version_id, run_id);

ALTER TABLE agent_skill_bindings
    ADD COLUMN resolution_mode VARCHAR(32) NOT NULL DEFAULT 'FOLLOW_PUBLISHED' COMMENT '跟随发布或固定版本策略',
    ADD COLUMN pinned_version_id CHAR(36) NULL COMMENT '固定模式下使用的版本标识，跟随模式为空',
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '绑定策略乐观锁修订号',
    ADD COLUMN updated_by VARCHAR(160) NULL COMMENT '最近更新绑定策略的主体标识',
    ADD COLUMN updated_at TIMESTAMP NULL COMMENT '绑定策略最近更新时间';
UPDATE agent_skill_bindings
SET resolution_mode = 'FOLLOW_PUBLISHED',
    pinned_version_id = NULL,
    revision = 0,
    updated_by = bound_by,
    updated_at = created_at;
ALTER TABLE agent_skill_bindings
    MODIFY COLUMN updated_by VARCHAR(160) NOT NULL COMMENT '最近更新绑定策略的主体标识',
    MODIFY COLUMN updated_at TIMESTAMP NOT NULL COMMENT '绑定策略最近更新时间';

ALTER TABLE runs
    ADD COLUMN run_kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '正式运行或指定技能版本试运行';
UPDATE runs SET run_kind = 'NORMAL' WHERE run_kind <> 'NORMAL';
ALTER TABLE runs
    MODIFY COLUMN run_kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '正式运行或指定技能版本试运行';

ALTER TABLE tool_approval_requests
    ADD COLUMN approval_scope VARCHAR(16) NOT NULL DEFAULT 'CONVERSATION'
        COMMENT '审批依附的正式会话或独立 TEST Run';
UPDATE tool_approval_requests SET approval_scope = 'CONVERSATION';
ALTER TABLE tool_approval_requests
    MODIFY COLUMN conversation_id CHAR(36) NULL COMMENT '审批所属正式会话标识，独立 TEST Run 为空';

INSERT INTO skill_releases (
    id, tenant_id, skill_id, release_no, action, version_id,
    previous_version_id, preflight_id, trial_run_id, created_by, created_at
)
SELECT id, tenant_id, id, 1, 'BASELINE', published_version_id,
       NULL, NULL, NULL, updated_by, updated_at
FROM skill_definitions
WHERE published_version_id IS NOT NULL;

CREATE TEMPORARY TABLE v13_legacy_dependency_validation (
    invalid_count BIGINT NOT NULL,
    CONSTRAINT ck_v13_legacy_dependency_validation CHECK (invalid_count = 0)
);
INSERT INTO v13_legacy_dependency_validation (invalid_count)
SELECT COUNT(*)
FROM skill_versions
WHERE JSON_VALID(metadata_json) = 0
   OR (
       JSON_EXTRACT(metadata_json, '$.dependencies') IS NOT NULL
       AND NOT JSON_SCHEMA_VALID(
           '{
             "type": "object",
             "properties": {
               "dependencies": {
                 "type": "object",
                 "additionalProperties": false,
                 "properties": {
                   "tools": {
                     "type": "array",
                     "items": {
                       "type": "object",
                       "additionalProperties": false,
                       "required": ["key"],
                       "properties": {
                         "key": {
                           "type": "string",
                           "maxLength": 64,
                           "pattern": "^(?:[a-z0-9](?:[a-z0-9]|-(?!-)){0,62}[a-z0-9]|[a-z0-9])$"
                         },
                         "required": {"type": "boolean"},
                         "description": {"type": "string", "maxLength": 1024}
                       }
                     }
                   }
                 }
               }
             }
           }',
           metadata_json
       )
   );
DROP TEMPORARY TABLE v13_legacy_dependency_validation;

INSERT INTO skill_dependencies (
    tenant_id, skill_id, version_id, logical_key, required, description, position
)
SELECT v.tenant_id, v.skill_id, v.id, dep.logical_key, COALESCE(dep.required, true),
       COALESCE(dep.description, ''), dep.dependency_no - 1
FROM skill_versions v
CROSS JOIN JSON_TABLE(v.metadata_json, '$.dependencies.tools[*]'
    COLUMNS (
        logical_key VARCHAR(64) PATH '$.key',
        required BOOLEAN PATH '$.required',
        description VARCHAR(1024) PATH '$.description',
        dependency_no FOR ORDINALITY
    )
) AS dep;
