package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.common.Page;
import com.taskly.dto.task.TaskResponse;
import com.taskly.entity.TaskViewType;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskListingService;
import com.taskly.service.TaskViewService;
import com.taskly.service.display.DisplayQuery;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskListController {

    private final TaskViewService taskViewService;

    private final TaskListingService taskListingService;

    public TaskListController(TaskViewService taskViewService, TaskListingService taskListingService) {
        this.taskViewService = taskViewService;
        this.taskListingService = taskListingService;
    }

    /**
     * Đúng một trong view / projectId. limit 1–200 (mặc định 50); cursor = nextCursor của trang trước.
     * completed=true chỉ đi với projectId; from / to chỉ đi với view=upcoming; since / until chỉ đi với view=completed.
     * M2: labelId / filter / tham số Display (sort, order, group, filterPriority…) do TaskListingService.tryList xử lý
     * trước; request không có tham số nào của M2 thì tryList trả empty và nhánh view / projectId của M1 chạy như cũ.
     */
    @GetMapping("/api/v1/tasks")
    public Page<TaskResponse> list(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(required = false) TaskViewType view,
                                   @RequestParam(required = false) UUID projectId,
                                   @RequestParam(required = false) Boolean completed,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate to,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate since,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate until,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit,
                                   @Parameter(description = "M2: task đang mở có label này")
                                   @RequestParam(required = false) UUID labelId,
                                   @Parameter(description = "M2: một danh sách filter (không có ',' cấp ngoài cùng)")
                                   @RequestParam(required = false) String filter,
                                   @Parameter(description = "Display: manual|date|deadline|priority|name|added")
                                   @RequestParam(required = false) String sort,
                                   @Parameter(description = "Display: asc|desc")
                                   @RequestParam(required = false) String order,
                                   @Parameter(description = "Display: none|date|deadline|priority|project|label")
                                   @RequestParam(required = false) String group,
                                   @Parameter(description = "Display: 1-4")
                                   @RequestParam(required = false) String filterPriority,
                                   @Parameter(description = "Display: id label của mình")
                                   @RequestParam(required = false) String filterLabelId,
                                   @Parameter(description = "Display: today|this_week|next_7_days|this_month|next_30_days|no_date|overdue")
                                   @RequestParam(required = false) String filterDue) {
        Optional<Page<TaskResponse>> m2 = taskListingService.tryList(CurrentUser.id(jwt),
                new TaskListingService.ListingParams(view, projectId, labelId, filter, Boolean.TRUE.equals(completed),
                        from, to, since, until,
                        new DisplayQuery(sort, order, group, filterPriority, filterLabelId, filterDue)),
                cursor, limit);
        if (m2.isPresent()) {
            return m2.get();
        }
        return listByViewOrProject(CurrentUser.id(jwt), view, projectId, completed, from, to, since, until, cursor, limit);
    }

    /** Toàn bộ công việc con (mọi cấp, kể cả đã xong) của một task, theo thứ tự hiển thị. Không phân trang (≤ 4 cấp). */
    @GetMapping("/api/v1/tasks/{taskId}/subtasks")
    public List<TaskResponse> subtasks(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return taskViewService.subtasks(CurrentUser.id(jwt), taskId);
    }

    /** Nhánh M1: view hoặc projectId (đúng một trong hai, không thì 400). */
    private Page<TaskResponse> listByViewOrProject(UUID userId, TaskViewType view, UUID projectId, Boolean completed,
                                                   LocalDate from, LocalDate to, LocalDate since, LocalDate until,
                                                   String cursor, Integer limit) {
        return taskViewService.list(userId, view, projectId, completed, from, to, since, until, cursor, limit);
    }
}
