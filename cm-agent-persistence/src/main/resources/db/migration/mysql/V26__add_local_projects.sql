-- 私有本机项目与旧参考资料来源隔离；五个新表均使用可信租户，路径及操作清单只存密文。
-- 项目协议保留值4须与固定关联同时写入，旧实例不认识该来源时应拒绝接管。
ALTER TABLE runs DROP CHECK chk_run_workspace_protocol;
ALTER TABLE runs ADD CONSTRAINT chk_run_workspace_protocol CHECK (workspace_protocol IN (0,3,4));
ALTER TABLE conversations DROP CHECK chk_conversation_workspace_protocol;
ALTER TABLE conversations ADD CONSTRAINT chk_conversation_workspace_protocol CHECK (workspace_protocol IN (0,3,4));
ALTER TABLE runs MODIFY workspace_protocol SMALLINT NOT NULL DEFAULT 0 COMMENT '不可变来源见证，零为旧来源，三为导入，四为不能回退默认资料的私有本地项目';
ALTER TABLE conversations MODIFY workspace_protocol SMALLINT NOT NULL DEFAULT 0 COMMENT '会话来源见证，私有本地项目为四，固定关联缺失必须拒绝';

CREATE TABLE local_project_roots (
    id VARCHAR(36) NOT NULL COMMENT '受信原生选择产生的根登记标识',
    tenant_id VARCHAR(36) NOT NULL COMMENT '认证上下文中的可信租户',
    owner_id VARCHAR(200) NOT NULL COMMENT '精确原选择主体，不隐式授权其他主体',
    node_id VARCHAR(200) NOT NULL COMMENT '本机执行节点身份，不能由客户端地址替代',
    encrypted_directory MEDIUMTEXT NOT NULL COMMENT '宿主加密的根路径，不保存明文目录',
    identity_digest VARCHAR(64) NOT NULL COMMENT '卷及文件身份的固定摘要',
    created_at TIMESTAMP(6) NOT NULL COMMENT '服务端固定登记时间',
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CHECK (CHAR_LENGTH(identity_digest)=64)
) COMMENT='本机私有项目的加密根登记，不提供明文路径API';

CREATE TABLE local_projects (
    id VARCHAR(36) NOT NULL COMMENT '项目标识，归档后不复用',
    tenant_id VARCHAR(36) NOT NULL COMMENT '认证上下文中的可信租户',
    owner_id VARCHAR(200) NOT NULL COMMENT '项目私有主体，列表必须在分页前过滤',
    display_name VARCHAR(120) NOT NULL COMMENT '项目展示名，不参与路径寻址',
    node_id VARCHAR(200) NOT NULL COMMENT '固定本机执行节点',
    root_binding_id VARCHAR(36) NOT NULL COMMENT '固定根登记引用，创建后不得换根',
    root_identity VARCHAR(64) NOT NULL COMMENT '绑定时固定的目录身份摘要',
    state VARCHAR(16) NOT NULL COMMENT '活动或归档状态，不代表目录实时可用性',
    version BIGINT NOT NULL COMMENT '元数据条件更新版本，从一开始',
    created_at TIMESTAMP(6) NOT NULL COMMENT '固定创建时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近元数据修改时间及分页游标',
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,root_binding_id) REFERENCES local_project_roots(tenant_id,id),
    CHECK (state IN ('ACTIVE','ARCHIVED')),
    CHECK (version>=1 AND CHAR_LENGTH(root_identity)=64)
) COMMENT='本地项目长期登记，归档不删除原目录';

CREATE TABLE local_project_conversations (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信租户，与项目及关联资源一致',
    owner_id VARCHAR(200) NOT NULL COMMENT '原创建主体，需与会话及运行发起人一致',
    project_id VARCHAR(36) NOT NULL COMMENT '固定私有项目',
    agent_id VARCHAR(36) NOT NULL COMMENT '固定智能体软引用，由服务核验',
    conversation_id VARCHAR(36) NOT NULL COMMENT '固定会话软引用，由服务核验',
    root_binding_id VARCHAR(36) NOT NULL COMMENT '固定根登记引用',
    root_identity VARCHAR(64) NOT NULL COMMENT '固定目录身份摘要',
    node_id VARCHAR(200) NOT NULL COMMENT '固定本机执行节点',
    project_version BIGINT NOT NULL COMMENT '固定来源时的项目版本',
    created_at TIMESTAMP(6) NOT NULL COMMENT '来源固定时间',
    PRIMARY KEY (tenant_id,conversation_id),
    FOREIGN KEY (tenant_id,project_id) REFERENCES local_projects(tenant_id,id),
    FOREIGN KEY (tenant_id,root_binding_id) REFERENCES local_project_roots(tenant_id,id),
    CHECK (project_version>=1 AND CHAR_LENGTH(root_identity)=64)
) COMMENT='项目会话固定来源，智能体及会话主体由服务校验软引用';

CREATE TABLE local_project_runs (
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信租户，与项目及关联资源一致',
    owner_id VARCHAR(200) NOT NULL COMMENT '原创建主体，需与会话及运行发起人一致',
    project_id VARCHAR(36) NOT NULL COMMENT '固定私有项目',
    agent_id VARCHAR(36) NOT NULL COMMENT '固定智能体软引用，由服务核验',
    conversation_id VARCHAR(36) NOT NULL COMMENT '固定会话软引用，由服务核验',
    run_id VARCHAR(36) NOT NULL COMMENT '固定运行软引用，由服务验证原主体',
    root_binding_id VARCHAR(36) NOT NULL COMMENT '固定根登记引用',
    root_identity VARCHAR(64) NOT NULL COMMENT '固定目录身份摘要',
    node_id VARCHAR(200) NOT NULL COMMENT '固定本机执行节点',
    project_version BIGINT NOT NULL COMMENT '固定来源时的项目版本',
    created_at TIMESTAMP(6) NOT NULL COMMENT '来源固定时间',
    PRIMARY KEY (tenant_id,run_id),
    FOREIGN KEY (tenant_id,conversation_id) REFERENCES local_project_conversations(tenant_id,conversation_id),
    FOREIGN KEY (tenant_id,project_id) REFERENCES local_projects(tenant_id,id),
    CHECK (project_version>=1 AND CHAR_LENGTH(root_identity)=64)
) COMMENT='项目运行固定来源，不随项目改名或审批恢复替换';

CREATE TABLE local_project_operations (
    id VARCHAR(36) NOT NULL COMMENT '操作幂等标识，不允许重复覆盖',
    tenant_id VARCHAR(36) NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(200) NOT NULL COMMENT '操作原主体，不能由模型切换',
    project_id VARCHAR(36) NOT NULL COMMENT '固定项目',
    agent_id VARCHAR(36) NOT NULL COMMENT '固定智能体软引用',
    conversation_id VARCHAR(36) NOT NULL COMMENT '固定会话引用',
    run_id VARCHAR(36) NOT NULL COMMENT '固定运行引用',
    kind VARCHAR(16) NOT NULL COMMENT '文件变更或命令执行',
    manifest_digest VARCHAR(64) NOT NULL COMMENT '精确变更或执行清单摘要',
    encrypted_payload MEDIUMTEXT NOT NULL COMMENT '受保护的不可变操作清单，不记录明文正文或环境',
    state VARCHAR(24) NOT NULL COMMENT '提案、审批、准备、运行、终态或结果未知',
    version BIGINT NOT NULL COMMENT '操作状态条件版本',
    created_at TIMESTAMP(6) NOT NULL COMMENT '意图建立时间',
    updated_at TIMESTAMP(6) NOT NULL COMMENT '最近状态推进时间',
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id,run_id) REFERENCES local_project_runs(tenant_id,run_id),
    FOREIGN KEY (tenant_id,project_id) REFERENCES local_projects(tenant_id,id),
    CHECK (kind IN ('FILE_CHANGE','COMMAND')),
    CHECK (state IN ('PROPOSED','WAITING_APPROVAL','PREPARED','RUNNING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
    CHECK (version>=1 AND CHAR_LENGTH(manifest_digest)=64)
) COMMENT='文件及进程操作的持久意图和条件状态，不承诺跨资源原子事务';

CREATE INDEX idx_local_projects_owner ON local_projects(tenant_id,owner_id,state,updated_at,id);
CREATE INDEX idx_local_project_ops_run ON local_project_operations(tenant_id,run_id,state);
-- 只登记权限定义，不向任何旧角色自动赋权，本机项目仍须显式授权。
INSERT INTO permissions(code,description) VALUES ('project:read','读取本人获准本地项目文件'),('project:write','审批并修改本人本地项目文件'),('project:execute','审批并执行本人本地项目命令'),('project:manage','登记和归档本人本地项目');

