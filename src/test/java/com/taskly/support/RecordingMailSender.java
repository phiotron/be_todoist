package com.taskly.support;

import com.taskly.integration.MailDeliveryException;
import com.taskly.integration.MailSender;
import com.taskly.integration.OutgoingMail;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MailSender giả cho test: ghi lại thư thay vì gửi SMTP; setFailing(true) giả lập Mailpit / SMTP lỗi.
 * Là bean @Primary (TestMailConfiguration) nên mọi service nhận bản này; IntegrationTestBase gọi reset() trước mỗi test.
 */
public class RecordingMailSender implements MailSender {

    private final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    public void send(OutgoingMail mail) {
        if (failing) {
            throw new MailDeliveryException("Giả lập SMTP lỗi");
        }
        sent.add(mail);
    }

    /** Thư đã gửi tới email (đã chuẩn hoá), theo thứ tự gửi. */
    public List<OutgoingMail> sentTo(String email) {
        return sent.stream().filter(mail -> mail.to().equals(email)).toList();
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void reset() {
        sent.clear();
        failing = false;
    }
}
