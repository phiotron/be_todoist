package com.taskly.repository.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.taskly.common.error.ApiException;
import com.taskly.common.web.CursorCodec;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Thuần (không DB): kiểm câu SQL và tham số mà KeysetPager đưa cho JDBC. Chạy thật trên Postgres: KeysetPagerTest. */
class KeysetPagerSqlTest {

    private static final List<SortKey> KEYS = List.of(
            new SortKey("t.grp", KeyKind.INT, false),
            new SortKey("t.name", KeyKind.TEXT, true),
            new SortKey("t.id", KeyKind.ID, false));

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final KeysetPager pager = new KeysetPager(jdbc);

    private String capturedSql;
    private SqlParameterSource capturedParams;

    private void run(CursorCodec.Cursor after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("owner", "o1");
        pager.page("t.id", "FROM tasks t WHERE t.owner = :owner", KEYS, params, after, limit);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> source = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), source.capture(), any(RowMapper.class));
        capturedSql = sql.getValue();
        capturedParams = source.getValue();
    }

    @Test
    void firstPage_hasNoAfterConditionAndKeepsCallerParams() {
        run(null, 11);

        assertThat(capturedSql).isEqualTo("SELECT * FROM (SELECT t.id AS id, (t.grp) AS k0, (t.name) AS k1, (t.id) AS k2"
                + " FROM tasks t WHERE t.owner = :owner) x ORDER BY x.k0 ASC, x.k1 DESC, x.k2 ASC LIMIT :pageLimit");
        assertThat(capturedParams.getValue("owner")).isEqualTo("o1");
        assertThat(capturedParams.getValue("pageLimit")).isEqualTo(11);
        assertThat(capturedParams.hasValue("c0")).isFalse();
    }

    @Test
    void nextPage_addsAfterConditionAndBindsCursorValues() {
        UUID id = UUID.randomUUID();
        String cursor = CursorCodec.encode("test", List.of("2", KeyKind.encodeText("họp"), id.toString()));

        run(CursorCodec.decode("test", cursor, 3), 6);

        assertThat(capturedSql).contains(") x WHERE " + KeysetSql.afterCondition(KEYS) + " ORDER BY ");
        assertThat(capturedParams.getValue("c0")).isEqualTo(2);
        assertThat(capturedParams.getValue("c1")).isEqualTo("họp");
        assertThat(capturedParams.getValue("c2")).isEqualTo(id);
        assertThat(capturedParams.getValue("pageLimit")).isEqualTo(6);
    }

    @Test
    void tamperedCursorValue_failsBeforeAnyQuery() {
        CursorCodec.Cursor tampered = new CursorCodec.Cursor(List.of("x", "aA", "nope"));

        assertThatThrownBy(() -> pager.page("t.id", "FROM tasks t", KEYS, new MapSqlParameterSource(), tampered, 5))
                .isInstanceOf(ApiException.class);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void afterCondition_forSingleKeyIsPlainComparison() {
        assertThat(KeysetSql.afterCondition(List.of(new SortKey("t.id", KeyKind.ID, true))))
                .isEqualTo("((x.k0 < CAST(:c0 AS uuid)))");
    }
}
