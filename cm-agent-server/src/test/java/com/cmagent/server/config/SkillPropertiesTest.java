package com.cmagent.server.config;

import com.cmagent.server.service.SkillPackageLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPropertiesTest {

    @Test
    void 沙箱默认关闭且开启时能力白名单自动包含Python() {
        SkillProperties properties = new SkillProperties();
        assertThat(properties.getSandbox().isEnabled()).isFalse();
        assertThat(properties.getAllowedResourceTypes()).doesNotContain(".py");
        properties.getSandbox().setEnabled(true);
        properties.validate();
        assertThat(properties.getAllowedResourceTypes()).contains(".py");
        properties.getSandbox().setTimeout(java.time.Duration.ofSeconds(21));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 默认关闭并提供当前服务端容量快照() {
        SkillProperties properties = new SkillProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.toPackageLimits()).isEqualTo(new SkillPackageLimits(
                4 * 1024 * 1024, 4 * 1024 * 1024, 256, 32 * 1024, 256 * 1024, 240));
        assertThat(properties.getMaxBoundSkills()).isEqualTo(20);
        assertThat(properties.getMaxRunBytes()).isEqualTo(8 * 1024 * 1024);
        assertThat(properties.getMaxLoadAttempts()).isEqualTo(32);
        assertThat(properties.getMaxLoadedBytes()).isEqualTo(16 * 1024 * 1024);
        assertThat(properties.getAllowedResourceTypes()).containsExactly(
                ".md", ".txt", ".json", ".yaml", ".yml", ".csv");
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
    void 拒绝零值和超过服务端硬上限() {
        SkillProperties zero = new SkillProperties();
        zero.setMaxFiles(0);
        SkillProperties expanded = new SkillProperties();
        expanded.setMaxZipBytes(16 * 1024 * 1024 + 1);

        assertThatThrownBy(zero::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-files");
        assertThatThrownBy(expanded::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-zip-bytes");
    }

    @Test
    void 部署值可在硬上限内放大但不能越界() {
        SkillProperties properties = new SkillProperties();
        properties.setMaxZipBytes(16 * 1024 * 1024);
        properties.setMaxFiles(512);
        properties.setMaxResourceBytes(1024 * 1024);
        properties.setMaxLoadedBytes(16 * 1024 * 1024);
        properties.validate();

        properties.setMaxFiles(513);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-files");
        properties.setMaxFiles(512);
        properties.setMaxResourceBytes(1024 * 1024 + 1);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-resource-bytes");
        properties.setMaxResourceBytes(1024 * 1024);
        properties.setMaxLoadedBytes(16 * 1024 * 1024 + 1);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("max-loaded-bytes");
    }

    @Test
    void 拒绝空资源类型和非扩展名() {
        SkillProperties empty = new SkillProperties();
        empty.setAllowedResourceTypes(List.of());

        assertThatThrownBy(empty::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-resource-types");

        SkillProperties invalid = new SkillProperties();
        invalid.setAllowedResourceTypes(List.of("md"));

        assertThatThrownBy(invalid::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-resource-types");
    }
}
