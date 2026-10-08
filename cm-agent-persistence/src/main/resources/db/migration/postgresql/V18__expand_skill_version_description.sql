-- 对齐解析器的 1024 字符约束，避免合法技能包在版本写入阶段失败；既有值与发布指针保持原样。
ALTER TABLE skill_versions ALTER COLUMN description TYPE VARCHAR(1024);
COMMENT ON COLUMN skill_versions.description IS '供模型选择技能的描述，最多1024个Unicode字符';
