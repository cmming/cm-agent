ALTER TABLE model_configs
    MODIFY COLUMN encrypted_api_key TEXT NOT NULL COMMENT '使用受控主密钥 AES/GCM 加密的模型 API Key 密文，禁止保存明文';
