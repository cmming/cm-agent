-- AgentScope 状态包含历史与工具结果，AES-GCM 密文经 Base64 编码后会超过 TEXT 的 65535 字节上限。
-- 使用 LONGTEXT 保存完整密文，避免引入 MEDIUMTEXT 的另一容量瓶颈；不截断、不重加密既有状态。
ALTER TABLE runtime_checkpoints
    MODIFY COLUMN encrypted_payload LONGTEXT NOT NULL
        COMMENT 'AES-GCM认证加密状态密文，完整保存较大运行状态';
