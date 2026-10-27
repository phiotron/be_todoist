package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Mọi câu lỗi M2 nằm ở ProblemTexts, mọi `code` M2 nằm ở ProblemCodes (M3 sẽ chuyển câu thành key i18n như hằng M1). */
class M2ProblemTextsTest {

    @Test
    void m2Texts_areNonBlankAndDistinct() {
        List<String> texts = List.of(
                ProblemTexts.SEARCH_QUERY_TOO_LONG,
                ProblemTexts.LABEL_NOT_FOUND,
                ProblemTexts.LABEL_NAME_SIZE,
                ProblemTexts.LABEL_NAME_CHARS,
                ProblemTexts.LABEL_NAME_TAKEN,
                ProblemTexts.LABELS_PER_TASK,
                ProblemTexts.FILTER_NOT_FOUND,
                ProblemTexts.FILTER_NAME_SIZE,
                ProblemTexts.FILTER_QUERY_SIZE,
                ProblemTexts.FILTER_SYNTAX,
                ProblemTexts.VIEW_KEY_INVALID,
                ProblemTexts.DISPLAY_INVALID,
                ProblemTexts.TASK_LIST_SELECTOR,
                ProblemTexts.BATCH_LABEL_IDS,
                ProblemTexts.ID_CONFLICT,
                ProblemTexts.UPCOMING_WINDOW_INVALID);

        assertThat(texts).doesNotHaveDuplicates().allSatisfy(text -> assertThat(text).isNotBlank());
    }

    /** Mã `code` máy đọc được của M2 (spec 12.10) — app so chuỗi này, không so câu tiếng Việt. */
    @Test
    void m2Codes_matchSpec1210() {
        assertThat(ProblemCodes.FILTER_SYNTAX).isEqualTo("filter-syntax");
        assertThat(ProblemCodes.LABEL_NAME_TAKEN).isEqualTo("label-name-taken");
        assertThat(ProblemCodes.ID_CONFLICT).isEqualTo("id-conflict");
        assertThat(ProblemCodes.LIMIT_REACHED).isEqualTo("limit-reached");
    }

    /** Câu M2 đã được các task thuần dùng tạm ở nơi khác — gộp về ProblemTexts, giữ nguyên từng chữ. */
    @Test
    void m2Texts_keepWordingUsedByPureTasks() {
        assertThat(ProblemTexts.SEARCH_QUERY_TOO_LONG).isEqualTo("Từ khoá tìm kiếm tối đa 100 ký tự");
        assertThat(ProblemTexts.FILTER_SYNTAX).isEqualTo("Truy vấn filter sai cú pháp");
        assertThat(ProblemTexts.VIEW_KEY_INVALID).isEqualTo("Khoá view không hợp lệ");
        assertThat(ProblemTexts.DISPLAY_INVALID).isEqualTo("Tuỳ chọn hiển thị không hợp lệ với view này");
    }
}
