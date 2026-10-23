package com.taskly.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Lỗi nghiệp vụ có mã HTTP rõ ràng. Kế thừa ErrorResponseException nên
 * ResponseEntityExceptionHandler tự trả về ProblemDetail (type/title/status/detail/instance).
 * title tiếng Việt theo mã HTTP, detail tiếng Việt do nơi ném lỗi truyền vào.
 */
public class ApiException extends ErrorResponseException {

    public ApiException(HttpStatus status, String detail) {
        super(status, problem(status, detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(ProblemTexts.title(status.value()));
        return body;
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }
}
