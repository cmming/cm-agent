ALTER TABLE conversations ADD COLUMN updated_at TIMESTAMP;
UPDATE conversations SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE conversations ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE messages ADD COLUMN sequence_no BIGINT;
ALTER TABLE messages ADD COLUMN sender_name VARCHAR(160);
ALTER TABLE messages ADD COLUMN content_blocks_json TEXT;
ALTER TABLE messages ADD COLUMN run_id CHAR(36);

WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY created_at, id) AS sequence_no
    FROM messages
)
UPDATE messages
SET sequence_no = ranked.sequence_no
FROM ranked
WHERE messages.id = ranked.id;

UPDATE messages
SET content_blocks_json = jsonb_build_array(
        jsonb_build_object(
                'type', 'TEXT',
                'text', content,
                'toolCallId', NULL,
                'toolName', NULL,
                'status', NULL
        )
    )::text
WHERE content_blocks_json IS NULL;

ALTER TABLE messages ALTER COLUMN sequence_no SET NOT NULL;
ALTER TABLE messages ALTER COLUMN content_blocks_json SET NOT NULL;

CREATE INDEX idx_conversations_tenant_agent_updated
    ON conversations (tenant_id, agent_id, updated_at, id);
CREATE UNIQUE INDEX ux_messages_tenant_conversation_sequence
    ON messages (tenant_id, conversation_id, sequence_no);
CREATE INDEX idx_messages_tenant_run ON messages (tenant_id, run_id);

ALTER TABLE messages
    ADD CONSTRAINT fk_messages_run
        FOREIGN KEY (run_id, tenant_id) REFERENCES runs (id, tenant_id);

COMMENT ON COLUMN conversations.updated_at IS '最后消息或标题更新时间';
COMMENT ON COLUMN messages.sequence_no IS '会话内连续递增序号';
COMMENT ON COLUMN messages.sender_name IS '发送方名称快照';
COMMENT ON COLUMN messages.content_blocks_json IS '有序受控内容块 JSON';
COMMENT ON COLUMN messages.run_id IS '关联运行标识，可为空';
