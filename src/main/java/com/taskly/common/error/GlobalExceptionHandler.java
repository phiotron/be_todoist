package com.taskly.common.error;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Mọi lỗi → ProblemDetail (application/problem+json), title/detail/errors[].message đều tiếng Việt
 * vì app mobile hiển thị nguyên văn.
 * - Lỗi chuẩn của Spring MVC (JSON hỏng, 404, 405, ErrorResponseException/ApiException...) do lớp cha xử lý,
 *   rồi handleExceptionInternal dịch title (và detail của lỗi framework) sang tiếng Việt.
 * - "instance" được Spring tự điền bằng đường dẫn request.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Detail của mọi 401 do JWT thiếu/sai/hết hạn hoặc refresh token không dùng được. */
    public static final String SESSION_INVALID = "Phiên đăng nhập không hợp lệ hoặc đã hết hạn";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Spring Framework 7 để ProblemDetail.type = null (Jackson bỏ field khi serialize); hợp đồng API yêu cầu
     * "type": "about:blank" nên phải đặt tường minh.
     */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    /** Bean Validation trên @RequestBody → 400 kèm "errors": [{field, message}] (message lấy từ annotation DTO). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dữ liệu gửi lên không hợp lệ");
        List<FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorItem(error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), "Giá trị không hợp lệ")))
                .sorted(Comparator.comparing(FieldErrorItem::field))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Điểm chung của mọi lỗi lớp cha xử lý: dịch title; lỗi do framework sinh thì dịch luôn detail. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getType() == null) {
                problem.setType(BLANK_TYPE);
            }
            problem.setTitle(ProblemTexts.title(problem.getStatus()));
            if (!(ex instanceof ApiException) && !(ex instanceof MethodArgumentNotValidException)) {
                problem.setDetail(ProblemTexts.defaultDetail(problem.getStatus()));
            }
        }
        return response;
    }

    /** 401 từ Spring Security (chuyển qua ProblemDetailAuthenticationEntryPoint). */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, SESSION_INVALID);
    }

    /** Vi phạm ràng buộc DB (vd hai request đăng ký cùng email chạy song song) → 409 thay vì 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Vi phạm ràng buộc dữ liệu: {}", ex.getClass().getSimpleName()); // không log giá trị (có thể chứa email)
        return problem(HttpStatus.CONFLICT, "Dữ liệu bị trùng hoặc xung đột với bản ghi đã có");
    }

    /** Lưới an toàn cuối: không lộ chi tiết nội bộ ra client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Lỗi không mong đợi", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi phía máy chủ, vui lòng thử lại sau");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(BLANK_TYPE);
        body.setTitle(ProblemTexts.title(status.value()));
        return ResponseEntity.status(status).body(body);
    }
}
