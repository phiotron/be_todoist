package com.taskly.dto.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import org.junit.jupiter.api.Test;

/** Unit thuần: chuẩn hoá từ khoá, tách từ, giới hạn 100 ký tự, escape LIKE, '*' đại diện (spec 12.6, 5.9). */
class SearchQueryTest {

    @Test
    void nullEmptyOrWhitespaceOnly_isEmpty() {
        assertThat(SearchQuery.parse(null)).isEmpty();
        assertThat(SearchQuery.parse("")).isEmpty();
        assertThat(SearchQuery.parse("  \t \n ")).isEmpty();
        assertThat(SearchQuery.parse("\u00A0\u2003")).isEmpty(); // NBSP, EM SPACE
    }

    @Test
    void collapsesUnicodeWhitespaceAndSplitsTerms() {
        SearchQuery query = SearchQuery.parse("  Họp \t  nhóm\u00A0sáng ").orElseThrow();

        assertThat(query.normalized()).isEqualTo("Họp nhóm sáng");
        assertThat(query.terms()).containsExactly("Họp", "nhóm", "sáng");
    }

    @Test
    void repeatedTerm_isKeptOnceInTerms_butStaysInNormalized() {
        SearchQuery query = SearchQuery.parse("hop mua hop").orElseThrow();

        assertThat(query.terms()).containsExactly("hop", "mua");
        assertThat(query.normalized()).isEqualTo("hop mua hop");
    }

    @Test
    void decomposedVietnamese_isNormalizedToNfc() {
        SearchQuery query = SearchQuery.parse("Ho\u0323p").orElseThrow(); // o + dấu nặng tổ hợp → "ọ" U+1ECD

        assertThat(query.normalized()).isEqualTo("H\u1ECDp");
    }

    private static void assertTooLong(String raw) {
        assertThatThrownBy(() -> SearchQuery.parse(raw))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                    assertThat(ex.getBody().getDetail()).isEqualTo(SearchQuery.TOO_LONG_DETAIL);
                });
    }

    @Test
    void lengthIsCountedInCodePointsAfterTrimming_100Accepted_101Rejected() {
        assertThat(SearchQuery.parse("a".repeat(100))).isPresent();
        assertThat(SearchQuery.parse("   " + "a".repeat(100) + "\t ")).isPresent();
        assertThat(SearchQuery.parse("😀".repeat(100))).isPresent();
        assertTooLong("a".repeat(101));
        assertTooLong("😀".repeat(101));
        assertTooLong("a" + " ".repeat(99) + "b");
    }

    @Test
    void escapeLike_escapesBackslashPercentAndUnderscore() {
        assertThat(SearchQuery.escapeLike("50%")).isEqualTo("50\\%");
        assertThat(SearchQuery.escapeLike("file_name")).isEqualTo("file\\_name");
        assertThat(SearchQuery.escapeLike("C:\\tmp")).isEqualTo("C:\\\\tmp");
        assertThat(SearchQuery.escapeLike("họp")).isEqualTo("họp");
    }

    @Test
    void likePatterns_wrapEachTermAndTurnStarIntoWildcard() {
        SearchQuery query = SearchQuery.parse("h*p 50% a_b").orElseThrow();

        assertThat(query.likePatterns()).containsExactly("%h%p%", "%50\\%%", "%a\\_b%");
    }

    @Test
    void similarityText_dropsStars() {
        assertThat(SearchQuery.parse("họp * nhóm*").orElseThrow().similarityText()).isEqualTo("họp nhóm");
        assertThat(SearchQuery.parse("*").orElseThrow().similarityText()).isEmpty();
    }
}
