package com.taskly;

import com.taskly.support.MutableClock;
import com.taskly.support.TestClockConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
public abstract class IntegrationTestBase {

    /** Mật khẩu fixture cho test tự động — không phải mật khẩu thật của ai. */
    protected static final String FIXTURE_PASSWORD = "Fixture-Pass-123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

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
}
