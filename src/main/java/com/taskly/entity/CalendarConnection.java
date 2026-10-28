package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Kết nối lịch của một user (M13b): MỘT dòng / user (UNIQUE user_id — chỉ 1 nhà cung cấp / 1 tài khoản như Todoist).
 * access / refresh token lưu BẢN MÃ của SecretCipher (aad = userId) — không bao giờ log hay trả ra API.
 */
@Entity
@Table(name = "calendar_connections")
public class CalendarConnection extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16, updatable = false)
    private CalendarProvider provider;

    @Column(name = "account_email", length = 254)
    private String accountEmail;

    @Column(name = "access_token_enc", nullable = false, columnDefinition = "text")
    private String accessTokenEnc;

    @Column(name = "refresh_token_enc", columnDefinition = "text")
    private String refreshTokenEnc;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "show_events", nullable = false)
    private boolean showEvents = true;

    @Column(name = "sync_tasks", nullable = false)
    private boolean syncTasks;

    @Column(name = "taskly_calendar_id", length = 512)
    private String tasklyCalendarId;

    @Column(name = "needs_reauth", nullable = false)
    private boolean needsReauth;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    protected CalendarConnection() {
        // cho JPA
    }

    public CalendarConnection(UUID userId, CalendarProvider provider, String accountEmail) {
        this.userId = userId;
        this.provider = provider;
        this.accountEmail = accountEmail;
    }

    /** Bản mã do caller tạo (SecretCipher). refresh null = giữ refresh token cũ (Google không luôn trả lại). */
    public void storeTokens(String accessTokenEnc, String refreshTokenEnc, Instant expiresAt) {
        this.accessTokenEnc = accessTokenEnc;
        if (refreshTokenEnc != null) {
            this.refreshTokenEnc = refreshTokenEnc;
        }
        this.tokenExpiresAt = expiresAt;
        this.needsReauth = false;
    }

    public void markNeedsReauth() {
        this.needsReauth = true;
    }

    public void changeShowEvents(boolean showEvents) {
        this.showEvents = showEvents;
    }

    public void changeSyncTasks(boolean syncTasks) {
        this.syncTasks = syncTasks;
    }

    public void changeTasklyCalendarId(String tasklyCalendarId) {
        this.tasklyCalendarId = tasklyCalendarId;
    }

    public void markSynced(Instant at) {
        this.lastSyncedAt = at;
    }

    public UUID getUserId() {
        return userId;
    }

    public CalendarProvider getProvider() {
        return provider;
    }

    public String getAccountEmail() {
        return accountEmail;
    }

    public String getAccessTokenEnc() {
        return accessTokenEnc;
    }

    public String getRefreshTokenEnc() {
        return refreshTokenEnc;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public boolean isShowEvents() {
        return showEvents;
    }

    public boolean isSyncTasks() {
        return syncTasks;
    }

    public String getTasklyCalendarId() {
        return tasklyCalendarId;
    }

    public boolean isNeedsReauth() {
        return needsReauth;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }
}
