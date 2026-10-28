package com.taskly.service.sync;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * syncToken = "<xmin>.<epoch giây lúc phát>" (plan M9 F3). xmin: mốc xid để lấy thay đổi; giờ phát: token cũ hơn hạn giữ
 * tombstone → bắt full sync. null / "" / "*" = full sync (parse trả null). Sai dạng → 400 sync-token-invalid.
 */
public record SyncToken(long xmin, Instant issuedAt) {

    public static final String FULL = "*";
    private static final Pattern FORMAT = Pattern.compile("^(\\d{1,19})\\.(\\d{1,12})$");

    public static SyncToken parse(String raw) {
        if (raw == null || raw.isBlank() || FULL.equals(raw)) {
            return null;
        }
        Matcher m = FORMAT.matcher(raw);
        if (!m.matches()) {
            throw invalid();
        }
        try {
            return new SyncToken(Long.parseLong(m.group(1)), Instant.ofEpochSecond(Long.parseLong(m.group(2))));
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    public String format() {
        return xmin + "." + issuedAt.getEpochSecond();
    }

    private static ApiException invalid() {
        return ApiException.badRequest(ErrorMessage.SYNC_TOKEN_INVALID).withCode(ProblemCodes.SYNC_TOKEN_INVALID);
    }
}
