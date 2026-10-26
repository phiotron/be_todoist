package com.taskly.common.error;

import jakarta.validation.ConstraintViolation;
import java.net.URI;
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
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Mọi lỗi → ProblemDetail (application/problem+json). Handler chỉ đặt KEY:
 * title theo mã HTTP (ProblemTexts), detail theo ErrorMessage hoặc detail mặc định của lỗi framework,
 * errors[].message theo key trong message template "{key}" của Bean Validation.
 * ProblemDetailLocalizer dịch key sang vi / en theo Accept-Language ngay trước khi ghi body.
 * - Lỗi chuẩn của Spring MVC (JSON hỏng, 404, 405, ErrorResponseException/ApiException...) do lớp cha xử lý,
 *   rồi handleExceptionInternal đặt type, title và (với lỗi framework) detail mặc định.
 * - "instance" được Spring tự điền bằng đường dẫn request.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Key của errors[].message khi lỗi không có message (vd lỗi bind kiểu dữ liệu). */
    static final String INVALID_VALUE_KEY = "validation.invalid";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Spring Framework 7 để ProblemDetail.type = null (Jackson bỏ field khi serialize); hợp đồng API yêu cầu
     * "type": "about:blank" nên phải đặt tường minh.
     */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    /** Bean Validation trên @RequestBody → 400 kèm "errors": [{field, message}] (message là key, sẽ được dịch). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ErrorMessage.VALIDATION_FAILED.key());
        List<FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorItem(error.getField(), messageKey(error)))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Điểm chung của mọi lỗi lớp cha xử lý: đặt type, title; lỗi do framework sinh thì đặt detail mặc định. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getType() == null) {
                problem.setType(BLANK_TYPE);
            }
            problem.setTitle(ProblemTexts.titleKey(problem.getStatus()));
            if (!(ex instanceof ApiException) && !(ex instanceof MethodArgumentNotValidException)) {
                problem.setDetail(ProblemTexts.defaultDetailKey(problem.getStatus()));
            }
        }
        return response;
    }

    /** 401 từ Spring Security (chuyển qua ProblemDetailAuthenticationEntryPoint). */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ErrorMessage.SESSION_INVALID);
    }

    /** Vi phạm ràng buộc DB (vd hai request đăng ký cùng email chạy song song) → 409 thay vì 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Vi phạm ràng buộc dữ liệu: {}", ex.getClass().getSimpleName()); // không log giá trị (có thể chứa email)
        return problem(HttpStatus.CONFLICT, ErrorMessage.DATA_CONFLICT);
    }

    /** Lưới an toàn cuối: không lộ chi tiết nội bộ ra client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Lỗi không mong đợi", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorMessage.INTERNAL_ERROR);
    }

    /**
     * Key của errors[].message: lấy từ message template "{key}" của constraint (không phụ thuộc message interpolator);
     * message viết cứng (chưa chuyển sang key) giữ nguyên; lỗi bind không có message → validation.invalid.
     */
    static String messageKey(FieldError error) {
        if (error.contains(ConstraintViolation.class)) {
            String template = error.unwrap(ConstraintViolation.class).getMessageTemplate();
            if (template.length() > 2 && template.startsWith("{") && template.endsWith("}")) {
                return template.substring(1, template.length() - 1);
            }
        }
        return Objects.requireNonNullElse(error.getDefaultMessage(), INVALID_VALUE_KEY);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, ErrorMessage message) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, message.key());
        body.setType(BLANK_TYPE);
        body.setTitle(ProblemTexts.titleKey(status.value()));
        return ResponseEntity.status(status).body(body);
    }
}
