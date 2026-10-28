package com.taskly.repository;

import com.taskly.dto.search.SearchQuery;
import com.taskly.dto.search.SectionSearchItem;
import com.taskly.security.ReadScope;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tìm theo tên trên projects / sections / labels / filters — truy vấn CHỈ ĐỌC xuyên tính năng (ngoại lệ có chủ đích
 * của spec 8.2 quy tắc 2, như TaskViewRepository). Dựng DTO dự án / label / filter do service chủ sở hữu.
 */
@Repository
public class ObjectSearchRepository {

    private static final String ORDER =
            " ORDER BY COALESCE(similarity(f_unaccent(lower(x.name)), f_unaccent(lower(:simText))), 0) DESC,"
                    + " lower(x.name), x.id";

    private final NamedParameterJdbcTemplate jdbc;

    public ObjectSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** M8a: phạm vi = dự án đã tham gia (ReadScope). */
    public List<UUID> projectIds(ReadScope scope, SearchQuery query, int max) {
        return ids("projects x",
                "x.workspace_id IN (:workspaceIds) AND x.id IN (:projectIds) AND x.archived_at IS NULL",
                scope.params(), query, max);
    }

    public List<SectionSearchItem> sections(ReadScope scope, SearchQuery query, int max) {
        MapSqlParameterSource params = scope.params().addValue("max", max);
        String sql = "SELECT x.id, x.project_id, p.name AS project_name, x.name FROM sections x "
                + "JOIN projects p ON p.id = x.project_id "
                + "WHERE p.workspace_id IN (:workspaceIds) AND p.id IN (:projectIds) AND p.archived_at IS NULL"
                + " AND x.archived_at IS NULL"
                + terms(query, params) + ORDER + " LIMIT :max";
        return jdbc.query(sql, params, (rs, rowNum) -> new SectionSearchItem(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("project_name"), rs.getString("name")));
    }

    public List<UUID> labelIds(UUID userId, SearchQuery query, int max) {
        return ids("labels x", "x.user_id = :userId", new MapSqlParameterSource("userId", userId), query, max);
    }

    public List<UUID> filterIds(UUID userId, SearchQuery query, int max) {
        return ids("filters x", "x.user_id = :userId", new MapSqlParameterSource("userId", userId), query, max);
    }

    private List<UUID> ids(String from, String scope, MapSqlParameterSource params, SearchQuery query, int max) {
        params.addValue("max", max);
        String sql = "SELECT x.id FROM " + from + " WHERE " + scope + terms(query, params) + ORDER + " LIMIT :max";
        return jdbc.queryForList(sql, params, UUID.class);
    }

    private static String terms(SearchQuery query, MapSqlParameterSource params) {
        params.addValue("simText", query.similarityText());
        StringBuilder sql = new StringBuilder();
        List<String> patterns = query.likePatterns();
        for (int i = 0; i < patterns.size(); i++) {
            params.addValue("term" + i, patterns.get(i));
            sql.append(" AND f_unaccent(lower(x.name)) ILIKE f_unaccent(lower(:term").append(i)
                    .append(")) ESCAPE '\\'");
        }
        return sql.toString();
    }
}
