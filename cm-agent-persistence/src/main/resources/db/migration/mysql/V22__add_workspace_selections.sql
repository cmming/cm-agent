-- 选择仅保存元数据，正文继续使用原加密检查点；资源采用软引用，归属由短事务校验。
CREATE TABLE conversation_workspace_bindings (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信认证租户标识',
    agent_id VARCHAR(36) NOT NULL COMMENT '归属智能体标识',
    resource_id VARCHAR(36) NOT NULL COMMENT '会话或运行的软引用标识，由表名区分且由服务校验归属',
    selection_mode VARCHAR(16) NOT NULL CHECK (selection_mode IN ('DEFAULT','SELECTED','NONE')) COMMENT '工作区选择模式，沿用配置、指定目录或不使用',
    workspace_id VARCHAR(36) COMMENT '指定目录的稳定逻辑标识，其他模式为空',
    mapping_digest VARCHAR(64) NOT NULL COMMENT '目录声明身份摘要，不含资料正文',
    display_name VARCHAR(120) NOT NULL COMMENT '选择时固定的安全展示名称',
    directory VARCHAR(160) NOT NULL COMMENT '已授权的根内相对目录，不保存宿主绝对路径',
    version BIGINT NOT NULL COMMENT '从一开始的乐观更新版本',
    principal_id VARCHAR(200) NOT NULL COMMENT '来自认证上下文的操作主体标识',
    created_at TIMESTAMP(6) NOT NULL COMMENT '首次创建时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近更新或固定时间',
    PRIMARY KEY (tenant_id, resource_id)
) COMMENT='会话只读工作区绑定';
CREATE INDEX idx_conversation_workspace_bindings_agent ON conversation_workspace_bindings (tenant_id, agent_id);

CREATE TABLE run_workspace_selections (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信认证租户标识',
    agent_id VARCHAR(36) NOT NULL COMMENT '归属智能体标识',
    resource_id VARCHAR(36) NOT NULL COMMENT '会话或运行的软引用标识，由表名区分且由服务校验归属',
    selection_mode VARCHAR(16) NOT NULL CHECK (selection_mode IN ('DEFAULT','SELECTED','NONE')) COMMENT '工作区选择模式，沿用配置、指定目录或不使用',
    workspace_id VARCHAR(36) COMMENT '指定目录的稳定逻辑标识，其他模式为空',
    mapping_digest VARCHAR(64) NOT NULL COMMENT '目录声明身份摘要，不含资料正文',
    display_name VARCHAR(120) NOT NULL COMMENT '选择时固定的安全展示名称',
    directory VARCHAR(160) NOT NULL COMMENT '已授权的根内相对目录，不保存宿主绝对路径',
    version BIGINT NOT NULL COMMENT '从一开始的乐观更新版本',
    principal_id VARCHAR(200) NOT NULL COMMENT '来自认证上下文的操作主体标识',
    created_at TIMESTAMP(6) NOT NULL COMMENT '首次创建时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近更新或固定时间',
    PRIMARY KEY (tenant_id, resource_id)
) COMMENT='运行固定的只读工作区选择';
CREATE INDEX idx_run_workspace_selections_agent ON run_workspace_selections (tenant_id, agent_id);

