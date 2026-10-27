package com.taskly.repository.listing;

import com.taskly.common.web.CursorCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Máy phân trang keyset dùng chung cho mọi danh sách M2: chỉ trả id + giá trị khoá (chuỗi con trỏ).
 * fromWhere: "FROM ... [WHERE ...]" do nơi gọi dựng (chỉ chứa tham số có tên, không nối chuỗi người dùng).
 */
@Repository
public class KeysetPager {

    /** Một dòng: id + giá trị các khoá theo thứ tự (cuối cùng là id) — đưa thẳng vào CursorCodec.encode. */
    public record KeyedId(UUID id, List<String> keys) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public KeysetPager(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** limit = số dòng lấy (nơi gọi truyền pageSize + 1 để biết còn trang sau). after null = trang đầu. */
    public List<KeyedId> page(String idExpression, String fromWhere, List<SortKey> keys,
                              MapSqlParameterSource params, CursorCodec.Cursor after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM (SELECT ").append(idExpression).append(" AS id, ")
                .append(KeysetSql.selectKeys(keys)).append(' ').append(fromWhere).append(") x");
        if (after != null) {
            KeysetSql.bindAfter(keys, after, params);
            sql.append(" WHERE ").append(KeysetSql.afterCondition(keys));
        }
        sql.append(" ORDER BY ").append(KeysetSql.orderBy(keys)).append(" LIMIT :pageLimit");
        params.addValue("pageLimit", limit);
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            List<String> values = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                values.add(keys.get(i).kind().read(rs, "k" + i));
            }
            return new KeyedId(rs.getObject("id", UUID.class), List.copyOf(values));
        });
    }
}
