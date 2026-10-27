package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.config.MessageSourceConfig;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Unit thuần: bộ dịch thay key bằng câu theo ngôn ngữ, giữ nguyên chuỗi không phải key, sắp errors theo field. */
class ProblemDetailLocalizerTest {

    private final MessageSourceConfig config = new MessageSourceConfig();
    private final ProblemDetailLocalizer localizer =
            new ProblemDetailLocalizer(config.messageSource(), config.localeResolver());

    private static ProblemDetail validationProblem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ErrorMessage.VALIDATION_FAILED.key());
        problem.setTitle(ProblemTexts.titleKey(400));
        problem.setProperty("errors", List.of(
                new FieldErrorItem("password", "validation.password.required"),
                new FieldErrorItem("email", "validation.email.required"),
                new FieldErrorItem("email", "validation.email.invalid")));
        return problem;
    }

    @Test
    void english_translatesTitleDetailAndErrorsThenSortsErrors() {
        ProblemDetail problem = validationProblem();

        localizer.localize(problem, Locale.ENGLISH);

        assertThat(problem.getTitle()).isEqualTo("Invalid data");
        assertThat(problem.getDetail()).isEqualTo("The submitted data is invalid");
        assertThat(problem.getProperties()).containsKey("errors");
        assertThat(problem.getProperties().get("errors")).isEqualTo(List.of(
                new FieldErrorItem("email", "Invalid email address"),
                new FieldErrorItem("email", "Please enter your email"),
                new FieldErrorItem("password", "Please enter your password")));
    }

    @Test
    void vietnamese_translatesToVietnamese() {
        ProblemDetail problem = validationProblem();

        localizer.localize(problem, MessageSourceConfig.VIETNAMESE);

        assertThat(problem.getTitle()).isEqualTo("Dữ liệu không hợp lệ");
        assertThat(problem.getDetail()).isEqualTo("Dữ liệu gửi lên không hợp lệ");
        assertThat(problem.getProperties().get("errors")).isEqualTo(List.of(
                new FieldErrorItem("email", "Email không hợp lệ"),
                new FieldErrorItem("email", "Vui lòng nhập email"),
                new FieldErrorItem("password", "Vui lòng nhập mật khẩu")));
    }

    @Test
    void textThatIsNotAKey_isKeptAsIs_andUnknownStatusGetsDefaultTitle() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.I_AM_A_TEAPOT, "Câu viết cứng chưa có key");

        localizer.localize(problem, Locale.ENGLISH);

        assertThat(problem.getTitle()).isEqualTo("Error");
        assertThat(problem.getDetail()).isEqualTo("Câu viết cứng chưa có key");
    }
}
