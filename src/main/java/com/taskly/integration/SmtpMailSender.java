package com.taskly.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** MailSender gửi qua SMTP bằng JavaMailSender của Spring Boot (spring.mail.*); địa chỉ gửi = app.mail.from. */
@Component
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMailSender, @Value("${app.mail.from}") String from) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void send(OutgoingMail mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.body());
        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Chỉ log tên lớp lỗi: thông điệp gốc có thể chứa địa chỉ người nhận.
            log.warn("Gửi email thất bại ({})", e.getClass().getSimpleName());
            throw new MailDeliveryException("Không gửi được email");
        }
    }
}
