package com.taskly.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Navigation menu (M3x, Todoist Settings → Navigation menu): danh sách mục đang hiện, thứ tự = thứ tự hiện, vắng = ẩn.
 * Phần tử ∈ ALL_ITEMS, không trùng, không null; 0–6 phần tử. Phân biệt hoa thường (như các enum khác của me/settings).
 */
public class NavMenuItemsValidator implements ConstraintValidator<NavMenuItems, List<String>> {

    /** Đủ 6 mục theo thứ tự Todoist — cũng là giá trị mặc định (V7, User.DEFAULT_NAV_MENU_ITEMS). */
    public static final List<String> ALL_ITEMS =
            List.of("INBOX", "TODAY", "UPCOMING", "FILTERS_LABELS", "REPORTING", "BROWSE_TEMPLATES");

    @Override
    public boolean isValid(List<String> value, ConstraintValidatorContext context) {
        return value == null || isValidItems(value);
    }

    public static boolean isValidItems(List<String> items) {
        Set<String> seen = new HashSet<>();
        for (String item : items) {
            if (item == null || !ALL_ITEMS.contains(item) || !seen.add(item)) {
                return false;
            }
        }
        return true;
    }
}
