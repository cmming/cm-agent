package com.cmagent.server.config;

import com.cmagent.core.repository.WorkspaceImportRepository;
import com.cmagent.core.runtime.WorkspaceImportStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class WorkspaceImportConfigurationTest {
    @Test void 默认不创建磁盘存储且不接受顶层关闭时启用导入() {
        var parent = new WorkspaceContextProperties(); parent.validate();
        assertThat(parent.getImports().isEnabled()).isFalse(); assertThat(parent.getImports().limits(parent).maxFiles()).isEqualTo(4);
        new ApplicationContextRunner().withUserConfiguration(WorkspaceImportConfiguration.class).withBean(WorkspaceContextProperties.class, () -> parent)
                .run(context -> { assertThat(context).hasSingleBean(WorkspaceImportRepository.class); assertThat(context).doesNotHaveBean(WorkspaceImportStorage.class); });
        parent.getImports().setEnabled(true);
        assertThatThrownBy(parent::validate).isInstanceOf(IllegalStateException.class).hasMessage("导入策略、私有卷或独立密钥未正确配置");
    }
    @Test void 配额期限和密钥错误不泄露配置值() {
        var parent = new WorkspaceContextProperties(); var imports = parent.getImports();
        imports.setReadyTtl(Duration.ofDays(31)); assertThatThrownBy(parent::validate).isInstanceOf(IllegalStateException.class);
        imports.setReadyTtl(Duration.ofDays(30)); imports.setRootDirectory("合成私有卷"); imports.setEncryptionKey("合成非法密钥");
        assertThatThrownBy(parent::validate).hasMessage("导入策略、私有卷或独立密钥未正确配置");
        imports.setRootDirectory(""); imports.setMaxImportsPerOwner(9);
        assertThatThrownBy(parent::validate).isInstanceOf(IllegalStateException.class);
    }
}
