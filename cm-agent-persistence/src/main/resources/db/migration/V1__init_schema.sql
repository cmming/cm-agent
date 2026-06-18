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
    UNIQUE (tenant_id, username)
);

CREATE TABLE roles (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    code VARCHAR(120) NOT NULL,
    name VARCHAR(120) NOT NULL,
    UNIQUE (tenant_id, code)
);

CREATE TABLE permissions (
    code VARCHAR(120) PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE user_roles (
    user_id CHAR(36) NOT NULL,
    role_id CHAR(36) NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_permissions (
    role_id CHAR(36) NOT NULL,
    permission_code VARCHAR(120) NOT NULL,
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE api_keys (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    key_hash VARCHAR(255) NOT NULL,
    permissions_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    rotated_at TIMESTAMP
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
    created_at TIMESTAMP NOT NULL
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
    updated_at TIMESTAMP NOT NULL
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
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE tool_grants (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    tool_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    role_code VARCHAR(120) NOT NULL,
    granted BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE conversations (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE messages (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    role VARCHAR(30) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE runs (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    principal_id VARCHAR(120) NOT NULL,
    status VARCHAR(30) NOT NULL,
    input_text TEXT NOT NULL,
    output_text TEXT NOT NULL,
    error_message TEXT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP
);

CREATE TABLE tool_calls (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    tool_id CHAR(36) NOT NULL,
    tool_name VARCHAR(160) NOT NULL,
    input_summary TEXT NOT NULL,
    output_summary TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    authorized BOOLEAN NOT NULL,
    duration_ms BIGINT NOT NULL,
    error_message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE audit_events (
    id CHAR(36) PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    principal_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    resource_type VARCHAR(120) NOT NULL,
    resource_id VARCHAR(120) NOT NULL,
    status VARCHAR(30) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_agent_definitions_tenant ON agent_definitions (tenant_id);
CREATE INDEX idx_tool_definitions_tenant ON tool_definitions (tenant_id);
CREATE INDEX idx_tool_grants_tenant_agent ON tool_grants (tenant_id, agent_id);
CREATE INDEX idx_runs_tenant_agent ON runs (tenant_id, agent_id);
CREATE INDEX idx_audit_events_tenant_time ON audit_events (tenant_id, created_at);
