-- 远端设备项目来源与新控制协议隔离；V26协议4继续表示Server本机项目。
ALTER TABLE runs DROP CHECK chk_run_workspace_protocol;
ALTER TABLE runs ADD CONSTRAINT chk_run_workspace_protocol CHECK (workspace_protocol IN (0,3,4,5));
ALTER TABLE conversations DROP CHECK chk_conversation_workspace_protocol;
ALTER TABLE conversations ADD CONSTRAINT chk_conversation_workspace_protocol CHECK (workspace_protocol IN (0,3,4,5));
ALTER TABLE runs MODIFY workspace_protocol SMALLINT NOT NULL DEFAULT 0 COMMENT '不可变来源见证：零旧来源，三导入，四Server本机项目，五已配对设备项目';
ALTER TABLE conversations MODIFY workspace_protocol SMALLINT NOT NULL DEFAULT 0 COMMENT '会话来源见证：四Server本机项目，五已配对设备项目，缺失固定关联必须拒绝';

CREATE TABLE client_projects (
    id VARCHAR(36) NOT NULL COMMENT 'Server生成的设备项目标识',
    tenant_id VARCHAR(36) NOT NULL COMMENT '认证上下文中的可信租户',
    owner_id VARCHAR(200) NOT NULL COMMENT '精确原主体标识，查询须逐字匹配',
    device_id VARCHAR(36) NOT NULL COMMENT '固定执行项目I/O的已配对设备',
    client_root_id VARCHAR(36) NOT NULL COMMENT '客户端持久化且不回传路径的不透明根标识',
    root_identity VARCHAR(64) NOT NULL COMMENT '客户端目录对象身份的SHA256摘要',
    display_name VARCHAR(120) NOT NULL COMMENT '项目页受限展示名，不参与路径寻址',
    state VARCHAR(16) NOT NULL COMMENT '活动或归档状态，不代表设备实时在线或根有效',
    version BIGINT NOT NULL COMMENT '项目元数据条件更新版本，从一开始',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'Server固定项目登记时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近项目元数据修改时间',
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,device_id,client_root_id),
    UNIQUE (tenant_id,device_id,root_identity),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES local_client_devices(tenant_id,id),
    CHECK (state IN ('ACTIVE','ARCHIVED')),
    CHECK (version>=1 AND CHAR_LENGTH(root_identity)=64)
) COMMENT='已配对设备上的长期项目来源，不保存绝对路径或文件正文';

CREATE TABLE client_project_operations (
    id VARCHAR(36) NOT NULL COMMENT '服务端操作编号并由计划任务号固定',
    tenant_id VARCHAR(36) NOT NULL COMMENT '认证上下文中的可信租户',
    owner_id VARCHAR(200) NOT NULL COMMENT '精确原主体标识',
    project_id VARCHAR(36) NOT NULL COMMENT '固定设备项目',
    agent_id VARCHAR(36) NOT NULL COMMENT '固定智能体',
    conversation_id VARCHAR(36) NOT NULL COMMENT '固定会话',
    run_id VARCHAR(36) NOT NULL COMMENT '固定运行',
    undo_of VARCHAR(36) NULL COMMENT '条件撤销时的原成功操作',
    plan_digest VARCHAR(64) NOT NULL COMMENT '原始计划摘要，绑定HIGH审批和条件执行',
    display_digest VARCHAR(64) NOT NULL COMMENT '脱敏预览摘要',
    display_exact BOOLEAN NOT NULL COMMENT '脱敏预览是否完整保留全部实际差异',
    encrypted_plan TEXT NOT NULL COMMENT '认证加密的计划和条件版本',
    state VARCHAR(16) NOT NULL COMMENT '提案、应用、成功、失败、未知或取消状态',
    version BIGINT NOT NULL COMMENT '条件版本，从一开始',
    created_at TIMESTAMP(6) NOT NULL COMMENT '计划形成时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近状态更新时间',
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,owner_id,id),
    FOREIGN KEY (tenant_id,project_id) REFERENCES client_projects(tenant_id,id),
    FOREIGN KEY (conversation_id,tenant_id) REFERENCES conversations(id,tenant_id),
    FOREIGN KEY (run_id,tenant_id) REFERENCES runs(id,tenant_id),
    FOREIGN KEY (tenant_id,undo_of) REFERENCES client_project_operations(tenant_id,id),
    CHECK (plan_digest REGEXP '^[0-9a-f]{64}$' AND display_digest REGEXP '^[0-9a-f]{64}$'),
    CHECK (state IN ('PROPOSED','APPLYING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
    CHECK (version>=1 AND updated_at>=created_at)
) COMMENT='已配对设备项目文件变更的加密计划与条件状态';

CREATE TABLE client_project_commands (
    id VARCHAR(36) NOT NULL COMMENT '服务端命令标识，同时作为高风险工具审批定位标识',
    tenant_id VARCHAR(36) NOT NULL COMMENT '认证上下文中的可信租户',
    owner_id VARCHAR(200) NOT NULL COMMENT '精确原主体标识',
    project_id VARCHAR(36) NOT NULL COMMENT '固定设备项目',
    agent_id VARCHAR(36) NOT NULL COMMENT '固定智能体',
    conversation_id VARCHAR(36) NOT NULL COMMENT '固定会话',
    run_id VARCHAR(36) NOT NULL COMMENT '固定运行',
    plan_digest VARCHAR(64) NOT NULL COMMENT '完整客户端执行清单摘要',
    display_digest VARCHAR(64) NOT NULL COMMENT '精确审批展示摘要',
    display_exact BOOLEAN NOT NULL COMMENT '展示是否覆盖全部可审批命令差异',
    encrypted_plan TEXT NOT NULL COMMENT '认证加密的工具链、可执行文件、参数和环境清单',
    encrypted_output TEXT NOT NULL COMMENT '认证加密的脱敏输出快照，无输出时为空',
    output_available BOOLEAN NOT NULL COMMENT '服务端是否持有可展示输出快照',
    truncated BOOLEAN NOT NULL COMMENT '设备输出是否达到保留上限',
    exit_code INTEGER NULL COMMENT '已确认根进程退出码，未知时为空',
    state VARCHAR(24) NOT NULL COMMENT '提案、排队、运行、停止请求、成功、失败、取消或未知状态',
    stop_requested BOOLEAN NOT NULL COMMENT '是否已经登记停止请求，不代表进程已退出',
    sequence_no BIGINT NOT NULL COMMENT '输出快照单调序号',
    version BIGINT NOT NULL COMMENT '条件状态版本，从一开始',
    created_at TIMESTAMP(6) NOT NULL COMMENT '命令提案形成时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近状态或输出更新时间',
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,owner_id,id),
    FOREIGN KEY (tenant_id,project_id) REFERENCES client_projects(tenant_id,id),
    FOREIGN KEY (conversation_id,tenant_id) REFERENCES conversations(id,tenant_id),
    FOREIGN KEY (run_id,tenant_id) REFERENCES runs(id,tenant_id),
    CHECK (plan_digest REGEXP '^[0-9a-f]{64}$' AND display_digest REGEXP '^[0-9a-f]{64}$'),
    CHECK (state IN ('PROPOSED','QUEUED','RUNNING','STOP_REQUESTED','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
    CHECK (sequence_no>=0 AND version>=1 AND updated_at>=created_at),
    CHECK (state<>'SUCCEEDED' OR exit_code=0)
) COMMENT='已配对设备项目固定工具链命令的加密清单、脱敏输出和条件状态';

CREATE TABLE client_project_tasks (
    id VARCHAR(36) NOT NULL COMMENT '浏览器幂等键和唯一设备任务标识',
    tenant_id VARCHAR(36) NOT NULL COMMENT '任务所属可信租户',
    owner_id VARCHAR(200) NOT NULL COMMENT '创建任务的原认证主体',
    device_id VARCHAR(36) NOT NULL COMMENT '唯一允许领取和回报任务的设备',
    project_id VARCHAR(36) NULL COMMENT '项目任务所属项目，选择根任务为空',
    run_id VARCHAR(36) NULL COMMENT '模型变更任务固定的Run，浏览器任务为空',
    agent_id VARCHAR(36) NULL COMMENT '模型变更任务固定的Agent，浏览器任务为空',
    conversation_id VARCHAR(36) NULL COMMENT '模型变更任务固定的会话，浏览器任务为空',
    operation_id VARCHAR(36) NULL COMMENT '应用任务对应的受保护操作',
    command_id VARCHAR(36) NULL COMMENT '命令任务对应的受保护命令，非命令任务为空',
    kind VARCHAR(24) NOT NULL COMMENT '固定选择或只读任务种类',
    encrypted_request TEXT NOT NULL COMMENT '认证加密后的设备任务请求',
    state VARCHAR(16) NOT NULL COMMENT '待领取、已领取、成功、失败、未知或取消状态',
    encrypted_result TEXT NOT NULL COMMENT '认证加密后的设备成功结果',
    safe_error VARCHAR(500) NOT NULL COMMENT '可展示且已脱敏的失败原因',
    created_at TIMESTAMP(6) NOT NULL COMMENT 'Server登记任务时间',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '任务最晚领取和完成期限',
    claimed_at TIMESTAMP(6) NULL COMMENT '设备唯一领取时间',
    completed_at TIMESTAMP(6) NULL COMMENT '任务终态时间',
    version BIGINT NOT NULL COMMENT '条件领取和状态推进版本，从一开始',
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES local_client_devices(tenant_id,id),
    FOREIGN KEY (tenant_id,project_id) REFERENCES client_projects(tenant_id,id),
    FOREIGN KEY (run_id,tenant_id) REFERENCES runs(id,tenant_id),
    FOREIGN KEY (conversation_id,tenant_id) REFERENCES conversations(id,tenant_id),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES client_project_operations(tenant_id,id),
    FOREIGN KEY (tenant_id,command_id) REFERENCES client_project_commands(tenant_id,id),
    CHECK (kind IN ('SELECT_ROOT','LIST','READ','SEARCH','PLAN_CHANGE','APPLY_CHANGE',
                    'LIST_TOOLCHAINS','PLAN_COMMAND','RUN_COMMAND','COMMAND_STATUS','STOP_COMMAND')),
    CHECK (state IN ('PENDING','DISPATCHED','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
    CHECK (version>=1 AND expires_at>created_at),
    CHECK ((kind='SELECT_ROOT' AND project_id IS NULL AND run_id IS NULL AND agent_id IS NULL
            AND conversation_id IS NULL AND operation_id IS NULL AND command_id IS NULL)
        OR (kind IN ('LIST','READ','SEARCH') AND project_id IS NOT NULL AND run_id IS NULL
            AND agent_id IS NULL AND conversation_id IS NULL AND operation_id IS NULL
            AND command_id IS NULL)
        OR (kind='PLAN_CHANGE' AND project_id IS NOT NULL AND run_id IS NOT NULL
            AND agent_id IS NOT NULL AND conversation_id IS NOT NULL AND operation_id IS NULL
            AND command_id IS NULL)
        OR (kind='APPLY_CHANGE' AND project_id IS NOT NULL AND run_id IS NOT NULL
            AND agent_id IS NOT NULL AND conversation_id IS NOT NULL AND operation_id IS NOT NULL
            AND command_id IS NULL)
        OR (kind='LIST_TOOLCHAINS' AND project_id IS NOT NULL AND run_id IS NOT NULL
            AND agent_id IS NOT NULL AND conversation_id IS NOT NULL AND operation_id IS NULL
            AND command_id IS NULL)
        OR (kind='PLAN_COMMAND' AND project_id IS NOT NULL AND run_id IS NOT NULL
            AND agent_id IS NOT NULL AND conversation_id IS NOT NULL AND operation_id IS NULL
            AND command_id IS NULL)
        OR (kind IN ('RUN_COMMAND','COMMAND_STATUS','STOP_COMMAND')
            AND project_id IS NOT NULL AND run_id IS NOT NULL AND agent_id IS NOT NULL
            AND conversation_id IS NOT NULL AND operation_id IS NULL AND command_id IS NOT NULL)),
    CHECK ((state='PENDING' AND claimed_at IS NULL AND completed_at IS NULL)
        OR (state='DISPATCHED' AND claimed_at IS NOT NULL AND completed_at IS NULL)
        OR (state IN ('SUCCEEDED','FAILED','UNKNOWN','CANCELLED') AND completed_at IS NOT NULL))
) COMMENT='已配对设备项目的选择、只读、文件变更和命令任务队列，正文仅存认证加密值';

CREATE TABLE client_project_conversations (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信租户，与会话和项目一致',
    owner_id VARCHAR(200) NOT NULL COMMENT '原创建主体，必须与会话及Run发起人一致',
    project_id VARCHAR(36) NOT NULL COMMENT '固定设备项目',
    agent_id VARCHAR(36) NOT NULL COMMENT '固定智能体软引用',
    conversation_id VARCHAR(36) NOT NULL COMMENT '固定会话软引用',
    device_id VARCHAR(36) NOT NULL COMMENT '固定执行设备',
    client_root_id VARCHAR(36) NOT NULL COMMENT '绑定时固定的客户端不透明根标识',
    root_identity VARCHAR(64) NOT NULL COMMENT '绑定时固定的目录对象身份摘要',
    project_version BIGINT NOT NULL COMMENT '固定来源时的项目版本',
    created_at TIMESTAMP(6) NOT NULL COMMENT '来源固定时间',
    PRIMARY KEY (tenant_id,conversation_id),
    FOREIGN KEY (tenant_id,project_id) REFERENCES client_projects(tenant_id,id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES local_client_devices(tenant_id,id),
    CHECK (project_version>=1 AND CHAR_LENGTH(root_identity)=64)
) COMMENT='已配对设备项目会话的不可变来源绑定';

CREATE TABLE client_project_runs (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信租户，与会话和项目一致',
    owner_id VARCHAR(200) NOT NULL COMMENT '原创建主体，必须与Run发起人一致',
    project_id VARCHAR(36) NOT NULL COMMENT '固定设备项目',
    agent_id VARCHAR(36) NOT NULL COMMENT '固定智能体软引用',
    conversation_id VARCHAR(36) NOT NULL COMMENT '固定会话软引用',
    run_id VARCHAR(36) NOT NULL COMMENT '固定Run软引用',
    device_id VARCHAR(36) NOT NULL COMMENT '固定执行设备',
    client_root_id VARCHAR(36) NOT NULL COMMENT '绑定时固定的客户端不透明根标识',
    root_identity VARCHAR(64) NOT NULL COMMENT '绑定时固定的目录对象身份摘要',
    project_version BIGINT NOT NULL COMMENT '固定来源时的项目版本',
    created_at TIMESTAMP(6) NOT NULL COMMENT '来源固定时间',
    PRIMARY KEY (tenant_id,run_id),
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES client_project_conversations(tenant_id,conversation_id),
    FOREIGN KEY (tenant_id,project_id) REFERENCES client_projects(tenant_id,id),
    FOREIGN KEY (tenant_id,device_id) REFERENCES local_client_devices(tenant_id,id),
    CHECK (project_version>=1 AND CHAR_LENGTH(root_identity)=64)
) COMMENT='已配对设备项目Run的不可变来源绑定';

CREATE INDEX idx_client_projects_owner
    ON client_projects(tenant_id,owner_id,state,updated_at,id);
CREATE INDEX idx_client_project_tasks_pending
    ON client_project_tasks(tenant_id,device_id,state,created_at);
CREATE INDEX idx_client_project_operations_project
    ON client_project_operations(tenant_id,owner_id,project_id,state);
CREATE INDEX idx_client_project_commands_run
    ON client_project_commands(tenant_id,owner_id,run_id,state,updated_at);
CREATE INDEX idx_client_project_commands_project
    ON client_project_commands(tenant_id,owner_id,project_id,state);
CREATE INDEX idx_client_project_tasks_command
    ON client_project_tasks(tenant_id,owner_id,command_id,state);
CREATE INDEX idx_client_project_conversations_project
    ON client_project_conversations(tenant_id,owner_id,project_id,created_at);
