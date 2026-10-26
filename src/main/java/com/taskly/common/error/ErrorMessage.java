package com.taskly.common.error;

/**
 * Câu lỗi nghiệp vụ dùng làm "detail" của ProblemDetail. Mỗi hằng là một key trong messages_vi.properties và
 * messages_en.properties; ProblemDetailLocalizer dịch theo Accept-Language (vi mặc định).
 * Thêm hằng mới thì thêm key vào CẢ HAI file properties (MessageBundlesTest kiểm).
 */
public enum ErrorMessage {
    VALIDATION_FAILED("error.validation-failed"),
    SESSION_INVALID("error.session-invalid"),
    INVALID_CREDENTIALS("error.invalid-credentials"),
    EMAIL_TAKEN("error.email-taken"),
    DATA_CONFLICT("error.data-conflict"),
    INTERNAL_ERROR("error.internal"),
    CURRENT_PASSWORD_INCORRECT("error.current-password-incorrect"),
    RESET_TOKEN_INVALID("error.reset-token-invalid"),
    RESET_TOKEN_EXPIRED("error.reset-token-expired"),
    MAIL_UNAVAILABLE("error.mail-unavailable");

    private final String key;

    ErrorMessage(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
