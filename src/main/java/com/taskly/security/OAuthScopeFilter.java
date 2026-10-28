package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Sau khi Bearer được xác thực: request mang token OAuth chỉ đi qua nếu scope cho phép (OAuthScopes.allows). Thiếu
 * scope hoặc endpoint không mở cho OAuth thì trả 403 code insufficient-scope. Token của app (typ = access) không bị
 * đụng tới. KHÔNG phải bean (SecurityConfig tạo và gắn vào chuỗi lọc).
 */
public class OAuthScopeFilter extends OncePerRequestFilter {

    private final HandlerExceptionResolver handlerExceptionResolver;

    public OAuthScopeFilter(HandlerExceptionResolver handlerExceptionResolver) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && OAuthScopes.TOKEN_TYPE.equals(jwtAuthentication.getToken().getClaim("typ"))) {
            Jwt jwt = jwtAuthentication.getToken();
            Set<String> scopes = OAuthScopes.fromStored(jwt.getClaimAsString("scope"));
            if (!OAuthScopes.allows(scopes, request.getMethod(), request.getRequestURI())) {
                handlerExceptionResolver.resolveException(request, response, null,
                        ApiException.forbidden(ErrorMessage.OAUTH_INSUFFICIENT_SCOPE)
                                .withCode(ProblemCodes.INSUFFICIENT_SCOPE));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
