package com.taskly.security;

import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Chuỗi lọc stateless: không session, không CSRF (API dùng Bearer token, không cookie).
 * Xác thực JWT do OAuth2 Resource Server của Spring Security làm (decoder ở JwtConfig).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** Endpoint không cần JWT. */
    static final String[] PUBLIC_AUTH_PATHS = {
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/google"
    };

    private static final String[] PUBLIC_DOC_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/error"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ProblemDetailAuthenticationEntryPoint entryPoint,
                                            BearerTokenResolver bearerTokenResolver) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_PATHS).permitAll()
                        .requestMatchers(PUBLIC_DOC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint(entryPoint)
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    /**
     * Bỏ qua header Authorization trên register/login/refresh/google: app mobile có thể gửi kèm access token
     * đã hết hạn khi gọi refresh — nếu không bỏ qua, Resource Server sẽ trả 401 trước khi tới controller.
     */
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        Set<String> publicPaths = Set.of(PUBLIC_AUTH_PATHS);
        return request -> publicPaths.contains(request.getRequestURI()) ? null : delegate.resolve(request);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
