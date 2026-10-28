package com.taskly.mapper;

import com.taskly.dto.reminder.ReminderResponse;
import com.taskly.entity.Reminder;

public final class ReminderMapper {

    private ReminderMapper() {
    }

    public static ReminderResponse toResponse(Reminder reminder) {
        return new ReminderResponse(reminder.getId(), reminder.getTaskId(), reminder.getType(), reminder.getAt(),
                reminder.getMinuteOffset(), reminder.getCreatedAt(), reminder.getLocationId(),
                reminder.getLocationTrigger(), reminder.isUrgent());
    }
}
