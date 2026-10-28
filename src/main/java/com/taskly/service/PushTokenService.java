package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.push.PushTestResponse;
import com.taskly.dto.push.RegisterPushTokenRequest;
import com.taskly.dto.push.UnregisterPushTokenRequest;
import com.taskly.integration.PushSender;
import com.taskly.integration.PushSender.PushDeliveryException;
import com.taskly.integration.PushSender.PushMessage;
import com.taskly.integration.PushSender.PushTokenInvalidException;
import com.taskly.repository.PushTokenRepository;
import com.taskly.security.OpaqueTokens;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đăng ký / huỷ device token FCM của app Android và "push thử" (plan M13c, F6). */
@Service
@Transactional
public class PushTokenService {

    static final int TOKEN_MAX = 4096;
    private static final Logger log = LoggerFactory.getLogger(PushTokenService.class);

    private final PushTokenRepository tokens;
    private final PushSender pushSender;
    private final MessageSource messages;
    private final UserService userService;
    private final Clock clock;

    public PushTokenService(PushTokenRepository tokens, PushSender pushSender, MessageSource messages,
                            UserService userService, Clock clock) {
        this.tokens = tokens;
        this.pushSender = pushSender;
        this.messages = messages;
        this.userService = userService;
        this.clock = clock;
    }

    public void register(UUID userId, RegisterPushTokenRequest request) {
        String token = validToken(request == null ? null : request.token());
        if (request.platform() == null || !"ANDROID".equals(request.platform())) {
            throw ApiException.badRequest(ErrorMessage.PUSH_PLATFORM_INVALID);
        }
        tokens.upsert(userId, token, OpaqueTokens.sha256Hex(token), request.platform(), clock.instant());
    }

    /** Idempotent: token không có vẫn 204. */
    public void unregister(UUID userId, UnregisterPushTokenRequest request) {
        String token = validToken(request == null ? null : request.token());
        tokens.deleteForUser(userId, OpaqueTokens.sha256Hex(token));
    }

    /** Gửi thử một push tới mọi thiết bị đã đăng ký; token hết hiệu lực bị xoá. devices = số thiết bị trước khi gửi. */
    public PushTestResponse test(UUID userId) {
        List<String> registered = tokens.tokensOf(userId);
        java.util.Locale locale = java.util.Locale.forLanguageTag(userService.requireUser(userId).getLocale());
        String title = messages.getMessage("push.test.title", null, locale);
        String body = messages.getMessage("push.test.body", null, locale);
        for (String token : registered) {
            try {
                pushSender.send(new PushMessage(token, title, body, Map.of("kind", "test")));
            } catch (PushTokenInvalidException e) {
                tokens.deleteByHash(OpaqueTokens.sha256Hex(token));
            } catch (PushDeliveryException e) {
                log.warn("Push thử lỗi ({})", e.getClass().getSimpleName());
            }
        }
        return new PushTestResponse(registered.size());
    }

    private static String validToken(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > TOKEN_MAX) {
            throw ApiException.badRequest(ErrorMessage.PUSH_TOKEN_INVALID);
        }
        return raw;
    }
}
