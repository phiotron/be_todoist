package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProblemTextsTest {

    @Test
    void titlesCoverM1StatusCodes() {
        assertThat(ProblemTexts.title(403)).isEqualTo("Không có quyền");
        assertThat(ProblemTexts.title(422)).isEqualTo("Không thể xử lý yêu cầu");
        assertThat(ProblemTexts.title(429)).isEqualTo("Quá nhiều yêu cầu");
    }

    @Test
    void defaultDetailsCoverForbiddenAndTooManyRequests() {
        assertThat(ProblemTexts.defaultDetail(403)).isEqualTo(ProblemTexts.FORBIDDEN);
        assertThat(ProblemTexts.defaultDetail(429)).isEqualTo(ProblemTexts.TOO_MANY_REQUESTS);
    }

    @Test
    void sessionInvalidTextIsTheOneMobileShows() {
        assertThat(ProblemTexts.SESSION_INVALID).isEqualTo("Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
    }

    @Test
    void problemCodesMatchSpecSection12_10() {
        assertThat(ProblemCodes.VERSION_CONFLICT).isEqualTo("version-conflict");
        assertThat(ProblemCodes.PROJECT_ARCHIVED).isEqualTo("project-archived");
        assertThat(ProblemCodes.SECTION_ARCHIVED).isEqualTo("section-archived");
        assertThat(ProblemCodes.PARENT_COMPLETED).isEqualTo("parent-completed");
        assertThat(ProblemCodes.TARGET_ARCHIVED).isEqualTo("target-archived");
        assertThat(ProblemCodes.LIMIT_REACHED).isEqualTo("limit-reached");
        assertThat(ProblemCodes.EMAIL_TAKEN).isEqualTo("email-taken");
        assertThat(ProblemCodes.IDEMPOTENCY_IN_PROGRESS).isEqualTo("idempotency-in-progress");
        assertThat(ProblemCodes.IDEMPOTENCY_MISMATCH).isEqualTo("idempotency-mismatch");
        assertThat(ProblemCodes.ID_CONFLICT).isEqualTo("id-conflict");
        assertThat(ProblemCodes.TASK_COMPLETED).isEqualTo("task-completed");
    }
}
