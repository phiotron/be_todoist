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

    /** LOCATION (M12): địa điểm đã lưu; xoá địa điểm → DB xoá nhắc (FK cascade). */
    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_trigger", length = 10, updatable = false)
    private LocationTrigger locationTrigger;

    /** M12: urgent reminder (báo thức toàn màn hình trên thiết bị đã chọn) — chỉ nhắc theo giờ. */
    @Column(name = "urgent", nullable = false, updatable = false)
    private boolean urgent;

    protected Reminder() {
        // cho JPA
    }

    private Reminder(UUID taskId, UUID userId, ReminderType type, LocalDateTime at, Integer minuteOffset,
                     boolean urgent) {
        this.taskId = taskId;
        this.userId = userId;
        this.type = type;
        this.at = at;
        this.minuteOffset = minuteOffset;
        this.urgent = urgent;
    }

    public static Reminder absolute(UUID taskId, UUID userId, LocalDateTime at) {
        return absolute(taskId, userId, at, false);
    }

    public static Reminder absolute(UUID taskId, UUID userId, LocalDateTime at, boolean urgent) {
        return new Reminder(taskId, userId, ReminderType.ABSOLUTE, at, null, urgent);
    }

    public static Reminder relative(UUID taskId, UUID userId, int minuteOffset) {
        return relative(taskId, userId, minuteOffset, false);
    }

    public static Reminder relative(UUID taskId, UUID userId, int minuteOffset, boolean urgent) {
        return new Reminder(taskId, userId, ReminderType.RELATIVE, null, minuteOffset, urgent);
    }

    /** M12: nhắc theo vị trí, không bao giờ urgent (CHECK ck_reminders_urgent). */
    public static Reminder location(UUID taskId, UUID userId, UUID locationId, LocationTrigger trigger) {
        Reminder reminder = new Reminder(taskId, userId, ReminderType.LOCATION, null, null, false);
        reminder.locationId = locationId;
        reminder.locationTrigger = trigger;
        return reminder;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public LocationTrigger getLocationTrigger() {
        return locationTrigger;
    }

    public boolean isUrgent() {
        return urgent;
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
