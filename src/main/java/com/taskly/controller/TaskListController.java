package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.common.Page;
import com.taskly.dto.task.TaskResponse;
import com.taskly.entity.TaskViewType;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskViewService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDate;
import java.util.List;
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

    public TaskListController(TaskViewService taskViewService) {
        this.taskViewService = taskViewService;
    }

    /**
     * Đúng một trong view / projectId. limit 1–200 (mặc định 50); cursor = nextCursor của trang trước.
     * completed=true chỉ đi với projectId; from / to chỉ đi với view=upcoming; since / until chỉ đi với view=completed.
     * Điểm mở rộng M2: thêm labelId / filter / tham số Display (sort, order, group, filterPriority…) vào handler này
     * và rẽ nhánh "chế độ label / filter" TRƯỚC lời gọi listByViewOrProject — không sửa nhánh view / projectId của M1.
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
                                   @RequestParam(required = false) Integer limit) {
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
