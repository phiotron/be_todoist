package com.taskly.integration;

/**
 * Không gửi được email (SMTP / Mailpit lỗi, hết thời gian chờ...). Cố ý không giữ exception gốc: thông điệp lỗi SMTP
 * thường chứa địa chỉ người nhận, không được lọt vào log.
 */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(String message) {
        super(message);
    }
}
