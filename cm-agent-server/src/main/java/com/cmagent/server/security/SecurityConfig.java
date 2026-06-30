package com.cmagent.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final Environment environment;
    private final boolean publicApiDocsEnabled;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          Environment environment,
                          @Value("${cm-agent.security.public-api-docs-enabled:true}") boolean publicApiDocsEnabled) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.environment = environment;
        this.publicApiDocsEnabled = publicApiDocsEnabled;
    }

    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(
                            "/",
                            "/assets/**",
                            "/api/auth/login",
                            "/actuator/health"
                    ).permitAll();
                    if (isPublicApiDocsAllowed()) {
                        authorize.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    }
                    authorize.requestMatchers("/api/auth/me").authenticated();
                    authorize.anyRequest().authenticated();
                });
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private boolean isPublicApiDocsAllowed() {
        if (!publicApiDocsEnabled) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("production".equalsIgnoreCase(profile)
                    || "prod".equalsIgnoreCase(profile)
                    || "supabase".equalsIgnoreCase(profile)) {
                return false;
            }
        }
        return true;
    }
}
