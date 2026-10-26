package com.taskly.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Lỗi nghiệp vụ có mã HTTP rõ ràng. Kế thừa ErrorResponseException nên ResponseEntityExceptionHandler tự trả
 * ProblemDetail. detail = KEY của ErrorMessage, title = key theo mã HTTP; ProblemDetailLocalizer dịch sang vi / en.
 * Cố ý KHÔNG có constructor / factory nhận String: câu lỗi mới phải thêm vào ErrorMessage + messages_vi/en.
 */
public class ApiException extends ErrorResponseException {

    private final ErrorMessage errorMessage;

    public ApiException(HttpStatus status, ErrorMessage errorMessage) {
        super(status, problem(status, errorMessage), null);
        this.errorMessage = errorMessage;
    }

    public ErrorMessage getErrorMessage() {
        return errorMessage;
    }

    private static ProblemDetail problem(HttpStatus status, ErrorMessage errorMessage) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, errorMessage.key());
        body.setTitle(ProblemTexts.titleKey(status.value()));
        return body;
    }

    public static ApiException badRequest(ErrorMessage errorMessage) {
        return new ApiException(HttpStatus.BAD_REQUEST, errorMessage);
    }

    public static ApiException unauthorized(ErrorMessage errorMessage) {
        return new ApiException(HttpStatus.UNAUTHORIZED, errorMessage);
    }

    public static ApiException notFound(ErrorMessage errorMessage) {
        return new ApiException(HttpStatus.NOT_FOUND, errorMessage);
    }

    public static ApiException conflict(ErrorMessage errorMessage) {
        return new ApiException(HttpStatus.CONFLICT, errorMessage);
    }

    public static ApiException gone(ErrorMessage errorMessage) {
        return new ApiException(HttpStatus.GONE, errorMessage);
    }

    public static ApiException serviceUnavailable(ErrorMessage errorMessage) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, errorMessage);
    }
}
