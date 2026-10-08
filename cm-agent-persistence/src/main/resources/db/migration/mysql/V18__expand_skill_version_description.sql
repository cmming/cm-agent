-- 解析器允许描述包含最多 1024 个 Unicode 字符；旧 VARCHAR(500) 会在解析通过后拒绝落库。
-- 只扩容描述，不重写既有技能版本、摘要、资源和发布指针；显式 utf8mb4 保留补充平面字符。
ALTER TABLE skill_versions
    MODIFY COLUMN description VARCHAR(1024) CHARACTER SET utf8mb4 NOT NULL
        COMMENT '供模型选择技能的描述，最多1024个Unicode字符';
