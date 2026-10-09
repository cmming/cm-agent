package com.cmagent.server.config;
import com.cmagent.core.repository.SkillArtifactRepository;
import com.cmagent.core.runtime.SkillArtifactStorage;
import com.cmagent.persistence.JdbcSkillArtifactRepository;
import com.cmagent.server.runtime.FileSystemSkillArtifactStorage;
import com.cmagent.server.store.InMemorySkillArtifactRepository;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
/** 元数据沿用 memory/JDBC 分层，关闭文件功能时不创建卷目录。 */
@Configuration(proxyBeanMethods=false)
public class SkillArtifactConfiguration {
    /** 具名策略用于调度表达式，避免依赖配置属性 Bean 的框架生成名称。 */
    @Bean SkillArtifactProperties skillArtifactPolicy(SkillProperties properties){return properties.getSandbox().getArtifacts();}
    /** @return 仅 local/test 的内存元数据 */
    @Bean @ConditionalOnProperty(prefix="cm-agent.persistence",name="mode",havingValue="memory",matchIfMissing=true)
    SkillArtifactRepository memorySkillArtifactRepository(){return new InMemorySkillArtifactRepository();}
    /** @param jdbc 当前 JDBC @param tx 同一事务模板 @return 可跨实例的持久元数据 */
    @Bean @ConditionalOnProperty(prefix="cm-agent.persistence",name="mode",havingValue="jdbc")
    SkillArtifactRepository jdbcSkillArtifactRepository(JdbcClient jdbc,TransactionTemplate tx){return new JdbcSkillArtifactRepository(jdbc,tx);}
    /** @param properties 部署者文件策略 @return 加密持久卷；没有默认可用密钥 */
    @Bean @ConditionalOnMissingBean(SkillArtifactStorage.class)
    @ConditionalOnProperty(prefix="cm-agent.skills.sandbox.artifacts",name="enabled",havingValue="true")
    SkillArtifactStorage skillArtifactStorage(SkillProperties properties){return new FileSystemSkillArtifactStorage(properties.getSandbox().getArtifacts());}
}
