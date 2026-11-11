CREATE TABLE client_project_task_fences (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信租户标识',
    task_id VARCHAR(36) NOT NULL COMMENT '固定的设备项目任务标识',
    run_id VARCHAR(36) NOT NULL COMMENT '任务所属设备项目运行标识',
    owner_id VARCHAR(200) NOT NULL COMMENT '运行和任务的原认证主体',
    instance_id VARCHAR(36) NOT NULL COMMENT '任务入队时的服务端实例标识',
    epoch BIGINT NOT NULL COMMENT '任务入队时的可信运行防护代次',
    created_at TIMESTAMP(6) NOT NULL COMMENT '数据库记录该代次的时间',
    PRIMARY KEY (tenant_id, task_id),
    FOREIGN KEY (tenant_id, task_id) REFERENCES client_project_tasks(tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES client_project_run_ownership(tenant_id, run_id),
    CHECK (epoch >= 1),
    INDEX idx_client_project_task_fences_run (tenant_id, run_id, epoch)
) COMMENT='设备项目任务固定的可信运行实例和防护代次';

CREATE TABLE client_project_run_fence_acknowledgements (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信租户标识',
    run_id VARCHAR(36) NOT NULL COMMENT '已固定来源的运行标识',
    epoch BIGINT NOT NULL COMMENT '设备确认的单调防护代次',
    owner_id VARCHAR(200) NOT NULL COMMENT '运行原发起主体',
    device_id VARCHAR(36) NOT NULL COMMENT '完成签名确认的已配对设备',
    phase VARCHAR(16) NOT NULL COMMENT '设备确认时的执行或受限恢复阶段',
    acknowledged_at TIMESTAMP(6) NOT NULL COMMENT '数据库记录确认的时间',
    PRIMARY KEY (tenant_id, run_id, epoch),
    FOREIGN KEY (tenant_id, run_id) REFERENCES client_project_run_ownership(tenant_id, run_id),
    FOREIGN KEY (tenant_id, device_id) REFERENCES local_client_devices(tenant_id, id),
    CHECK (epoch >= 1),
    CHECK (phase IN ('EXECUTING', 'RECOVERING'))
) COMMENT='签名设备确认已持久记录的运行代次';
