package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.location.CreateLocationRequest;
import com.taskly.dto.location.LocationResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.LocationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Địa điểm đã lưu cho nhắc theo vị trí (M12). Không có PATCH: sửa = xoá + tạo. */
@RestController
@RequestMapping("/api/v1/locations")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping
    public List<LocationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return locationService.list(CurrentUser.id(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationResponse create(@AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody CreateLocationRequest request) {
        return locationService.create(CurrentUser.id(jwt), request);
    }

    @DeleteMapping("/{locationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID locationId) {
        locationService.delete(CurrentUser.id(jwt), locationId);
    }
}
