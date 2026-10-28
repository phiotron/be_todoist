package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.oauth.AuthorizedAppResponse;
import com.taskly.repository.OAuthRepository;
import com.taskly.repository.OAuthRepository.AuthorizedAppRow;
import com.taskly.security.OAuthScopes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ứng dụng đã được người dùng cấp quyền (Cài đặt, Tích hợp): danh sách gộp theo app + thu hồi (plan M13c, F4). */
@Service
@Transactional
public class AuthorizedAppService {

    private final OAuthRepository oauth;
    private final Clock clock;

    public AuthorizedAppService(OAuthRepository oauth, Clock clock) {
        this.oauth = oauth;
        this.clock = clock;
    }

    /** Scope = hợp scope các token còn hiệu lực; authorizedAt = lần cấp gần nhất; lastUsedAt = lần dùng gần nhất. */
    @Transactional(readOnly = true)
    public List<AuthorizedAppResponse> list(UUID userId) {
        Map<String, Aggregate> byApp = new LinkedHashMap<>();
        for (AuthorizedAppRow row : oauth.activeTokensByApp(userId)) { // mới nhất trước
            Aggregate aggregate = byApp.computeIfAbsent(row.clientId(), id -> new Aggregate(row.name(), row.authorizedAt()));
            aggregate.scopes.addAll(OAuthScopes.fromStored(row.scopes()));
            if (row.lastUsedAt() != null && (aggregate.lastUsedAt == null || row.lastUsedAt().isAfter(aggregate.lastUsedAt))) {
                aggregate.lastUsedAt = row.lastUsedAt();
            }
        }
        List<AuthorizedAppResponse> result = new ArrayList<>();
        byApp.forEach((clientId, a) -> result.add(
                new AuthorizedAppResponse(clientId, a.name, List.copyOf(a.scopes), a.authorizedAt, a.lastUsedAt)));
        return result;
    }

    /** Thu hồi mọi token của user cho app; không có token nào còn hiệu lực → 404. */
    public void revoke(UUID userId, String clientId) {
        if (oauth.revokeForUserAndClient(userId, clientId, clock.instant()) == 0) {
            throw ApiException.notFound(ErrorMessage.AUTHORIZED_APP_NOT_FOUND);
        }
    }

    private static final class Aggregate {
        final String name;
        final Instant authorizedAt;
        final Set<String> scopes = new LinkedHashSet<>();
        Instant lastUsedAt;

        Aggregate(String name, Instant authorizedAt) {
            this.name = name;
            this.authorizedAt = authorizedAt;
        }
    }
}
