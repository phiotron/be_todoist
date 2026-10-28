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
 * M8a: lời mời email (ADR 0003): hạn 7 ngày, dùng một lần, bắt buộc trùng email, chỉ lưu SHA-256 của token. Đích là
 * MỘT workspace nhóm (workspaceId) HOẶC MỘT dự án (projectId). Link mời KHÔNG dùng bảng này (lưu ở projects /
 * workspaces.invite_link_token).
 */
@Entity
@Table(name = "invitations")
public class Invitation extends BaseEntity {

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private WorkspaceRole role;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "declined_at")
    private Instant declinedAt;

    protected Invitation() {
        // cho JPA
    }

    private Invitation(UUID workspaceId, UUID projectId, String email, WorkspaceRole role, String tokenHash,
                       UUID invitedBy, Instant expiresAt) {
        this.workspaceId = workspaceId;
        this.projectId = projectId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
    }

    public static Invitation toProject(UUID projectId, String email, String tokenHash, UUID invitedBy,
                                       Instant expiresAt) {
        return new Invitation(null, projectId, email, WorkspaceRole.MEMBER, tokenHash, invitedBy, expiresAt);
    }

    public static Invitation toWorkspace(UUID workspaceId, String email, WorkspaceRole role, String tokenHash,
                                         UUID invitedBy, Instant expiresAt) {
        return new Invitation(workspaceId, null, email, role, tokenHash, invitedBy, expiresAt);
    }

    /** Còn chờ: chưa chấp nhận, chưa từ chối, chưa hết hạn. */
    public boolean isPending(Instant now) {
        return acceptedAt == null && declinedAt == null && now.isBefore(expiresAt);
    }

    public void markAccepted(Instant now) {
        this.acceptedAt = now;
    }

    public void markDeclined(Instant now) {
        this.declinedAt = now;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getEmail() {
        return email;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }
}
