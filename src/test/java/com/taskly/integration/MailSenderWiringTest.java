package com.taskly.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Trong test, service nhận MailSender giả (@Primary) — không gửi SMTP thật; bản SMTP vẫn được tạo (cấu hình đủ).
 * Không assert giá trị cụ thể của host / from: chạy ở D:\project\backend thì file .env của người dùng (nếu có) được
 * import và có thể đổi MAIL_*.
 */
class MailSenderWiringTest extends IntegrationTestBase {

    @Autowired
    private MailSender mailSender;

    @Autowired
    private SmtpMailSender smtpMailSender;

    @Autowired
    private Environment environment;

    @Test
    void servicesGetTheRecordingFake_andSmtpAdapterIsConfigured() {
        assertThat(mailSender).isSameAs(mailbox);
        assertThat(smtpMailSender).isNotNull();
        assertThat(environment.getProperty("spring.mail.host")).isNotBlank();
        assertThat(environment.getProperty("spring.mail.port")).isNotBlank();
        assertThat(environment.getProperty("app.mail.from")).contains("@");
    }
}
