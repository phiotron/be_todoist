package com.taskly.repository.listing;

import com.taskly.common.web.CursorCodec;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Mảnh SQL keyset cho câu dạng SELECT * FROM (SELECT ..., (expr) AS kN ...) x WHERE <after> ORDER BY ...
 * Hỗ trợ khoá khác chiều: (k0 > c0) OR (k0 = c0 AND k1 < c1) OR ... Tham số con trỏ tên :c0, :c1...
 */
public final class KeysetSql {

    private KeysetSql() {
    }

    public static String selectKeys(List<SortKey> keys) {
        StringJoiner columns = new StringJoiner(", ");
        for (int i = 0; i < keys.size(); i++) {
            columns.add("(" + keys.get(i).expression() + ") AS k" + i);
        }
        return columns.toString();
    }

    public static String afterCondition(List<SortKey> keys) {
        StringJoiner any = new StringJoiner(" OR ", "(", ")");
        for (int i = 0; i < keys.size(); i++) {
            StringJoiner all = new StringJoiner(" AND ", "(", ")");
            for (int j = 0; j < i; j++) {
                all.add("x.k" + j + " = " + param(keys.get(j), j));
            }
            all.add("x.k" + i + (keys.get(i).descending() ? " < " : " > ") + param(keys.get(i), i));
            any.add(all.toString());
        }
        return any.toString();
    }

    public static String orderBy(List<SortKey> keys) {
        StringJoiner order = new StringJoiner(", ");
        for (int i = 0; i < keys.size(); i++) {
            order.add("x.k" + i + (keys.get(i).descending() ? " DESC" : " ASC"));
        }
        return order.toString();
    }

    public static void bindAfter(List<SortKey> keys, CursorCodec.Cursor after, MapSqlParameterSource params) {
        for (int i = 0; i < keys.size(); i++) {
            params.addValue("c" + i, keys.get(i).kind().bind(after.string(i)));
        }
    }

    private static String param(SortKey key, int index) {
        return "CAST(:c" + index + " AS " + key.kind().sqlType() + ")";
    }
}
