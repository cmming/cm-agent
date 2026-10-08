-- 与MySQL迁移语义相同；旧记录默认私钥，不新增明文密码列。
ALTER TABLE skill_sandbox_endpoints ADD COLUMN ssh_auth_type VARCHAR(16) NOT NULL DEFAULT 'KEY';
COMMENT ON COLUMN skill_sandbox_endpoints.ssh_auth_type IS 'SSH认证方式：KEY为私钥，PASSWORD为主机账号密码；非SSH保持KEY';
COMMENT ON COLUMN skill_sandbox_endpoints.encrypted_credential IS '私钥、SSH密码、证书及信任材料的AES/GCM认证密文';
