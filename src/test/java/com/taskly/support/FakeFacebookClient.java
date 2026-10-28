package com.taskly.support;

import com.taskly.integration.facebook.FacebookClient;
import com.taskly.security.ExternalIdentity;
import com.taskly.security.ExternalServiceUnavailableException;
import com.taskly.security.InvalidExternalTokenException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FacebookClient GIẢ (M13b): test đăng ký "access token → danh tính" trước; token lạ → InvalidExternalTokenException.
 * Không gọi Graph API. Token / code sinh ngẫu nhiên nên các test chạy song song trong cùng context không đụng nhau.
 */
public class FakeFacebookClient implements FacebookClient {

    public static final String UNAVAILABLE_TOKEN = "fb-unavailable";

    private final Map<String, ExternalIdentity> tokens = new ConcurrentHashMap<>();
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    /** Đăng ký một user Facebook giả; trả access token dùng được. email null = tài khoản không cấp email. */
    public String register(String subject, String email, String name) {
        String token = "fb-token-" + UUID.randomUUID();
        tokens.put(token, new ExternalIdentity(subject, email, email != null, name));
        return token;
    }

    /** Code luồng web đổi được lấy accessToken. */
    public String registerCode(String accessToken) {
        String code = "fb-code-" + UUID.randomUUID();
        codes.put(code, accessToken);
        return code;
    }

    @Override
    public ExternalIdentity verifyAccessToken(String accessToken) {
        if (UNAVAILABLE_TOKEN.equals(accessToken)) {
            throw new ExternalServiceUnavailableException(new IllegalStateException("fake"));
        }
        ExternalIdentity identity = tokens.get(accessToken);
        if (identity == null) {
            throw new InvalidExternalTokenException();
        }
        return identity;
    }

    @Override
    public String exchangeCode(String code, String redirectUri) {
        String token = codes.remove(code);
        if (token == null) {
            throw new InvalidExternalTokenException();
        }
        return token;
    }
}
