-- 新增沙箱准备状态仅扩展既有 VARCHAR 状态语义，不改动表结构或历史迁移。
COMMENT ON COLUMN skill_load_records.status IS '技能读取成功失败拒绝或沙箱资源准备状态，准备不代表交付模型或脚本成功';
