package com.taskly.controller;

import com.taskly.common.web.DeepLinks;
import com.taskly.config.CalendarProperties;
import com.taskly.config.OpenApiConfig;
import com.taskly.dto.calendar.CalendarEventsResponse;
import com.taskly.dto.calendar.CalendarFeedRequest;
import com.taskly.dto.calendar.CalendarFeedResponse;
import com.taskly.dto.calendar.CalendarStatusResponse;
import com.taskly.dto.calendar.CalendarSyncResponse;
import com.taskly.dto.calendar.ConnectCalendarRequest;
import com.taskly.dto.calendar.ConnectCalendarResponse;
import com.taskly.dto.calendar.UpdateCalendarRequest;
import com.taskly.security.CurrentUser;
import com.taskly.service.CalendarConnectionService;
import com.taskly.service.CalendarEventService;
import com.taskly.service.CalendarFeedService;
import com.taskly.service.CalendarSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Calendar M13b: kết nối Google / Outlook, sự kiện chỉ đọc, "Sync tasks to calendar", calendar feed iCal. */
@RestController
@RequestMapping("/api/v1")
public class CalendarController {

    static final MediaType TEXT_CALENDAR = MediaType.parseMediaType("text/calendar;charset=UTF-8");

    private final CalendarConnectionService connectionService;
    private final CalendarEventService eventService;
    private final CalendarSyncService syncService;
    private final CalendarFeedService feedService;
    private final CalendarProperties properties;

    public CalendarController(CalendarConnectionService connectionService, CalendarEventService eventService,
                              CalendarSyncService syncService, CalendarFeedService feedService,
                              CalendarProperties properties) {
        this.connectionService = connectionService;
        this.eventService = eventService;
        this.syncService = syncService;
        this.feedService = feedService;
        this.properties = properties;
    }

    @Operation(summary = "Trạng thái kết nối lịch", description = "connected = false khi chưa nối.")
    @GetMapping("/me/calendar")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public CalendarStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return connectionService.status(CurrentUser.id(jwt));
    }

    @Operation(summary = "Bắt đầu kết nối Google Calendar / Outlook",
            description = "200 {authorizationUrl} — app mở trong trình duyệt; xong nhà cung cấp chuyển về "
                    + "GET calendar/oauth/callback rồi deep link taskly://calendar/connected[?error=…]. "
                    + "409 code = \"calendar-already-connected\" (chỉ 1 nhà cung cấp / 1 tài khoản); "
                    + "503 code = \"calendar-not-configured\".")
    @PostMapping("/me/calendar/connect")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public ConnectCalendarResponse connect(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody ConnectCalendarRequest request) {
        return connectionService.connect(CurrentUser.id(jwt), request.provider());
    }

    @Operation(summary = "Callback OAuth lịch (nhà cung cấp gọi, không phải app)",
            description = "303 về taskly://calendar/connected, kèm error = denied | invalid-state | already-connected | "
                    + "unavailable khi không nối được.")
    @GetMapping("/calendar/oauth/callback")
    public ResponseEntity<Void> oauthCallback(@RequestParam(name = "state", required = false) String state,
                                              @RequestParam(name = "code", required = false) String code,
                                              @RequestParam(name = "error", required = false) String error) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", connectionService.completeOAuth(state, code, error));
        return DeepLinks.redirect(properties.appRedirect(), params);
    }

    @Operation(summary = "Bật / tắt hiện sự kiện và đồng bộ task", description = "404 khi chưa nối lịch.")
    @PatchMapping("/me/calendar")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public CalendarStatusResponse update(@AuthenticationPrincipal Jwt jwt,
                                         @RequestBody UpdateCalendarRequest request) {
        return connectionService.update(CurrentUser.id(jwt), request);
    }

    @Operation(summary = "Ngắt kết nối lịch", description = "204; 404 khi chưa nối.")
    @DeleteMapping("/me/calendar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public void disconnect(@AuthenticationPrincipal Jwt jwt) {
        connectionService.disconnect(CurrentUser.id(jwt));
    }

    @Operation(summary = "Đồng bộ task sang lịch \"Taskly\" ngay",
            description = "200 {created, updated, deleted, pulled}; 404 chưa nối; 503 code = \"calendar-unavailable\".")
    @PostMapping("/me/calendar/sync")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public CalendarSyncResponse sync(@AuthenticationPrincipal Jwt jwt) {
        return syncService.syncNow(CurrentUser.id(jwt));
    }

    @Operation(summary = "Sự kiện lịch (chỉ đọc) cho Hôm nay / Sắp tới",
            description = "from, to = yyyy-MM-dd (≤ 31 ngày, from ≤ to; sai → 400). Giờ theo múi giờ của user. Chưa nối "
                    + "hoặc tắt hiện sự kiện → items rỗng; 503 code = \"calendar-unavailable\".")
    @GetMapping("/calendar/events")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public CalendarEventsResponse events(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return eventService.events(CurrentUser.id(jwt), from, to);
    }

    @Operation(summary = "Link calendar feed (lấy hoặc tạo)",
            description = "projectId vắng = mọi task của tài khoản. 404 dự án không đọc được.")
    @PostMapping("/me/calendar-feeds")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public CalendarFeedResponse feed(@AuthenticationPrincipal Jwt jwt,
                                     @RequestBody(required = false) CalendarFeedRequest request) {
        return feedService.getOrCreate(CurrentUser.id(jwt), request == null ? null : request.projectId());
    }

    @Operation(summary = "Đặt lại link calendar feed", description = "Link cũ trả 404 ngay.")
    @PostMapping("/me/calendar-feeds/reset")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public CalendarFeedResponse resetFeed(@AuthenticationPrincipal Jwt jwt,
                                          @RequestBody(required = false) CalendarFeedRequest request) {
        return feedService.reset(CurrentUser.id(jwt), request == null ? null : request.projectId());
    }

    @Operation(summary = "Calendar feed iCal (công khai, token trong đường dẫn)",
            description = "text/calendar; task lặp hiện tối đa 7 lần. Token sai / đã đặt lại → 404.")
    @GetMapping(value = "/calendar/feed/{token}")
    public ResponseEntity<String> ics(@PathVariable("token") String token) {
        return ResponseEntity.ok()
                .contentType(TEXT_CALENDAR)
                .cacheControl(CacheControl.noStore())
                .body(feedService.render(token));
    }
}
