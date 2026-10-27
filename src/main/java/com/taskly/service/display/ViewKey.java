package com.taskly.service.display;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Khoá view: TODAY | UPCOMING | project:<uuid> | label:<uuid> | filter:<uuid> (uuid chữ thường, như API trả). */
public record ViewKey(ViewKind kind, UUID id) {

    private static final Pattern ENTITY_KEY = Pattern.compile(
            "^(project|label|filter):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    public static ViewKey parse(String raw) {
        if ("TODAY".equals(raw)) {
            return new ViewKey(ViewKind.TODAY, null);
        }
        if ("UPCOMING".equals(raw)) {
            return new ViewKey(ViewKind.UPCOMING, null);
        }
        Matcher matcher = raw == null ? null : ENTITY_KEY.matcher(raw);
        if (matcher == null || !matcher.matches()) {
            throw ApiException.badRequest(ProblemTexts.VIEW_KEY_INVALID);
        }
        return new ViewKey(ViewKind.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)),
                UUID.fromString(matcher.group(2)));
    }

    public String format() {
        return id == null ? kind.name() : kind.name().toLowerCase(Locale.ROOT) + ":" + id;
    }
}
