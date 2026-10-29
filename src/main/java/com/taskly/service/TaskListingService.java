package com.taskly.service;

import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ApiException;
import com.taskly.common.web.CursorCodec;
import com.taskly.dto.common.Page;
import com.taskly.dto.task.TaskResponse;
import com.taskly.entity.DueFilter;
import com.taskly.entity.TaskViewType;
import com.taskly.repository.TaskListingRepository;
import com.taskly.repository.TaskListingRepository.Context;
import com.taskly.repository.TaskListingRepository.Scope;
import com.taskly.repository.listing.KeysetPager.KeyedId;
import com.taskly.repository.listing.SortKey;
import com.taskly.repository.listing.SubtreeOrder;
import com.taskly.security.AccessPolicy;
import com.taskly.security.ReadScope;
import com.taskly.service.display.DisplayOptions;
import com.taskly.service.display.DisplayParams;
import com.taskly.service.display.DisplayQuery;
import com.taskly.service.display.DisplayRules;
import com.taskly.service.display.ViewKind;
import com.taskly.service.filterquery.FilterNode;
import com.taskly.service.filterquery.FilterQueryParser;
import com.taskly.service.filterquery.FilterSyntaxException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Danh sách task có Display của M2: label, filter (Task 18), Today / Upcoming / dự án (Task 19).
 * Phạm vi quyền lấy từ AccessPolicy (BR-09); TaskResponse dựng bằng TaskViewService (M1).
 */
@Service
@Transactional(readOnly = true)
public class TaskListingService {

    /** Tham số thô của GET tasks. completed = true chỉ khi client gửi completed=true. */
    public record ListingParams(TaskViewType view, UUID projectId, UUID labelId, String filter, boolean completed,
                                LocalDate from, LocalDate to, LocalDate since, LocalDate until,
                                DisplayQuery display) {
    }

    private final TaskListingRepository repository;
    private final AccessPolicy accessPolicy;
    private final UserService userService;
    private final TaskViewService taskViewService;
    private final LabelService labelService;
    private final FilterService filterService;
    private final ProjectService projectService;
    private final Clock clock;

    public TaskListingService(TaskListingRepository repository, AccessPolicy accessPolicy, UserService userService,
                              TaskViewService taskViewService, LabelService labelService,
                              FilterService filterService, ProjectService projectService, Clock clock) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.userService = userService;
        this.taskViewService = taskViewService;
        this.labelService = labelService;
        this.filterService = filterService;
        this.projectService = projectService;
        this.clock = clock;
    }

    /**
     * Bộ chọn chế độ của GET tasks. Trả empty = KHÔNG phải yêu cầu M2 (không có labelId / filter / tham số Display)
     * → handler M1 xử lý nguyên như cũ. Ngược lại kiểm tổ hợp tham số (400) rồi liệt kê.
     */
    public Optional<Page<TaskResponse>> tryList(UUID userId, ListingParams params, String cursor, Integer limit) {
        DisplayQuery display = params.display() == null ? new DisplayQuery(null, null, null, null, null, null)
                : params.display();
        if (params.view() == TaskViewType.calendar) {
            return Optional.of(byCalendar(userId, params, display, cursor, limit));
        }
        if (params.labelId() == null && params.filter() == null && display.isEmpty()) {
            return Optional.empty();
        }
        long selectors = Stream.of(params.view(), params.projectId(), params.labelId(), params.filter())
                .filter(selector -> selector != null).count();
        boolean upcomingOnly = params.from() != null || params.to() != null;
        boolean completedViewOnly = params.since() != null || params.until() != null;
        if (selectors != 1 || (params.completed() && params.projectId() == null)
                || (upcomingOnly && params.view() != TaskViewType.upcoming)
                || (completedViewOnly && params.view() != TaskViewType.completed)) {
            throw ApiException.badRequest(ErrorMessage.TASK_LIST_SELECTOR);
        }
        if (params.completed() || params.view() == TaskViewType.completed) {
            // Display không áp cho completed=true và view=completed (màn Completed toàn cục là của M1 — M2 không tạo)
            throw ApiException.badRequest(ErrorMessage.DISPLAY_INVALID);
        }
        if (params.labelId() != null) {
            return Optional.of(byLabel(userId, params.labelId(), display, cursor, limit));
        }
        if (params.filter() != null) {
            return Optional.of(byFilter(userId, params.filter(), display, cursor, limit));
        }
        if (params.projectId() != null) {
            return Optional.of(byProject(userId, params.projectId(), display, cursor, limit));
        }
        return Optional.of(byView(userId, params.view(), params.from(), params.to(), display, cursor, limit));
    }

    public Page<TaskResponse> byLabel(UUID userId, UUID labelId, DisplayQuery display, String cursor, Integer limit) {
        int pageSize = CursorCodec.limit(limit);
        labelService.requireOwned(userId, labelId);
        DisplayOptions options = options(userId, ViewKind.LABEL, display);
        return run(userId, new Scope(ViewKind.LABEL, labelId, null, null, null), options, cursor, pageSize);
    }

    public Page<TaskResponse> byFilter(UUID userId, String query, DisplayQuery display, String cursor, Integer limit) {
        int pageSize = CursorCodec.limit(limit);
        FilterNode node;
        try {
            node = FilterQueryParser.parse(query, filterService.dateOrder(userId));
        } catch (FilterSyntaxException ex) {
            throw ex.toApiException();
        }
        DisplayOptions options = options(userId, ViewKind.FILTER, display);
        return run(userId, new Scope(ViewKind.FILTER, null, node, null, null), options, cursor, pageSize);
    }

    public Page<TaskResponse> byView(UUID userId, TaskViewType view, LocalDate from, LocalDate to,
                                     DisplayQuery display, String cursor, Integer limit) {
        int pageSize = CursorCodec.limit(limit);
        ViewKind kind = switch (view) {
            case today -> ViewKind.TODAY;
            case upcoming -> ViewKind.UPCOMING;
            default -> throw ApiException.badRequest(ErrorMessage.DISPLAY_INVALID); // overdue / completed không nhận Display
        };
        DisplayOptions options = options(userId, kind, display);
        LocalDate windowFrom = null;
        LocalDate windowTo = null;
        if (kind == ViewKind.UPCOMING) {
            // Cửa sổ ngày dùng chung với M1 (TaskViewService.upcomingWindow): kẹp from ở hôm nay, to mặc định from + 13, <= 31 ngày.
            LocalDate today = LocalDate.now(clock.withZone(userService.timeZoneOf(userId)));
            TaskViewService.UpcomingWindow window = TaskViewService.upcomingWindow(from, to, today);
            windowFrom = window.first();
            windowTo = window.last();
        }
        return run(userId, new Scope(kind, null, null, windowFrom, windowTo), options, cursor, pageSize);
    }

    /** Task gốc đang mở của dự án (cả Inbox, cả dự án lưu trữ) sắp / nhóm theo Display, cây con đi theo cha. */
    public Page<TaskResponse> byProject(UUID userId, UUID projectId, DisplayQuery display, String cursor,
                                        Integer limit) {
        int pageSize = CursorCodec.limit(limit);
        projectService.requireReadable(userId, projectId);
        DisplayOptions options = options(userId, ViewKind.PROJECT, display);
        return run(userId, new Scope(ViewKind.PROJECT, projectId, null, null, null), options, cursor, pageSize);
    }

    /** M7: tối đa 42 ngày mỗi lần (lưới tháng 6 tuần): to − from ≤ 41. */
    static final int CALENDAR_MAX_SPAN_DAYS = 41;
    /** M7: tầm xem lịch ± 2 năm quanh hôm nay (như tầm xem Upcoming). */
    static final int CALENDAR_MAX_YEARS = 2;

    /**
     * M7 GET tasks?view=calendar&from=&to= (spec 12.9): ngoại lệ DUY NHẤT được kèm tối đa một phạm vi projectId /
     * labelId / filter. from / to bắt buộc; sort / order / group không nhận (thứ tự cố định); filterDue=no_date → 400
     * (lịch chỉ có task có ngày). Lấy mọi cấp task (sub-task có due cũng hiện).
     */
    public Page<TaskResponse> byCalendar(UUID userId, ListingParams params, DisplayQuery display, String cursor,
                                         Integer limit) {
        int pageSize = CursorCodec.limit(limit);
        long scopes = Stream.of(params.projectId(), params.labelId(), params.filter())
                .filter(selector -> selector != null).count();
        if (scopes > 1 || params.completed() || params.since() != null || params.until() != null) {
            throw ApiException.badRequest(ErrorMessage.TASK_LIST_SELECTOR);
        }
        if (display.sort() != null || display.order() != null || display.group() != null) {
            throw ApiException.badRequest(ErrorMessage.DISPLAY_INVALID);
        }
        ZoneId zone = userService.timeZoneOf(userId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate from = params.from();
        LocalDate to = params.to();
        if (from == null || to == null || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) > CALENDAR_MAX_SPAN_DAYS
                || from.isBefore(today.minusYears(CALENDAR_MAX_YEARS))
                || to.isAfter(today.plusYears(CALENDAR_MAX_YEARS))) {
            throw ApiException.badRequest(ErrorMessage.CALENDAR_WINDOW_INVALID);
        }
        // Chỉ bộ lọc: kiểm bằng luật của view dự án (nhận đủ 7 filterDue), rồi chặn no_date.
        DisplayOptions options = options(userId, ViewKind.PROJECT, display);
        if (options.filterDue() == DueFilter.NO_DATE) {
            throw ApiException.badRequest(ErrorMessage.DISPLAY_INVALID);
        }
        Scope scope;
        if (params.projectId() != null) {
            projectService.requireReadable(userId, params.projectId());
            scope = new Scope(ViewKind.PROJECT, params.projectId(), null, from, to);
        } else if (params.labelId() != null) {
            labelService.requireOwned(userId, params.labelId());
            scope = new Scope(ViewKind.LABEL, params.labelId(), null, from, to);
        } else if (params.filter() != null) {
            FilterNode node;
            try {
                node = FilterQueryParser.parse(params.filter(), filterService.dateOrder(userId));
            } catch (FilterSyntaxException ex) {
                throw ex.toApiException();
            }
            scope = new Scope(ViewKind.FILTER, null, node, from, to);
        } else {
            scope = new Scope(null, null, null, from, to);
        }
        // M8a: một dự án → phạm vi xem (gồm xem trước dự án Team); label / filter / lịch chung → dự án đã tham gia
        ReadScope readScope = scope.kind() == ViewKind.PROJECT
                ? accessPolicy.viewScope(userId) : accessPolicy.listScope(userId);
        if (readScope.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        Clock local = clock.withZone(zone);
        Context context = new Context(userId, readScope, today, LocalDateTime.now(local), zone.getId());
        String kind = "calendar:" + scope.kind();
        CursorCodec.Cursor after = CursorCodec.hasCursor(cursor)
                ? CursorCodec.decode(kind, cursor, TaskListingRepository.CALENDAR_KEYS.size()) : null;
        List<KeyedId> rows = repository.calendarPage(context, scope, options, after, pageSize + 1);
        Page<KeyedId> page = Page.fromLookahead(rows, pageSize, row -> CursorCodec.encode(kind, row.keys()));
        List<UUID> ids = page.items().stream().map(KeyedId::id).toList();
        return new Page<>(taskViewService.getResponsesInOrder(userId, ids, readScope), page.nextCursor());
    }

    /** Display của request (hoặc mặc định của view); filterLabelId phải là label của user (404). */
    private DisplayOptions options(UUID userId, ViewKind kind, DisplayQuery display) {
        DisplayOptions options = DisplayParams.fromQuery(kind, display).orElseGet(() -> DisplayRules.defaults(kind));
        if (options.filterLabelId() != null) {
            labelService.requireOwned(userId, options.filterLabelId());
        }
        return options;
    }

    private Page<TaskResponse> run(UUID userId, Scope scope, DisplayOptions options, String cursor, int pageSize) {
        // M8a: một dự án → phạm vi xem (gồm xem trước dự án Team); label / filter / view → dự án đã tham gia
        ReadScope readScope = scope.kind() == ViewKind.PROJECT
                ? accessPolicy.viewScope(userId) : accessPolicy.listScope(userId);
        if (readScope.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        ZoneId zone = userService.timeZoneOf(userId);
        Clock local = clock.withZone(zone);
        Context context = new Context(userId, readScope, LocalDate.now(local), LocalDateTime.now(local),
                zone.getId());
        List<SortKey> keys = repository.keys(scope, options);
        String kind = cursorKind(scope, options);
        CursorCodec.Cursor after = CursorCodec.hasCursor(cursor) ? CursorCodec.decode(kind, cursor, keys.size()) : null;
        List<KeyedId> rows = repository.page(context, scope, options, after, pageSize + 1);
        Page<KeyedId> page = Page.fromLookahead(rows, pageSize, row -> CursorCodec.encode(kind, row.keys()));
        List<UUID> rootIds = page.items().stream().map(KeyedId::id).toList();
        List<UUID> ids = scope.kind() == ViewKind.PROJECT
                ? SubtreeOrder.arrange(rootIds, repository.descendants(rootIds)) : rootIds;
        return new Page<>(taskViewService.getResponsesInOrder(userId, ids, readScope), page.nextCursor());
    }

    /** Con trỏ gắn với loại view + sort / order / group: đổi Display mà dùng lại con trỏ cũ → 400. */
    private static String cursorKind(Scope scope, DisplayOptions options) {
        return "list:" + scope.kind() + ":" + options.sortBy() + ":" + options.direction() + ":" + options.groupBy();
    }
}
