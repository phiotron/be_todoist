package com.taskly.integration;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.taskly.config.PushProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gửi push qua FCM HTTP v1 (taskly.push.provider=fcm). Xác thực bằng service account (JWT RS256 đổi lấy access token,
 * cache tới gần hết hạn). Cần PUSH: FCM_PROJECT_ID và FCM_SERVICE_ACCOUNT_FILE (người dùng tự tạo trong Firebase, không
 * commit). KHÔNG log token thiết bị, nội dung hay khoá. Chưa thử với Firebase thật (chỉ server giả trong test).
 */
@Component
@ConditionalOnProperty(prefix = "taskly.push", name = "provider", havingValue = "fcm")
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String CONFIG_ERROR =
            "Push FCM chưa cấu hình: cần FCM_PROJECT_ID và FCM_SERVICE_ACCOUNT_FILE (xem .env.example)";

    private final PushProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final HttpClient http;

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public FcmPushSender(PushProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.timeout()).build();
    }

    @Override
    public void send(PushMessage message) throws PushDeliveryException {
        if (isBlank(properties.projectId()) || isBlank(properties.serviceAccountFile())) {
            throw new PushDeliveryException(CONFIG_ERROR);
        }
        try {
            Map<String, Object> fcmMessage = new LinkedHashMap<>();
            fcmMessage.put("token", message.token());
            fcmMessage.put("notification", Map.of("title", message.title(), "body", message.body()));
            if (message.data() != null && !message.data().isEmpty()) {
                fcmMessage.put("data", message.data());
            }
            fcmMessage.put("android", Map.of("priority", "HIGH"));
            String body = jsonMapper.writeValueAsString(Map.of("message", fcmMessage));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.fcmBaseUrl() + "/v1/projects/"
                            + properties.projectId() + "/messages:send"))
                    .timeout(properties.timeout())
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return;
            }
            if (status == 404 || response.body().contains("UNREGISTERED")) {
                throw new PushTokenInvalidException("FCM: token không còn hiệu lực");
            }
            log.warn("FCM trả HTTP {}", status);
            throw new PushDeliveryException("FCM trả HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushDeliveryException("Bị ngắt khi gửi FCM");
        } catch (IOException | RuntimeException e) {
            if (e instanceof PushDeliveryException) {
                throw (PushDeliveryException) e;
            }
            log.warn("Gửi FCM lỗi ({})", e.getClass().getSimpleName());
            throw new PushDeliveryException("Gửi FCM lỗi");
        }
    }

    private synchronized String accessToken() throws IOException, InterruptedException {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpiresAt.minusSeconds(60))) {
            return accessToken;
        }
        JsonNode account = jsonMapper.readTree(Files.readString(Path.of(properties.serviceAccountFile())));
        String clientEmail = account.path("client_email").asString("");
        String tokenUri = account.path("token_uri").asString("https://oauth2.googleapis.com/token");
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(clientEmail).claim("scope", SCOPE)
                    .audience(tokenUri).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                    .build();
            SignedJWT assertion = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            assertion.sign(new RSASSASigner(privateKey(account.path("private_key").asString(""))));
            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                    StandardCharsets.UTF_8) + "&assertion=" + URLEncoder.encode(assertion.serialize(), StandardCharsets.UTF_8);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(tokenUri))
                    .timeout(properties.timeout()).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Không lấy được access token FCM (HTTP " + response.statusCode() + ")");
            }
            JsonNode token = jsonMapper.readTree(response.body());
            accessToken = token.path("access_token").asString("");
            accessTokenExpiresAt = now.plusSeconds(token.path("expires_in").asLong(3600));
            return accessToken;
        } catch (JOSEException | GeneralSecurityException e) {
            throw new IOException("Không ký được yêu cầu access token FCM");
        }
    }

    private static RSAPrivateKey privateKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
