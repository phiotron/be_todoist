package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemTexts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Quy tắc due / quá hạn (spec 5.1, 5.2, BR-04) — hàm thuần. Giờ FLOATING: due_datetime là LocalDateTime không múi
 * giờ; "bây giờ" để so = giờ treo tường theo múi giờ tài khoản (localNow). Không bao giờ đổi due sang Instant.
 */
public final class TaskDueRules {

    /** Due sau khi đổi (một trong hai, hoặc cả hai null = không có due). */
    public record Due(LocalDate date, LocalDateTime datetime) {
    }

    private TaskDueRules() {
    }

    /** "Hôm nay" theo múi giờ tài khoản. Không dùng Clock.withZone để tránh phụ thuộc bản Clock của test. */
    public static LocalDate today(Instant now, ZoneId zone) {
        return LocalDate.ofInstant(now, zone);
    }

    /** "Bây giờ" để so với due floating (spec 8.2.5). */
    public static LocalDateTime localNow(Instant now, ZoneId zone) {
        return LocalDateTime.ofInstant(now, zone);
    }

    /**
     * CỜ TÔ ĐỎ `isOverdue` (không phải khối Quá hạn — xem isInOverdueBlock). Đã xong → không quá hạn. Có giờ → quá
     * hạn khi due_datetime < bây giờ (kể cả trong hôm nay). Chỉ có ngày → quá hạn khi due_date < hôm nay. Không có
     * due → không quá hạn. Deadline không xét (BR-04). Filter `overdue` / `od` của M2 dùng định nghĩa này.
     */
    public static boolean isOverdue(LocalDate dueDate, LocalDateTime dueDatetime, Instant completedAt,
                                    LocalDateTime localNow) {
        if (completedAt != null) {
            return false;
        }
        if (dueDatetime != null) {
            return dueDatetime.isBefore(localNow);
        }
        return dueDate != null && dueDate.isBefore(localNow.toLocalDate());
    }

    /** = cột sinh tasks.due_day (spec 10): ngày của due (chỉ ngày hoặc ngày của due có giờ); không có due → null. */
    public static LocalDate dueDay(LocalDate dueDate, LocalDateTime dueDatetime) {
        if (dueDate != null) {
            return dueDate;
        }
        return dueDatetime == null ? null : dueDatetime.toLocalDate();
    }

    /**
     * KHỐI Quá hạn (spec 5.1, 3.1 — số đếm của Reschedule, view=overdue, phần đầu của view=today): chưa xong VÀ
     * due_day < hôm nay, có giờ hay không. Task có giờ đã qua nhưng vẫn TRONG hôm nay KHÔNG thuộc khối này (nó ở khối
     * Hôm nay, chỉ tô đỏ nhờ isOverdue). Hàm thuần mô tả điều kiện SQL `due_day < :today` của TaskViewService.
     */
    public static boolean isInOverdueBlock(LocalDate dueDate, LocalDateTime dueDatetime, Instant completedAt,
                                           LocalDate today) {
        LocalDate day = dueDay(dueDate, dueDatetime);
        return completedAt == null && day != null && day.isBefore(today);
    }

    /** Server cắt due có giờ về phút (spec 5.2). */
    public static LocalDateTime normalize(LocalDateTime dueDatetime) {
        return dueDatetime == null ? null : dueDatetime.truncatedTo(ChronoUnit.MINUTES);
    }

    public static void checkSingleDue(LocalDate dueDate, LocalDateTime dueDatetime) {
        if (dueDate != null && dueDatetime != null) {
            throw ApiException.badRequest(ProblemTexts.DUE_CONFLICT);
        }
    }

    /** Duration chỉ có khi task có giờ (spec 5.2) — gửi duration cho task không có giờ → 400. */
    public static void checkDuration(LocalDateTime dueDatetime, Integer durationMinutes) {
        if (durationMinutes != null && dueDatetime == null) {
            throw ApiException.badRequest(ProblemTexts.DURATION_NEEDS_TIME);
        }
    }

    /**
     * Dời due sang newDate (chọn nhiều → Lên lịch, Reschedule quá hạn — spec 12.5): task có giờ giữ giờ; task chỉ
     * có ngày hoặc chưa có due → chỉ ngày; newDate null → bỏ due.
     */
    public static Due reschedule(LocalDate dueDate, LocalDateTime dueDatetime, LocalDate newDate) {
        if (newDate == null) {
            return new Due(null, null);
        }
        if (dueDatetime != null) {
            return new Due(null, newDate.atTime(dueDatetime.toLocalTime()));
        }
        return new Due(newDate, null);
    }
}
