package com.taskly.service.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * RecurrenceService THUẦN (contract 8.3): cùng bộ vector với lib/recurrence/next.ts của mobile — lệch kết quả = lỗi.
 * Vector: {name, rule, fromCompletion, due ("yyyy-MM-dd" | "yyyy-MM-ddTHH:mm:ss"), completedOn (instant),
 * timezone (múi giờ tài khoản), expectedNext (cùng dạng due | null = hết chu kỳ)}.
 */
class RecurrenceServiceTest {

    private final RecurrenceService service = new RecurrenceService();

    static Stream<Arguments> vectors() throws IOException {
        try (InputStream in = RecurrenceServiceTest.class.getResourceAsStream("/recurrence-vectors.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<Map<String, Object>> list = JsonPath.read(json, "$");
            return list.stream().map(v -> Arguments.of(v.get("name"), v));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void sharedVector(String name, Map<String, Object> vector) {
        RecurrenceService.Due due = RecurrenceService.Due.parse((String) vector.get("due"));
        Optional<RecurrenceService.Due> next = service.next(RecurrenceRule.parse((String) vector.get("rule")),
                (Boolean) vector.get("fromCompletion"), due, Instant.parse((String) vector.get("completedOn")),
                ZoneId.of((String) vector.get("timezone")));

        Object expected = vector.get("expectedNext");
        assertThat(next.map(RecurrenceService.Due::format).orElse(null)).as(name).isEqualTo(expected);
    }

    @Test
    void vectorsCoverEveryMandatoryCase() throws IOException {
        // contract 8.3: cuối tháng, 29/2, cách tuần, 3rd friday, last workday, every!, trễ nhiều kỳ, UNTIL, nửa đêm, 31→30→31
        List<String> names = vectors().map(a -> (String) a.get()[0]).toList();
        assertThat(names).contains("month end 31 -> 30", "month 30 -> back to 31", "month end 31 -> 28 Feb",
                "29 Feb yearly -> 28 Feb", "every other week", "every 3rd friday", "every last workday",
                "every! 3 days counts from the completion day", "overdue weekly jumps to the first occurrence after today",
                "until includes the last day", "until reached ends the cycle",
                "just after midnight in the account zone is already tomorrow");
    }

    @Test
    void due_parseAndFormat_roundTrip() {
        assertThat(RecurrenceService.Due.parse("2026-10-27").date()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(RecurrenceService.Due.parse("2026-10-27").time()).isNull();
        assertThat(RecurrenceService.Due.parse("2026-10-27T09:05:00").toDatetime())
                .isEqualTo(LocalDateTime.of(2026, 10, 27, 9, 5));
        assertThat(RecurrenceService.Due.parse("2026-10-27T09:05:00").format()).isEqualTo("2026-10-27T09:05:00");
    }

    @Test
    void dailyFilteredByMonthDay_clampsToTheLastDayOfAShortMonth() {
        // BYMONTHDAY=30 trong tháng 2 kẹp về ngày cuối tháng (như MONTHLY) → 2027-02-28
        RecurrenceRule rule = RecurrenceRule.parse("FREQ=DAILY;BYMONTH=2;BYMONTHDAY=30;UNTIL=20271231");
        Optional<RecurrenceService.Due> next = service.next(rule, false, RecurrenceService.Due.parse("2026-10-27"),
                Instant.parse("2026-10-27T03:00:00Z"), ZoneId.of("UTC"));
        assertThat(next.map(RecurrenceService.Due::format)).contains("2027-02-28");
    }

    @Test
    void fromCompletion_ignoresAFutureDue() {
        Optional<RecurrenceService.Due> next = service.next(RecurrenceRule.parse("FREQ=DAILY;INTERVAL=2"), true,
                RecurrenceService.Due.parse("2026-11-20"), Instant.parse("2026-10-27T03:00:00Z"), ZoneId.of("UTC"));
        assertThat(next.map(RecurrenceService.Due::format)).contains("2026-10-29");
    }

    @Test
    void untilBeforeTheFirstCandidate_endsTheCycle() {
        Optional<RecurrenceService.Due> next = service.next(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=15;UNTIL=20261110"),
                false, RecurrenceService.Due.parse("2026-10-15"), Instant.parse("2026-10-15T03:00:00Z"), ZoneId.of("UTC"));
        assertThat(next).isEmpty();
    }
}
