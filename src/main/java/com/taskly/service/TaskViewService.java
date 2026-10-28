package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.web.CursorCodec;
import com.taskly.dto.common.Page;
import com.taskly.dto.task.TaskResponse;
import com.taskly.entity.TaskViewType;
import com.taskly.mapper.TaskMapper;
import com.taskly.repository.TaskView;
import com.taskly.repository.TaskViewRepository;
import com.taskly.repository.TaskViewRepository.Key;
import com.taskly.repository.TaskViewRepository.KeyType;
import com.taskly.repository.TaskViewRepository.KeyedTaskView;
import com.taskly.repository.TaskViewRepository.Keyset;
import com.taskly.security.AccessPolicy;
import com.taskly.security.ReadScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GET tasks?view= | ?projectId= (spec 12.3, quyết định Q14–Q15). Bảng định nghĩa ở đầu Task 21 của plan M1. */
@Service
@Transactional(readOnly = true)
public class TaskViewService {

    /** Cột sinh t.due_sort (V2) = COALESCE(due_datetime, due_date::timestamp) — khoá sắp theo due (floating). */
    private static final String DUE_KEY = "t.due_sort";
    private static final String OPEN_IN_ACTIVE_PROJECT = "t.completed_at is null and p.archived_at is null";
    /** KHỐI Quá hạn (spec 5.1, C3): due_day < hôm nay. Task có giờ đã qua TRONG hôm nay không thuộc khối này (chỉ tô đỏ). */
    private static final String OVERDUE_BLOCK = "t.due_day < cast(:today as date)";
    private static final int UPCOMING_DEFAULT_DAYS = 14;   // to mặc định = from + 13
    private static final int UPCOMING_MAX_SPAN_DAYS = 30;  // to − from ≤ 30 (cửa sổ ≤ 31 ngày)
    private static final int UPCOMING_MAX_YEARS = 2;       // tầm xem tối đa 2 năm
    private static final Key DUE = new Key(DUE_KEY, KeyType.LOCAL_TIMESTAMP);
    private static final Key PRIORITY = new Key("t.priority", KeyType.INT);
    private static final Key PROJECT_ORDER = new Key("p.sort_order", KeyType.LONG);
    private static final Key TASK_ORDER = new Key("t.sort_order", KeyType.LONG);
    private static final Key COMPLETED_AT = new Key("t.completed_at", KeyType.TIMESTAMP);

    private final TaskViewRepository repository;
    private final ProjectService projectService;
    private final UserService userService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public TaskViewService(TaskViewRepository repository, ProjectService projectService, UserService userService,
                           AccessPolicy accessPolicy, Clock clock) {
        this.repository = repository;
        this.projectService = projectService;
        this.userService = userService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /**
     * Đúng một trong view / projectId (không thì 400); from / to chỉ đi với upcoming; since / until chỉ đi với
     * completed; completed=true chỉ đi với projectId. Rồi chuyển sang listView hoặc listProject.
     */
    public Page<TaskResponse> list(UUID userId, TaskViewType view, UUID projectId, Boolean completed, LocalDate from,
                                   LocalDate to, LocalDate since, LocalDate until, String cursor, Integer limit) {
        if ((view == null) == (projectId == null)) {
            throw ApiException.badRequest(ErrorMessage.TASK_LIST_SELECTOR);
        }
        if ((from != null || to != null) && view != TaskViewType.upcoming) {
            throw ApiException.badRequest(ErrorMessage.FROM_ONLY_UPCOMING);
        }
        if ((since != null || until != null) && view != TaskViewType.completed) {
            throw ApiException.badRequest(ErrorMessage.SINCE_ONLY_COMPLETED);
        }
        if (Boolean.TRUE.equals(completed) && projectId == null) {
            throw ApiException.badRequest(ErrorMessage.COMPLETED_ONLY_PROJECT);
        }
        return projectId != null
                ? listProject(userId, projectId, Boolean.TRUE.equals(completed), cursor, limit)
                : listView(userId, view, from, to, since, until, cursor, limit);
    }

    /**
     * tasks?projectId= : task gốc đang mở + cây con; completed = true: task gốc đã hoàn thành + cây con.
     * Dự án lưu trữ vẫn xem được (chỉ đọc).
     */
    public Page<TaskResponse> listProject(UUID userId, UUID projectId, boolean completed, String cursor, Integer limit) {
        UUID id = projectService.requireReadable(userId, projectId).getId();
        Request request = request(userId, limit, accessPolicy.viewScope(userId)); // M8a: gồm xem trước dự án Team
        if (request == null) {
            return new Page<>(List.of(), null);
        }
        return projectPage(request.context(), request.params().addValue("projectId", id), completed, cursor);
    }

    /** tasks?view= (bảng định nghĩa ở đầu Task 21). */
    public Page<TaskResponse> listView(UUID userId, TaskViewType view, LocalDate from, LocalDate to, LocalDate since,
                                       LocalDate until, String cursor, Integer limit) {
        Request request = request(userId, limit, accessPolicy.listScope(userId)); // M8a: dự án đã tham gia
        if (request == null) {
            return new Page<>(List.of(), null);
        }
        MapSqlParameterSource params = request.params();
        Keyset keyset = switch (view) {
            // Khối Quá hạn (due_day < hôm nay) rồi khối Hôm nay: due_sort tăng dần đã tự xếp đúng thứ tự đó (mọi task
            // Quá hạn có due_sort < 00:00 hôm nay), nên không cần khoá khối riêng. Task có giờ đã qua TRONG hôm nay ở
            // khối Hôm nay (isOverdue chỉ để tô đỏ).
            case today -> new Keyset(OPEN_IN_ACTIVE_PROJECT + " and t.due_day <= cast(:today as date)",
                    List.of(DUE, PRIORITY, PROJECT_ORDER, TASK_ORDER), false);
            case upcoming -> {
                UpcomingWindow window = upcomingWindow(from, to, request.context().today());
                params.addValue("fromDay", window.first()).addValue("toDay", window.last());
                yield new Keyset(OPEN_IN_ACTIVE_PROJECT
                        + " and t.due_day between cast(:fromDay as date) and cast(:toDay as date)",
                        List.of(DUE, PRIORITY, PROJECT_ORDER, TASK_ORDER), false);
            }
            case overdue -> new Keyset(OPEN_IN_ACTIVE_PROJECT + " and " + OVERDUE_BLOCK,
                    List.of(DUE, PRIORITY, PROJECT_ORDER, TASK_ORDER), false);
            case completed -> completedKeyset(since, until, request.context().zone(), params);
            // M7: view=calendar do TaskListingService.byCalendar xử lý trước (tryList); tới đây là gọi sai.
            case calendar -> throw ApiException.badRequest(ErrorMessage.CALENDAR_WINDOW_INVALID);
        };
        return flatPage(request.context(), keyset, params, view.name(), cursor);
    }

    /** Cửa sổ ngày của view=upcoming, đã kiểm hợp lệ: [first, last]. */
    record UpcomingWindow(LocalDate first, LocalDate last) {
    }

    /**
     * MỘT chỗ quyết định cửa sổ view=upcoming (spec 12.3, I9): from mặc định hôm nay, from < hôm nay → kẹp về hôm nay;
     * to mặc định from + 13; to − from ≤ 30; from / to không quá hôm nay + 2 năm; to < from → 400.
     * CHỜ NGƯỜI DÙNG QUYẾT: kiểm to < from SAU khi kẹp from → cả from và to trước hôm nay trả 400 (theo plan); nếu spec
     * muốn "cửa sổ hợp lệ nhưng rỗng" thì sửa đúng ở đây (kiểm to < from trên from gốc, rồi trả cửa sổ rỗng).
     */
    static UpcomingWindow upcomingWindow(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate first = from == null || from.isBefore(today) ? today : from;
        LocalDate last = to != null ? to : first.plusDays(UPCOMING_DEFAULT_DAYS - 1);
        LocalDate horizon = today.plusYears(UPCOMING_MAX_YEARS);
        if (last.isBefore(first) || ChronoUnit.DAYS.between(first, last) > UPCOMING_MAX_SPAN_DAYS
                || first.isAfter(horizon) || last.isAfter(horizon)) {
            throw ApiException.badRequest(ErrorMessage.UPCOMING_WINDOW_INVALID);
        }
        return new UpcomingWindow(first, last);
    }

    /**
     * Màn Completed toàn cục (spec 12.3, C2): mọi task đã xong, loại dự án lưu trữ (task trong section lưu trữ vẫn
     * có); since / until = ngày theo múi giờ tài khoản, gồm cả hai đầu.
     */
    private static Keyset completedKeyset(LocalDate since, LocalDate until, ZoneId zone,
                                          MapSqlParameterSource params) {
        if (since != null && until != null && since.isAfter(until)) {
            throw ApiException.badRequest(ErrorMessage.COMPLETED_RANGE_INVALID);
        }
        StringBuilder filter = new StringBuilder("t.completed_at is not null and p.archived_at is null");
        if (since != null) {
            filter.append(" and t.completed_at >= cast(:sinceAt as timestamptz)");
            params.addValue("sinceAt", OffsetDateTime.ofInstant(since.atStartOfDay(zone).toInstant(), ZoneOffset.UTC));
        }
        if (until != null) {
            filter.append(" and t.completed_at < cast(:untilAt as timestamptz)");
            params.addValue("untilAt",
                    OffsetDateTime.ofInstant(until.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC));
        }
        return new Keyset(filter.toString(), List.of(COMPLETED_AT), true);
    }

    /**
     * TaskResponse (kèm projectName, parentContent, isOverdue) cho danh sách id, GIỮ đúng thứ tự ids; id không thấy
     * được (đã xoá / ngoài quyền) bị bỏ qua. Dùng cho M2 (tìm kiếm, lọc): M2 tự chọn + sắp id, gọi hàm này để dựng response.
     */
    public List<TaskResponse> getResponsesInOrder(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        LocalDateTime localNow = localNow(userId);
        Map<UUID, TaskView> byId = repository.findByIds(accessPolicy.viewScope(userId), ids)
                .stream().collect(Collectors.toMap(TaskView::id, view -> view));
        return ids.stream().filter(byId::containsKey)
                .map(id -> TaskMapper.toResponse(byId.get(id), localNow)).toList();
    }

    /**
     * GET tasks/{id}/subtasks: toàn bộ con cháu (kể cả đã xong) theo thứ tự hiển thị — để màn chi tiết task hiện công
     * việc con cả khi task gốc đã hoàn thành (danh sách theo dự án chỉ phân trang theo task gốc). Quyết định Q31.
     */
    public List<TaskResponse> subtasks(UUID userId, UUID taskId) {
        ReadScope scope = accessPolicy.viewScope(userId); // M8a
        repository.findOne(scope, taskId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.TASK_NOT_FOUND));
        Map<UUID, List<TaskView>> children = childrenByParent(repository.findSubtrees(scope, List.of(taskId)));
        List<TaskView> ordered = new ArrayList<>();
        appendChildren(taskId, children, ordered);
        LocalDateTime localNow = localNow(userId);
        return ordered.stream().map(view -> TaskMapper.toResponse(view, localNow)).toList();
    }

    /** Tham số chung của một lần gọi list. */
    private record Context(ReadScope scope, ZoneId zone, LocalDateTime localNow, LocalDate today,
                           int pageSize) {
    }

    private record Request(Context context, MapSqlParameterSource params) {
    }

    private LocalDateTime localNow(UUID userId) {
        return TaskDueRules.localNow(clock.instant(), userService.timeZoneOf(userId));
    }

    /** Dựng context + tham số SQL chung (workspaceIds, today); null nếu user không thuộc workspace nào. */
    private Request request(UUID userId, Integer limit, ReadScope scope) {
        int pageSize = CursorCodec.limit(limit);
        ZoneId zone = userService.timeZoneOf(userId);
        LocalDateTime localNow = TaskDueRules.localNow(clock.instant(), zone);
        LocalDate today = localNow.toLocalDate();
        if (scope.isEmpty()) {
            return null;
        }
        MapSqlParameterSource params = scope.params() // M8a: :workspaceIds + :projectIds
                .addValue("today", today);
        return new Request(new Context(scope, zone, localNow, today, pageSize), params);
    }

    private Page<TaskResponse> flatPage(Context context, Keyset keyset, MapSqlParameterSource params, String kind,
                                        String cursor) {
        List<KeyedTaskView> rows = repository.page(keyset, params, decode(kind, cursor, keyset), context.pageSize() + 1);
        return Page.fromLookahead(rows, context.pageSize(), row -> CursorCodec.encode(kind, row.keys()))
                .map(row -> TaskMapper.toResponse(row.view(), context.localNow()));
    }

    /** Phân trang theo task GỐC; mỗi trang kèm toàn bộ cây con theo thứ tự hiển thị (duyệt sâu). */
    private Page<TaskResponse> projectPage(Context context, MapSqlParameterSource params, boolean completed,
                                           String cursor) {
        String kind = (completed ? "project-completed:" : "project:") + params.getValue("projectId");
        Keyset roots = completed
                ? new Keyset("t.project_id = :projectId and t.parent_id is null and t.completed_at is not null",
                        List.of(COMPLETED_AT), true)
                : new Keyset("t.project_id = :projectId and t.parent_id is null and t.completed_at is null", List.of(
                        new Key("case when t.section_id is null then 0 else 1 end", KeyType.INT),
                        new Key("coalesce((select s.sort_order from sections s where s.id = t.section_id), 0)",
                                KeyType.LONG),
                        // phá hoà hai section trùng sort_order (sau unarchive): giữ task cùng section liền nhau
                        new Key("coalesce(t.section_id, cast('00000000-0000-0000-0000-000000000000' as uuid))",
                                KeyType.ID),
                        TASK_ORDER), false);
        List<KeyedTaskView> rows = repository.page(roots, params, decode(kind, cursor, roots), context.pageSize() + 1);
        Page<KeyedTaskView> rootPage = Page.fromLookahead(rows, context.pageSize(),
                row -> CursorCodec.encode(kind, row.keys()));

        List<UUID> rootIds = rootPage.items().stream().map(row -> row.view().id()).toList();
        Map<UUID, List<TaskView>> children = childrenByParent(repository.findSubtrees(context.scope(), rootIds));
        List<TaskView> ordered = new ArrayList<>();
        for (KeyedTaskView root : rootPage.items()) {
            ordered.add(root.view());
            appendChildren(root.view().id(), children, ordered);
        }
        return new Page<>(ordered.stream().map(view -> TaskMapper.toResponse(view, context.localNow())).toList(),
                rootPage.nextCursor());
    }

    /** Nhóm theo cha, mỗi nhóm theo sort_order rồi id (groupingBy giữ thứ tự đã sắp). */
    private static Map<UUID, List<TaskView>> childrenByParent(List<TaskView> views) {
        return views.stream()
                .sorted(Comparator.comparingLong(TaskView::sortOrder).thenComparing(TaskView::id))
                .collect(Collectors.groupingBy(TaskView::parentId));
    }

    private static void appendChildren(UUID parentId, Map<UUID, List<TaskView>> children, List<TaskView> out) {
        for (TaskView child : children.getOrDefault(parentId, List.of())) {
            out.add(child);
            appendChildren(child.id(), children, out);
        }
    }

    private static CursorCodec.Cursor decode(String kind, String cursor, Keyset keyset) {
        return CursorCodec.hasCursor(cursor) ? CursorCodec.decode(kind, cursor, keyset.cursorSize()) : null;
    }
}
