package com.taskly.repository.listing;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.Texts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KeysetSqlTest {

    private static final List<SortKey> KEYS = List.of(
            new SortKey("t.priority", KeyKind.INT, false),
            new SortKey("lower(t.content)", KeyKind.TEXT, true),
            new SortKey("t.id", KeyKind.ID, false));

    @Test
    void selectKeys_aliasesEveryExpression() {
        assertThat(KeysetSql.selectKeys(KEYS))
                .isEqualTo("(t.priority) AS k0, (lower(t.content)) AS k1, (t.id) AS k2");
    }

    @Test
    void afterCondition_expandsMixedDirections() {
        assertThat(KeysetSql.afterCondition(KEYS)).isEqualTo("((x.k0 > CAST(:c0 AS integer))"
                + " OR (x.k0 = CAST(:c0 AS integer) AND x.k1 < CAST(:c1 AS text))"
                + " OR (x.k0 = CAST(:c0 AS integer) AND x.k1 = CAST(:c1 AS text) AND x.k2 > CAST(:c2 AS uuid)))");
    }

    @Test
    void orderBy_followsEachKeyDirection() {
        assertThat(KeysetSql.orderBy(KEYS)).isEqualTo("x.k0 ASC, x.k1 DESC, x.k2 ASC");
    }

    @Test
    void bind_convertsCursorStringsToJdbcValues() {
        UUID id = UUID.randomUUID();
        assertThat(KeyKind.INT.bind("3")).isEqualTo(3);
        assertThat(KeyKind.LONG.bind("9000000000")).isEqualTo(9_000_000_000L);
        assertThat(KeyKind.DATE.bind("2026-11-05")).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(KeyKind.INSTANT.bind("2026-11-05T01:02:03.456789Z"))
                .isEqualTo(OffsetDateTime.ofInstant(Instant.parse("2026-11-05T01:02:03.456789Z"), ZoneOffset.UTC));
        assertThat(KeyKind.LOCAL_DATETIME.bind("2026-11-05T09:30")).isEqualTo(LocalDateTime.of(2026, 11, 5, 9, 30));
        assertThat(KeyKind.TEXT.bind(KeyKind.encodeText("họp\nnhóm"))).isEqualTo("họp\nnhóm");
        assertThat(KeyKind.ID.bind(id.toString())).isEqualTo(id);
        assertThat(KeyKind.LONG_ARRAY.bind("1024,-2048")).isEqualTo("{1024,-2048}");
    }

    @Test
    void bind_rejectsTamperedValuesWith400() {
        for (Runnable bad : List.<Runnable>of(
                () -> KeyKind.INT.bind("x"),
                () -> KeyKind.DATE.bind("2026-13-01"),
                () -> KeyKind.INSTANT.bind("yesterday"),
                () -> KeyKind.TEXT.bind("%%%"),
                () -> KeyKind.ID.bind("nope"),
                () -> KeyKind.LONG_ARRAY.bind("1,a"))) {
            assertThatThrownBy(bad::run).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getErrorMessage()).isEqualTo(ErrorMessage.INVALID_CURSOR));
        }
    }
}
