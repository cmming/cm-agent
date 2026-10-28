-- 见证与Run首次INSERT原子保存，不随选择行或短期检查点清理；零兼容旧目录/无资料运行。
ALTER TABLE runs ADD COLUMN workspace_protocol SMALLINT NOT NULL DEFAULT 0 COMMENT '不可变工作区协议见证，零为旧目录或无资料，三为不能回退旧映射的本机导入';
ALTER TABLE runs ADD CONSTRAINT chk_run_workspace_protocol CHECK (workspace_protocol IN (0,3));
ALTER TABLE conversations ADD COLUMN workspace_protocol SMALLINT NOT NULL DEFAULT 0 COMMENT '当前工作区来源见证，仅空会话可随显式改绑修改，导入选择缺失不能回退旧映射';
ALTER TABLE conversations ADD CONSTRAINT chk_conversation_workspace_protocol CHECK (workspace_protocol IN (0,3));
