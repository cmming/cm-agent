ALTER TABLE tool_definitions
    ADD COLUMN deleted_at TIMESTAMP NULL;

ALTER TABLE tool_definitions
    ADD COLUMN deleted_name VARCHAR(160) NULL;

CREATE INDEX idx_tool_definitions_tenant_deleted
    ON tool_definitions (tenant_id, deleted_at);
