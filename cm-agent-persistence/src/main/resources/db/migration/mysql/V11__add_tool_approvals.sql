CREATE TABLE tool_approval_requests (
    id CHAR(36) NOT NULL COMMENT '审批请求唯一标识',
    tenant_id CHAR(36) NOT NULL COMMENT '审批归属租户标识',
    agent_id CHAR(36) NOT NULL COMMENT '被运行的 Agent 标识',
    conversation_id CHAR(36) NOT NULL COMMENT '审批所属会话标识',
    run_id CHAR(36) NOT NULL COMMENT '被暂停的运行标识',
    requested_by VARCHAR(160) NOT NULL COMMENT '运行发起主体标识',
    requested_by_display_name VARCHAR(160) NOT NULL COMMENT '运行发起人展示名称快照',
    status VARCHAR(32) NOT NULL COMMENT '审批请求生命周期状态',
    expires_at TIMESTAMP NOT NULL COMMENT '服务端权威过期时间',
    version_no BIGINT NOT NULL COMMENT '审批乐观锁版本',
    checkpoint_ref VARCHAR(255) NOT NULL COMMENT '加密运行检查点引用',
    decided_by VARCHAR(160) NULL COMMENT '作出决定的主体标识',
    decided_by_display_name VARCHAR(160) NULL COMMENT '审批人展示名称快照',
    decided_at TIMESTAMP NULL COMMENT '审批决定生效时间',
    created_at TIMESTAMP NOT NULL COMMENT '审批请求创建时间',
    updated_at TIMESTAMP NOT NULL COMMENT '审批请求最后更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_tool_approval_requests_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tool_approval_requests_agent FOREIGN KEY (agent_id, tenant_id) REFERENCES agent_definitions (id, tenant_id),
    CONSTRAINT fk_tool_approval_requests_conversation FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversations (id, tenant_id),
    CONSTRAINT fk_tool_approval_requests_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id)
) COMMENT='AgentScope 工具调用审批请求';

CREATE TABLE tool_approval_items (
    id CHAR(36) NOT NULL COMMENT '审批明细唯一标识',
    tenant_id CHAR(36) NOT NULL COMMENT '审批明细归属租户标识',
    approval_id CHAR(36) NOT NULL COMMENT '所属审批请求标识',
    tool_call_id VARCHAR(255) NOT NULL COMMENT 'AgentScope 工具调用标识',
    tool_id CHAR(36) NOT NULL COMMENT 'CM Agent 工具标识',
    tool_name VARCHAR(160) NOT NULL COMMENT '创建审批时的工具名称快照',
    risk_level VARCHAR(16) NOT NULL COMMENT '创建审批时的风险等级快照',
    input_summary TEXT NOT NULL COMMENT '已脱敏限长的输入展示摘要',
    input_hash CHAR(64) NOT NULL COMMENT '规范化原始输入 SHA-256 哈希',
    approval_policy VARCHAR(32) NOT NULL COMMENT '创建审批时命中的审批策略',
    policy_version BIGINT NOT NULL COMMENT '创建审批时的策略版本',
    decision VARCHAR(16) NULL COMMENT '单项允许或拒绝决定',
    created_at TIMESTAMP NOT NULL COMMENT '审批明细创建时间',
    updated_at TIMESTAMP NOT NULL COMMENT '审批明细最后更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_tool_approval_items_request FOREIGN KEY (approval_id) REFERENCES tool_approval_requests (id),
    CONSTRAINT fk_tool_approval_items_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tool_approval_items_tool FOREIGN KEY (tool_id, tenant_id) REFERENCES tool_definitions (id, tenant_id),
    CONSTRAINT ux_tool_approval_items_call UNIQUE (approval_id, tool_call_id)
) COMMENT='一次审批请求中的工具调用明细';

CREATE TABLE runtime_checkpoints (
    id CHAR(36) NOT NULL COMMENT '检查点条目唯一标识',
    tenant_id CHAR(36) NOT NULL COMMENT '检查点归属租户标识',
    user_id VARCHAR(320) NOT NULL COMMENT 'AgentScope 状态槽用户标识',
    session_id VARCHAR(160) NOT NULL COMMENT '使用运行标识构造的状态槽会话标识',
    state_key VARCHAR(160) NOT NULL COMMENT 'AgentScope 状态条目键',
    state_type VARCHAR(320) NOT NULL COMMENT '序列化状态 Java 类型名称',
    list_payload BOOLEAN NOT NULL COMMENT '载荷是否为状态列表',
    encrypted_payload TEXT NOT NULL COMMENT 'AES-GCM 认证加密状态密文',
    expires_at TIMESTAMP NOT NULL COMMENT '检查点失效时间',
    created_at TIMESTAMP NOT NULL COMMENT '检查点创建时间',
    updated_at TIMESTAMP NOT NULL COMMENT '检查点最后更新时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_runtime_checkpoints_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ux_runtime_checkpoints_slot UNIQUE (tenant_id, user_id, session_id, state_key)
) COMMENT='AgentScope 可恢复运行的加密状态条目';

CREATE INDEX idx_tool_approvals_pending
    ON tool_approval_requests (tenant_id, agent_id, conversation_id, status, expires_at, created_at);
CREATE INDEX idx_tool_approvals_run ON tool_approval_requests (tenant_id, run_id);
CREATE INDEX idx_runtime_checkpoints_expiry ON runtime_checkpoints (tenant_id, expires_at);
