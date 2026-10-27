package com.taskly.common.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Lỗi nghiệp vụ có mã HTTP rõ ràng. Kế thừa ErrorResponseException nên ResponseEntityExceptionHandler tự trả
 * ProblemDetail. detail = KEY của ErrorMessage, title = key theo mã HTTP; ProblemDetailLocalizer dịch sang vi / en.
 * Cố ý KHÔNG có constructor / factory nhận String: câu lỗi mới phải thêm vào ErrorMessage + messages_vi/en.
 * Thuộc tính "code" (spec 12.10) đặt bằng withCode(ProblemCodes.X).
 */
public class ApiException extends ErrorResponseException {

    private final ErrorMessage errorMessage;

    /** Header riêng (vd Retry-After). Giữ bản của mình để chắc chắn sửa được ở mọi phiên bản Spring (M1). */
    private final HttpHeaders headers = new HttpHeaders();

    public ApiException(HttpStatusCode status, ErrorMessage errorMessage) {
        super(status, problem(status, errorMessage), null);
        this.errorMessage = errorMessage;
    }

    public ErrorMessage getErrorMessage() {
        return errorMessage;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    /** Thêm một thuộc tính vào ProblemDetail (vd "current" = bản mới nhất khi lệch version, "twoFactorToken"). */
    public ApiException withProperty(String name, Object value) {
        getBody().setProperty(name, value);
        return this;
    }

    /** Đặt thuộc tính "code" (spec mục 12.10); giá trị lấy từ ProblemCodes. */
    public ApiException withCode(String code) {
        return withProperty(ProblemCodes.PROPERTY, code);
    }

    private static ProblemDetail problem(HttpStatusCode status, ErrorMessage errorMessage) {
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

    public static ApiException forbidden(ErrorMessage errorMessage) {
        return new ApiException(HttpStatus.FORBIDDEN, errorMessage);
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

    /** 422 — dùng HttpStatusCode.valueOf vì tên hằng 422 đổi giữa các bản Spring. */
    public static ApiException unprocessable(ErrorMessage errorMessage) {
        return new ApiException(HttpStatusCode.valueOf(422), errorMessage);
    }

    public static ApiException tooManyRequests(ErrorMessage errorMessage, long retryAfterSeconds) {
        ApiException ex = new ApiException(HttpStatus.TOO_MANY_REQUESTS, errorMessage);
        ex.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return ex;
    }

    public static ApiException serviceUnavailable(ErrorMessage errorMessage) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, errorMessage);
    }
}
