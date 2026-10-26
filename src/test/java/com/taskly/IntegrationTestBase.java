package com.taskly;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.GoogleTestConfiguration;
import com.taskly.support.MutableClock;
import com.taskly.support.TestClockConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Nền chung cho test tích hợp: Postgres thật (Testcontainers), MockMvc có Spring Security,
 * profile "test" (JWT secret giả) và MutableClock để dời "bây giờ".
 * Mọi class con dùng chung MỘT Spring context và MỘT container (context cache).
 * Dữ liệu không bị xoá giữa các test → luôn dùng uniqueEmail().
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class, GoogleTestConfiguration.class})
public abstract class IntegrationTestBase {

    /** Mật khẩu fixture cho test tự động — không phải mật khẩu thật của ai. */
    protected static final String FIXTURE_PASSWORD = "Fixture-Pass-123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    /** Kết quả đăng ký qua API, dùng làm dữ liệu đầu vào cho test khác. */
    protected record RegisteredUser(UUID id, String email, String accessToken, String refreshToken) {
    }

    @BeforeEach
    protected void resetClock() {
        clock.reset();
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
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
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
}
