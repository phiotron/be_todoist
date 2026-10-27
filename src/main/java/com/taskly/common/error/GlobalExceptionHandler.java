package com.taskly.common.error;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Mọi lỗi → ProblemDetail (application/problem+json), title/detail/errors[].message đều tiếng Việt
 * (chữ ở ProblemTexts) vì app mobile hiển thị nguyên văn; "code" (ProblemCodes) cho tình huống app cần phân biệt.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Spring 7 để type = null (Jackson bỏ field); hợp đồng API yêu cầu "about:blank". */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ProblemTexts.INVALID_DATA);
        List<FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorItem(error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), ProblemTexts.INVALID_VALUE)))
                .sorted(Comparator.comparing(FieldErrorItem::field).thenComparing(FieldErrorItem::message))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

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

    /** 401 từ Spring Security (ProblemDetailAuthenticationEntryPoint) hoặc CurrentUser. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ProblemTexts.SESSION_INVALID);
    }

    /** 403: ném trong MVC, hoặc chuyển từ ProblemDetailAccessDeniedHandler (Spring Security). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, ProblemTexts.FORBIDDEN);
    }

    /**
     * Hai request cùng sửa một task (@Version) — bản thua nhận 409 thay vì 500. Cùng code "version-conflict" với
     * lệch version do client gửi (spec 12.10) nhưng không có "current" → app tải lại.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT, ProblemTexts.CONCURRENT_UPDATE);
        Objects.requireNonNull(response.getBody()).setProperty("code", ProblemCodes.VERSION_CONFLICT);
        return response;
    }

    /** Vi phạm ràng buộc DB → 409. Log tên constraint (không log giá trị — có thể chứa email/nội dung). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        String constraint = ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
                ? cve.getConstraintName() : "không rõ";
        log.warn("Vi phạm ràng buộc dữ liệu: {} (constraint={})", ex.getClass().getSimpleName(), constraint);
        return problem(HttpStatus.CONFLICT, ProblemTexts.DATA_CONFLICT);
    }

    /** Lưới an toàn cuối: không lộ chi tiết nội bộ ra client; stack trace vào log. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Lỗi không mong đợi", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTexts.SERVER_ERROR);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(BLANK_TYPE);
        body.setTitle(ProblemTexts.title(status.value()));
        return ResponseEntity.status(status).body(body);
    }
}
