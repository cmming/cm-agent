CREATE TABLE client_project_run_ownership (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信租户标识',
    run_id VARCHAR(36) NOT NULL COMMENT '设备项目运行标识',
    owner_id VARCHAR(200) NOT NULL COMMENT '运行原发起主体',
    instance_id VARCHAR(36) NOT NULL COMMENT '当前执行或恢复实例随机标识',
    epoch BIGINT NOT NULL COMMENT '单调递增的运行防护代次',
    phase VARCHAR(16) NOT NULL COMMENT '执行、受限恢复或关闭阶段',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '由数据库时间计算的归属到期时间',
    created_at TIMESTAMP(6) NOT NULL COMMENT '首次创建归属的数据库时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近推进归属的数据库时间',
    PRIMARY KEY (tenant_id, run_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES client_project_runs(tenant_id, run_id),
    CHECK (epoch >= 1),
    CHECK (phase IN ('EXECUTING', 'RECOVERING', 'CLOSED'))
) COMMENT='设备项目运行的持久实例归属和防护代次';

CREATE INDEX idx_client_project_run_ownership_expiry
    ON client_project_run_ownership(phase, expires_at, tenant_id, run_id);
