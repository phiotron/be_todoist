package com.taskly.dto.notification;

/** Trường vắng hoặc null = giữ nguyên. */
public record UpdateNotificationPrefsRequest(
        Boolean reminderPush, Boolean reminderEmail, Boolean morningOverview, Boolean eveningReview,
        Boolean subscribedEmails) {
}
