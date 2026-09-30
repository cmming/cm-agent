-- 系统扫描按状态、到期时间和审批标识分页；不改变表字段和既有迁移。
CREATE INDEX idx_approval_expiry_scan ON tool_approval_requests (status, expires_at, id);
