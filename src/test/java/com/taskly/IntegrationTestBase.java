package com.taskly;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.security.AuthRateLimiter;
import com.taskly.service.UsageLimits;
import com.taskly.support.MutableClock;
import com.taskly.support.RecordingMailSender;
import com.taskly.support.TestClockConfiguration;
import com.taskly.support.TestData;
import com.taskly.support.TestMailConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Nền chung cho test tích hợp: Postgres thật (Testcontainers), MockMvc có Spring Security,
 * profile "test" (JWT secret giả), MutableClock để dời "bây giờ" và MailSender giả (mailbox) ghi lại thư.
 * Mọi class con dùng chung MỘT Spring context và MỘT container (context cache).
 * Dữ liệu không bị xoá giữa các test → luôn dùng uniqueEmail().
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class, TestMailConfiguration.class})
public abstract class IntegrationTestBase {

    /** Mật khẩu fixture cho test tự động — không phải mật khẩu thật của ai. */
    protected static final String FIXTURE_PASSWORD = "Fixture-Pass-123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    /** Hộp thư giả: thư service đã "gửi" trong test. */
    @Autowired
    protected RecordingMailSender mailbox;

    @Autowired
    private AuthRateLimiter authRateLimiter;

    /** Chèn dữ liệu mẫu bằng SQL; tạo mới trước mỗi test. */
    protected TestData testData;

    /** Giới hạn Pro: test hạ bằng usageLimits.overrideMax(LimitKind.X, n); tự gỡ trước mỗi test. */
    @Autowired
    protected UsageLimits usageLimits;

    /** Kết quả đăng ký qua API, dùng làm dữ liệu đầu vào cho test khác. */
    protected record RegisteredUser(UUID id, String email, String accessToken, String refreshToken) {
    }

    @BeforeEach
    protected void resetClock() {
        clock.reset();
    }

    @BeforeEach
    protected void resetMailbox() {
        mailbox.reset();
    }

    /** Bộ đếm rate limit sống suốt context → xoá trước mỗi test để test không ảnh hưởng nhau. */
    @BeforeEach
    protected void resetRateLimits() {
        authRateLimiter.clear();
    }

    @BeforeEach
    protected void initTestData() {
        testData = new TestData(jdbcTemplate, clock);
    }

    @BeforeEach
    protected void resetUsageLimits() {
        usageLimits.resetOverrides();
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    /** Đọc body response dạng UTF-8 (mặc định MockMvc đọc ISO-8859-1 làm hỏng tiếng Việt). */
    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Đăng ký user mới qua POST /api/v1/auth/register với mật khẩu fixture; email trả về là bản server đã chuẩn hoá. */
    protected RegisteredUser registerUser(String email) throws Exception {
        String randomIp = "10.99." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .with(request -> {
                            request.setRemoteAddr(randomIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "displayName": "Người dùng thử"}
                                """.formatted(email, FIXTURE_PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        String json = body(result);
        return new RegisteredUser(
                UUID.fromString(JsonPath.read(json, "$.user.id")),
                JsonPath.read(json, "$.user.email"),
                JsonPath.read(json, "$.accessToken"),
                JsonPath.read(json, "$.refreshToken"));
    }

    // ---- Helper gửi request (M1) ----

    /** POST JSON không kèm JWT (endpoint public). */
    protected ResultActions postJson(String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions authGet(String token, String uri) throws Exception {
        return mockMvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    protected ResultActions authPost(String token, String uri, String json) throws Exception {
        return mockMvc.perform(post(uri).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** POST không body (close, reopen, archive, unarchive, duplicate...). */
    protected ResultActions authPost(String token, String uri) throws Exception {
        return mockMvc.perform(post(uri).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    protected ResultActions authPatch(String token, String uri, String json) throws Exception {
        return mockMvc.perform(patch(uri).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions authPut(String token, String uri) throws Exception {
        return mockMvc.perform(put(uri).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    protected ResultActions authDelete(String token, String uri) throws Exception {
        return mockMvc.perform(delete(uri).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    protected static String readString(ResultActions actions, String jsonPath) throws Exception {
        Object value = JsonPath.read(body(actions.andReturn()), jsonPath);
        return value == null ? null : value.toString();
    }

    protected static UUID readUuid(ResultActions actions, String jsonPath) throws Exception {
        return UUID.fromString(readString(actions, jsonPath));
    }

    protected static long readLong(ResultActions actions, String jsonPath) throws Exception {
        return ((Number) JsonPath.read(body(actions.andReturn()), jsonPath)).longValue();
    }
}
