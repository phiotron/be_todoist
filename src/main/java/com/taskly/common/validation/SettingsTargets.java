package com.taskly.common.validation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Định dạng hợp lệ của startPage và navTabs trong me/settings (spec mục 10, 12.1.1, 9.2) — hàm thuần.
 * - startPage: INBOX | TODAY | UPCOMING | project:<uuid> | label:<uuid> | filter:<uuid>.
 * - navTabs: 3–5 phần tử khác nhau; mỗi phần tử ∈ INBOX, TODAY, UPCOMING, SEARCH, FILTERS_LABELS, BROWSE hoặc một mục
 *   ghim; đúng một BROWSE và đứng cuối; tối đa một mục ghim.
 * uuid phải ở dạng chuẩn chữ thường (đúng dạng server trả về). Chỉ kiểm ĐỊNH DẠNG; id ghim có đọc được không do
 * SettingsService kiểm (Task 13b, cần M1).
 */
public final class SettingsTargets {

    public static final String BROWSE = "BROWSE";
    public static final String DEFAULT_START_PAGE = "TODAY";
    public static final List<String> DEFAULT_NAV_TABS = List.of("INBOX", "TODAY", "UPCOMING", BROWSE);
    public static final Set<String> FIXED_START_PAGES = Set.of("INBOX", "TODAY", "UPCOMING");
    public static final Set<String> FIXED_NAV_TABS =
            Set.of("INBOX", "TODAY", "UPCOMING", "SEARCH", "FILTERS_LABELS", BROWSE);
    public static final int MIN_NAV_TABS = 3;
    public static final int MAX_NAV_TABS = 5;

    private static final Pattern PINNED = Pattern.compile(
            "(project|label|filter):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    /** Loại mục ghim; tiền tố trong chuỗi = tên viết thường + ":". */
    public enum PinnedKind {
        PROJECT,
        LABEL,
        FILTER;

        public String prefix() {
            return name().toLowerCase(Locale.ROOT) + ":";
        }
    }

    /** Một mục ghim đã tách: loại + id. */
    public record PinnedTarget(PinnedKind kind, UUID id) {

        public String value() {
            return kind.prefix() + id;
        }
    }

    private SettingsTargets() {
    }

    /** "project:<uuid>" / "label:<uuid>" / "filter:<uuid>" → mục ghim; giá trị khác (kể cả null) → rỗng. */
    public static Optional<PinnedTarget> pinned(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = PINNED.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        PinnedKind kind = PinnedKind.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
        return Optional.of(new PinnedTarget(kind, UUID.fromString(matcher.group(2))));
    }

    public static boolean isValidStartPage(String value) {
        return value != null && (FIXED_START_PAGES.contains(value) || pinned(value).isPresent());
    }

    public static boolean isValidNavTabs(List<String> tabs) {
        if (tabs == null || tabs.size() < MIN_NAV_TABS || tabs.size() > MAX_NAV_TABS) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        int pinnedCount = 0;
        for (String tab : tabs) {
            if (tab == null || !seen.add(tab)) {
                return false;
            }
            if (pinned(tab).isPresent()) {
                pinnedCount++;
            } else if (!FIXED_NAV_TABS.contains(tab)) {
                return false;
            }
        }
        // Không trùng + phần tử cuối là BROWSE ⇒ đúng một BROWSE.
        return pinnedCount <= 1 && BROWSE.equals(tabs.get(tabs.size() - 1));
    }

    /** Các mục ghim có trong danh sách giá trị (startPage + navTabs), theo thứ tự; bỏ qua khoá cố định và null. */
    public static List<PinnedTarget> pinnedIn(Collection<String> values) {
        List<PinnedTarget> result = new ArrayList<>();
        for (String value : values) {
            pinned(value).ifPresent(result::add);
        }
        return result;
    }
}
