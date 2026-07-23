package com.cmagent.server.security;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.DispatcherType;

import java.io.IOException;
import java.time.Instant;

@Configuration
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final Environment environment;
    private final boolean publicApiDocsEnabled;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          Environment environment,
                          @Value("${cm-agent.security.public-api-docs-enabled:true}") boolean publicApiDocsEnabled,
                          ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.environment = environment;
        this.publicApiDocsEnabled = publicApiDocsEnabled;
        this.objectMapper = objectMapper;
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
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeHttpRequests(authorize -> {
                    authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
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

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> writeError(
                response,
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.UNAUTHORIZED,
                "未登录或令牌无效"
        );
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> writeError(
                response,
                HttpStatus.FORBIDDEN,
                ApiErrorCode.FORBIDDEN,
                "没有权限执行该操作"
        );
    }

    private void writeError(HttpServletResponse response,
                            HttpStatus status,
                            ApiErrorCode code,
                            String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(code, message, Instant.now()));
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
