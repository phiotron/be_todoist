package com.taskly.repository.listing;

import com.taskly.service.filterquery.FilterDate;
import com.taskly.service.filterquery.FilterNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dịch cây FilterNode thành điều kiện SQL có tham số (alias t = tasks). Câu kết quả dùng thêm :workspaceIds và
 * :userId — nơi gọi (TaskListingService) cấp. Không bao giờ nối chuỗi người dùng vào SQL (spec 8.2 quy tắc 15).
 */
public final class FilterSqlTranslator {

    /** today / now = giờ floating theo múi giờ tài khoản; timeZone = IANA (đổi created_at sang ngày). */
    public record Context(LocalDate today, LocalDateTime now, String timeZone) {
    }

    public record Sql(String condition, Map<String, Object> params) {
    }

    private static final String LIKE_ESCAPE = " ESCAPE '\\'";

    private final Context context;
    private final Map<String, Object> params = new LinkedHashMap<>();
    private int next;

    private FilterSqlTranslator(Context context) {
        this.context = context;
    }

    public static Sql translate(FilterNode node, Context context) {
        FilterSqlTranslator translator = new FilterSqlTranslator(context);
        translator.params.put("fqToday", context.today());
        translator.params.put("fqNow", context.now());
        translator.params.put("fqTz", context.timeZone());
        String condition = translator.visit(node);
        return new Sql(condition, Map.copyOf(translator.params));
    }

    private String visit(FilterNode node) {
        if (node instanceof FilterNode.Or or) {
            return "(" + visit(or.left()) + " OR " + visit(or.right()) + ")";
        }
        if (node instanceof FilterNode.And and) {
            return "(" + visit(and.left()) + " AND " + visit(and.right()) + ")";
        }
        if (node instanceof FilterNode.Not not) {
            return "(NOT " + visit(not.inner()) + ")";
        }
        return "COALESCE(" + atom(node) + ", false)";
    }

    private String atom(FilterNode node) {
        if (node instanceof FilterNode.DateTerm term) {
            if (term.date() instanceof FilterDate.AbsoluteTime time) {
                return timeTerm(term, time.dateTime());
            }
            String column = switch (term.field()) {
                case DUE -> "t.due_day";
                case DEADLINE -> "t.deadline_date";
                case CREATED -> "CAST(t.created_at AT TIME ZONE :fqTz AS date)";
            };
            return column + " " + term.comparison().symbol() + " " + bind(term.date().resolve(context.today()));
        }
        if (node instanceof FilterNode.Overdue) {
            // isOverdue (spec 5.1) — KHÔNG phải khối Quá hạn của Today (t.due_day < hôm nay): task có giờ đã qua
            // trong hôm nay cũng khớp.
            return "(t.due_datetime < :fqNow OR t.due_date < :fqToday)";
        }
        if (node instanceof FilterNode.NoDate) {
            return "t.due_day IS NULL";
        }
        if (node instanceof FilterNode.NoTime) {
            return "t.due_date IS NOT NULL";
        }
        if (node instanceof FilterNode.NextDays nextDays) {
            return "(t.due_day >= :fqToday AND t.due_day < " + bind(context.today().plusDays(nextDays.days())) + ")";
        }
        if (node instanceof FilterNode.NoDeadline) {
            return "t.deadline_date IS NULL";
        }
        if (node instanceof FilterNode.Priority priority) {
            return "t.priority = " + bind(priority.level());
        }
        if (node instanceof FilterNode.ProjectName project) {
            String match = "lower(fp.name) LIKE lower(" + bind(project.pattern()) + ")" + LIKE_ESCAPE;
            if (!project.withChildren()) {
                return "t.project_id IN (SELECT fp.id FROM projects fp WHERE fp.workspace_id IN (:workspaceIds) AND "
                        + match + ")";
            }
            return "t.project_id IN (WITH RECURSIVE fpt(id) AS (SELECT fp.id FROM projects fp "
                    + "WHERE fp.workspace_id IN (:workspaceIds) AND " + match
                    + " UNION SELECT fc.id FROM projects fc JOIN fpt ON fc.parent_id = fpt.id) SELECT id FROM fpt)";
        }
        if (node instanceof FilterNode.SectionName section) {
            return "t.section_id IN (SELECT fs.id FROM sections fs JOIN projects fsp ON fsp.id = fs.project_id "
                    + "WHERE fsp.workspace_id IN (:workspaceIds) AND lower(fs.name) LIKE lower("
                    + bind(section.pattern()) + ")" + LIKE_ESCAPE + ")";
        }
        if (node instanceof FilterNode.AnySection) {
            return "t.section_id IS NOT NULL";
        }
        if (node instanceof FilterNode.LabelName label) {
            return "EXISTS (SELECT 1 FROM task_labels ftl JOIN labels fl ON fl.id = ftl.label_id "
                    + "WHERE ftl.task_id = t.id AND fl.user_id = :userId AND lower(fl.name) LIKE lower("
                    + bind(label.pattern()) + ")" + LIKE_ESCAPE + ")";
        }
        if (node instanceof FilterNode.NoLabels) {
            return "NOT EXISTS (SELECT 1 FROM task_labels ftl WHERE ftl.task_id = t.id)";
        }
        if (node instanceof FilterNode.SearchText search) {
            String pattern = bind("%" + search.pattern() + "%");
            return "(f_unaccent(lower(t.content)) ILIKE f_unaccent(lower(" + pattern + "))" + LIKE_ESCAPE
                    + " OR f_unaccent(lower(t.description)) ILIKE f_unaccent(lower(" + pattern + "))" + LIKE_ESCAPE
                    + ")";
        }
        if (node instanceof FilterNode.Subtask) {
            return "t.parent_id IS NOT NULL";
        }
        if (node instanceof FilterNode.Recurring) {
            return "t.recurrence_rule IS NOT NULL";
        }
        throw new IllegalArgumentException("Nút filter chưa hỗ trợ: " + node.describe());
    }

    /**
     * Mốc ngày-giờ floating, độ phân giải phút (thứ tự bind: fq0 rồi fq1 — test khoá). "on" = đúng phút đó; task chỉ có
     * ngày (due_datetime null) so theo ngày ở before / after và không bao giờ khớp "on". deadline không có giờ
     * (parser đã chặn).
     */
    private String timeTerm(FilterNode.DateTerm term, LocalDateTime time) {
        return switch (term.field()) {
            case DUE -> switch (term.comparison()) {
                case ON -> "(t.due_datetime >= " + bind(time) + " AND t.due_datetime < " + bind(time.plusMinutes(1)) + ")";
                case BEFORE -> "(t.due_datetime < " + bind(time) + " OR t.due_date < " + bind(time.toLocalDate()) + ")";
                case AFTER ->
                        "(t.due_datetime >= " + bind(time.plusMinutes(1)) + " OR t.due_date > " + bind(time.toLocalDate()) + ")";
            };
            case CREATED -> {
                String local = "(t.created_at AT TIME ZONE :fqTz)";
                yield switch (term.comparison()) {
                    case ON -> "(" + local + " >= " + bind(time) + " AND " + local + " < " + bind(time.plusMinutes(1)) + ")";
                    case BEFORE -> local + " < " + bind(time);
                    case AFTER -> local + " >= " + bind(time.plusMinutes(1));
                };
            }
            case DEADLINE -> throw new IllegalArgumentException("deadline không có giờ (parser đã chặn)");
        };
    }

    private String bind(Object value) {
        String name = "fq" + next++;
        params.put(name, value);
        return ":" + name;
    }
}
