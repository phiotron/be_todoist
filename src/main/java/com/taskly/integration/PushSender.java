package com.taskly.integration;

import java.util.Map;

/**
 * Gửi push tới MỘT thiết bị (device token FCM). Bản thật: FcmPushSender (taskly.push.provider=fcm); mặc định và trong
 * test: FakePushSender (không gọi mạng).
 */
public interface PushSender {

    /**
     * Gửi một push. Token đã hết hiệu lực (gỡ app, đổi token) → PushTokenInvalidException (người gọi xoá token);
     * lỗi khác (mạng, FCM 5xx) → PushDeliveryException (thử lại sau).
     */
    void send(PushMessage message) throws PushDeliveryException;

    /** Một thông báo; toString KHÔNG in token và nội dung. */
    record PushMessage(String token, String title, String body, Map<String, String> data) {

        @Override
        public String toString() {
            return "PushMessage[token=***, title=***, body=***]";
        }
    }

    class PushDeliveryException extends Exception {
        public PushDeliveryException(String message) {
            super(message);
        }
    }

    class PushTokenInvalidException extends PushDeliveryException {
        public PushTokenInvalidException(String message) {
            super(message);
        }
    }
}
