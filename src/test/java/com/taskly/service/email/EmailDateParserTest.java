package com.taskly.service.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Bộ đọc ngày tiếng Anh tối giản của `date …` trong tiêu đề thư (M13a). "Hôm nay" cố định: thứ Tư 28/10/2026. */
class EmailDateParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private static Optional<EmailDateParser.Result> parse(String text) {
        List<String> tokens = text.isBlank() ? List.of() : Arrays.asList(text.split(" "));
        return EmailDateParser.parse(tokens, TODAY);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "today, 2026-10-28",
            "tod, 2026-10-28",
            "tomorrow, 2026-10-29",
            "tom, 2026-10-29",
            "friday, 2026-10-30",
            "fri, 2026-10-30",
            "wed, 2026-10-28",
            "mon, 2026-11-02",
            "Sunday, 2026-11-01",
            "next week, 2026-11-02",
            "in 3 days, 2026-10-31",
            "in 1 day, 2026-10-29",
            "in 2 weeks, 2026-11-11",
            "in 1 month, 2026-11-28",
            "2026-12-01, 2026-12-01",
            "5 nov, 2026-11-05",
            "nov 5, 2026-11-05",
            "nov 5th, 2026-11-05",
            "1st december, 2026-12-01",
            "5 nov 2027, 2027-11-05",
            "november 5 2027, 2027-11-05",
            "jan 3, 2027-01-03",
            "oct 28, 2026-10-28",
            "sept 2, 2027-09-02"
    })
    void datesWithoutTime(String text, LocalDate expected) {
        assertThat(parse(text)).hasValueSatisfying(result -> {
            assertThat(result.date()).isEqualTo(expected);
            assertThat(result.time()).isNull();
        });
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource({
            "tomorrow 5pm, 2026-10-29, 17:00",
            "tomorrow at 17:30, 2026-10-29, 17:30",
            "at 9am, 2026-10-28, 09:00",
            "5:30pm, 2026-10-28, 17:30",
            "fri 5 pm, 2026-10-30, 17:00",
            "12am, 2026-10-28, 00:00",
            "12pm, 2026-10-28, 12:00",
            "nov 5 at 8:05, 2026-11-05, 08:05",
            "today 23:59, 2026-10-28, 23:59"
    })
    void datesWithTime(String text, LocalDate date, LocalTime time) {
        assertThat(parse(text)).hasValueSatisfying(result -> {
            assertThat(result.date()).isEqualTo(date);
            assertThat(result.time()).isEqualTo(time);
        });
    }

    @ParameterizedTest(name = "\"{0}\" -> không hiểu")
    @ValueSource(strings = {"", "banana", "5", "in x days", "in 0 days", "25:00", "13pm", "2026-02-30", "feb 30",
            "tomorrow banana", "at", "next", "today tomorrow", "in 3", "5 nov 99999"})
    void rejectsWhatItCannotFullyRead(String text) {
        assertThat(parse(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tomorrow,", "TOMORROW", "tomorrow."})
    void ignoresCaseAndTrailingPunctuation(String text) {
        assertThat(parse(text)).hasValueSatisfying(result -> assertThat(result.date()).isEqualTo(TODAY.plusDays(1)));
    }
}
