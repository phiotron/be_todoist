package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.task.CreateTaskRequest;
import com.taskly.dto.task.TaskResponse;
import com.taskly.dto.task.UpdateTaskRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** CRUD + xoá vĩnh viễn. Danh sách (GET /tasks) ở TaskListController; close/move/duplicate/batch ở controller riêng. */
@RestController
@RequestMapping("/api/v1/tasks")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 201 khi tạo mới; 200 + Idempotent-Replay: true khi `id` client đã có cùng nội dung (spec 12.11). */
    @PostMapping
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", description = "Chống gửi trùng (UUID v4)",
            schema = @Schema(type = "string", maxLength = 64))
    @ApiResponse(responseCode = "201", description = "Đã tạo")
    @ApiResponse(responseCode = "200", description = "`id` client đã có cùng nội dung — kèm header Idempotent-Replay: true")
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody CreateTaskRequest request) {
        UUID userId = CurrentUser.id(jwt);
        try {
            return taskService.create(userId, request).toResponseEntity();
        } catch (DataIntegrityViolationException e) {
            if (request.id() == null) {
                throw e;
            }
            // Hai request song song cùng `id` client: bên thua vi phạm khoá chính ở INSERT, transaction đã rollback.
            // Chạy lại MỘT lần trong transaction mới — lúc này `id` đã có nên thành replay (200) hoặc 409 id-conflict
            // (spec 12.11) thay vì 409 "xung đột dữ liệu" chung. Lỗi ràng buộc khác lặp lại ở lần hai → handler chung.
            return taskService.create(userId, request).toResponseEntity();
        }
    }

    @GetMapping("/{taskId}")
    public TaskResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return taskService.get(CurrentUser.id(jwt), taskId);
    }

    @PatchMapping("/{taskId}")
    public TaskResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                               @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(CurrentUser.id(jwt), taskId, request);
    }

    /** Xoá vĩnh viễn kèm cây con (app hỏi xác nhận, ghi số sub-task — BR-11). Dự án / section lưu trữ → 409. */
    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        taskService.delete(CurrentUser.id(jwt), taskId);
    }
}
