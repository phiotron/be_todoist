package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.filter.CreateFilterRequest;
import com.taskly.dto.filter.FilterResponse;
import com.taskly.dto.filter.UpdateFilterRequest;
import com.taskly.dto.filter.ValidateFilterRequest;
import com.taskly.dto.filter.ValidateFilterResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.FilterService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/filters")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class FilterController {

    private final FilterService filterService;

    public FilterController(FilterService filterService) {
        this.filterService = filterService;
    }

    @GetMapping
    public List<FilterResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return filterService.list(CurrentUser.id(jwt));
    }

    /** 201; `id` đã có + cùng nội dung → 200 kèm `Idempotent-Replay: true`; khác nội dung / của người khác → 409. */
    @PostMapping
    public ResponseEntity<FilterResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestBody CreateFilterRequest request) {
        return filterService.create(CurrentUser.id(jwt), request).toResponseEntity();
    }

    @PostMapping("/validate")
    public ValidateFilterResponse validate(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody ValidateFilterRequest request) {
        return filterService.validate(CurrentUser.id(jwt), request.query());
    }

    @PatchMapping("/{filterId}")
    public FilterResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID filterId,
                                 @RequestBody UpdateFilterRequest request) {
        return filterService.update(CurrentUser.id(jwt), filterId, request);
    }

    @DeleteMapping("/{filterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID filterId) {
        filterService.delete(CurrentUser.id(jwt), filterId);
    }

    @PutMapping("/{filterId}/favorite")
    public FilterResponse favorite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID filterId) {
        return filterService.setFavorite(CurrentUser.id(jwt), filterId, true);
    }

    @DeleteMapping("/{filterId}/favorite")
    public FilterResponse unfavorite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID filterId) {
        return filterService.setFavorite(CurrentUser.id(jwt), filterId, false);
    }
}
