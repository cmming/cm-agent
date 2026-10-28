-- 导入输入独立于 Skill 产物；租户行锁保证配额原子性，逻辑删除不提前释放存储容量。
-- V22 的匿名 CHECK 名称由 MySQL 生成，从元数据定位而非依赖生成序号。
SET @cm_workspace_check = (SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='conversation_workspace_bindings' AND cc.CHECK_CLAUSE LIKE '%selection_mode%' LIMIT 1);
SET @cm_workspace_sql = CONCAT('ALTER TABLE conversation_workspace_bindings DROP CHECK `', @cm_workspace_check, '`');
PREPARE cm_workspace_stmt FROM @cm_workspace_sql;
EXECUTE cm_workspace_stmt;
DEALLOCATE PREPARE cm_workspace_stmt;
ALTER TABLE conversation_workspace_bindings ADD CONSTRAINT chk_conversation_workspace_bindings_mode CHECK (selection_mode IN ('DEFAULT','SELECTED','NONE','IMPORTED'));
ALTER TABLE conversation_workspace_bindings ADD CONSTRAINT chk_conversation_workspace_bindings_identity CHECK ((selection_mode IN ('SELECTED','IMPORTED') AND workspace_id IS NOT NULL) OR (selection_mode IN ('DEFAULT','NONE') AND workspace_id IS NULL));
ALTER TABLE conversation_workspace_bindings ADD CONSTRAINT chk_conversation_workspace_bindings_import CHECK (selection_mode<>'IMPORTED' OR (directory='' AND CHAR_LENGTH(mapping_digest)=64));
ALTER TABLE conversation_workspace_bindings MODIFY selection_mode VARCHAR(16) NOT NULL COMMENT '来源为旧映射、服务器登记目录、不使用或本机只读导入';
ALTER TABLE conversation_workspace_bindings MODIFY workspace_id VARCHAR(36) COMMENT '登记目录标识或导入批次标识，由来源模式区分命名空间';
ALTER TABLE conversation_workspace_bindings MODIFY mapping_digest VARCHAR(64) NOT NULL COMMENT '目录声明或导入清单的固定身份摘要';
ALTER TABLE conversation_workspace_bindings MODIFY directory VARCHAR(160) NOT NULL COMMENT '授权的服务器相对目录，导入来源必须为空';
-- V22 的匿名 CHECK 名称由 MySQL 生成，从元数据定位而非依赖生成序号。
SET @cm_workspace_check = (SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='run_workspace_selections' AND cc.CHECK_CLAUSE LIKE '%selection_mode%' LIMIT 1);
SET @cm_workspace_sql = CONCAT('ALTER TABLE run_workspace_selections DROP CHECK `', @cm_workspace_check, '`');
PREPARE cm_workspace_stmt FROM @cm_workspace_sql;
EXECUTE cm_workspace_stmt;
DEALLOCATE PREPARE cm_workspace_stmt;
ALTER TABLE run_workspace_selections ADD CONSTRAINT chk_run_workspace_selections_mode CHECK (selection_mode IN ('DEFAULT','SELECTED','NONE','IMPORTED'));
ALTER TABLE run_workspace_selections ADD CONSTRAINT chk_run_workspace_selections_identity CHECK ((selection_mode IN ('SELECTED','IMPORTED') AND workspace_id IS NOT NULL) OR (selection_mode IN ('DEFAULT','NONE') AND workspace_id IS NULL));
ALTER TABLE run_workspace_selections ADD CONSTRAINT chk_run_workspace_selections_import CHECK (selection_mode<>'IMPORTED' OR (directory='' AND CHAR_LENGTH(mapping_digest)=64));
ALTER TABLE run_workspace_selections MODIFY selection_mode VARCHAR(16) NOT NULL COMMENT '来源为旧映射、服务器登记目录、不使用或本机只读导入';
ALTER TABLE run_workspace_selections MODIFY workspace_id VARCHAR(36) COMMENT '登记目录标识或导入批次标识，由来源模式区分命名空间';
ALTER TABLE run_workspace_selections MODIFY mapping_digest VARCHAR(64) NOT NULL COMMENT '目录声明或导入清单的固定身份摘要';
ALTER TABLE run_workspace_selections MODIFY directory VARCHAR(160) NOT NULL COMMENT '授权的服务器相对目录，导入来源必须为空';

CREATE TABLE workspace_imports (
    id VARCHAR(36) NOT NULL COMMENT '服务端生成且不复用的批次标识',
    tenant_id VARCHAR(36) NOT NULL COMMENT '认证上下文中的可信租户',
    principal_id VARCHAR(200) NOT NULL COMMENT '原上传主体，权限不隐式扩展给同租户其他主体',
    agent_id VARCHAR(36) NOT NULL COMMENT '归属智能体软引用，由服务核实',
    display_name VARCHAR(120) NOT NULL COMMENT '不参与对象寻址的展示名称',
    manifest_digest VARCHAR(64) NOT NULL COMMENT '绑定来源归属与不可变文件清单的摘要',
    state VARCHAR(16) NOT NULL COMMENT '草稿、就绪、撤销或过期状态',
    version BIGINT NOT NULL COMMENT '从一开始的乐观条件版本',
    total_bytes BIGINT NOT NULL COMMENT '预留明文字节，实际密文删除后才归还配额',
    created_at TIMESTAMP(6) NOT NULL COMMENT '服务端创建时间',
    expires_at TIMESTAMP(6) NOT NULL COMMENT '当前固定截止时间，读取不会延长',
    reclaimed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已经确认物理密文删除',
    PRIMARY KEY (tenant_id,id),
    FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CHECK (state IN ('DRAFT','READY','REVOKED','EXPIRED')),
    CHECK (version>=1 AND total_bytes BETWEEN 1 AND 65536),
    CHECK (NOT reclaimed OR state IN ('REVOKED','EXPIRED'))
) COMMENT='用户电脑只读资料导入批次，智能体采用服务校验的软引用';

CREATE TABLE workspace_import_files (
    tenant_id VARCHAR(36) NOT NULL COMMENT '继承批次的可信租户',
    import_id VARCHAR(36) NOT NULL COMMENT '归属的导入批次',
    file_id VARCHAR(36) NOT NULL COMMENT '服务端生成的文件对象标识',
    ordinal INTEGER NOT NULL COMMENT '固定清单顺序，用于重建相同摘要',
    relative_path VARCHAR(160) NOT NULL COMMENT '去除选择根后的相对路径，只作元数据不参与磁盘寻址',
    size_bytes INTEGER NOT NULL COMMENT '声明并须由实际读取复核的明文字节',
    sha256 VARCHAR(64) NOT NULL COMMENT '声明并须由服务端重算的明文摘要',
    uploaded BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已确认完整持久化的不可变密文',
    PRIMARY KEY (tenant_id,import_id,file_id),
    UNIQUE (tenant_id,import_id,ordinal),
    FOREIGN KEY (tenant_id,import_id) REFERENCES workspace_imports(tenant_id,id),
    CHECK (ordinal BETWEEN 0 AND 7 AND size_bytes BETWEEN 1 AND 32768)
) COMMENT='导入批次不可变文件清单，不含资料正文';

CREATE TABLE workspace_import_leases (
    tenant_id VARCHAR(36) NOT NULL COMMENT '继承批次的可信租户',
    import_id VARCHAR(36) NOT NULL COMMENT '受租约保护的导入批次',
    token VARCHAR(36) NOT NULL COMMENT '随机围栏令牌，迟到持有者不能覆盖新租约',
    kind VARCHAR(16) NOT NULL COMMENT '上传独占、共享读取或回收独占',
    lease_until TIMESTAMP(6) NOT NULL COMMENT '租约截止时间，过期不再允许确认结果',
    PRIMARY KEY (tenant_id,import_id,token),
    FOREIGN KEY (tenant_id,import_id) REFERENCES workspace_imports(tenant_id,id),
    CHECK (kind IN ('UPLOAD','READ','GC'))
) COMMENT='导入上传、在途运行读取及回收租约';
CREATE INDEX idx_workspace_imports_owner ON workspace_imports(tenant_id,principal_id,reclaimed);
CREATE INDEX idx_workspace_imports_gc ON workspace_imports(reclaimed,expires_at);
