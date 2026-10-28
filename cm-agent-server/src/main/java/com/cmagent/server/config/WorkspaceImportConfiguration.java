package com.cmagent.server.config;

import com.cmagent.core.repository.WorkspaceImportRepository;
import com.cmagent.core.runtime.WorkspaceImportStorage;
import com.cmagent.persistence.JdbcWorkspaceImportRepository;
import com.cmagent.server.store.InMemoryWorkspaceImportRepository;
import com.cmagent.server.runtime.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 导入域独立装配；未配置持久卷时不创建目录，已关闭新上传的卷仍参与到期回收。 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceImportConfiguration {
    /** @return 仅 local/test 的内存元数据，生产由已有 profile 安全门禁拒绝 memory */
    @Bean @ConditionalOnProperty(prefix="cm-agent.persistence", name="mode", havingValue="memory", matchIfMissing=true)
    WorkspaceImportRepository memoryWorkspaceImportRepository() { return new InMemoryWorkspaceImportRepository(); }
    /** @return 同数据源短事务及跨实例配额锁 */
    @Bean @ConditionalOnProperty(prefix="cm-agent.persistence", name="mode", havingValue="jdbc")
    WorkspaceImportRepository jdbcWorkspaceImportRepository(JdbcClient jdbc, TransactionTemplate tx) { return new JdbcWorkspaceImportRepository(jdbc, tx); }
    /** @return 具名清理策略，调度表达式不依赖框架自动生成的配置属性名称 */
    @Bean WorkspaceImportProperties workspaceImportPolicy(WorkspaceContextProperties properties) { return properties.getImports(); }
    /** @return 独立私有密文卷；配置卷时不因上传关闭而移除旧数据清理能力 */
    @Bean @ConditionalOnMissingBean(WorkspaceImportStorage.class)
    @ConditionalOnProperty(prefix="cm-agent.workspace-context.imports", name="root-directory")
    WorkspaceImportStorage workspaceImportStorage(WorkspaceContextProperties properties) {
        properties.getImports().validate(properties);
        return new FileSystemWorkspaceImportStorage(java.nio.file.Path.of(properties.getImports().getRootDirectory()), properties.getImports().getEncryptionKey());
    }
}
