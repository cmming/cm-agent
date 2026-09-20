package com.cmagent.server.config;

import com.cmagent.server.service.SkillPackageLimits;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPropertiesTest {

    @Test
    void 默认关闭并使用设计约定的全部上限() {
        SkillProperties properties = new SkillProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.toPackageLimits()).isEqualTo(SkillPackageLimits.defaults());
        assertThat(properties.getMaxBoundSkills()).isEqualTo(20);
        assertThat(properties.getMaxRunBytes()).isEqualTo(8 * 1024 * 1024);
        assertThat(properties.getMaxLoadAttempts()).isEqualTo(32);
        assertThat(properties.getMaxLoadedBytes()).isEqualTo(256 * 1024);
    }

    @Test
    void 允许管理员调低限制() {
        SkillProperties properties = new SkillProperties();
        properties.setMaxFiles(8);
        properties.setMaxLoadAttempts(4);

        properties.validate();

        assertThat(properties.toPackageLimits().fileCount()).isEqualTo(8);
        assertThat(properties.getMaxLoadAttempts()).isEqualTo(4);
    }

    @Test
    void 拒绝零值和放大默认上限() {
        SkillProperties zero = new SkillProperties();
        zero.setMaxFiles(0);
        SkillProperties expanded = new SkillProperties();
        expanded.setMaxZipBytes(2 * 1024 * 1024 + 1);

        assertThatThrownBy(zero::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-files");
        assertThatThrownBy(expanded::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-zip-bytes");
    }
}
