package com.taskly.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.integration.FakePushSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

/** Nền test M13c: ứng dụng nhà phát triển, luồng OAuth + PKCE đầy đủ, push giả. Không thêm @Import / @MockitoBean. */
public abstract class M13cApiTestBase extends IntegrationTestBase {

    protected static final String REDIRECT = "https://client.example/callback";

    @Autowired
    protected FakePushSender pushSender;

    @BeforeEach
    protected void resetPushSender() {
        pushSender.reset();
    }

    /** App vừa tạo: secret thô chỉ có ở lúc tạo. */
    protected record App(UUID id, String clientId, String secret, String redirectUri) {
    }

    protected App createApp(String userToken) throws Exception {
        return createApp(userToken, REDIRECT);
    }

    protected App createApp(String userToken, String redirectUri) throws Exception {
        ResultActions result = authPost(userToken, "/api/v1/developer/apps",
                "{\"name\": \"App thử\", \"redirectUri\": \"%s\"}".formatted(redirectUri))
                .andExpect(status().isCreated());
        return new App(readUuid(result, "$.id"), readString(result, "$.clientId"), readString(result, "$.clientSecret"),
                redirectUri);
    }

    protected static String newVerifier() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    protected static String challengeOf(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    protected ResultActions authorize(String userToken, App app, String scope, String challenge) throws Exception {
        return authPost(userToken, "/api/v1/oauth/authorize", """
                {"clientId": "%s", "redirectUri": "%s", "scope": "%s", "state": "xyz",
                 "codeChallenge": "%s", "codeChallengeMethod": "S256"}
                """.formatted(app.clientId(), app.redirectUri(), scope, challenge));
    }

    /** Người dùng cho phép → mã ủy quyền thô (lấy từ redirectUrl). */
    protected String authorizeCode(String userToken, App app, String scope, String challenge) throws Exception {
        ResultActions result = authorize(userToken, app, scope, challenge).andExpect(status().isOk());
        String url = readString(result, "$.redirectUrl");
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("code");
    }

    protected ResultActions tokenRequest(String grantType, String clientId, String clientSecret, String code,
                                         String redirectUri, String verifier) throws Exception {
        var request = post("/api/v1/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (grantType != null) {
            request.param("grant_type", grantType);
        }
        if (clientId != null) {
            request.param("client_id", clientId);
        }
        if (clientSecret != null) {
            request.param("client_secret", clientSecret);
        }
        if (code != null) {
            request.param("code", code);
        }
        if (redirectUri != null) {
            request.param("redirect_uri", redirectUri);
        }
        if (verifier != null) {
            request.param("code_verifier", verifier);
        }
        return mockMvc.perform(request);
    }

    /** Luồng đủ: authorize → đổi mã → access_token thô (tkl_...). */
    protected String oauthToken(String userToken, App app, String scope) throws Exception {
        String verifier = newVerifier();
        String code = authorizeCode(userToken, app, scope, challengeOf(verifier));
        ResultActions result = tokenRequest("authorization_code", app.clientId(), app.secret(), code,
                app.redirectUri(), verifier).andExpect(status().isOk());
        return readString(result, "$.access_token");
    }
}
