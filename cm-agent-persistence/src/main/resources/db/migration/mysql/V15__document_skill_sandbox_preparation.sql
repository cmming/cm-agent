-- 与 PostgreSQL 同版更新状态原生注释；保持已有长度、非空约束和存量值。
ALTER TABLE skill_load_records MODIFY COLUMN status VARCHAR(32) NOT NULL
    COMMENT '技能读取成功失败拒绝或沙箱资源准备状态，准备不代表交付模型或脚本成功';
