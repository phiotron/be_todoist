package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Khoá chống gửi trùng (spec 12.11). status/responseBody null = request đầu tiên đang xử lý. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idem_key", nullable = false, length = 64)
    private String idemKey;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 300)
    private String path;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "status")
    private Integer status;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKey() {
        // cho JPA
    }

    public IdempotencyKey(UUID userId, String idemKey, String method, String path, String requestHash,
                          Instant expiresAt) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.method = method;
        this.path = path;
        this.requestHash = requestHash;
        this.expiresAt = expiresAt;
    }

    public void complete(int status, String responseBody) {
        this.status = status;
        this.responseBody = responseBody;
    }

    public boolean isInProgress() {
        return status == null;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getStatus() {
        return status;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
