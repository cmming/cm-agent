-- PostgreSQL 的 TEXT 已支持较大载荷，无需改类型；与 MySQL 同版本同步字段语义，不重写既有密文。
COMMENT ON COLUMN runtime_checkpoints.encrypted_payload IS 'AES-GCM认证加密状态密文，完整保存较大运行状态';
