CREATE TABLE tenants (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE users (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    username VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, username),
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE roles (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    code VARCHAR(120) NOT NULL,
    name VARCHAR(120) NOT NULL,
    UNIQUE (tenant_id, code),
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_roles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE permissions (
    code VARCHAR(120) PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE user_roles (
    tenant_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    role_id CHAR(36) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, role_id),
    CONSTRAINT fk_user_roles_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id, tenant_id) REFERENCES users (id, tenant_id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id, tenant_id) REFERENCES roles (id, tenant_id)
);

CREATE TABLE role_permissions (
    role_id CHAR(36) NOT NULL,
    permission_code VARCHAR(120) NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_code) REFERENCES permissions (code)
);

CREATE TABLE api_keys (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    key_hash VARCHAR(255) NOT NULL,
    permissions_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    rotated_at TIMESTAMP,
    CONSTRAINT fk_api_keys_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE model_configs (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_model_configs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE agent_definitions (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500) NOT NULL,
    system_prompt TEXT NOT NULL,
    model_provider_id CHAR(36) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    max_iterations INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    tool_ids_json TEXT NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    updated_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_agent_definitions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_agent_definitions_model_provider FOREIGN KEY (model_provider_id, tenant_id) REFERENCES model_configs (id, tenant_id)
);

CREATE TABLE tool_definitions (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500) NOT NULL,
    type VARCHAR(30) NOT NULL,
    input_schema TEXT NOT NULL,
    risk_level VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL,
    endpoint VARCHAR(500) NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    updated_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_tool_definitions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE tool_grants (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    tool_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    role_code VARCHAR(120) NOT NULL,
    granted BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, tool_id, agent_id, role_code),
    CONSTRAINT fk_tool_grants_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tool_grants_tool FOREIGN KEY (tool_id, tenant_id) REFERENCES tool_definitions (id, tenant_id),
    CONSTRAINT fk_tool_grants_agent FOREIGN KEY (agent_id, tenant_id) REFERENCES agent_definitions (id, tenant_id),
    CONSTRAINT fk_tool_grants_role FOREIGN KEY (tenant_id, role_code) REFERENCES roles (tenant_id, code)
);

CREATE TABLE conversations (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_conversations_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_conversations_agent FOREIGN KEY (agent_id, tenant_id) REFERENCES agent_definitions (id, tenant_id)
);

CREATE TABLE messages (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    role VARCHAR(30) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_messages_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id, tenant_id) REFERENCES conversations (id, tenant_id)
);

CREATE TABLE runs (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    principal_id VARCHAR(120) NOT NULL,
    status VARCHAR(30) NOT NULL,
    input_text TEXT NOT NULL,
    output_text TEXT,
    error_message TEXT,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    UNIQUE (id, tenant_id),
    CONSTRAINT fk_runs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_runs_agent FOREIGN KEY (agent_id, tenant_id) REFERENCES agent_definitions (id, tenant_id)
);

CREATE TABLE tool_calls (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    tool_id CHAR(36) NOT NULL,
    tool_name VARCHAR(160) NOT NULL,
    input_summary TEXT NOT NULL,
    output_summary TEXT,
    status VARCHAR(30) NOT NULL,
    authorized BOOLEAN NOT NULL,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_tool_calls_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tool_calls_run FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id),
    CONSTRAINT fk_tool_calls_tool FOREIGN KEY (tool_id, tenant_id) REFERENCES tool_definitions (id, tenant_id)
);

-- principal_id 和 resource_id 故意保留为软引用，用于不可变审计历史，不强制外键约束。
CREATE TABLE audit_events (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    principal_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    resource_type VARCHAR(120) NOT NULL,
    resource_id VARCHAR(120) NOT NULL,
    status VARCHAR(30) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_audit_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE INDEX idx_agent_definitions_tenant ON agent_definitions (tenant_id);
CREATE INDEX idx_tool_definitions_tenant ON tool_definitions (tenant_id);
CREATE INDEX idx_tool_grants_tenant_agent ON tool_grants (tenant_id, agent_id);
CREATE INDEX idx_runs_tenant_agent ON runs (tenant_id, agent_id);
CREATE INDEX idx_audit_events_tenant_time ON audit_events (tenant_id, created_at);
