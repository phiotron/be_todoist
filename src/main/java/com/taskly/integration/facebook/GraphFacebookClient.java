package com.taskly.integration.facebook;

import com.taskly.common.EmailNormalizer;
import com.taskly.security.ExternalIdentity;
import com.taskly.security.ExternalServiceUnavailableException;
import com.taskly.security.FacebookProperties;
import com.taskly.security.InvalidExternalTokenException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Bản thật của {@link FacebookClient}: Graph API (developers.facebook.com/docs/facebook-login/guides/advanced/manual-flow).
 * - debug_token?input_token=…&access_token={app-id}|{app-secret} → data.is_valid, data.app_id, data.user_id;
 * - /me?fields=id,name,email&appsecret_proof=… (HMAC-SHA256 của token bằng app secret);
 * - /oauth/access_token?client_id&redirect_uri&client_secret&code → access_token.
 * Không bao giờ log token / secret. Lỗi 4xx = token sai; mạng / 5xx = dịch vụ không sẵn sàng.
 */
public class GraphFacebookClient implements FacebookClient {

    static final int MAX_EMAIL_LENGTH = 254;

    private final FacebookProperties properties;
    private final RestClient http;

    public GraphFacebookClient(FacebookProperties properties) {
        this.properties = properties;
        this.http = RestClient.builder().baseUrl(properties.graphBaseUrl()).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExternalIdentity verifyAccessToken(String accessToken) {
        requireConfigured();
        Map<String, Object> debug = get("/debug_token?input_token={t}&access_token={app}",
                accessToken, properties.appId() + "|" + properties.appSecret());
        Object raw = debug == null ? null : debug.get("data");
        if (!(raw instanceof Map<?, ?> data)
                || !Boolean.TRUE.equals(data.get("is_valid"))
                || !properties.appId().equals(String.valueOf(data.get("app_id")))) {
            throw new InvalidExternalTokenException();
        }
        Map<String, Object> me = get("/me?fields=id,name,email&access_token={t}&appsecret_proof={p}",
                accessToken, appSecretProof(accessToken));
        String id = me == null ? null : (String) me.get("id");
        if (id == null || id.isBlank() || !id.equals(String.valueOf(((Map<String, Object>) raw).get("user_id")))) {
            throw new InvalidExternalTokenException();
        }
        String email = EmailNormalizer.normalize((String) me.get("email"));
        if (email != null && (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH)) {
            email = null;
        }
        // Facebook chỉ trả email chính đã xác nhận → coi là đã xác minh.
        return new ExternalIdentity(id, email, email != null, (String) me.get("name"));
    }

    @Override
    public String exchangeCode(String code, String redirectUri) {
        requireConfigured();
        Map<String, Object> token = get("/oauth/access_token?client_id={id}&redirect_uri={r}&client_secret={s}&code={c}",
                properties.appId(), redirectUri, properties.appSecret(), code);
        Object value = token == null ? null : token.get("access_token");
        if (!(value instanceof String accessToken) || accessToken.isBlank()) {
            throw new InvalidExternalTokenException();
        }
        return accessToken;
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ExternalServiceUnavailableException(new IllegalStateException("FACEBOOK_APP_ID chưa cấu hình"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String uri, Object... variables) {
        try {
            return http.get().uri(uri, variables).retrieve().body(Map.class);
        } catch (HttpClientErrorException ex) {
            throw new InvalidExternalTokenException();
        } catch (RestClientException ex) {
            throw new ExternalServiceUnavailableException(ex);
        }
    }

    private String appSecretProof(String accessToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(accessToken.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Không tính được appsecret_proof");
        }
    }
}
