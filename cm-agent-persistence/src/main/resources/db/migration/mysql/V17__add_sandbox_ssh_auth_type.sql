-- 既有端点维持私钥认证，不通过升级自动切换认证；密码仍仅保存于既有AES/GCM材料中。
ALTER TABLE skill_sandbox_endpoints ADD COLUMN ssh_auth_type VARCHAR(16) NOT NULL DEFAULT 'KEY'
    COMMENT 'SSH认证方式：KEY为私钥，PASSWORD为主机账号密码；非SSH保持KEY';
ALTER TABLE skill_sandbox_endpoints MODIFY COLUMN encrypted_credential MEDIUMTEXT NOT NULL
    COMMENT '私钥、SSH密码、证书及信任材料的AES/GCM认证密文';
