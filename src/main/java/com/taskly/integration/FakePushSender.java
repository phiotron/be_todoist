package com.taskly.integration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * PushSender giả (mặc định): không gọi mạng, chỉ ghi lại để test / dev xem. failing = giả lập FCM lỗi tạm thời;
 * invalidate(token) = giả lập token đã hết hiệu lực (FCM UNREGISTERED). Giữ tối đa 200 tin gần nhất.
 */
@Component
@ConditionalOnExpression("'${taskly.push.provider:fake}' != 'fcm'")
public class FakePushSender implements PushSender {

    private static final int KEEP = 200;

    private final List<PushMessage> sent = new CopyOnWriteArrayList<>();
    private final Set<String> invalidTokens = java.util.Collections.synchronizedSet(new HashSet<>());
    private final AtomicBoolean failing = new AtomicBoolean();

    @Override
    public void send(PushMessage message) throws PushDeliveryException {
        if (failing.get()) {
            throw new PushDeliveryException("Giả lập FCM lỗi");
        }
        if (invalidTokens.contains(message.token())) {
            throw new PushTokenInvalidException("Giả lập token hết hiệu lực");
        }
        sent.add(message);
        while (sent.size() > KEEP) {
            sent.remove(0);
        }
    }

    public List<PushMessage> sent() {
        return List.copyOf(sent);
    }

    public List<PushMessage> sentTo(String token) {
        return sent.stream().filter(message -> message.token().equals(token)).toList();
    }

    public void setFailing(boolean value) {
        failing.set(value);
    }

    public void invalidate(String token) {
        invalidTokens.add(token);
    }

    public void reset() {
        sent.clear();
        invalidTokens.clear();
        failing.set(false);
    }
}
