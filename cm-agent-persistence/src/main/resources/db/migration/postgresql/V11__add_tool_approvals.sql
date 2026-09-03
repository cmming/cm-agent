CREATE TABLE tool_approval_requests (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    requested_by VARCHAR(160) NOT NULL,
    requested_by_display_name VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    version_no BIGINT NOT NULL,
    checkpoint_ref VARCHAR(255) NOT NULL,
    decided_by VARCHAR(160),
    decided_by_display_name VARCHAR(160),
    decided_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tool_approval_requests_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tool_approval_requests_agent FOREIGN KEY (agent_id, tenant_id) REFERENCES agent_definitions (id, tenant_id),
    CONSTRAINT fk_tool_approval_requests_conversation FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversations (id, tenant_id),
    CONSTRAINT fk_tool_approval_requests_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id)
);

CREATE TABLE tool_approval_items (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    approval_id CHAR(36) NOT NULL,
    tool_call_id VARCHAR(255) NOT NULL,
    tool_id CHAR(36) NOT NULL,
    tool_name VARCHAR(160) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    input_summary TEXT NOT NULL,
    input_hash CHAR(64) NOT NULL,
    approval_policy VARCHAR(32) NOT NULL,
    policy_version BIGINT NOT NULL,
    decision VARCHAR(16),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tool_approval_items_request FOREIGN KEY (approval_id) REFERENCES tool_approval_requests (id),
    CONSTRAINT fk_tool_approval_items_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tool_approval_items_tool FOREIGN KEY (tool_id, tenant_id) REFERENCES tool_definitions (id, tenant_id),
    CONSTRAINT ux_tool_approval_items_call UNIQUE (approval_id, tool_call_id)
);

CREATE TABLE runtime_checkpoints (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    user_id VARCHAR(320) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    state_key VARCHAR(160) NOT NULL,
    state_type VARCHAR(320) NOT NULL,
    list_payload BOOLEAN NOT NULL,
    encrypted_payload TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_runtime_checkpoints_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ux_runtime_checkpoints_slot UNIQUE (tenant_id, user_id, session_id, state_key)
);

CREATE INDEX idx_tool_approvals_pending
    ON tool_approval_requests (tenant_id, agent_id, conversation_id, status, expires_at, created_at);
CREATE INDEX idx_tool_approvals_run ON tool_approval_requests (tenant_id, run_id);
CREATE INDEX idx_runtime_checkpoints_expiry ON runtime_checkpoints (tenant_id, expires_at);

COMMENT ON TABLE tool_approval_requests IS 'AgentScope 工具调用审批请求';
COMMENT ON COLUMN tool_approval_requests.id IS '审批请求唯一标识';
COMMENT ON COLUMN tool_approval_requests.tenant_id IS '审批归属租户标识';
COMMENT ON COLUMN tool_approval_requests.agent_id IS '被运行的 Agent 标识';
COMMENT ON COLUMN tool_approval_requests.conversation_id IS '审批所属会话标识';
COMMENT ON COLUMN tool_approval_requests.run_id IS '被暂停的运行标识';
COMMENT ON COLUMN tool_approval_requests.requested_by IS '运行发起主体标识';
COMMENT ON COLUMN tool_approval_requests.requested_by_display_name IS '运行发起人展示名称快照';
COMMENT ON COLUMN tool_approval_requests.status IS '审批请求生命周期状态';
COMMENT ON COLUMN tool_approval_requests.expires_at IS '服务端权威过期时间';
COMMENT ON COLUMN tool_approval_requests.version_no IS '审批乐观锁版本';
COMMENT ON COLUMN tool_approval_requests.checkpoint_ref IS '加密运行检查点引用';
COMMENT ON COLUMN tool_approval_requests.decided_by IS '作出决定的主体标识';
COMMENT ON COLUMN tool_approval_requests.decided_by_display_name IS '审批人展示名称快照';
COMMENT ON COLUMN tool_approval_requests.decided_at IS '审批决定生效时间';
COMMENT ON COLUMN tool_approval_requests.created_at IS '审批请求创建时间';
COMMENT ON COLUMN tool_approval_requests.updated_at IS '审批请求最后更新时间';

COMMENT ON TABLE tool_approval_items IS '一次审批请求中的工具调用明细';
COMMENT ON COLUMN tool_approval_items.id IS '审批明细唯一标识';
COMMENT ON COLUMN tool_approval_items.tenant_id IS '审批明细归属租户标识';
COMMENT ON COLUMN tool_approval_items.approval_id IS '所属审批请求标识';
COMMENT ON COLUMN tool_approval_items.tool_call_id IS 'AgentScope 工具调用标识';
COMMENT ON COLUMN tool_approval_items.tool_id IS 'CM Agent 工具标识';
COMMENT ON COLUMN tool_approval_items.tool_name IS '创建审批时的工具名称快照';
COMMENT ON COLUMN tool_approval_items.risk_level IS '创建审批时的风险等级快照';
COMMENT ON COLUMN tool_approval_items.input_summary IS '已脱敏限长的输入展示摘要';
COMMENT ON COLUMN tool_approval_items.input_hash IS '规范化原始输入 SHA-256 哈希';
COMMENT ON COLUMN tool_approval_items.approval_policy IS '创建审批时命中的审批策略';
COMMENT ON COLUMN tool_approval_items.policy_version IS '创建审批时的策略版本';
COMMENT ON COLUMN tool_approval_items.decision IS '单项允许或拒绝决定';
COMMENT ON COLUMN tool_approval_items.created_at IS '审批明细创建时间';
COMMENT ON COLUMN tool_approval_items.updated_at IS '审批明细最后更新时间';

COMMENT ON TABLE runtime_checkpoints IS 'AgentScope 可恢复运行的加密状态条目';
COMMENT ON COLUMN runtime_checkpoints.id IS '检查点条目唯一标识';
COMMENT ON COLUMN runtime_checkpoints.tenant_id IS '检查点归属租户标识';
COMMENT ON COLUMN runtime_checkpoints.user_id IS 'AgentScope 状态槽用户标识';
COMMENT ON COLUMN runtime_checkpoints.session_id IS '使用运行标识构造的状态槽会话标识';
COMMENT ON COLUMN runtime_checkpoints.state_key IS 'AgentScope 状态条目键';
COMMENT ON COLUMN runtime_checkpoints.state_type IS '序列化状态 Java 类型名称';
COMMENT ON COLUMN runtime_checkpoints.list_payload IS '载荷是否为状态列表';
COMMENT ON COLUMN runtime_checkpoints.encrypted_payload IS 'AES-GCM 认证加密状态密文';
COMMENT ON COLUMN runtime_checkpoints.expires_at IS '检查点失效时间';
COMMENT ON COLUMN runtime_checkpoints.created_at IS '检查点创建时间';
COMMENT ON COLUMN runtime_checkpoints.updated_at IS '检查点最后更新时间';
