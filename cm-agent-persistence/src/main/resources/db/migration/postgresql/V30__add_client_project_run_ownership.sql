CREATE TABLE client_project_run_ownership (
    tenant_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    epoch BIGINT NOT NULL,
    phase VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES client_project_runs(tenant_id, run_id),
    CHECK (epoch >= 1),
    CHECK (phase IN ('EXECUTING', 'RECOVERING', 'CLOSED'))
);
COMMENT ON TABLE client_project_run_ownership IS '设备项目运行的持久实例归属和防护代次';
COMMENT ON COLUMN client_project_run_ownership.tenant_id IS '可信租户标识';
COMMENT ON COLUMN client_project_run_ownership.run_id IS '设备项目运行标识';
COMMENT ON COLUMN client_project_run_ownership.owner_id IS '运行原发起主体';
COMMENT ON COLUMN client_project_run_ownership.instance_id IS '当前执行或恢复实例随机标识';
COMMENT ON COLUMN client_project_run_ownership.epoch IS '单调递增的运行防护代次';
COMMENT ON COLUMN client_project_run_ownership.phase IS '执行、受限恢复或关闭阶段';
COMMENT ON COLUMN client_project_run_ownership.expires_at IS '由数据库时间计算的归属到期时间';
COMMENT ON COLUMN client_project_run_ownership.created_at IS '首次创建归属的数据库时间';
COMMENT ON COLUMN client_project_run_ownership.updated_at IS '最近推进归属的数据库时间';

CREATE INDEX idx_client_project_run_ownership_expiry
    ON client_project_run_ownership(phase, expires_at, tenant_id, run_id);
