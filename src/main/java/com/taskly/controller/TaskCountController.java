package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.filter.FilterTaskCountResponse;
import com.taskly.dto.label.LabelTaskCountResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TaskCountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cột số việc của màn Bộ lọc &amp; Nhãn (FL): một lần gọi cho cả danh sách, không đếm từng nhãn / filter. */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TaskCountController {

    private final TaskCountService taskCountService;

    public TaskCountController(TaskCountService taskCountService) {
        this.taskCountService = taskCountService;
    }

    @GetMapping("/api/v1/labels/counts")
    @Operation(summary = "Số việc chưa xong của mỗi nhãn (trong các dự án đọc được, chưa lưu trữ)")
    public List<LabelTaskCountResponse> labelCounts(@AuthenticationPrincipal Jwt jwt) {
        return taskCountService.labelCounts(CurrentUser.id(jwt));
    }

    @GetMapping("/api/v1/filters/counts")
    @Operation(summary = "Số việc chưa xong khớp mỗi bộ lọc (không trùng giữa các phần; bỏ query không còn hợp lệ)")
    public List<FilterTaskCountResponse> filterCounts(@AuthenticationPrincipal Jwt jwt) {
        return taskCountService.filterCounts(CurrentUser.id(jwt));
    }
}
