package com.taskly.repository.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.common.web.CursorCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Chạy thật trên Postgres với bảng VALUES: đi từng trang phải ra đúng thứ tự của một lần ORDER BY. */
class KeysetPagerTest extends IntegrationTestBase {

    private static final String ROWS = """
            FROM (VALUES
              (CAST('00000000-0000-0000-0000-000000000001' AS uuid), 1, 'b', DATE '2026-11-05',
               TIMESTAMP '2026-11-05 09:00', TIMESTAMPTZ '2026-11-01 00:00:00+00', ARRAY[1024, 2048]::bigint[]),
              (CAST('00000000-0000-0000-0000-000000000002' AS uuid), 1, 'a', DATE '2026-11-05',
               TIMESTAMP '2026-11-05 08:00', TIMESTAMPTZ '2026-11-02 00:00:00+00', ARRAY[1024]::bigint[]),
              (CAST('00000000-0000-0000-0000-000000000003' AS uuid), 1, 'b', DATE '2026-11-04',
               TIMESTAMP '2026-11-04 10:00', TIMESTAMPTZ '2026-11-01 00:00:00+00', ARRAY[2048]::bigint[]),
              (CAST('00000000-0000-0000-0000-000000000004' AS uuid), 0, 'z', DATE '2026-11-06',
               TIMESTAMP '2026-11-06 00:00', TIMESTAMPTZ '2026-11-03 12:00:00+00', ARRAY[1024, 2048]::bigint[]),
              (CAST('00000000-0000-0000-0000-000000000005' AS uuid), 0, 'm', DATE '2026-11-06',
               TIMESTAMP '2026-11-06 00:00', TIMESTAMPTZ '2026-11-03 12:00:00.000001+00', ARRAY[1024, 1024]::bigint[]),
              (CAST('00000000-0000-0000-0000-000000000006' AS uuid), 2, 'a', DATE '2026-11-01',
               TIMESTAMP '2026-11-01 00:00', TIMESTAMPTZ '2026-10-01 00:00:00+00', ARRAY[4096]::bigint[]),
              (CAST('00000000-0000-0000-0000-000000000007' AS uuid), 0, 'z', DATE '2026-11-06',
               TIMESTAMP '2026-11-06 00:00', TIMESTAMPTZ '2026-11-03 12:00:00+00', ARRAY[1024, 2048]::bigint[])
            ) AS t(id, grp, name, day, at, created, path)
            """;

    @Autowired
    private KeysetPager pager;

    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + n);
    }

    private List<UUID> all(List<SortKey> keys) {
        return pager.page("t.id", ROWS, keys, new MapSqlParameterSource(), null, 100).stream()
                .map(KeysetPager.KeyedId::id).toList();
    }

    private List<UUID> pageThrough(List<SortKey> keys, int limit) {
        List<UUID> out = new ArrayList<>();
        CursorCodec.Cursor after = null;
        for (int guard = 0; guard < 50; guard++) {
            List<KeysetPager.KeyedId> rows = pager.page("t.id", ROWS, keys, new MapSqlParameterSource(), after, limit + 1);
            List<KeysetPager.KeyedId> page = rows.size() > limit ? rows.subList(0, limit) : rows;
            page.forEach(row -> out.add(row.id()));
            if (rows.size() <= limit) {
                return out;
            }
            String cursor = CursorCodec.encode("test", page.get(page.size() - 1).keys());
            after = CursorCodec.decode("test", cursor, keys.size());
        }
        throw new AssertionError("Con trỏ không tiến");
    }

    @Test
    void groupAscendingThenTextDescendingThenId() {
        List<SortKey> keys = List.of(
                new SortKey("t.grp", KeyKind.INT, false),
                new SortKey("t.name", KeyKind.TEXT, true),
                new SortKey("t.id", KeyKind.ID, false));

        assertThat(all(keys)).containsExactly(uuid(4), uuid(7), uuid(5), uuid(1), uuid(3), uuid(2), uuid(6));
        for (int limit = 1; limit <= 4; limit++) {
            assertThat(pageThrough(keys, limit)).as("limit " + limit).isEqualTo(all(keys));
        }
    }

    @Test
    void everyKeyKind_roundTripsThroughTheCursor() {
        List<SortKey> keys = List.of(
                new SortKey("t.path", KeyKind.LONG_ARRAY, false),
                new SortKey("t.day", KeyKind.DATE, true),
                new SortKey("t.at", KeyKind.LOCAL_DATETIME, false),
                new SortKey("t.created", KeyKind.INSTANT, true),
                new SortKey("CAST(t.grp AS bigint)", KeyKind.LONG, false),
                new SortKey("t.id", KeyKind.ID, false));

        List<UUID> expected = all(keys);
        assertThat(expected).hasSize(7);
        for (int limit = 1; limit <= 3; limit++) {
            assertThat(pageThrough(keys, limit)).as("limit " + limit).isEqualTo(expected);
        }
    }
}
