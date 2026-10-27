package com.taskly.service.filterquery;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.Texts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;

import com.taskly.service.filterquery.FilterQueryParser.Part;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Unit thuần cho FilterQueryParser: mỗi từ khoá, ưu tiên toán tử, thoát ký tự, vị trí lỗi (spec 12.7, 14.1). */
class FilterQueryParserTest {

    static Stream<Arguments> validQueries() {
        return Stream.of(
                // Ngày (due)
                arguments("today", "due=today"),
                arguments("Today", "due=today"),
                arguments("hôm nay", "due=today"),
                arguments("Hôm Nay", "due=today"),
                arguments("tomorrow", "due=today+1"),
                arguments("ngày mai", "due=today+1"),
                arguments("yesterday", "due=today-1"),
                arguments("hôm qua", "due=today-1"),
                arguments("overdue", "overdue"),
                arguments("od", "overdue"),
                arguments("quá hạn", "overdue"),
                arguments("no date", "no-date"),
                arguments("no   date", "no-date"),
                arguments("không có ngày", "no-date"),
                arguments("no time", "no-time"),
                arguments("date: 2026-11-05", "due=2026-11-05"),
                arguments("date:05/11", "due=--11-05"),
                arguments("date : 5/11", "due=--11-05"),
                arguments("date before: 05/11/2026", "due<2026-11-05"),
                arguments("date after: today", "due>today"),
                arguments("date: hôm nay", "due=today"),
                arguments("next 7 days", "next-days=7"),
                arguments("3 days", "next-days=3"),
                arguments("1 day", "next-days=1"),
                arguments("2026-11-05", "due=2026-11-05"),
                arguments("5/11", "due=--11-05"),
                arguments("29/02", "due=--02-29"),
                // Ngày-giờ floating (yyyy-MM-ddTHH:mm[:ss]) — giây cắt về phút
                arguments("date: 2026-11-05T09:00", "due=2026-11-05T09:00:00"),
                arguments("date : 2026-11-05T09:30:45", "due=2026-11-05T09:30:00"),
                arguments("date before: 2026-11-05T18:00:00", "due<2026-11-05T18:00:00"),
                arguments("date after: 2026-11-05t08:15", "due>2026-11-05T08:15:00"),
                arguments("2026-11-05T09:00", "due=2026-11-05T09:00:00"),
                arguments("created after: 2026-01-01T08:00", "created>2026-01-01T08:00:00"),
                // Deadline
                arguments("deadline: 2026-12-31", "deadline=2026-12-31"),
                arguments("deadline before: tomorrow", "deadline<today+1"),
                arguments("deadline after: 1/1/2027", "deadline>2027-01-01"),
                arguments("no deadline", "no-deadline"),
                // Ưu tiên
                arguments("p1", "p1"),
                arguments("P4", "p4"),
                arguments("no priority", "p4"),
                // Vị trí
                arguments("#Work", "#Work"),
                arguments("##Work", "##Work"),
                arguments("#Dự án mới", "#Dự án mới"),
                arguments("# Work", "#Work"),
                arguments("#Work*", "#Work%"),
                arguments("#50\\%", "#50\\%"),
                arguments("#R\\&D", "#R&D"),
                arguments("#A\\, B", "#A, B"),
                arguments("#a_b", "#a\\_b"),
                arguments("/Meetings", "/Meetings"),
                arguments("/*", "/*"),
                arguments("!/*", "!/*"),
                arguments("#Work & /Meetings", "(#Work & /Meetings)"),
                // Label
                arguments("@home", "@home"),
                arguments("%home", "@home"),
                arguments("@ho*", "@ho%"),
                arguments("no labels", "no-labels"),
                // Khác
                arguments("search: họp nhóm", "search=họp nhóm"),
                arguments("search:a_b", "search=a\\_b"),
                arguments("search: báo*cáo", "search=báo%cáo"),
                arguments("created: today", "created=today"),
                arguments("created before: 2026-01-01", "created<2026-01-01"),
                arguments("created after: yesterday", "created>today-1"),
                arguments("subtask", "subtask"),
                arguments("!subtask", "!subtask"),
                arguments("recurring", "recurring"),
                // Toán tử, ưu tiên, ngoặc
                arguments("p1 | p2", "(p1 | p2)"),
                arguments("p1 | p2 & @home", "(p1 | (p2 & @home))"),
                arguments("(p1 | p2) & @home", "((p1 | p2) & @home)"),
                arguments("!p1 & !p2", "(!p1 & !p2)"),
                arguments("!!p1", "!!p1"),
                arguments("!(today | overdue)", "!(due=today | overdue)"),
                arguments("p1 | p2 | p3", "((p1 | p2) | p3)"),
                arguments("today & #Work & @home", "((due=today & #Work) & @home)"),
                arguments("  today  ", "due=today"),
                arguments("(((p1)))", "p1"),
                arguments("p1&p2", "(p1 & p2)"));
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @MethodSource("validQueries")
    void parsesValidQuery(String query, String expected) {
        assertThat(FilterQueryParser.parse(query, DateOrder.DMY).describe()).isEqualTo(expected);
    }

    static Stream<Arguments> invalidQueries() {
        return Stream.of(
                arguments("", 0, "empty"),
                arguments("   ", 0, "empty"),
                arguments("p1 |", 4, "expected-term"),
                arguments("p1 & ", 4, "expected-term"),
                arguments("p1 | (p2 & ", 10, "expected-term"),
                arguments("| p1", 0, "unexpected-token"),
                arguments("(p1", 3, "expected-rparen"),
                arguments("(p1 & p2", 8, "expected-rparen"),
                arguments("p1)", 2, "unexpected-token"),
                arguments("()", 1, "unexpected-token"),
                arguments("p1 !p2", 3, "unexpected-token"),
                arguments("p5", 0, "unknown-keyword"),
                arguments("hello", 0, "unknown-keyword"),
                arguments("p1 p2", 0, "unknown-keyword"),
                arguments("today & bogus", 8, "unknown-keyword"),
                arguments("#", 0, "empty-name"),
                arguments("##", 0, "empty-name"),
                arguments("@", 0, "empty-name"),
                arguments("/", 0, "empty-name"),
                arguments("date: 32/13", 0, "invalid-date"),
                arguments("date: 2026-02-30", 0, "invalid-date"),
                arguments("31/02", 0, "invalid-date"),
                arguments("date before: soon", 0, "invalid-date"),
                arguments("date:", 0, "invalid-date"),
                arguments("deadline: 2026-12-31T09:00", 0, "invalid-date"),
                arguments("date: 2026-11-05T25:00", 0, "invalid-date"),
                arguments("date: 2026-11-05T09", 0, "invalid-date"),
                arguments("next 0 days", 0, "invalid-number"),
                arguments("search:", 0, "empty-value"),
                arguments("assigned to: me", 0, "unsupported"),
                arguments("(p1, p2)", 3, "comma-in-parens"),
                arguments("p1,,p2", 3, "empty-part"),
                arguments("p1,", 3, "empty-part"),
                arguments("today\\", 5, "dangling-escape"),
                arguments("p".repeat(1025), 1024, "too-long"));
    }

    @ParameterizedTest(name = "[{index}] {0} → {2} @ {1}")
    @MethodSource("invalidQueries")
    void rejectsInvalidQueryWithPosition(String query, int position, String reason) {
        assertThatThrownBy(() -> FilterQueryParser.parseAll(query, DateOrder.DMY))
                .isInstanceOfSatisfying(FilterSyntaxException.class, ex -> {
                    assertThat(ex.position()).isEqualTo(position);
                    assertThat(ex.reason()).isEqualTo(reason);
                });
    }

    @Test
    void parse_rejectsTopLevelCommaAtItsPosition() {
        assertThatThrownBy(() -> FilterQueryParser.parse("p1, p2", DateOrder.DMY))
                .isInstanceOfSatisfying(FilterSyntaxException.class, ex -> {
                    assertThat(ex.position()).isEqualTo(2);
                    assertThat(ex.reason()).isEqualTo("comma");
                });
    }

    @Test
    void split_returnsTrimmedPartsWithOffsets() {
        assertThat(FilterQueryParser.split("p1, @home ,  #Work"))
                .containsExactly(new Part("p1", 0), new Part("@home", 4), new Part("#Work", 13));
        assertThat(FilterQueryParser.split("#A\\, B")).containsExactly(new Part("#A\\, B", 0));
        assertThat(FilterQueryParser.split("(p1 | p2), today")).extracting(Part::text)
                .containsExactly("(p1 | p2)", "today");
    }

    @Test
    void parseAll_parsesEachPart_andReportsPositionsInWholeQuery() {
        assertThat(FilterQueryParser.parseAll("today, p1 | @home", DateOrder.DMY))
                .extracting(FilterNode::describe).containsExactly("due=today", "(p1 | @home)");
        assertThatThrownBy(() -> FilterQueryParser.parseAll("p1, (p2", DateOrder.DMY))
                .isInstanceOfSatisfying(FilterSyntaxException.class, ex -> assertThat(ex.position()).isEqualTo(7));
    }

    @Test
    void monthFirstOrder_readsSlashDatesAsMonthDay() {
        assertThat(FilterQueryParser.parse("11/05", DateOrder.MDY).describe()).isEqualTo("due=--11-05");
        assertThat(FilterQueryParser.parse("date: 11/05/2026", DateOrder.MDY).describe()).isEqualTo("due=2026-11-05");
    }

    @Test
    void filterDates_resolveAgainstToday() {
        LocalDate today = LocalDate.of(2027, 3, 10);
        assertThat(new FilterDate.Relative(-1).resolve(today)).isEqualTo(LocalDate.of(2027, 3, 9));
        assertThat(new FilterDate.Absolute(LocalDate.of(2026, 1, 2)).resolve(today)).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(((FilterNode.DateTerm) FilterQueryParser.parse("29/02", DateOrder.DMY)).date().resolve(today))
                .as("năm không nhuận → 28/02").isEqualTo(LocalDate.of(2027, 2, 28));

        FilterDate time = ((FilterNode.DateTerm) FilterQueryParser.parse("date: 2026-11-05T09:30", DateOrder.DMY)).date();
        assertThat(time.resolve(today)).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(time.hasTime()).isTrue();
        assertThat(new FilterDate.Relative(0).hasTime()).isFalse();
    }

    @Test
    void syntaxError_becomesProblemDetailWithCodeAndPosition() {
        ApiException problem = new FilterSyntaxException(4, "expected-term").toApiException();

        assertThat(problem.getStatusCode().value()).isEqualTo(400);
        assertThat(problem.getErrorMessage()).isEqualTo(ErrorMessage.FILTER_SYNTAX);
        assertThat(problem.getBody().getProperties())
                .containsEntry("code", ProblemCodes.FILTER_SYNTAX)
                .containsEntry("position", 4)
                .containsEntry("reason", "expected-term");
    }

    @Test
    void deepNestingWithinTheLengthLimit_doesNotOverflowTheStack() {
        String nested = "(".repeat(500) + "p1" + ")".repeat(500);
        assertThat(FilterQueryParser.parse(nested, DateOrder.DMY).describe()).isEqualTo("p1");
        String negations = "!".repeat(1000) + "p1";
        assertThat(FilterQueryParser.parse(negations, DateOrder.DMY)).isInstanceOf(FilterNode.Not.class);
    }
}
