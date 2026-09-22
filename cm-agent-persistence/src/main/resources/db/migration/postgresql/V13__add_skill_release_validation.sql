-- 新增发布治理、依赖映射、预检和试运行状态；所有字段都持久化中文注释。
ALTER TABLE skill_definitions ADD COLUMN candidate_version_id CHAR(36);
ALTER TABLE skill_definitions ADD COLUMN published_version_id CHAR(36);
ALTER TABLE skill_definitions ADD COLUMN dependency_mapping_revision BIGINT NOT NULL DEFAULT 0;

UPDATE skill_definitions
SET candidate_version_id = current_version_id,
    published_version_id = current_version_id
WHERE current_version_id IS NOT NULL;
ALTER TABLE skill_definitions DROP COLUMN current_version_id;

COMMENT ON COLUMN skill_definitions.candidate_version_id IS '当前唯一候选版本标识，没有候选时为空';
COMMENT ON COLUMN skill_definitions.published_version_id IS '当前正式发布版本标识，从未发布时为空';
COMMENT ON COLUMN skill_definitions.dependency_mapping_revision IS '技能级依赖映射集合修订号';

CREATE TABLE skill_dependencies (
    tenant_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    logical_key VARCHAR(64) NOT NULL,
    required BOOLEAN NOT NULL,
    description VARCHAR(1024) NOT NULL,
    position INTEGER NOT NULL,
    CONSTRAINT ux_skill_dependencies_version_key UNIQUE (tenant_id, version_id, logical_key),
    CONSTRAINT fk_skill_dependencies_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_dependencies_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
);
COMMENT ON TABLE skill_dependencies IS '技能版本声明的不可变逻辑依赖';
COMMENT ON COLUMN skill_dependencies.tenant_id IS '依赖声明归属租户标识';
COMMENT ON COLUMN skill_dependencies.skill_id IS '依赖声明所属技能标识';
COMMENT ON COLUMN skill_dependencies.version_id IS '依赖声明所属不可变版本标识';
COMMENT ON COLUMN skill_dependencies.logical_key IS '技能包内的稳定逻辑工具键';
COMMENT ON COLUMN skill_dependencies.required IS '是否为阻断运行和发布的必需依赖';
COMMENT ON COLUMN skill_dependencies.description IS '给管理员的依赖用途说明';
COMMENT ON COLUMN skill_dependencies.position IS '技能包声明顺序，从零开始';

CREATE INDEX idx_skill_dependencies_tenant_version ON skill_dependencies (tenant_id, version_id, position);
COMMENT ON TABLE skill_dependencies IS '技能版本声明的不可变逻辑依赖';
COMMENT ON COLUMN skill_dependencies.tenant_id IS '依赖声明归属租户标识';
COMMENT ON COLUMN skill_dependencies.skill_id IS '依赖声明所属技能标识';
COMMENT ON COLUMN skill_dependencies.version_id IS '依赖声明所属不可变版本标识';
COMMENT ON COLUMN skill_dependencies.logical_key IS '技能包内的稳定逻辑工具键';
COMMENT ON COLUMN skill_dependencies.required IS '是否为阻断运行和发布的必需依赖';
COMMENT ON COLUMN skill_dependencies.description IS '给管理员的依赖用途说明';
COMMENT ON COLUMN skill_dependencies.position IS '技能包声明顺序，从零开始';

CREATE TABLE skill_dependency_mappings (
    tenant_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    logical_key VARCHAR(64) NOT NULL,
    tool_id CHAR(36) NOT NULL,
    updated_by VARCHAR(160) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, skill_id, logical_key),
    CONSTRAINT fk_skill_dependency_mappings_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_dependency_mappings_tool FOREIGN KEY (tool_id, tenant_id)
        REFERENCES tool_definitions (id, tenant_id)
);
    COMMENT ON TABLE skill_dependency_mappings IS '技能逻辑依赖到租户工具映射';
COMMENT ON COLUMN skill_dependency_mappings.tenant_id IS '映射归属租户标识';
COMMENT ON COLUMN skill_dependency_mappings.skill_id IS '映射所属技能标识';
COMMENT ON COLUMN skill_dependency_mappings.logical_key IS '技能包声明的逻辑工具键';
COMMENT ON COLUMN skill_dependency_mappings.tool_id IS '管理员选择的租户工具标识';
COMMENT ON COLUMN skill_dependency_mappings.updated_by IS '最近修改映射的可信主体标识';
COMMENT ON COLUMN skill_dependency_mappings.updated_at IS '最近修改映射的时间';

CREATE TABLE skill_releases (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    release_no BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    previous_version_id CHAR(36),
    version_id CHAR(36) NOT NULL,
    preflight_id CHAR(36),
    trial_run_id CHAR(36),
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_releases_tenant_release_no UNIQUE (tenant_id, skill_id, release_no),
    CONSTRAINT fk_skill_releases_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_releases_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id),
    CONSTRAINT fk_skill_releases_previous FOREIGN KEY (tenant_id, skill_id, previous_version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
);
COMMENT ON TABLE skill_releases IS '追加式技能发布事实';
COMMENT ON COLUMN skill_releases.id IS '发布事实标识，基线发布使用技能标识';
COMMENT ON COLUMN skill_releases.tenant_id IS '发布事实归属租户标识';
COMMENT ON COLUMN skill_releases.skill_id IS '发布事实所属技能标识';
COMMENT ON COLUMN skill_releases.release_no IS '技能内从 1 开始的发布序号';
COMMENT ON COLUMN skill_releases.action IS '发布、回滚或迁移基线动作';
COMMENT ON COLUMN skill_releases.previous_version_id IS '动作前已发布版本，首次发布或基线为空';
COMMENT ON COLUMN skill_releases.version_id IS '动作最终生效的版本标识';
COMMENT ON COLUMN skill_releases.preflight_id IS '发布时采用的即时预检标识，基线为空';
COMMENT ON COLUMN skill_releases.trial_run_id IS '候选发布采用的合格试运行标识，基线或回滚为空';
COMMENT ON COLUMN skill_releases.created_by IS '执行动作的可信主体标识';
COMMENT ON COLUMN skill_releases.created_at IS '动作发生时间';

CREATE TABLE skill_preflight_checks (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    mapping_revision BIGINT NOT NULL,
    scope VARCHAR(32) NOT NULL,
    agent_id CHAR(36),
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_skill_preflight_checks_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_skill_preflight_checks_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_preflight_checks_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
);
CREATE INDEX idx_skill_preflight_checks_tenant_version
    ON skill_preflight_checks (tenant_id, skill_id, version_id, created_at DESC, id DESC);
COMMENT ON TABLE skill_preflight_checks IS '技能依赖预检汇总';
COMMENT ON COLUMN skill_preflight_checks.id IS '预检标识';
COMMENT ON COLUMN skill_preflight_checks.tenant_id IS '预检归属租户标识';
COMMENT ON COLUMN skill_preflight_checks.skill_id IS '目标技能标识';
COMMENT ON COLUMN skill_preflight_checks.version_id IS '目标不可变版本标识';
COMMENT ON COLUMN skill_preflight_checks.mapping_revision IS '预检时使用的映射修订号';
COMMENT ON COLUMN skill_preflight_checks.scope IS '预检治理入口';
COMMENT ON COLUMN skill_preflight_checks.agent_id IS '单 Agent 预检目标，结构或发布可为空';
COMMENT ON COLUMN skill_preflight_checks.status IS '预检汇总状态';
COMMENT ON COLUMN skill_preflight_checks.created_by IS '发起预检的可信主体标识';
COMMENT ON COLUMN skill_preflight_checks.created_at IS '预检完成时间';

CREATE TABLE skill_preflight_items (
    tenant_id CHAR(36) NOT NULL,
    check_id CHAR(36) NOT NULL,
    agent_key VARCHAR(36) NOT NULL,
    agent_id CHAR(36),
    logical_key VARCHAR(64) NOT NULL,
    required BOOLEAN NOT NULL,
    tool_id CHAR(36),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(80),
    message VARCHAR(1000) NOT NULL,
    error_id VARCHAR(160),
    CONSTRAINT ux_skill_preflight_items_tenant_check
        UNIQUE (tenant_id, check_id, agent_key, logical_key),
    CONSTRAINT fk_skill_preflight_items_check FOREIGN KEY (tenant_id, check_id)
        REFERENCES skill_preflight_checks (tenant_id, id),
    CONSTRAINT fk_skill_preflight_items_tool FOREIGN KEY (tool_id, tenant_id)
        REFERENCES tool_definitions (id, tenant_id)
);
CREATE INDEX idx_skill_preflight_items_tenant_check
    ON skill_preflight_items (tenant_id, check_id, agent_key, logical_key);
COMMENT ON TABLE skill_preflight_items IS '一次预检中针对逻辑依赖或 Agent 的明细';
COMMENT ON COLUMN skill_preflight_items.tenant_id IS '预检明细归属租户标识';
COMMENT ON COLUMN skill_preflight_items.check_id IS '所属预检标识';
COMMENT ON COLUMN skill_preflight_items.agent_key IS '明细键中用于区分绑定和结构项的归属键';
COMMENT ON COLUMN skill_preflight_items.agent_id IS '受影响 Agent 标识，纯结构检查为空';
COMMENT ON COLUMN skill_preflight_items.logical_key IS '技能版本声明的逻辑工具键';
COMMENT ON COLUMN skill_preflight_items.required IS '是否为必需依赖';
COMMENT ON COLUMN skill_preflight_items.tool_id IS '已解析的租户工具标识，未映射时为空';
COMMENT ON COLUMN skill_preflight_items.status IS '明细检查状态';
COMMENT ON COLUMN skill_preflight_items.error_code IS '未就绪依赖的稳定错误码，就绪项为空';
COMMENT ON COLUMN skill_preflight_items.message IS '已脱敏的中文结果说明';
COMMENT ON COLUMN skill_preflight_items.error_id IS '需要后台定位时使用的关联编号';

CREATE TABLE skill_trials (
    run_id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    mapping_revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    qualifies_release BOOLEAN NOT NULL,
    created_by VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ux_skill_trials_tenant_run UNIQUE (tenant_id, run_id),
    CONSTRAINT fk_skill_trials_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id),
    CONSTRAINT fk_skill_trials_definition FOREIGN KEY (skill_id, tenant_id)
        REFERENCES skill_definitions (id, tenant_id),
    CONSTRAINT fk_skill_trials_version FOREIGN KEY (tenant_id, skill_id, version_id)
        REFERENCES skill_versions (tenant_id, skill_id, id)
);
COMMENT ON TABLE skill_trials IS '指定技能版本试运行的治理状态';
COMMENT ON COLUMN skill_trials.run_id IS '复用真实运行链路的 TEST Run 标识';
COMMENT ON COLUMN skill_trials.tenant_id IS '试运行归属租户标识';
COMMENT ON COLUMN skill_trials.skill_id IS '被临时注入的技能标识';
COMMENT ON COLUMN skill_trials.version_id IS '被临时注入的不可变版本标识';
COMMENT ON COLUMN skill_trials.agent_id IS '执行真实模型调用的 Agent 标识';
COMMENT ON COLUMN skill_trials.mapping_revision IS '试运行固定的映射修订号';
COMMENT ON COLUMN skill_trials.status IS '试运行状态';
COMMENT ON COLUMN skill_trials.qualifies_release IS '是否满足候选发布依据要求';
COMMENT ON COLUMN skill_trials.created_by IS '发起试运行的可信主体标识';
COMMENT ON COLUMN skill_trials.created_at IS '试运行创建时间';
COMMENT ON COLUMN skill_trials.updated_at IS '试运行最近更新时间';

ALTER TABLE agent_skill_bindings
    ADD COLUMN resolution_mode VARCHAR(32) NOT NULL DEFAULT 'FOLLOW_PUBLISHED',
    ADD COLUMN pinned_version_id CHAR(36),
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_by VARCHAR(160),
    ADD COLUMN updated_at TIMESTAMP;

UPDATE agent_skill_bindings
SET resolution_mode = 'FOLLOW_PUBLISHED',
    pinned_version_id = NULL,
    revision = 0,
    updated_by = bound_by,
    updated_at = created_at;
ALTER TABLE agent_skill_bindings ALTER COLUMN updated_by SET NOT NULL;
ALTER TABLE agent_skill_bindings ALTER COLUMN updated_at SET NOT NULL;

COMMENT ON COLUMN agent_skill_bindings.resolution_mode IS '跟随发布或固定版本策略';
COMMENT ON COLUMN agent_skill_bindings.pinned_version_id IS '固定模式下使用的版本标识，跟随模式为空';
COMMENT ON COLUMN agent_skill_bindings.revision IS '绑定策略乐观锁修订号';
COMMENT ON COLUMN agent_skill_bindings.updated_by IS '最近更新绑定策略的主体标识';
COMMENT ON COLUMN agent_skill_bindings.updated_at IS '绑定策略最近更新时间';

ALTER TABLE runs ADD COLUMN run_kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
UPDATE runs SET run_kind = 'NORMAL' WHERE run_kind IS DISTINCT FROM 'NORMAL';
COMMENT ON COLUMN runs.run_kind IS '正式运行或指定技能版本试运行';

ALTER TABLE tool_approval_requests ADD COLUMN approval_scope VARCHAR(16) NOT NULL DEFAULT 'CONVERSATION';
UPDATE tool_approval_requests SET approval_scope = 'CONVERSATION';
ALTER TABLE tool_approval_requests ALTER COLUMN conversation_id DROP NOT NULL;
COMMENT ON COLUMN tool_approval_requests.approval_scope IS '审批依附的正式会话或独立 TEST Run';
COMMENT ON COLUMN tool_approval_requests.conversation_id IS '审批所属正式会话标识，独立 TEST Run 为空';

INSERT INTO skill_releases (
        id, tenant_id, skill_id, release_no, action, version_id,
        previous_version_id, preflight_id, trial_run_id, created_by, created_at
)
SELECT id, tenant_id, id, 1, 'BASELINE', published_version_id,
       NULL, NULL, NULL, updated_by, updated_at
FROM skill_definitions
WHERE published_version_id IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM skill_versions v
        WHERE v.metadata_json::jsonb #> '{dependencies}' IS NOT NULL
          AND jsonb_typeof(v.metadata_json::jsonb #> '{dependencies}') <> 'object'
    ) THEN
        RAISE EXCEPTION '技能元数据 dependencies 必须是对象';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM skill_versions v
        WHERE v.metadata_json::jsonb #> '{dependencies,tools}' IS NOT NULL
          AND jsonb_typeof(v.metadata_json::jsonb #> '{dependencies,tools}') <> 'array'
    ) THEN
        RAISE EXCEPTION '技能元数据 dependencies.tools 必须是数组';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM skill_versions v
        CROSS JOIN LATERAL jsonb_array_elements(v.metadata_json::jsonb #> '{dependencies,tools}') item
        WHERE jsonb_typeof(item) <> 'object'
           OR item->>'key' IS NULL
           OR item->>'key' !~ '^(?:[a-z0-9](?:[a-z0-9]|-(?!-)){0,62}[a-z0-9]|[a-z0-9])$'
           OR (item ? 'required' AND jsonb_typeof(item->'required') <> 'boolean')
           OR (item ? 'description' AND jsonb_typeof(item->'description') <> 'string')
           OR EXISTS (
               SELECT 1 FROM jsonb_object_keys(item) key_name
               WHERE key_name NOT IN ('key', 'required', 'description')
           )
    ) THEN
        RAISE EXCEPTION '技能元数据 dependencies.tools 包含非法条目，请先修复数据';
    END IF;
END $$;

INSERT INTO skill_dependencies (tenant_id, skill_id, version_id, logical_key, required, description, position)
SELECT v.tenant_id, v.skill_id, v.id, dep.value->>'key',
       CASE
           WHEN dep.value ? 'required' THEN (dep.value->>'required')::boolean
           ELSE true
       END,
       COALESCE(dep.value->>'description', ''),
       dep.ordinality - 1
FROM skill_versions v
CROSS JOIN LATERAL JSONB_ARRAY_ELEMENTS(v.metadata_json::jsonb #> '{dependencies,tools}')
     WITH ORDINALITY AS dep(value, ordinality);

CREATE INDEX idx_skill_trials_tenant_skill ON skill_trials (tenant_id, skill_id, version_id, run_id);
