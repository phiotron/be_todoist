package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.service.recurrence.InvalidRecurrenceException;
import com.taskly.service.recurrence.RecurrenceRule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Quy tắc ĐẦU VÀO của task lặp khi tạo / sửa (M6c, contract 8.1; plan M6c mục 3) — hàm thuần, ném ApiException 400.
 */
public final class TaskRecurrenceRules {

    private TaskRecurrenceRules() {
    }

    /**
     * Rule người dùng gửi → dạng chuẩn để lưu. null → null (không lặp). Ngoài tập con → 400 `recurrence-invalid`; không có
     * due → 400 `recurrence-requires-due`. `every` (không every!) → thêm neo theo ngày của due (RecurrenceRule.anchoredTo).
     */
    public static String normalize(String rule, boolean fromCompletion, LocalDate dueDay) {
        if (rule == null) {
            return null;
        }
        RecurrenceRule parsed = parse(rule);
        if (dueDay == null) {
            throw ApiException.badRequest(ErrorMessage.RECURRENCE_REQUIRES_DUE)
                    .withCode(ProblemCodes.RECURRENCE_REQUIRES_DUE);
        }
        return (fromCompletion ? parsed : parsed.anchoredTo(dueDay)).format();
    }

    /** Như normalize nhưng KHÔNG ném (so nội dung khi phát lại `id` client): rule lỗi giữ nguyên chuỗi gốc. */
    public static String normalizeQuietly(String rule, boolean fromCompletion, LocalDate dueDay) {
        try {
            return normalize(rule, fromCompletion, dueDay);
        } catch (ApiException e) {
            return rule;
        }
    }

    public static RecurrenceRule parse(String rule) {
        try {
            return RecurrenceRule.parse(rule);
        } catch (InvalidRecurrenceException e) {
            throw ApiException.badRequest(ErrorMessage.RECURRENCE_INVALID).withCode(ProblemCodes.RECURRENCE_INVALID);
        }
    }

    /**
     * Múi giờ cố định (spec 3.2: "chỉ múi giờ hiện tại của người dùng"): null → floating. Có giá trị thì task phải có giờ và
     * giá trị phải TRÙNG múi giờ tài khoản lúc ghi; khác → 400.
     */
    public static String checkDueTimezone(String dueTimezone, LocalDateTime dueDatetime, ZoneId accountZone) {
        if (dueTimezone == null) {
            return null;
        }
        if (dueDatetime == null || !dueTimezone.equals(accountZone.getId())) {
            throw ApiException.badRequest(ErrorMessage.DUE_TIMEZONE_INVALID);
        }
        return dueTimezone;
    }
}
