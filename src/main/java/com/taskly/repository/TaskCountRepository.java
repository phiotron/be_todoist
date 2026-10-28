package com.taskly.repository;

import com.taskly.repository.listing.FilterSqlTranslator;
import com.taskly.security.ReadScope;
import com.taskly.service.filterquery.FilterNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Đếm việc CHƯA xong cho cột số của màn Bộ lọc &amp; Nhãn — cùng điều kiện với danh sách tasks?labelId= / ?filter=
 * (TaskListingRepository): trong phạm vi đọc (ReadScope), dự án chưa lưu trữ, mọi cấp task. Không nối chuỗi người dùng:
 * điều kiện filter do FilterSqlTranslator sinh, mọi giá trị là tham số có tên.
 */
@Repository
public class TaskCountRepository {

    /** Số filter mỗi câu SQL (mỗi filter một COUNT(*) FILTER): giữ câu và số tham số ở mức vừa phải. */
    static final int FILTERS_PER_QUERY = 25;

    private static final String OPEN_IN_SCOPE = """
            WHERE t.workspace_id IN (:workspaceIds)
              AND t.project_id IN (:projectIds)
              AND p.archived_at IS NULL
              AND t.completed_at IS NULL
            """;

    /** Tên tham số do FilterSqlTranslator đặt (:fq0, :fqToday…) — đổi tên theo từng filter để không đè nhau. */
    private static final Pattern TRANSLATOR_PARAM = Pattern.compile(":(fq\\w+)");

    private final NamedParameterJdbcTemplate jdbc;

    public TaskCountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** labelId → số việc, chỉ nhãn CỦA user có ít nhất một việc (nhãn vắng = 0). Một câu GROUP BY. */
    public Map<UUID, Long> countByLabel(UUID userId, ReadScope scope) {
        String sql = """
                SELECT tl.label_id, COUNT(*) AS n
                FROM tasks t
                JOIN projects p ON p.id = t.project_id
                JOIN task_labels tl ON tl.task_id = t.id
                JOIN labels l ON l.id = tl.label_id AND l.user_id = :userId
                """ + OPEN_IN_SCOPE + " GROUP BY tl.label_id";
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query(sql, scope.params().addValue("userId", userId),
                rs -> {
                    counts.put(rs.getObject("label_id", UUID.class), rs.getLong("n"));
                });
        return counts;
    }

    /** filterId → số việc khớp cây filter (đã gộp các phần bằng OR nên một việc chỉ đếm một lần). */
    public Map<UUID, Long> countByFilter(UUID userId, ReadScope scope, LocalDate today, LocalDateTime now,
                                         String timeZone, Map<UUID, FilterNode> filters) {
        FilterSqlTranslator.Context context = new FilterSqlTranslator.Context(today, now, timeZone);
        List<Map.Entry<UUID, FilterNode>> entries = new ArrayList<>(filters.entrySet());
        Map<UUID, Long> counts = new HashMap<>();
        for (int from = 0; from < entries.size(); from += FILTERS_PER_QUERY) {
            List<Map.Entry<UUID, FilterNode>> chunk = entries.subList(from, Math.min(entries.size(),
                    from + FILTERS_PER_QUERY));
            MapSqlParameterSource params = scope.params().addValue("userId", userId);
            StringBuilder select = new StringBuilder("SELECT ");
            for (int i = 0; i < chunk.size(); i++) {
                FilterSqlTranslator.Sql sql = FilterSqlTranslator.translate(chunk.get(i).getValue(), context);
                String prefix = "c" + i + "_";
                sql.params().forEach((name, value) -> params.addValue(prefix + name, value));
                select.append(i == 0 ? "" : ", ").append("COUNT(*) FILTER (WHERE ")
                        .append(prefixed(sql.condition(), prefix)).append(") AS c").append(i);
            }
            select.append(" FROM tasks t JOIN projects p ON p.id = t.project_id ").append(OPEN_IN_SCOPE);
            jdbc.query(select.toString(), params, rs -> {
                for (int i = 0; i < chunk.size(); i++) {
                    counts.put(chunk.get(i).getKey(), rs.getLong("c" + i));
                }
            });
        }
        return counts;
    }

    private static String prefixed(String condition, String prefix) {
        return TRANSLATOR_PARAM.matcher(condition).replaceAll(match -> ":" + prefix
                + Matcher.quoteReplacement(match.group(1)));
    }
}
