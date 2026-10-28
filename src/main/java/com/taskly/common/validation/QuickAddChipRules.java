package com.taskly.common.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chip của Quick Add (M6b, contract mục 2): thứ tự mảng = thứ tự hiển thị, chip vắng = ẩn (vào "More"), PROJECT luôn
 * có. Khoá không chứa dấu phẩy → users.quick_add_chips lưu nối bằng dấu phẩy như nav_tabs.
 */
public final class QuickAddChipRules {

    public static final List<String> ALL = List.of("PROJECT", "DATE", "ASSIGNEE", "ATTACHMENT", "PRIORITY",
            "REMINDERS", "LABELS", "DEADLINE", "LOCATION");

    public static final List<String> DEFAULT = List.of("PROJECT", "DATE", "ASSIGNEE");

    public static final String REQUIRED = "PROJECT";

    private QuickAddChipRules() {
    }

    /** Không rỗng, chỉ chip đã biết (đúng hoa thường), không trùng, có PROJECT. */
    public static boolean isValid(List<String> chips) {
        if (chips == null || chips.isEmpty()) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (String chip : chips) {
            if (chip == null || !ALL.contains(chip) || !seen.add(chip)) {
                return false;
            }
        }
        return seen.contains(REQUIRED);
    }
}
