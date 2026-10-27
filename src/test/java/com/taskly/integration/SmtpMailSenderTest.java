package com.taskly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Unit thuần: SmtpMailSender dựng thư văn bản thường từ địa chỉ cấu hình, lỗi SMTP → MailDeliveryException. */
class SmtpMailSenderTest {

    private final JavaMailSender javaMailSender = mock(JavaMailSender.class);
    private final SmtpMailSender sender = new SmtpMailSender(javaMailSender, "no-reply@taskly.test");

    @Test
    void send_buildsPlainTextMessageFromConfiguredAddress() {
        sender.send(new OutgoingMail("an@example.com", "Tiêu đề", "Nội dung thư"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@taskly.test");
        assertThat(message.getTo()).containsExactly("an@example.com");
        assertThat(message.getSubject()).isEqualTo("Tiêu đề");
        assertThat(message.getText()).isEqualTo("Nội dung thư");
    }

    @Test
    void send_smtpFailure_throwsMailDeliveryExceptionWithoutCause() {
        doThrow(new MailSendException("Connection refused: an@example.com"))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send(new OutgoingMail("an@example.com", "Tiêu đề", "Nội dung")))
                .isInstanceOf(MailDeliveryException.class)
                .hasNoCause()
                .hasMessageNotContaining("an@example.com");
    }

    @Test
    void outgoingMail_toStringHidesRecipientAndBody() {
        String text = new OutgoingMail("an@example.com", "Tiêu đề", "ma-bi-mat-123").toString();

        assertThat(text).contains("Tiêu đề").doesNotContain("an@example.com").doesNotContain("ma-bi-mat-123");
    }
}
