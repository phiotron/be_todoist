package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.entity.ProjectAccess;
import com.taskly.entity.WorkspaceRole;
import com.taskly.entity.WorkspaceType;
import com.taskly.security.AccessPolicy.ProjectAccessInfo;
import com.taskly.security.AccessPolicy.ProjectRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bảng quyết định thuần của AccessPolicy.evaluate (plan M8a F1) — không cần Spring / DB. */
class AccessPolicyProjectRoleTest {

    private static final UUID P = UUID.randomUUID();
    private static final UUID W = UUID.randomUUID();

    private static ProjectAccessInfo personal(boolean owner, boolean joined) {
        return AccessPolicy.evaluate(P, W, WorkspaceType.PERSONAL, owner ? WorkspaceRole.ADMIN : null, owner, joined,
                null, false);
    }

    private static ProjectAccessInfo team(WorkspaceRole role, boolean joined, ProjectAccess access, boolean creator) {
        return AccessPolicy.evaluate(P, W, WorkspaceType.TEAM, role, false, joined, access, creator);
    }

    @Test
    void personalProject_ownerManages_collaboratorEditsOnly_outsiderSeesNothing() {
        ProjectAccessInfo owner = personal(true, false);
        assertThat(owner.role()).isEqualTo(ProjectRole.MEMBER);
        assertThat(owner.canManage()).isTrue();
        assertThat(owner.canWrite()).isTrue();

        ProjectAccessInfo collaborator = personal(false, true);
        assertThat(collaborator.canWrite()).isTrue();
        assertThat(collaborator.canManage()).isFalse();

        ProjectAccessInfo outsider = personal(false, false);
        assertThat(outsider.canRead()).isFalse();
        assertThat(outsider.canManage()).isFalse();
    }

    /**
     * PERM (quy tắc người dùng chốt 28/10, KHÁC Todoist): trong dự án NHÓM chỉ Admin sửa / quản lý; Member và Guest đã
     * tham gia chỉ xem + đánh dấu hoàn thành / mở lại (kể cả Member là người tạo dự án).
     */
    @Test
    void teamProject_onlyAdminEditsAndManages_memberAndGuestOnlyComplete() {
        ProjectAccessInfo admin = team(WorkspaceRole.ADMIN, true, ProjectAccess.TEAM, false);
        assertThat(admin.canEdit()).isTrue();
        assertThat(admin.canComplete()).isTrue();
        assertThat(admin.canManage()).isTrue();

        for (boolean creator : new boolean[] {false, true}) {
            ProjectAccessInfo member = team(WorkspaceRole.MEMBER, true, ProjectAccess.TEAM, creator);
            assertThat(member.canComplete()).isTrue();
            assertThat(member.canEdit()).isFalse();
            assertThat(member.canManage()).isFalse();
        }
        ProjectAccessInfo guest = team(WorkspaceRole.GUEST, true, ProjectAccess.RESTRICTED, true);
        assertThat(guest.canComplete()).isTrue();
        assertThat(guest.canEdit()).isFalse();
        assertThat(guest.canManage()).isFalse();
    }

    @Test
    void personalProject_ownerAndCollaboratorEditAndComplete() {
        assertThat(personal(true, false).canEdit()).isTrue();
        assertThat(personal(false, true).canEdit()).isTrue();
        assertThat(personal(false, true).canComplete()).isTrue();
        assertThat(personal(false, false).canComplete()).isFalse();
    }

    @Test
    void teamPreview_cannotCompleteNorEdit() {
        ProjectAccessInfo adminPreview = team(WorkspaceRole.ADMIN, false, ProjectAccess.TEAM, false);
        assertThat(adminPreview.canComplete()).isFalse();
        assertThat(adminPreview.canEdit()).isFalse();
    }

    @Test
    void teamProject_notJoined_adminAndMemberPreviewTeamAccessOnly() {
        ProjectAccessInfo memberPreview = team(WorkspaceRole.MEMBER, false, ProjectAccess.TEAM, false);
        assertThat(memberPreview.role()).isEqualTo(ProjectRole.PREVIEW);
        assertThat(memberPreview.canRead()).isTrue();
        assertThat(memberPreview.canWrite()).isFalse();
        assertThat(memberPreview.canManage()).isFalse();

        ProjectAccessInfo adminPreview = team(WorkspaceRole.ADMIN, false, ProjectAccess.TEAM, false);
        assertThat(adminPreview.role()).isEqualTo(ProjectRole.PREVIEW);
        assertThat(adminPreview.canManage()).isTrue();

        assertThat(team(WorkspaceRole.ADMIN, false, ProjectAccess.RESTRICTED, false).canRead()).isFalse();
        assertThat(team(WorkspaceRole.MEMBER, false, ProjectAccess.RESTRICTED, false).canRead()).isFalse();
        assertThat(team(WorkspaceRole.GUEST, false, ProjectAccess.TEAM, false).canRead()).isFalse();
    }

    @Test
    void teamProject_notInWorkspace_seesNothing_evenIfJoinedRowIsStale() {
        assertThat(team(null, false, ProjectAccess.TEAM, false).canRead()).isFalse();
        assertThat(team(null, true, ProjectAccess.TEAM, false).canRead()).isFalse();
    }
}
