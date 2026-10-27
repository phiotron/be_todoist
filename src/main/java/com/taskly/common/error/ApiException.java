package com.taskly.common.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Lỗi nghiệp vụ có mã HTTP rõ ràng → ProblemDetail (title tiếng Việt theo mã, detail do nơi ném truyền vào,
 * nên lấy từ ProblemTexts). Kế thừa ErrorResponseException nên ResponseEntityExceptionHandler tự xử lý.
 */
public class ApiException extends ErrorResponseException {

    /** Header riêng (vd Retry-After). Giữ bản của mình để chắc chắn sửa được ở mọi phiên bản Spring. */
    private final HttpHeaders headers = new HttpHeaders();

    public ApiException(HttpStatusCode status, String detail) {
        super(status, problem(status, detail), null);
    }

    private static ProblemDetail problem(HttpStatusCode status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(ProblemTexts.title(status.value()));
        return body;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    /** Thêm một field vào ProblemDetail (vd "current" = bản mới nhất khi lệch version; "limit", "max"). */
    public ApiException withProperty(String name, Object value) {
        getBody().setProperty(name, value);
        return this;
    }

    /** Mã máy đọc (spec 12.10) — luôn lấy từ ProblemCodes. */
    public ApiException withCode(String code) {
        return withProperty("code", code);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }

    /** 422 — dùng HttpStatusCode.valueOf vì tên hằng 422 đổi giữa các bản Spring. */
    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatusCode.valueOf(422), detail);
    }

    public static ApiException tooManyRequests(String detail, long retryAfterSeconds) {
        ApiException ex = new ApiException(HttpStatus.TOO_MANY_REQUESTS, detail);
        ex.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return ex;
    }
}
