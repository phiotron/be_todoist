package com.taskly.common.error;

import com.taskly.common.web.RequestIdFilter;
import jakarta.validation.ConstraintViolation;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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

    /** Spring 7 để type = null (Jackson bỏ field); hợp đồng API yêu cầu "about:blank". */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    /** Bean Validation trên @RequestBody → 400 kèm "errors": [{field, message}] (message là key, sẽ được dịch). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ErrorMessage.VALIDATION_FAILED.key());
        List<FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorItem(error.getField(), messageKey(error)))
                .sorted(Comparator.comparing(FieldErrorItem::field).thenComparing(FieldErrorItem::message))
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
            addRequestId(problem);
        }
        return response;
    }

    /** 401 từ Spring Security (ProblemDetailAuthenticationEntryPoint) hoặc CurrentUser. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ErrorMessage.SESSION_INVALID);
    }

    /** 403: ném trong MVC, hoặc chuyển từ ProblemDetailAccessDeniedHandler (Spring Security). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, ErrorMessage.FORBIDDEN);
    }

    /**
     * Hai request cùng sửa một task (@Version) — bản thua nhận 409 thay vì 500. Cùng code "version-conflict" với
     * lệch version do client gửi (spec 12.10) nhưng không có "current" → app tải lại.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT, ErrorMessage.CONCURRENT_UPDATE);
        Objects.requireNonNull(response.getBody()).setProperty("code", ProblemCodes.VERSION_CONFLICT);
        return response;
    }

    /**
     * Khoá hàng của UsageLimits (SELECT … FOR NO KEY UPDATE): bản thua deadlock / hết thời gian chờ khoá nhận 409 thay vì
     * 500 — giao dịch đã bị huỷ hoàn toàn nên app thử lại an toàn. Dùng chung code + chữ với lệch version (không thêm
     * hằng mới): với app đều là "đụng độ đồng thời → tải lại / thử lại".
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handlePessimisticLock(PessimisticLockingFailureException ex) {
        log.warn("Đụng độ khoá hàng: {}", ex.getClass().getSimpleName());
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT, ErrorMessage.CONCURRENT_UPDATE);
        Objects.requireNonNull(response.getBody()).setProperty("code", ProblemCodes.VERSION_CONFLICT);
        return response;
    }

    /** Vi phạm ràng buộc DB → 409. Log tên constraint (không log giá trị — có thể chứa email/nội dung). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        String constraint = ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
                ? cve.getConstraintName() : "không rõ";
        log.warn("Vi phạm ràng buộc dữ liệu: {} (constraint={})", ex.getClass().getSimpleName(), constraint);
        return problem(HttpStatus.CONFLICT, ErrorMessage.DATA_CONFLICT);
    }

    /** Lưới an toàn cuối: không lộ chi tiết nội bộ ra client; stack trace vào log. */
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
        addRequestId(body);
        return ResponseEntity.status(status).body(body);
    }

    /** Mọi ProblemDetail kèm requestId để người dùng báo lỗi và dev tra log (mục 5.9). */
    private static void addRequestId(ProblemDetail problem) {
        String requestId = RequestIdFilter.current();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }
}
