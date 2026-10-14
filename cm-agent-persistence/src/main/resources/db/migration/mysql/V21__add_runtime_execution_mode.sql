-- 系统部署元数据只记录一个数据集的策略；没有租户资源或凭据，不开放租户写入。
CREATE TABLE runtime_execution_modes (
    id SMALLINT NOT NULL COMMENT '全数据集模式记录的固定标识，值为一',
    execution_mode VARCHAR(16) NOT NULL COMMENT '当前部署执行模式，react 或 harness',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '部署模式最后切换时间',
    PRIMARY KEY (id),
    CONSTRAINT ck_runtime_mode_id CHECK (id = 1),
    CONSTRAINT ck_runtime_mode_value CHECK (execution_mode IN ('react', 'harness'))
) COMMENT='全数据集执行策略与排空切换门禁';
INSERT INTO runtime_execution_modes (id, execution_mode) VALUES (1, 'react');

