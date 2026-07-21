CREATE UNIQUE INDEX ux_tool_definitions_tenant_name ON tool_definitions (tenant_id, name);

CREATE TABLE tool_http_configs (
    tenant_id CHAR(36) NOT NULL,
    tool_id CHAR(36) NOT NULL,
    method VARCHAR(10) NOT NULL,
    url_template VARCHAR(500) NOT NULL,
    input_schema TEXT NOT NULL,
    parameter_mappings TEXT NOT NULL,
    secret_headers TEXT NOT NULL,
    timeout_ms BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, tool_id),
    CONSTRAINT fk_http_tool FOREIGN KEY (tool_id, tenant_id)
        REFERENCES tool_definitions (id, tenant_id)
);

CREATE TABLE tool_mcp_publications (
    tenant_id CHAR(36) NOT NULL,
    tool_id CHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL,
    published_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, tool_id),
    CONSTRAINT fk_mcp_tool FOREIGN KEY (tool_id, tenant_id)
        REFERENCES tool_definitions (id, tenant_id)
);

INSERT INTO permissions (code, description)
SELECT 'tool:debug', '调试工具'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE code = 'tool:debug');

INSERT INTO permissions (code, description)
SELECT 'tool:mcp:invoke', '通过 MCP 调用已发布工具'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE code = 'tool:mcp:invoke');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'admin'
  AND p.code IN ('tool:debug', 'tool:mcp:invoke')
  AND NOT EXISTS (
      SELECT 1
      FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_code = p.code
  );
