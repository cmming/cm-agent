-- 系统部署元数据只记录一个数据集的策略；没有租户资源或凭据，不开放租户写入。
CREATE TABLE runtime_execution_modes (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    execution_mode VARCHAR(16) NOT NULL CHECK (execution_mode IN ('react', 'harness')),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE runtime_execution_modes IS '全数据集执行策略与排空切换门禁';
COMMENT ON COLUMN runtime_execution_modes.id IS '全数据集模式记录的固定标识，值为一';
COMMENT ON COLUMN runtime_execution_modes.execution_mode IS '当前部署执行模式，react 或 harness';
COMMENT ON COLUMN runtime_execution_modes.updated_at IS '部署模式最后切换时间';
INSERT INTO runtime_execution_modes (id, execution_mode) VALUES (1, 'react');

