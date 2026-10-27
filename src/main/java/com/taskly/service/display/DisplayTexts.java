package com.taskly.service.display;

/**
 * Chữ lỗi của Display, đặt TẠM ở đây vì Task 1 của M2 (ProblemTexts) chưa gộp khi làm Task 6. Giá trị khớp từng chữ
 * với {@code ProblemTexts.VIEW_KEY_INVALID} / {@code ProblemTexts.DISPLAY_INVALID} của plan; sau khi Task 1 gộp thì
 * đổi tham chiếu sang ProblemTexts và xoá lớp này.
 */
final class DisplayTexts {

    static final String VIEW_KEY_INVALID = "Khoá view không hợp lệ";
    static final String DISPLAY_INVALID = "Tuỳ chọn hiển thị không hợp lệ với view này";

    private DisplayTexts() {
    }
}
