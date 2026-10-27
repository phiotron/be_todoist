package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.common.Page;
import com.taskly.dto.search.SearchObjectsResponse;
import com.taskly.dto.task.TaskResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.SearchService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public Page<TaskResponse> search(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Từ khoá ≤ 100 ký tự; rỗng → kết quả rỗng; * là ký tự đại diện")
            @RequestParam(required = false) String q,
            @Parameter(description = "true = chỉ task đã hoàn thành (mới xong trước)")
            @RequestParam(defaultValue = "false") boolean completed,
            @RequestParam(required = false) String cursor,
            @Parameter(description = "1–200, mặc định 50") @RequestParam(required = false) Integer limit) {
        return searchService.searchTasks(CurrentUser.id(jwt), q, completed, cursor, limit);
    }

    @GetMapping("/objects")
    public SearchObjectsResponse objects(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Từ khoá ≤ 100 ký tự; rỗng → 4 mảng rỗng") @RequestParam(required = false) String q) {
        return searchService.searchObjects(CurrentUser.id(jwt), q);
    }
}
