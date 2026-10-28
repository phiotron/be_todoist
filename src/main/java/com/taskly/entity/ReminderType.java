package com.taskly.entity;

/**
 * Loại nhắc riêng (spec 5.8, 10): ABSOLUTE = thời điểm at; RELATIVE = minuteOffset phút trước giờ của task;
 * LOCATION (M12) = khi tới / rời một địa điểm đã lưu (locationId + trigger), không theo giờ.
 */
public enum ReminderType {
    ABSOLUTE,
    RELATIVE,
    LOCATION
}
