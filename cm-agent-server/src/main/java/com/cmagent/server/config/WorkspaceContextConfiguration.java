package com.cmagent.server.config;

import com.cmagent.core.runtime.AgentRuntime;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** 独立显式开关与预算验证，启用时旧自定义Runtime不能静默丢弃资料。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkspaceContextProperties.class)
public class WorkspaceContextConfiguration {
    /** @return 不读文件的启动验证；真实路径/资料错误由运行边界给出安全原因和编号 */
    @Bean InitializingBean workspaceContextValidator(WorkspaceContextProperties properties, AgentRuntime runtime) {
        return () -> {
            properties.validate();
            if (properties.isEnabled() && !runtime.supportsWorkspaceContext())
                throw new IllegalStateException("当前运行时未声明只读工作区支持，不能启用参考资料");
        };
    }
}
