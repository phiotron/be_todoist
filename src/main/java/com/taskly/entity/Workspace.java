package com.taskly.entity;

import com.taskly.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "workspaces")
public class Workspace extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private WorkspaceType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /** M8a: link mời workspace nhóm (lưu nguyên để chép lại; null = tắt — ADR 0003). */
    @Column(name = "invite_link_token", length = 64)
    private String inviteLinkToken;

    protected Workspace() {
        // cho JPA
    }

    public Workspace(String name, WorkspaceType type, User owner) {
        this.name = name;
        this.type = type;
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public WorkspaceType getType() {
        return type;
    }

    public User getOwner() {
        return owner;
    }

    public boolean isTeam() {
        return type == WorkspaceType.TEAM;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    /** Chủ sở hữu (cột owner_id, chỉ dùng khi xoá tài khoản) chuyển cho người khác — M8a. */
    public void transferOwnership(User newOwner) {
        this.owner = newOwner;
    }

    public String getInviteLinkToken() {
        return inviteLinkToken;
    }

    public void setInviteLinkToken(String inviteLinkToken) {
        this.inviteLinkToken = inviteLinkToken;
    }
}
