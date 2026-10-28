-- 关联标识只用于原主体找回创建响应；旧V23数据保持NULL，不伪造历史请求。
ALTER TABLE workspace_imports ADD COLUMN request_id VARCHAR(36);
-- 原主体是认证标识，不能按部署环境的语言或大小写规则合并。
ALTER TABLE workspace_imports ALTER COLUMN principal_id TYPE VARCHAR(200) COLLATE "C";
COMMENT ON COLUMN workspace_imports.principal_id IS '原上传主体，按认证标识逐字匹配，不隐式扩展给同租户其他主体';
COMMENT ON COLUMN workspace_imports.request_id IS '原主体单次确认的随机请求关联标识，用于未知创建结果核对，旧批次为空';
CREATE UNIQUE INDEX uq_workspace_import_request ON workspace_imports(tenant_id,principal_id,agent_id,request_id);
