package com.taskly.integration.calendar;

import java.time.Instant;

/** Token OAuth của lịch. refreshToken / expiresAt có thể null. toString che giá trị. */
public record CalendarTokens(String accessToken, String refreshToken, Instant expiresAt) {

    @Override
    public String toString() {
        return "CalendarTokens[accessToken=***, refreshToken=***, expiresAt=" + expiresAt + "]";
    }
}
