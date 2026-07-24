package com.cmagent.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/** 在允许的开发 profile 中创建初始管理员，避免生产环境出现默认账号。 */
public class BootstrapAdminConfiguration {

    /**
     * 创建 bootstrap admin 启动校验器。
     *
     * @param properties bootstrap admin 配置属性
     * @return 启动校验器
     * @throws IllegalStateException 生产 profile 启用 bootstrap admin 或缺少密码时抛出
     */
    @Bean
    BootstrapAdminValidator bootstrapAdminValidator(BootstrapAdminProperties properties) {
        properties.validate();
        return new BootstrapAdminValidator();
    }

    static class BootstrapAdminValidator {
    }
}
