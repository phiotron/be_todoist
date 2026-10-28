package com.taskly.repository;

import com.taskly.common.web.CursorCodec;
import com.taskly.dto.search.SearchQuery;
import com.taskly.security.ReadScope;
import com.taskly.repository.listing.KeyKind;
import com.taskly.repository.listing.KeysetPager;
import com.taskly.repository.listing.SortKey;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * SQL tìm task (spec 12.6). Vế trái điều kiện viết đúng f_unaccent(lower(cột)) để dùng chỉ mục trigram V3.
 * Từ khoá đi qua tham số :termN (đã escape LIKE, '*' → '%'); không nối chuỗi người dùng. Mọi khoá sắp đều không null
 * (COALESCE) và khoá cuối là t.id — điều kiện của KeysetPager.
 */
@Repository
public class TaskSearchRepository {

    /**
     * Phạm vi workspace lọc thẳng trên tasks.workspace_id (spec 3.1); JOIN projects chỉ để loại dự án lưu trữ (lưu trữ
     * dự án cha đặt archived_at cho cả dự án con). KHÔNG loại section lưu trữ: task trong đó đều đã hoàn thành (spec
     * 5.7) nên tự biến mất khỏi chế độ đang mở và chỉ hiện ở completed=true (spec 12.6, 18.1 m5).
     */
    private static final String SCOPE = """
            FROM tasks t
            JOIN projects p ON p.id = t.project_id
            WHERE t.workspace_id IN (:workspaceIds)
              AND t.project_id IN (:projectIds)
              AND p.archived_at IS NULL
            """;

    /** Mốc "không due" cho khoá due_sort: sau mọi ngày hợp lệ → không due xếp cuối, khoá không null. */
    private static final String NO_DUE = "TIMESTAMP '9999-12-31 00:00:00'";

    private final KeysetPager pager;

    public TaskSearchRepository(KeysetPager pager) {
        this.pager = pager;
    }

    /**
     * Khoá sắp: đang mở = khớp tên hết các từ trước, similarity giảm (làm tròn 10⁻⁶), due_sort tăng (không due cuối),
     * id; đã xong = completed_at giảm, id.
     */
    public List<SortKey> keys(SearchQuery query, boolean completed) {
        if (completed) {
            return List.of(new SortKey("t.completed_at", KeyKind.INSTANT, true), new SortKey("t.id", KeyKind.ID, false));
        }
        String inContent = allTermsIn("t.content", query.terms().size());
        String similarity = "CAST(round(CAST(COALESCE(CASE WHEN " + inContent
                + " THEN similarity(f_unaccent(lower(t.content)), f_unaccent(lower(:simText)))"
                + " ELSE similarity(f_unaccent(lower(t.description)), f_unaccent(lower(:simText))) END, 0)"
                + " * 1000000 AS numeric)) AS integer)";
        return List.of(
                new SortKey("CASE WHEN " + inContent + " THEN 0 ELSE 1 END", KeyKind.INT, false),
                new SortKey(similarity, KeyKind.INT, true),
                new SortKey("COALESCE(t.due_sort, " + NO_DUE + ")", KeyKind.LOCAL_DATETIME, false),
                new SortKey("t.id", KeyKind.ID, false));
    }

    public List<KeysetPager.KeyedId> search(ReadScope scope, SearchQuery query, boolean completed,
                                            CursorCodec.Cursor after, int limit) {
        MapSqlParameterSource params = scope.params() // M8a: :workspaceIds + :projectIds
                .addValue("simText", query.similarityText());
        StringBuilder where = new StringBuilder(SCOPE)
                .append(completed ? " AND t.completed_at IS NOT NULL" : " AND t.completed_at IS NULL");
        List<String> patterns = query.likePatterns();
        for (int i = 0; i < patterns.size(); i++) {
            params.addValue("term" + i, patterns.get(i));
            where.append(" AND (").append(contains("t.content", i)).append(" OR ")
                    .append(contains("t.description", i)).append(')');
        }
        return pager.page("t.id", where.toString(), keys(query, completed), params, after, limit);
    }

    static String contains(String column, int term) {
        return "f_unaccent(lower(" + column + ")) ILIKE f_unaccent(lower(:term" + term + ")) ESCAPE '\\'";
    }

    private static String allTermsIn(String column, int termCount) {
        StringJoiner all = new StringJoiner(" AND ", "(", ")");
        for (int i = 0; i < termCount; i++) {
            all.add(contains(column, i));
        }
        return all.toString();
    }
}
