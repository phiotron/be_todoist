package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Link calendar feed iCal (M13b): projectId null = mọi task của tài khoản, có = một dự án. tokenHash = SHA-256 hex của
 * token (tra cứu khi lịch ngoài gọi feed); tokenEnc = bản mã SecretCipher để hiện lại link cho chính chủ.
 */
@Entity
@Table(name = "calendar_feeds")
public class CalendarFeed extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_enc", nullable = false, columnDefinition = "text")
    private String tokenEnc;

    protected CalendarFeed() {
        // cho JPA
    }

    public CalendarFeed(UUID userId, UUID projectId, String tokenHash, String tokenEnc) {
        this.userId = userId;
        this.projectId = projectId;
        this.tokenHash = tokenHash;
        this.tokenEnc = tokenEnc;
    }

    /** Đặt lại link: token cũ hết hiệu lực ngay. */
    public void replaceToken(String tokenHash, String tokenEnc) {
        this.tokenHash = tokenHash;
        this.tokenEnc = tokenEnc;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getTokenEnc() {
        return tokenEnc;
    }
}
