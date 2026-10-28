-- 关联标识只用于原主体找回创建响应；旧V23数据保持NULL，不伪造历史请求。
ALTER TABLE workspace_imports ADD COLUMN request_id VARCHAR(36) COMMENT '原主体单次确认的随机请求关联标识，用于未知创建结果核对，旧批次为空';
-- 二进制且NO PAD的比较与Java主体判等一致，避免大小写或尾随空格把两个确认误合并。
ALTER TABLE workspace_imports MODIFY principal_id VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '原上传主体，按认证标识逐字匹配，不隐式扩展给同租户其他主体';
CREATE UNIQUE INDEX uq_workspace_import_request ON workspace_imports(tenant_id,principal_id,agent_id,request_id);
