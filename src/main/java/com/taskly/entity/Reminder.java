package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Nhắc riêng của một task (bảng reminders, V5). Giữ cột UUID, không @ManyToOne (như Task / Section của M1).
 * Không sửa được (spec 12.8: sửa = xoá + tạo) nên mọi cột updatable = false.
 */
@Entity
@Table(name = "reminders")
public class Reminder extends BaseEntity {

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private ReminderType type;

    /** ABSOLUTE: giờ floating (không múi giờ), đã cắt về phút. */
    @Column(name = "at", updatable = false)
    private LocalDateTime at;

    /** RELATIVE: 0..10080 phút trước tasks.due_datetime. */
    @Column(name = "minute_offset", updatable = false)
    private Integer minuteOffset;

    protected Reminder() {
        // cho JPA
    }

    private Reminder(UUID taskId, UUID userId, ReminderType type, LocalDateTime at, Integer minuteOffset) {
        this.taskId = taskId;
        this.userId = userId;
        this.type = type;
        this.at = at;
        this.minuteOffset = minuteOffset;
    }

    public static Reminder absolute(UUID taskId, UUID userId, LocalDateTime at) {
        return new Reminder(taskId, userId, ReminderType.ABSOLUTE, at, null);
    }

    public static Reminder relative(UUID taskId, UUID userId, int minuteOffset) {
        return new Reminder(taskId, userId, ReminderType.RELATIVE, null, minuteOffset);
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getUserId() {
        return userId;
    }

    public ReminderType getType() {
        return type;
    }

    public LocalDateTime getAt() {
        return at;
    }

    public Integer getMinuteOffset() {
        return minuteOffset;
    }
}
