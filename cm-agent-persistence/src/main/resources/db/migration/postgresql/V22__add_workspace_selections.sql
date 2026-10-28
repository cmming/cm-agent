-- 选择仅保存元数据，正文继续使用原加密检查点；资源采用软引用，归属由短事务校验。
CREATE TABLE conversation_workspace_bindings (
    tenant_id VARCHAR(36) NOT NULL,
    agent_id VARCHAR(36) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    selection_mode VARCHAR(16) NOT NULL CHECK (selection_mode IN ('DEFAULT','SELECTED','NONE')),
    workspace_id VARCHAR(36),
    mapping_digest VARCHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    directory VARCHAR(160) NOT NULL,
    version BIGINT NOT NULL,
    principal_id VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id, resource_id)
);
CREATE INDEX idx_conversation_workspace_bindings_agent ON conversation_workspace_bindings (tenant_id, agent_id);
COMMENT ON TABLE conversation_workspace_bindings IS '会话只读工作区绑定';
COMMENT ON COLUMN conversation_workspace_bindings.tenant_id IS '可信认证租户标识';
COMMENT ON COLUMN conversation_workspace_bindings.agent_id IS '归属智能体标识';
COMMENT ON COLUMN conversation_workspace_bindings.resource_id IS '会话或运行的软引用标识，由表名区分且由服务校验归属';
COMMENT ON COLUMN conversation_workspace_bindings.selection_mode IS '工作区选择模式，沿用配置、指定目录或不使用';
COMMENT ON COLUMN conversation_workspace_bindings.workspace_id IS '指定目录的稳定逻辑标识，其他模式为空';
COMMENT ON COLUMN conversation_workspace_bindings.mapping_digest IS '目录声明身份摘要，不含资料正文';
COMMENT ON COLUMN conversation_workspace_bindings.display_name IS '选择时固定的安全展示名称';
COMMENT ON COLUMN conversation_workspace_bindings.directory IS '已授权的根内相对目录，不保存宿主绝对路径';
COMMENT ON COLUMN conversation_workspace_bindings.version IS '从一开始的乐观更新版本';
COMMENT ON COLUMN conversation_workspace_bindings.principal_id IS '来自认证上下文的操作主体标识';
COMMENT ON COLUMN conversation_workspace_bindings.created_at IS '首次创建时间';
COMMENT ON COLUMN conversation_workspace_bindings.updated_at IS '最近更新或固定时间';

CREATE TABLE run_workspace_selections (
    tenant_id VARCHAR(36) NOT NULL,
    agent_id VARCHAR(36) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    selection_mode VARCHAR(16) NOT NULL CHECK (selection_mode IN ('DEFAULT','SELECTED','NONE')),
    workspace_id VARCHAR(36),
    mapping_digest VARCHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    directory VARCHAR(160) NOT NULL,
    version BIGINT NOT NULL,
    principal_id VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id, resource_id)
);
CREATE INDEX idx_run_workspace_selections_agent ON run_workspace_selections (tenant_id, agent_id);
COMMENT ON TABLE run_workspace_selections IS '运行固定的只读工作区选择';
COMMENT ON COLUMN run_workspace_selections.tenant_id IS '可信认证租户标识';
COMMENT ON COLUMN run_workspace_selections.agent_id IS '归属智能体标识';
COMMENT ON COLUMN run_workspace_selections.resource_id IS '会话或运行的软引用标识，由表名区分且由服务校验归属';
COMMENT ON COLUMN run_workspace_selections.selection_mode IS '工作区选择模式，沿用配置、指定目录或不使用';
COMMENT ON COLUMN run_workspace_selections.workspace_id IS '指定目录的稳定逻辑标识，其他模式为空';
COMMENT ON COLUMN run_workspace_selections.mapping_digest IS '目录声明身份摘要，不含资料正文';
COMMENT ON COLUMN run_workspace_selections.display_name IS '选择时固定的安全展示名称';
COMMENT ON COLUMN run_workspace_selections.directory IS '已授权的根内相对目录，不保存宿主绝对路径';
COMMENT ON COLUMN run_workspace_selections.version IS '从一开始的乐观更新版本';
COMMENT ON COLUMN run_workspace_selections.principal_id IS '来自认证上下文的操作主体标识';
COMMENT ON COLUMN run_workspace_selections.created_at IS '首次创建时间';
COMMENT ON COLUMN run_workspace_selections.updated_at IS '最近更新或固定时间';

