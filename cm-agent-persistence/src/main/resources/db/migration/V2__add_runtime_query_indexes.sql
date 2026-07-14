CREATE INDEX idx_runs_tenant_agent_started ON runs (tenant_id, agent_id, started_at, id);
CREATE INDEX idx_tool_calls_tenant_run ON tool_calls (tenant_id, run_id, id);
CREATE INDEX idx_audit_events_tenant_time_id ON audit_events (tenant_id, created_at, id);
