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
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Dự án. Dự án con giữ parentId dạng cột UUID (quyết định Q4); xoá vĩnh viễn — dự án con, section, task bị xoá theo
 * bằng ON DELETE CASCADE ở DB.
 * @DynamicUpdate: UPDATE qua entity chỉ ghi cột ĐÃ ĐỔI → request đã nạp entity trước khi chờ khoá không ghi đè
 * archived_at / name / color... do request khác vừa commit.
 */
@Entity
@DynamicUpdate
@Table(name = "projects")
public class Project extends BaseEntity {

    public static final String INBOX_NAME = "Inbox";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    /** null chỉ với Inbox (CHECK ck_projects_color_unless_inbox). */
    @Enumerated(EnumType.STRING)
    @Column(name = "color", length = 20)
    private ProjectColor color;

    @Column(name = "is_inbox", nullable = false)
    private boolean inbox;

    @Enumerated(EnumType.STRING)
    @Column(name = "view_style", nullable = false, length = 10)
    private ViewStyle viewStyle;

    @Column(name = "sort_order", nullable = false)
    private long sortOrder;

    @Column(name = "archived_at")
    private Instant archivedAt;

    // ---- M8a Team & chia sẻ
    /** Folder của workspace nhóm (null = không folder; dự án cá nhân luôn null). */
    @Column(name = "folder_id")
    private UUID folderId;

    /** Chỉ dự án nhóm: TEAM (mọi Admin / Member thấy, tự tham gia) | RESTRICTED (chỉ người được mời); cá nhân null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "access", length = 10)
    private ProjectAccess access;

    /** Người tạo (Member chỉ quản lý dự án nhóm mình tạo); null với dự án trước M8a / người đã xoá tài khoản. */
    @Column(name = "created_by")
    private UUID createdBy;

    /** Link mời dự án (lưu nguyên; null = tắt). */
    @Column(name = "invite_link_token", length = 64)
    private String inviteLinkToken;

    protected Project() {
        // cho JPA
    }

    public Project(Workspace workspace, UUID parentId, String name, String description, ProjectColor color,
                   boolean inbox, ViewStyle viewStyle, long sortOrder) {
        this.workspace = workspace;
        this.parentId = parentId;
        this.name = name;
        this.description = description;
        this.color = color;
        this.inbox = inbox;
        this.viewStyle = viewStyle;
        this.sortOrder = sortOrder;
    }

    /** Inbox của workspace cá nhân: đứng đầu (sort_order 0), không màu, không cha, layout LIST. */
    public static Project inbox(Workspace personalWorkspace) {
        return new Project(personalWorkspace, null, INBOX_NAME, "", null, true, ViewStyle.LIST, 0);
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void changeColor(ProjectColor newColor) {
        this.color = newColor;
    }

    public void setSortOrder(long sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** Đổi dự án cha (null = gốc) và vị trí trong vùng chứa mới. */
    public void moveUnder(UUID newParentId, long newSortOrder) {
        this.parentId = newParentId;
        this.sortOrder = newSortOrder;
    }

    /** Lặp lại không ghi đè thời điểm lưu trữ đầu tiên. */
    public void archive(Instant now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public void unarchive() {
        archivedAt = null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ProjectColor getColor() {
        return color;
    }

    public boolean isInbox() {
        return inbox;
    }

    public ViewStyle getViewStyle() {
        return viewStyle;
    }

    public long getSortOrder() {
        return sortOrder;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    // ---- M8a

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public ProjectAccess getAccess() {
        return access;
    }

    public void setAccess(ProjectAccess access) {
        this.access = access;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public String getInviteLinkToken() {
        return inviteLinkToken;
    }

    public void setInviteLinkToken(String inviteLinkToken) {
        this.inviteLinkToken = inviteLinkToken;
    }

    /** Chuyển dự án gốc vào workspace nhóm (chỉ chiều vào — plan M8a F8): thành dự án gốc cuối vùng chứa đích. */
    public void moveToWorkspace(Workspace target, long newSortOrder, ProjectAccess newAccess, UUID newFolderId,
                                UUID mover) {
        this.workspace = target;
        this.parentId = null;
        this.sortOrder = newSortOrder;
        this.access = newAccess;
        this.folderId = newFolderId;
        this.createdBy = mover;
    }
}
