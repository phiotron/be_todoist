package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Địa chỉ nhận thư (M13a) của MỘT người dùng cho MỘT dự án (kể cả Inbox) hoặc MỘT task — đúng một trong projectId /
 * taskId (CHECK ở DB). Token (phần trước @) là bí mật: chỉ lưu tokenHash (SHA-256, tra cứu) và tokenCipher (AES-GCM, aad =
 * id, để hiện lại cho chủ). Hàng mới chèn bằng SQL ON CONFLICT (EmailInboxRepository.insertIfAbsent) chống đua.
 */
@Entity
@Table(name = "email_inboxes")
public class EmailInbox extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "task_id", updatable = false)
    private UUID taskId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_cipher", nullable = false, columnDefinition = "text")
    private String tokenCipher;

    protected EmailInbox() {
        // cho JPA
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public EmailTargetType getTargetType() {
        return projectId != null ? EmailTargetType.PROJECT : EmailTargetType.TASK;
    }

    public UUID getTargetId() {
        return projectId != null ? projectId : taskId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getTokenCipher() {
        return tokenCipher;
    }

    /** Tạo lại địa chỉ: token mới thay token cũ (địa chỉ cũ hết hiệu lực ngay khi commit). */
    public void replaceToken(String tokenHash, String tokenCipher) {
        this.tokenHash = tokenHash;
        this.tokenCipher = tokenCipher;
    }
}
