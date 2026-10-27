package com.taskly.entity;

/** Loại nhắc riêng (spec 5.8, 10): ABSOLUTE = thời điểm at; RELATIVE = minuteOffset phút trước giờ của task. */
public enum ReminderType {
    ABSOLUTE,
    RELATIVE
}
