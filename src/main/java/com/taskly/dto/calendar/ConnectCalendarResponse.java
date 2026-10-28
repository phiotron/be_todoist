package com.taskly.dto.calendar;

import io.swagger.v3.oas.annotations.media.Schema;

/** URL trang đồng ý OAuth của nhà cung cấp; app mở trong trình duyệt. */
public record ConnectCalendarResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String authorizationUrl) {
}
