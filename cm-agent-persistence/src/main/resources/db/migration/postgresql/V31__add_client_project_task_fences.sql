CREATE TABLE client_project_task_fences (
    tenant_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    epoch BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id, task_id),
    FOREIGN KEY (tenant_id, task_id) REFERENCES client_project_tasks(tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES client_project_run_ownership(tenant_id, run_id),
    CHECK (epoch >= 1)
);
COMMENT ON TABLE client_project_task_fences IS '设备项目任务固定的可信运行实例和防护代次';
COMMENT ON COLUMN client_project_task_fences.tenant_id IS '可信租户标识';
COMMENT ON COLUMN client_project_task_fences.task_id IS '固定的设备项目任务标识';
COMMENT ON COLUMN client_project_task_fences.run_id IS '任务所属设备项目运行标识';
COMMENT ON COLUMN client_project_task_fences.owner_id IS '运行和任务的原认证主体';
COMMENT ON COLUMN client_project_task_fences.instance_id IS '任务入队时的服务端实例标识';
COMMENT ON COLUMN client_project_task_fences.epoch IS '任务入队时的可信运行防护代次';
COMMENT ON COLUMN client_project_task_fences.created_at IS '数据库记录该代次的时间';
CREATE INDEX idx_client_project_task_fences_run ON client_project_task_fences(tenant_id, run_id, epoch);

CREATE TABLE client_project_run_fence_acknowledgements (
    tenant_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    epoch BIGINT NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    device_id VARCHAR(36) NOT NULL,
    phase VARCHAR(16) NOT NULL,
    acknowledged_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id, run_id, epoch),
    FOREIGN KEY (tenant_id, run_id) REFERENCES client_project_run_ownership(tenant_id, run_id),
    FOREIGN KEY (tenant_id, device_id) REFERENCES local_client_devices(tenant_id, id),
    CHECK (epoch >= 1),
    CHECK (phase IN ('EXECUTING', 'RECOVERING'))
);
COMMENT ON TABLE client_project_run_fence_acknowledgements IS '签名设备确认已持久记录的运行代次';
COMMENT ON COLUMN client_project_run_fence_acknowledgements.tenant_id IS '可信租户标识';
COMMENT ON COLUMN client_project_run_fence_acknowledgements.run_id IS '已固定来源的运行标识';
COMMENT ON COLUMN client_project_run_fence_acknowledgements.epoch IS '设备确认的单调防护代次';
COMMENT ON COLUMN client_project_run_fence_acknowledgements.owner_id IS '运行原发起主体';
COMMENT ON COLUMN client_project_run_fence_acknowledgements.device_id IS '完成签名确认的已配对设备';
COMMENT ON COLUMN client_project_run_fence_acknowledgements.phase IS '设备确认时的执行或受限恢复阶段';
COMMENT ON COLUMN client_project_run_fence_acknowledgements.acknowledged_at IS '数据库记录确认的时间';
