package com.cmagent.server.config;

import com.cmagent.core.repository.SandboxEndpointRepository;
import com.cmagent.persistence.JdbcSandboxEndpointRepository;
import com.cmagent.server.runtime.DockerSkillSandbox;
import com.cmagent.server.store.InMemorySandboxEndpointRepository;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 沿用现有memory/JDBC分层，Docker实现独立装配供治理SPI选择。 */
@Configuration(proxyBeanMethods=false)
public class SandboxEndpointConfiguration {
    /** @param properties 部署策略 @return 服务生命周期复用的Docker后端 */
    @Bean DockerSkillSandbox dockerSkillSandbox(SkillProperties properties){return new DockerSkillSandbox(properties.getSandbox());}
    /** @return 仅local/test的内存仓储 */
    @Bean @ConditionalOnProperty(prefix="cm-agent.persistence",name="mode",havingValue="memory",matchIfMissing=true)
    SandboxEndpointRepository memorySandboxEndpointRepository(){return new InMemorySandboxEndpointRepository();}
    /** @param jdbc 同一数据源客户端 @param transactions 严格审计共用的事务 @return 租户JDBC仓储 */
    @Bean @ConditionalOnProperty(prefix="cm-agent.persistence",name="mode",havingValue="jdbc")
    SandboxEndpointRepository jdbcSandboxEndpointRepository(JdbcClient jdbc,TransactionTemplate transactions){
        return new JdbcSandboxEndpointRepository(jdbc,transactions);
    }
}
