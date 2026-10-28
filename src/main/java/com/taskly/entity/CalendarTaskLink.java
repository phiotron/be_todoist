package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Task ↔ sự kiện đã đẩy sang lịch "Taskly" (M13b). fingerprint = SHA-256 nội dung đã đẩy (đổi → cập nhật sự kiện);
 * taskVersion = version của task lúc đẩy (task đổi version sau đó = Taskly đã sửa → Taskly thắng khi kéo ngược).
 */
@Entity
@Table(name = "calendar_task_links")
public class CalendarTaskLink extends BaseEntity {

    @Column(name = "connection_id", nullable = false, updatable = false)
    private UUID connectionId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "event_id", nullable = false, length = 512)
    private String eventId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "task_version", nullable = false)
    private long taskVersion;

    protected CalendarTaskLink() {
        // cho JPA
    }

    public CalendarTaskLink(UUID connectionId, UUID taskId, String eventId, String fingerprint, long taskVersion) {
        this.connectionId = connectionId;
        this.taskId = taskId;
        this.eventId = eventId;
        this.fingerprint = fingerprint;
        this.taskVersion = taskVersion;
    }

    public void pushed(String eventId, String fingerprint, long taskVersion) {
        this.eventId = eventId;
        this.fingerprint = fingerprint;
        this.taskVersion = taskVersion;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public long getTaskVersion() {
        return taskVersion;
    }
}
