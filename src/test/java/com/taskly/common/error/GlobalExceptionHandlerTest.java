package com.taskly.common.error;

import org.slf4j.MDC;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

/** Unit thuần (không Spring context): mỗi handler trả ProblemDetail tiếng Việt, có type, không lộ chi tiết nội bộ. */
class GlobalExceptionHandlerTest {

    private static final URI BLANK = URI.create("about:blank");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static ServletWebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/auth/register"));
    }

    private static ProblemDetail problemOf(ResponseEntity<?> response) {
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        return (ProblemDetail) response.getBody();
    }

    @Test
    void unexpectedException_returns500WithGenericDetail() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpected(new IllegalStateException("SELECT * FROM users WHERE email='a@b.c'"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("Lỗi máy chủ");
        assertThat(problem.getDetail()).isEqualTo("Đã có lỗi phía máy chủ, vui lòng thử lại sau");
        assertThat(problem.getDetail()).doesNotContain("SELECT").doesNotContain("a@b.c");
    }

    @Test
    void dataIntegrityViolation_returns409WithoutConstraintName() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique constraint uq_users_email"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("Xung đột dữ liệu");
        assertThat(problem.getDetail()).isEqualTo("Dữ liệu bị trùng hoặc xung đột với bản ghi đã có");
        assertThat(problem.getDetail()).doesNotContain("uq_users_email");
    }

    @Test
    void methodNotSupported_returns405InVietnamese() throws Exception {
        ResponseEntity<Object> response =
                handler.handleException(new HttpRequestMethodNotSupportedException("DELETE"), request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("Phương thức không được hỗ trợ");
        assertThat(problem.getDetail()).isEqualTo("Đường dẫn này không hỗ trợ phương thức HTTP đã gửi");
    }

    @Test
    void mediaTypeNotSupported_returns415InVietnamese() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("Định dạng dữ liệu không được hỗ trợ");
        assertThat(problem.getDetail()).isEqualTo("Chỉ chấp nhận dữ liệu JSON");
    }

    @Test
    void apiException_keepsItsOwnDetailAndGetsTypeAndTitle() throws Exception {
        ResponseEntity<Object> response =
                handler.handleException(ApiException.conflict("Email đã được dùng"), request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("Xung đột dữ liệu");
        assertThat(problem.getDetail()).isEqualTo("Email đã được dùng");
    }

    @Test
    void accessDenied_returns403ProblemDetail() {
        ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(new AccessDeniedException("denied"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("Không có quyền");
        assertThat(problem.getDetail()).isEqualTo("Bạn không có quyền thực hiện thao tác này");
    }

    @Test
    void optimisticLock_returns409WithVersionConflictCode() {
        ResponseEntity<ProblemDetail> response =
                handler.handleOptimisticLock(new OptimisticLockingFailureException("row was updated"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getDetail()).isEqualTo("Dữ liệu đã được thay đổi ở nơi khác, hãy tải lại");
        assertThat(problem.getDetail()).doesNotContain("row");
        assertThat(problem.getProperties()).containsEntry("code", "version-conflict");
    }

    @Test
    void apiException_unprocessable_has422VietnameseTitle() throws Exception {
        ResponseEntity<Object> response =
                handler.handleException(ApiException.unprocessable(ProblemTexts.TARGET_ARCHIVED), request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo("Không thể xử lý yêu cầu");
        assertThat(problem.getDetail()).isEqualTo("Nơi chuyển đến đang được lưu trữ");
    }

    @Test
    void apiException_tooManyRequests_keepsRetryAfterHeader() throws Exception {
        ResponseEntity<Object> response =
                handler.handleException(ApiException.tooManyRequests(ProblemTexts.TOO_MANY_REQUESTS, 42), request());

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
        assertThat(problemOf(response).getTitle()).isEqualTo("Quá nhiều yêu cầu");
    }

    @Test
    void apiException_withProperty_addsFieldToBody() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                ApiException.conflict(ProblemTexts.VERSION_CONFLICT).withProperty("current", "bản mới"), request());

        ProblemDetail problem = problemOf(response);
        assertThat(problem.getProperties()).containsEntry("current", "bản mới");
        assertThat(problem.getDetail()).isEqualTo("Đã có thay đổi ở nơi khác");
    }

    @Test
    void apiException_withCode_addsMachineReadableCode() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                ApiException.conflict(ProblemTexts.PROJECT_ARCHIVED).withCode(ProblemCodes.PROJECT_ARCHIVED), request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "project-archived");
        assertThat(problem.getDetail()).isEqualTo("Dự án đã lưu trữ");
    }

    @Test
    void problemDetail_includesRequestIdFromMdc() {
        MDC.put("requestId", "rid-123");
        try {
            ProblemDetail problem = problemOf(handler.handleUnexpected(new IllegalStateException("x")));
            assertThat(problem.getProperties()).containsEntry("requestId", "rid-123");
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    void frameworkProblemDetail_includesRequestIdFromMdc() throws Exception {
        MDC.put("requestId", "rid-456");
        try {
            ProblemDetail problem = problemOf(handler.handleException(ApiException.notFound("Không tìm thấy dự án"), request()));
            assertThat(problem.getProperties()).containsEntry("requestId", "rid-456");
        } finally {
            MDC.remove("requestId");
        }
    }
}
