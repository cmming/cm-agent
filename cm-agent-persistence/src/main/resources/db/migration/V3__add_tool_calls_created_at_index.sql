CREATE INDEX idx_tool_calls_tenant_run_created_at
    ON tool_calls (tenant_id, run_id, created_at, id);
