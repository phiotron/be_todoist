package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.task.RestoreReviewRequest;
import com.taskly.dto.task.ReviewCountResponse;
import com.taskly.dto.task.TaskChangeResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskCompletionService;
import com.taskly.service.TaskViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Chờ duyệt (review) của việc trong dự án NHÓM: Admin duyệt / trả lại, số việc chờ duyệt cho badge. */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskReviewController {

    private final TaskCompletionService completion;
    private final TaskViewService views;

    public TaskReviewController(TaskCompletionService completion, TaskViewService views) {
        this.completion = completion;
        this.views = views;
    }

    @PostMapping("/api/v1/tasks/{taskId}/review/approve")
    @Operation(summary = "Admin nhóm duyệt việc PENDING = hoàn thành thật (403 team-admin-only; 409 review-not-pending)")
    public TaskChangeResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return completion.approve(CurrentUser.id(jwt), taskId);
    }

    @PostMapping("/api/v1/tasks/{taskId}/review/reject")
    @Operation(summary = "Admin nhóm trả lại việc PENDING (về NONE, vẫn mở; 403 team-admin-only; 409 review-not-pending)")
    public TaskChangeResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return completion.reject(CurrentUser.id(jwt), taskId);
    }

    @PostMapping("/api/v1/tasks/{taskId}/review/restore")
    @Operation(summary = "Admin nhóm hoàn tác \"Trả lại\": khôi phục PENDING với requestedBy / requestedAt cũ (403 team-admin-only; "
            + "409 review-not-restorable; 400 requestedBy không thuộc workspace)")
    public TaskChangeResponse restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                                      @Valid @RequestBody RestoreReviewRequest request) {
        return completion.restore(CurrentUser.id(jwt), taskId, request);
    }

    @GetMapping("/api/v1/tasks/review/count")
    @Operation(summary = "Số việc chờ duyệt: Admin = mọi việc PENDING của workspace nhóm mình quản trị; người khác = việc mình yêu cầu")
    public ReviewCountResponse count(@AuthenticationPrincipal Jwt jwt) {
        return new ReviewCountResponse(views.reviewCount(CurrentUser.id(jwt)));
    }
}
