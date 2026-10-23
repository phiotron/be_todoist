package com.taskly.common.error;

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
}
