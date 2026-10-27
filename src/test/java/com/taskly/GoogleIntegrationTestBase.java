package com.taskly;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.GoogleTestTokens;
import com.taskly.support.TotpTestCodes;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Nền cho test đăng nhập / liên kết Google (M5). KHÔNG thêm annotation nào ở đây: dùng chung context với
 * IntegrationTestBase (GoogleTestConfiguration đã @Import ở lớp cha). ID token là token GIẢ do GoogleTestTokens ký.
 */
public abstract class GoogleIntegrationTestBase extends IntegrationTestBase {

    protected static final String GOOGLE_SIGN_IN = "/api/v1/auth/google";
    protected static final String GOOGLE_IDENTITY = "/api/v1/me/identities/google";

    @Autowired
    protected GoogleTestTokens googleTokens;

    protected record GoogleUser(UUID id, String email, String subject, String accessToken, String refreshToken) {
    }

    protected static RequestPostProcessor remoteAddress(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** Mỗi request một IP ngẫu nhiên (dải 10.x) → không đụng giới hạn tần suất theo IP của M1. */
    protected static RequestPostProcessor randomRemoteAddress() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return remoteAddress("10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254)));
    }

    protected static String idTokenJson(String idToken) {
        return """
                {"idToken": "%s"}
                """.formatted(idToken);
    }

    protected static String linkTokenJson(String linkToken) {
        return """
                {"linkToken": "%s"}
                """.formatted(linkToken);
    }

    protected static MockHttpServletRequestBuilder googleSignInRequest(String idToken) {
        return post(GOOGLE_SIGN_IN).with(randomRemoteAddress())
                .contentType(MediaType.APPLICATION_JSON)
                .content(idTokenJson(idToken));
    }

    protected ResultActions postGoogleSignIn(String idToken) throws Exception {
        return mockMvc.perform(googleSignInRequest(idToken));
    }

    /** Đăng nhập Google bằng ID token giả; tạo tài khoản mới nếu sub / email chưa có. Mong đợi 200. */
    protected GoogleUser signInWithGoogle(GoogleTestTokens.IdToken token) throws Exception {
        String json = body(postGoogleSignIn(token.sign()).andExpect(status().isOk()).andReturn());
        return new GoogleUser(UUID.fromString(JsonPath.read(json, "$.user.id")), JsonPath.read(json, "$.user.email"),
                token.getSubject(), JsonPath.read(json, "$.accessToken"), JsonPath.read(json, "$.refreshToken"));
    }

    protected ResultActions postLinkGoogle(String accessToken, String json) throws Exception {
        return mockMvc.perform(post(GOOGLE_IDENTITY)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    protected ResultActions deleteGoogleLink(String accessToken) throws Exception {
        return mockMvc.perform(delete(GOOGLE_IDENTITY).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }

    /** Đăng nhập bằng mật khẩu fixture qua API (như người dùng làm sau 409 link-required), trả access token. */
    protected String loginWithPassword(String email) throws Exception {
        String json = body(mockMvc.perform(post("/api/v1/auth/login").with(randomRemoteAddress())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, FIXTURE_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn());
        return JsonPath.read(json, "$.accessToken");
    }

    protected int countIdentities(String subject) {
        return jdbcTemplate.queryForObject(
                "select count(*) from user_identities where subject = ?", Integer.class, subject);
    }

    protected int countUsersByEmail(String email) {
        return jdbcTemplate.queryForObject("select count(*) from users where email = ?", Integer.class, email);
    }

    protected static final String TWO_FACTOR_VERIFY = "/api/v1/auth/2fa/verify";

    /** Secret base32 + 10 mã khôi phục trả về lúc bật 2FA (chỉ dùng trong test, không in ra log). */
    protected record TwoFactorSetup(String secret, List<String> recoveryCodes) {
    }

    /**
     * Bật 2FA qua API của M3: setup → enable bằng mã TOTP tính từ secret (cùng Clock với server). Dời đồng hồ 30 giây
     * sau khi bật: M3 không nhận lại một bước TOTP đã dùng (spec 5.9) nên mã kế tiếp phải thuộc bước mới.
     */
    protected TwoFactorSetup enableTwoFactor(String accessToken) throws Exception {
        String setup = body(mockMvc.perform(post("/api/v1/me/2fa/setup")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().is2xxSuccessful())
                .andReturn());
        String secret = JsonPath.read(setup, "$.secret");
        String enabled = body(mockMvc.perform(post("/api/v1/me/2fa/enable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s"}
                                """.formatted(TotpTestCodes.code(secret, clock.instant()))))
                .andExpect(status().is2xxSuccessful())
                .andReturn());
        List<String> recoveryCodes = JsonPath.read(enabled, "$.recoveryCodes");
        clock.advance(Duration.ofSeconds(30));
        return new TwoFactorSetup(secret, recoveryCodes);
    }

    /** Mã TOTP của bước hiện tại theo đồng hồ test. */
    protected String currentTotp(TwoFactorSetup twoFactor) {
        return TotpTestCodes.code(twoFactor.secret(), clock.instant());
    }

    protected ResultActions postTwoFactorVerify(String twoFactorToken, String code) throws Exception {
        return mockMvc.perform(post(TWO_FACTOR_VERIFY).with(randomRemoteAddress())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"twoFactorToken": "%s", "code": "%s"}
                        """.formatted(twoFactorToken, code)));
    }

    /** auth/google mong đợi 401 two-factor-required; trả twoFactorToken. */
    protected String requestTwoFactorTokenWithGoogle(GoogleTestTokens.IdToken token) throws Exception {
        return JsonPath.read(body(postGoogleSignIn(token.sign())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("two-factor-required"))
                .andReturn()), "$.twoFactorToken");
    }

    protected int countRefreshTokens(UUID userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
    }
}
