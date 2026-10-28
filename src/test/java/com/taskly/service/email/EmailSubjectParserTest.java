package com.taskly.service.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Cú pháp trong tiêu đề thư (M13a): `date …`, `@nhãn`, `p1`–`p3` (`p4`), `+người` (giữ nguyên tới M8b). */
class EmailSubjectParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final UUID HOME = UUID.randomUUID();
    private static final UUID WORK = UUID.randomUUID();
    private static final Map<String, UUID> LABELS = Map.of("home", HOME, "work", WORK);
    private static final Function<String, Optional<UUID>> RESOLVER =
            name -> Optional.ofNullable(LABELS.get(name.toLowerCase(Locale.ROOT)));

    private static EmailSubjectParser.Parsed parse(String subject) {
        return EmailSubjectParser.parse(subject, TODAY, RESOLVER);
    }

    @Test
    void plainSubjectBecomesTheTaskName() {
        EmailSubjectParser.Parsed parsed = parse("  Buy   milk ");
        assertThat(parsed.content()).isEqualTo("Buy milk");
        assertThat(parsed.priority()).isNull();
        assertThat(parsed.labelIds()).isEmpty();
        assertThat(parsed.dueDate()).isNull();
        assertThat(parsed.dueDatetime()).isNull();
    }

    @Test
    void stripsReplyAndForwardPrefixes() {
        assertThat(parse("Fwd: Buy milk").content()).isEqualTo("Buy milk");
        assertThat(parse("RE: Fw: FWD:Buy milk").content()).isEqualTo("Buy milk");
        assertThat(parse("Rework plan").content()).isEqualTo("Rework plan");
    }

    @Test
    void readsPriorityLabelsAndDateTogether() {
        EmailSubjectParser.Parsed parsed = parse("Pay bills p1 @home date tomorrow 5pm");
        assertThat(parsed.content()).isEqualTo("Pay bills");
        assertThat(parsed.priority()).isEqualTo(1);
        assertThat(parsed.labelIds()).containsExactly(HOME);
        assertThat(parsed.dueDate()).isNull();
        assertThat(parsed.dueDatetime()).isEqualTo(LocalDateTime.of(2026, 10, 29, 17, 0));
    }

    @Test
    void dateWithoutTimeSetsDueDate() {
        EmailSubjectParser.Parsed parsed = parse("Call mom date friday");
        assertThat(parsed.content()).isEqualTo("Call mom");
        assertThat(parsed.dueDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(parsed.dueDatetime()).isNull();
    }

    @Test
    void dateTakesTheLongestReadablePrefix_restStaysInTheName() {
        EmailSubjectParser.Parsed parsed = parse("Report date tomorrow banana");
        assertThat(parsed.content()).isEqualTo("Report banana");
        assertThat(parsed.dueDate()).isEqualTo(LocalDate.of(2026, 10, 29));
    }

    @Test
    void dateStopsAtTheNextSyntaxToken() {
        EmailSubjectParser.Parsed parsed = parse("Report date nov 5 p2 tail");
        assertThat(parsed.content()).isEqualTo("Report tail");
        assertThat(parsed.priority()).isEqualTo(2);
        assertThat(parsed.dueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void unreadableDateIsLeftAsText() {
        EmailSubjectParser.Parsed parsed = parse("Meet date banana");
        assertThat(parsed.content()).isEqualTo("Meet date banana");
        assertThat(parsed.dueDate()).isNull();
    }

    @Test
    void unknownLabelsStayInTheName_knownOnesMatchIgnoringCase_once() {
        EmailSubjectParser.Parsed parsed = parse("Task @unknown @WORK @work @");
        assertThat(parsed.content()).isEqualTo("Task @unknown @");
        assertThat(parsed.labelIds()).containsExactly(WORK);
    }

    @Test
    void plusPersonIsKeptUntilAssigneesExist() {
        assertThat(parse("Task +john").content()).isEqualTo("Task +john");
    }

    @Test
    void lastPriorityWins_p4AllowedButNotP5() {
        assertThat(parse("Task p2 P3").priority()).isEqualTo(3);
        assertThat(parse("Task p4").priority()).isEqualTo(4);
        EmailSubjectParser.Parsed p5 = parse("Plan p5");
        assertThat(p5.content()).isEqualTo("Plan p5");
        assertThat(p5.priority()).isNull();
    }

    @Test
    void onlySyntaxLeavesAnEmptyName() {
        assertThat(parse("p1 @home").content()).isEmpty();
        assertThat(parse(null).content()).isEmpty();
    }

    @Test
    void nameIsCutAt500Characters() {
        assertThat(parse("a".repeat(600)).content()).hasSize(500);
    }
}
