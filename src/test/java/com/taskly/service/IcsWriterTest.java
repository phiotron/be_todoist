package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.entity.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phần thuần của calendar feed (M13b): quy tắc lặp tối đa 7, thoát ký tự, gập dòng 75 octet. */
class IcsWriterTest {

    private static Task task(String content, LocalDate due) {
        return new Task(UUID.randomUUID(), UUID.randomUUID(), null, null, UUID.randomUUID(), content, "", 4, due, null,
                null, null, 1024);
    }

    @Test
    void limitedRuleCapsAtSevenOccurrences() {
        assertThat(IcsWriter.limitedRule("FREQ=DAILY")).isEqualTo("FREQ=DAILY;COUNT=7");
        assertThat(IcsWriter.limitedRule("RRULE:FREQ=WEEKLY;BYDAY=MO,WE")).isEqualTo("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=7");
        assertThat(IcsWriter.limitedRule("FREQ=DAILY;COUNT=30")).isEqualTo("FREQ=DAILY;COUNT=7");
        assertThat(IcsWriter.limitedRule("FREQ=MONTHLY;UNTIL=20271231")).isEqualTo("FREQ=MONTHLY;UNTIL=20271231");
        assertThat(IcsWriter.limitedRule(null)).isNull();
        assertThat(IcsWriter.limitedRule("  ")).isNull();
        assertThat(IcsWriter.limitedRule("INTERVAL=2")).isNull();
    }

    @Test
    void escapesSpecialCharactersAndNewlines() {
        assertThat(IcsWriter.escape("a;b,c\\d\ne")).isEqualTo("a\\;b\\,c\\\\d\\ne");
    }

    @Test
    void foldsLongLinesWithoutSplittingACharacter() {
        String line = "SUMMARY:" + "việt nam ".repeat(30);

        String folded = IcsWriter.fold(line);

        for (String part : folded.split("\r\n")) {
            assertThat(part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        assertThat(folded.replace("\r\n ", "")).isEqualTo(line);
        assertThat(IcsWriter.fold("short")).isEqualTo("short");
    }

    @Test
    void writesAnAllDayEventWithCrlfAndSkipsTasksWithoutDue() {
        Task dated = task("Việc", LocalDate.of(2026, 11, 2));
        Task undated = task("Không ngày", null);

        String ics = IcsWriter.write("Taskly", List.of(dated, undated), ZoneId.of("UTC"), 60,
                Instant.parse("2026-10-28T00:00:00Z"));

        assertThat(ics).contains("DTSTART;VALUE=DATE:20261102\r\n", "DTEND;VALUE=DATE:20261103\r\n",
                "DTSTAMP:20261028T000000Z\r\n", "X-WR-CALNAME:Taskly\r\n").doesNotContain("Không ngày");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
    }
}
