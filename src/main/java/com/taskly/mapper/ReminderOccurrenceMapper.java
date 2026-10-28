package com.taskly.mapper;

import com.taskly.dto.reminder.ReminderOccurrence;
import com.taskly.repository.ReminderOccurrenceRow;
import com.taskly.service.ReminderWindow;

public final class ReminderOccurrenceMapper {

    private ReminderOccurrenceMapper() {
    }

    public static ReminderOccurrence toResponse(ReminderOccurrenceRow row) {
        return new ReminderOccurrence(ReminderWindow.key(row.taskId(), row.reminderId()), row.taskId(),
                row.reminderId(), row.kind(), row.fireAt(), row.content(), row.urgent());
    }
}
