package com.cmagent.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BootstrapAdminConfiguration {

    @Bean
    BootstrapAdminValidator bootstrapAdminValidator(BootstrapAdminProperties properties) {
        properties.validate();
        return new BootstrapAdminValidator();
    }

    static class BootstrapAdminValidator {
    }
}
