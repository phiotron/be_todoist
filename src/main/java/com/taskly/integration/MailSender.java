package com.taskly.integration;

/**
 * Gửi email hệ thống (đặt lại mật khẩu M3, lời mời M8). Bản chạy thật: SmtpMailSender (Mailpit khi demo);
 * test dùng bản giả ghi lại thư. KHÔNG nhầm với org.springframework.mail.MailSender của Spring.
 */
public interface MailSender {

    /** Gửi một thư văn bản thường. Không gửi được → MailDeliveryException. */
    void send(OutgoingMail mail);
}
