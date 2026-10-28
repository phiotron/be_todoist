package com.taskly.security;

import com.taskly.common.web.IdempotencyFilter;
import com.taskly.repository.OAuthRepository;
import com.taskly.service.IdempotencyService;
import java.time.Clock;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Chuỗi lọc stateless: không session, không CSRF (API dùng Bearer token, không cookie).
 * Xác thực JWT do OAuth2 Resource Server của Spring Security làm (decoder ở JwtConfig).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** Endpoint không cần JWT (chỉ POST). */
    static final String[] PUBLIC_AUTH_PATHS = {
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/2fa/verify",
            "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm", "/api/v1/auth/google",
            // M13b: Apple / Facebook (callback Apple là form POST do Apple gửi)
            "/api/v1/auth/apple", "/api/v1/auth/facebook", "/api/v1/auth/apple/callback",
            // M13c: ứng dụng bên thứ ba đổi mã lấy token / thu hồi token (xác thực bằng client_id + client_secret)
            "/api/v1/oauth/token", "/api/v1/oauth/revoke"
    };

    /** Webhook từ dịch vụ ngoài (chỉ POST): tự xác thực bằng chữ ký HMAC, không JWT (M13a). */
    static final String[] PUBLIC_WEBHOOK_PATHS = {"/api/v1/integrations/email/inbound"};
    /** M13b: GET công khai — callback luồng web (Facebook, Calendar OAuth) và calendar feed iCal (token trong đường dẫn). */
    static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/auth/facebook/callback", "/api/v1/calendar/oauth/callback", "/api/v1/calendar/feed/*"
    };

    /** Tiền tố đường dẫn feed: bỏ qua header Authorization như các đường công khai khác. */
    static final String CALENDAR_FEED_PREFIX = "/api/v1/calendar/feed/";

    private static final String[] PUBLIC_DOC_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/error"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ProblemDetailAuthenticationEntryPoint entryPoint,
                                            ProblemDetailAccessDeniedHandler accessDeniedHandler,
                                            BearerTokenResolver bearerTokenResolver,
                                            IdempotencyService idempotencyService,
                                            JwtDecoder jwtDecoder,
                                            OAuthRepository oauthRepository,
                                            Clock clock,
                                            @Qualifier("handlerExceptionResolver")
                                            HandlerExceptionResolver handlerExceptionResolver) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_WEBHOOK_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers(PUBLIC_DOC_PATHS).permitAll()
                        // M8b: link tải file đính kèm đã ký HMAC + hạn ngắn thay cho Bearer (AttachmentService.open)
                        .requestMatchers(HttpMethod.GET, com.taskly.controller.AttachmentController.DOWNLOAD_PATH)
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.decoder(new OAuthAwareJwtDecoder(jwtDecoder, oauthRepository, clock))))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // M13c: token OAuth của app bên thứ ba chỉ đi qua khi đủ scope (sau khi Bearer được xác thực).
                .addFilterAfter(new OAuthScopeFilter(handlerExceptionResolver), BearerTokenAuthenticationFilter.class)
                // Sau AuthorizationFilter: đã biết user (khoá theo user). Không phải bean → không chạy hai lần.
                .addFilterAfter(new IdempotencyFilter(idempotencyService, handlerExceptionResolver),
                        AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Bỏ qua header Authorization trên các đường public (register/login/refresh/2fa-verify/password-reset/google): app mobile có
     * thể gửi kèm access token đã hết hạn — nếu không bỏ qua, Resource Server sẽ trả 401 trước khi tới controller.
     */
    @Bean
    BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        Set<String> publicPaths = new java.util.HashSet<>(Set.of(PUBLIC_AUTH_PATHS));
        publicPaths.addAll(Set.of(PUBLIC_WEBHOOK_PATHS));
        publicPaths.add("/api/v1/auth/facebook/callback");
        publicPaths.add("/api/v1/calendar/oauth/callback");
        return request -> publicPaths.contains(request.getRequestURI())
                || request.getRequestURI().startsWith(CALENDAR_FEED_PREFIX) ? null : delegate.resolve(request);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
