package com.taskly.dto.productivity;

import com.taskly.entity.KarmaLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/v1/productivity (M10 plan F4): đủ dữ liệu cho tab Daily / Weekly / Karma. Ngày theo múi giờ tài khoản.
 * karma = null khi người dùng tắt Karma (karmaEnabled = false).
 */
public record ProductivityResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate today,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int dailyGoal,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int weeklyGoal,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Integer> daysOff,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean vacationMode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean karmaEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean goalCelebrations,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Daily daily,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Weekly weekly,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Karma karma) {

    /** Tab Daily: hôm nay + 7 ngày gần nhất (cũ trước, phần tử cuối = hôm nay). */
    public record Daily(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int goal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reached,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int currentStreak,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int longestStreak,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Day> days) {
    }

    public record Day(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate date,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean dayOff,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean vacation) {
    }

    /** Tab Weekly: tuần này (bắt đầu theo startDay) + 4 tuần gần nhất (cũ trước). */
    public record Weekly(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int goal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reached,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int currentStreak,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int longestStreak,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate weekStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Week> weeks) {
    }

    public record Week(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate weekStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completed) {
    }

    /** Tab Karma: điểm (≥ 0), cấp, cấp kế (null khi Enlightened), trend 28 ngày (tổng cuối mỗi ngày), bảng 8 cấp. */
    public record Karma(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long karma,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) KarmaLevel level,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) KarmaLevel nextLevel,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Long nextLevelKarma,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long pointsToNextLevel,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TrendPoint> trend,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LevelInfo> levels) {
    }

    public record TrendPoint(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate date,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long karma) {
    }

    public record LevelInfo(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) KarmaLevel level,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long minKarma) {
    }
}
