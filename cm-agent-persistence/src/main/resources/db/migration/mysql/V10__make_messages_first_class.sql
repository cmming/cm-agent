ALTER TABLE conversations
    ADD COLUMN updated_at TIMESTAMP NULL COMMENT '最后消息或标题更新时间';
UPDATE conversations SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE conversations
    MODIFY COLUMN updated_at TIMESTAMP NOT NULL COMMENT '最后消息或标题更新时间';

ALTER TABLE messages
    ADD COLUMN sequence_no BIGINT NULL COMMENT '会话内连续递增序号',
    ADD COLUMN sender_name VARCHAR(160) NULL COMMENT '发送方名称快照',
    ADD COLUMN content_blocks_json TEXT NULL COMMENT '有序受控内容块 JSON',
    ADD COLUMN run_id CHAR(36) NULL COMMENT '关联运行标识，可为空';

UPDATE messages AS target
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY created_at, id) AS sequence_no
    FROM messages
) AS ranked ON target.id = ranked.id
SET target.sequence_no = ranked.sequence_no;

UPDATE messages
SET content_blocks_json = JSON_ARRAY(JSON_OBJECT(
        'type', 'TEXT',
        'text', content,
        'toolCallId', NULL,
        'toolName', NULL,
        'status', NULL
    ))
WHERE content_blocks_json IS NULL;

ALTER TABLE messages
    MODIFY COLUMN sequence_no BIGINT NOT NULL COMMENT '会话内连续递增序号',
    MODIFY COLUMN content_blocks_json TEXT NOT NULL COMMENT '有序受控内容块 JSON';

CREATE INDEX idx_conversations_tenant_agent_updated
    ON conversations (tenant_id, agent_id, updated_at, id);
CREATE UNIQUE INDEX ux_messages_tenant_conversation_sequence
    ON messages (tenant_id, conversation_id, sequence_no);
CREATE INDEX idx_messages_tenant_run ON messages (tenant_id, run_id);

ALTER TABLE messages
    ADD CONSTRAINT fk_messages_run
        FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id);
