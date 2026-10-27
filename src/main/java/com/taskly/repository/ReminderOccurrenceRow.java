package com.taskly.repository;

import com.taskly.entity.ReminderKind;
import java.time.LocalDateTime;
import java.util.UUID;

/** Một dòng của ReminderOccurrenceQuery; reminderId null với auto reminder. */
public record ReminderOccurrenceRow(UUID taskId, UUID reminderId, ReminderKind kind, LocalDateTime fireAt,
                                    String content) {
}
