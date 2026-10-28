package com.taskly.controller;

import com.taskly.common.web.IdempotencyFilter;
import com.taskly.config.OpenApiConfig;
import com.taskly.dto.comment.CommentAuthor;
import com.taskly.dto.comment.CommentResponse;
import com.taskly.dto.comment.CreateCommentRequest;
import com.taskly.dto.comment.ReactionRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.CommentService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comment của task / dự án (M8b, plan F1 + F3). Không có PATCH: Todoist Android không sửa được nội dung comment.
 * Đường dẫn chữ ".../comments/mentionable" được Spring ưu tiên hơn biến.
 */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/tasks/{taskId}/comments")
    public List<CommentResponse> listForTask(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return commentService.listForTask(CurrentUser.id(jwt), taskId);
    }

    @PostMapping("/tasks/{taskId}/comments")
    @Parameter(in = ParameterIn.HEADER, name = IdempotencyFilter.HEADER, required = false,
            description = "UUID v4 chống gửi trùng (server nhớ 24 giờ)", schema = @Schema(type = "string", maxLength = 64))
    public ResponseEntity<CommentResponse> createForTask(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId,
                                                         @Valid @RequestBody CreateCommentRequest request) {
        return commentService.createForTask(CurrentUser.id(jwt), taskId, request).toResponseEntity();
    }

    @GetMapping("/tasks/{taskId}/comments/mentionable")
    public List<CommentAuthor> mentionableForTask(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID taskId) {
        return commentService.mentionableForTask(CurrentUser.id(jwt), taskId);
    }

    @GetMapping("/projects/{projectId}/comments")
    public List<CommentResponse> listForProject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return commentService.listForProject(CurrentUser.id(jwt), projectId);
    }

    @PostMapping("/projects/{projectId}/comments")
    @Parameter(in = ParameterIn.HEADER, name = IdempotencyFilter.HEADER, required = false,
            description = "UUID v4 chống gửi trùng (server nhớ 24 giờ)", schema = @Schema(type = "string", maxLength = 64))
    public ResponseEntity<CommentResponse> createForProject(@AuthenticationPrincipal Jwt jwt,
                                                            @PathVariable UUID projectId,
                                                            @Valid @RequestBody CreateCommentRequest request) {
        return commentService.createForProject(CurrentUser.id(jwt), projectId, request).toResponseEntity();
    }

    @GetMapping("/projects/{projectId}/comments/mentionable")
    public List<CommentAuthor> mentionableForProject(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable UUID projectId) {
        return commentService.mentionableForProject(CurrentUser.id(jwt), projectId);
    }

    /** Chỉ tác giả (403 với người khác). */
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId) {
        commentService.delete(CurrentUser.id(jwt), commentId);
    }

    /** Thêm reaction của mình (đã có → giữ nguyên). */
    @PostMapping("/comments/{commentId}/reactions")
    public CommentResponse addReaction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId,
                                       @Valid @RequestBody ReactionRequest request) {
        return commentService.react(CurrentUser.id(jwt), commentId, request.emoji(), true);
    }

    @DeleteMapping("/comments/{commentId}/reactions")
    public CommentResponse removeReaction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId,
                                          @RequestParam String emoji) {
        return commentService.react(CurrentUser.id(jwt), commentId, emoji, false);
    }
}
