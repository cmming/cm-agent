-- 导入输入独立于 Skill 产物；租户行锁保证配额原子性，逻辑删除不提前释放存储容量。
ALTER TABLE conversation_workspace_bindings DROP CONSTRAINT conversation_workspace_bindings_selection_mode_check;
ALTER TABLE conversation_workspace_bindings ADD CONSTRAINT chk_conversation_workspace_bindings_mode CHECK (selection_mode IN ('DEFAULT','SELECTED','NONE','IMPORTED'));
ALTER TABLE conversation_workspace_bindings ADD CONSTRAINT chk_conversation_workspace_bindings_identity CHECK ((selection_mode IN ('SELECTED','IMPORTED') AND workspace_id IS NOT NULL) OR (selection_mode IN ('DEFAULT','NONE') AND workspace_id IS NULL));
ALTER TABLE conversation_workspace_bindings ADD CONSTRAINT chk_conversation_workspace_bindings_import CHECK (selection_mode<>'IMPORTED' OR (directory='' AND CHAR_LENGTH(mapping_digest)=64));
COMMENT ON COLUMN conversation_workspace_bindings.selection_mode IS '来源为旧映射、服务器登记目录、不使用或本机只读导入';
COMMENT ON COLUMN conversation_workspace_bindings.workspace_id IS '登记目录标识或导入批次标识，由来源模式区分命名空间';
COMMENT ON COLUMN conversation_workspace_bindings.mapping_digest IS '目录声明或导入清单的固定身份摘要';
COMMENT ON COLUMN conversation_workspace_bindings.directory IS '授权的服务器相对目录，导入来源必须为空';
ALTER TABLE run_workspace_selections DROP CONSTRAINT run_workspace_selections_selection_mode_check;
ALTER TABLE run_workspace_selections ADD CONSTRAINT chk_run_workspace_selections_mode CHECK (selection_mode IN ('DEFAULT','SELECTED','NONE','IMPORTED'));
ALTER TABLE run_workspace_selections ADD CONSTRAINT chk_run_workspace_selections_identity CHECK ((selection_mode IN ('SELECTED','IMPORTED') AND workspace_id IS NOT NULL) OR (selection_mode IN ('DEFAULT','NONE') AND workspace_id IS NULL));
ALTER TABLE run_workspace_selections ADD CONSTRAINT chk_run_workspace_selections_import CHECK (selection_mode<>'IMPORTED' OR (directory='' AND CHAR_LENGTH(mapping_digest)=64));
COMMENT ON COLUMN run_workspace_selections.selection_mode IS '来源为旧映射、服务器登记目录、不使用或本机只读导入';
COMMENT ON COLUMN run_workspace_selections.workspace_id IS '登记目录标识或导入批次标识，由来源模式区分命名空间';
COMMENT ON COLUMN run_workspace_selections.mapping_digest IS '目录声明或导入清单的固定身份摘要';
COMMENT ON COLUMN run_workspace_selections.directory IS '授权的服务器相对目录，导入来源必须为空';

CREATE TABLE workspace_imports (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    principal_id VARCHAR(200) NOT NULL,
    agent_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    manifest_digest VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    total_bytes BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    reclaimed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CHECK (state IN ('DRAFT','READY','REVOKED','EXPIRED')),
    CHECK (version>=1 AND total_bytes BETWEEN 1 AND 65536),
    CHECK (NOT reclaimed OR state IN ('REVOKED','EXPIRED'))
);
COMMENT ON TABLE workspace_imports IS '用户电脑只读资料导入批次，智能体采用服务校验的软引用';
COMMENT ON COLUMN workspace_imports.id IS '服务端生成且不复用的批次标识';
COMMENT ON COLUMN workspace_imports.tenant_id IS '认证上下文中的可信租户';
COMMENT ON COLUMN workspace_imports.principal_id IS '原上传主体，权限不隐式扩展给同租户其他主体';
COMMENT ON COLUMN workspace_imports.agent_id IS '归属智能体软引用，由服务核实';
COMMENT ON COLUMN workspace_imports.display_name IS '不参与对象寻址的展示名称';
COMMENT ON COLUMN workspace_imports.manifest_digest IS '绑定来源归属与不可变文件清单的摘要';
COMMENT ON COLUMN workspace_imports.state IS '草稿、就绪、撤销或过期状态';
COMMENT ON COLUMN workspace_imports.version IS '从一开始的乐观条件版本';
COMMENT ON COLUMN workspace_imports.total_bytes IS '预留明文字节，实际密文删除后才归还配额';
COMMENT ON COLUMN workspace_imports.created_at IS '服务端创建时间';
COMMENT ON COLUMN workspace_imports.expires_at IS '当前固定截止时间，读取不会延长';
COMMENT ON COLUMN workspace_imports.reclaimed IS '是否已经确认物理密文删除';

CREATE TABLE workspace_import_files (
    tenant_id VARCHAR(36) NOT NULL,
    import_id VARCHAR(36) NOT NULL,
    file_id VARCHAR(36) NOT NULL,
    ordinal INTEGER NOT NULL,
    relative_path VARCHAR(160) NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    uploaded BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id,import_id,file_id),
    UNIQUE (tenant_id,import_id,ordinal),
    FOREIGN KEY (tenant_id,import_id) REFERENCES workspace_imports(tenant_id,id),
    CHECK (ordinal BETWEEN 0 AND 7 AND size_bytes BETWEEN 1 AND 32768)
);
COMMENT ON TABLE workspace_import_files IS '导入批次不可变文件清单，不含资料正文';
COMMENT ON COLUMN workspace_import_files.tenant_id IS '继承批次的可信租户';
COMMENT ON COLUMN workspace_import_files.import_id IS '归属的导入批次';
COMMENT ON COLUMN workspace_import_files.file_id IS '服务端生成的文件对象标识';
COMMENT ON COLUMN workspace_import_files.ordinal IS '固定清单顺序，用于重建相同摘要';
COMMENT ON COLUMN workspace_import_files.relative_path IS '去除选择根后的相对路径，只作元数据不参与磁盘寻址';
COMMENT ON COLUMN workspace_import_files.size_bytes IS '声明并须由实际读取复核的明文字节';
COMMENT ON COLUMN workspace_import_files.sha256 IS '声明并须由服务端重算的明文摘要';
COMMENT ON COLUMN workspace_import_files.uploaded IS '是否已确认完整持久化的不可变密文';

CREATE TABLE workspace_import_leases (
    tenant_id VARCHAR(36) NOT NULL,
    import_id VARCHAR(36) NOT NULL,
    token VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    lease_until TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id,import_id,token),
    FOREIGN KEY (tenant_id,import_id) REFERENCES workspace_imports(tenant_id,id),
    CHECK (kind IN ('UPLOAD','READ','GC'))
);
COMMENT ON TABLE workspace_import_leases IS '导入上传、在途运行读取及回收租约';
COMMENT ON COLUMN workspace_import_leases.tenant_id IS '继承批次的可信租户';
COMMENT ON COLUMN workspace_import_leases.import_id IS '受租约保护的导入批次';
COMMENT ON COLUMN workspace_import_leases.token IS '随机围栏令牌，迟到持有者不能覆盖新租约';
COMMENT ON COLUMN workspace_import_leases.kind IS '上传独占、共享读取或回收独占';
COMMENT ON COLUMN workspace_import_leases.lease_until IS '租约截止时间，过期不再允许确认结果';
CREATE INDEX idx_workspace_imports_owner ON workspace_imports(tenant_id,principal_id,reclaimed);
CREATE INDEX idx_workspace_imports_gc ON workspace_imports(reclaimed,expires_at);
