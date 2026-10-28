package com.taskly.integration.calendar;

/** Kết quả đổi code OAuth: token + email tài khoản lịch (có thể null). */
public record CalendarAccount(CalendarTokens tokens, String accountEmail) {
}
