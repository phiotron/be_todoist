package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.support.Texts;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mọi câu lỗi M2 nằm ở ErrorMessage + messages_vi.properties, mọi `code` M2 nằm ở ProblemCodes (M3 sẽ chuyển câu thành key i18n như hằng M1). */
class M2ProblemTextsTest {

    @Test
    void m2Texts_areNonBlankAndDistinct() {
        List<String> texts = List.of(
                Texts.vi(ErrorMessage.SEARCH_QUERY_TOO_LONG),
                Texts.vi(ErrorMessage.LABEL_NOT_FOUND),
                Texts.vi(ErrorMessage.LABEL_NAME_SIZE),
                Texts.vi(ErrorMessage.LABEL_NAME_CHARS),
                Texts.vi(ErrorMessage.LABEL_NAME_TAKEN),
                Texts.vi(ErrorMessage.LABELS_PER_TASK),
                Texts.vi(ErrorMessage.FILTER_NOT_FOUND),
                Texts.vi(ErrorMessage.FILTER_NAME_SIZE),
                Texts.vi(ErrorMessage.FILTER_QUERY_SIZE),
                Texts.vi(ErrorMessage.FILTER_SYNTAX),
                Texts.vi(ErrorMessage.VIEW_KEY_INVALID),
                Texts.vi(ErrorMessage.DISPLAY_INVALID),
                Texts.vi(ErrorMessage.TASK_LIST_SELECTOR),
                Texts.vi(ErrorMessage.BATCH_LABEL_IDS),
                Texts.vi(ErrorMessage.ID_CONFLICT),
                Texts.vi(ErrorMessage.UPCOMING_WINDOW_INVALID));

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
        assertThat(Texts.vi(ErrorMessage.SEARCH_QUERY_TOO_LONG)).isEqualTo("Từ khoá tìm kiếm tối đa 100 ký tự");
        assertThat(Texts.vi(ErrorMessage.FILTER_SYNTAX)).isEqualTo("Truy vấn filter sai cú pháp");
        assertThat(Texts.vi(ErrorMessage.VIEW_KEY_INVALID)).isEqualTo("Khoá view không hợp lệ");
        assertThat(Texts.vi(ErrorMessage.DISPLAY_INVALID)).isEqualTo("Tuỳ chọn hiển thị không hợp lệ với view này");
    }
}
