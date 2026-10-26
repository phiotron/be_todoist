package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rào chắn: không viết thẳng câu có dấu (tiếng Việt) vào ProblemDetail — phải đi qua key (ErrorMessage, ProblemTexts)
 * để dịch được sang tiếng Anh. Quét từng dòng của src/main/java (Surefire chạy với thư mục làm việc = gốc repo).
 */
class ProblemTextSourceScanTest {

    /** setDetail / setTitle / forStatusAndDetail / new FieldErrorItem(...) có chuỗi chứa ký tự ngoài ASCII. */
    static final Pattern HARDCODED_PROBLEM_TEXT = Pattern.compile(
            "(forStatusAndDetail|setDetail|setTitle|FieldErrorItem)\\([^;]*\"[^\"]*[^\\x00-\\x7F][^\"]*\"");

    @Test
    void mainSourcesDoNotWriteVietnameseStraightIntoProblemDetail() throws IOException {
        Path root = Path.of("src", "main", "java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (HARDCODED_PROBLEM_TEXT.matcher(lines.get(i)).find()) {
                        offenders.add(root.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(offenders).as("ProblemDetail phải dùng key (ErrorMessage / ProblemTexts), không viết câu cứng")
                .isEmpty();
    }

    @Test
    void patternCatchesHardcodedTextButNotKeys() {
        assertThat(HARDCODED_PROBLEM_TEXT.matcher("problem.setDetail(\"Lỗi rồi\");").find()).isTrue();
        assertThat(HARDCODED_PROBLEM_TEXT.matcher(
                "ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, \"Dữ liệu sai\");").find()).isTrue();
        assertThat(HARDCODED_PROBLEM_TEXT.matcher(
                "problem.setDetail(ErrorMessage.SESSION_INVALID.key());").find()).isFalse();
        assertThat(HARDCODED_PROBLEM_TEXT.matcher(
                "problem.setTitle(ProblemTexts.titleKey(problem.getStatus()));").find()).isFalse();
    }
}
