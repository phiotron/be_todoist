package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.entity.Project;
import com.taskly.entity.ProjectAccess;
import com.taskly.entity.WorkspaceRole;
import com.taskly.entity.WorkspaceType;
import com.taskly.repository.WorkspaceMemberRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Nơi DUY NHẤT quyết định quyền (spec mục 5.5, 8.2.3, 11). Không thấy → 404 (không lộ id có tồn tại); thấy nhưng thiếu
 * quyền → 403.
 *
 * <p><b>M8a — quyền theo DỰ ÁN</b> (plan M8a F1):
 * <ul>
 *   <li>Dự án của workspace PERSONAL: chủ workspace = chủ dự án (đọc / ghi / quản lý); người có hàng project_members =
 *       cộng tác viên (đọc / ghi, KHÔNG quản lý); người khác 404.</li>
 *   <li>Dự án của workspace TEAM: chỉ thành viên workspace mới có thể thấy. Đã tham gia (project_members) → đọc / ghi;
 *       Admin / Member chưa tham gia dự án access = TEAM → XEM TRƯỚC (đọc được, ghi 403); RESTRICTED chưa được mời → 404
 *       (kể cả Admin). Quản lý: Admin, hoặc người tạo (Member) đã tham gia.</li>
 * </ul>
 * Phạm vi danh sách = {@link #listScope} (dự án đã tham gia); phạm vi xem một dự án / một task = {@link #viewScope}.
 *
 * <p><b>PERM — quy tắc người dùng chốt 28/10 (KHÁC Todoist)</b>: trong dự án của workspace TEAM (Team / Restricted),
 * chỉ Admin được sửa / quản lý; Member và Guest đã tham gia chỉ xem + đánh dấu hoàn thành / mở lại / Undo
 * ({@link #checkTaskComplete}). Mọi ghi khác đi qua {@link #checkProjectWrite} → 403 code team-admin-only. Dự án cá
 * nhân (kể cả được chia sẻ) giữ nguyên M8a. Tạo / nhân bản / chuyển dự án vào team: {@link #requireTeamEditor}.
 * Quyết định tạm: comment / reaction cũng bị chặn ("chỉ được tick"); mời người + Join / Leave vẫn được.
 */
@Component
public class AccessPolicy {

    /** Mức quyền của một user trên một dự án. */
    public enum ProjectRole {
        NONE,
        /** Admin / Member chưa tham gia dự án Team: đọc được, ghi 403. */
        PREVIEW,
        MEMBER
    }

    /**
     * Quyền của user trên một dự án. workspaceRole null với người ngoài workspace (vd cộng tác viên dự án cá nhân).
     * owner: chủ workspace cá nhân chứa dự án.
     */
    public record ProjectAccessInfo(UUID projectId, UUID workspaceId, WorkspaceType workspaceType,
                                    WorkspaceRole workspaceRole, boolean owner, boolean joined, ProjectRole role,
                                    boolean canManage) {

        public boolean canRead() {
            return role != ProjectRole.NONE;
        }

        public boolean canWrite() {
            return role == ProjectRole.MEMBER;
        }

        public boolean isTeam() {
            return workspaceType == WorkspaceType.TEAM;
        }

        /** PERM: đánh dấu hoàn thành / mở lại / Undo — mọi người ĐÃ tham gia dự án (kể cả Member / Guest của nhóm). */
        public boolean canComplete() {
            return canWrite();
        }

        /**
         * PERM: mọi ghi KHÁC hoàn thành (task, section, dự án, comment, nhắc, sắp xếp...). Dự án nhóm: chỉ Admin đã tham
         * gia; dự án cá nhân (kể cả được chia sẻ): như canWrite.
         */
        public boolean canEdit() {
            return canWrite() && (!isTeam() || workspaceRole == WorkspaceRole.ADMIN);
        }

        /** Chờ duyệt: dự án NHÓM, người ĐÃ tham gia nhưng không phải Admin → tick hoàn thành = yêu cầu duyệt (PENDING). */
        public boolean mustRequestReview() {
            return canWrite() && isTeam() && workspaceRole != WorkspaceRole.ADMIN;
        }

        /** Chờ duyệt: Admin của workspace nhóm (đọc được dự án) duyệt / trả lại. */
        public boolean canReview() {
            return canRead() && isTeam() && workspaceRole == WorkspaceRole.ADMIN;
        }
    }

    private static final String LIST_SCOPE_SQL = """
            select p.id, p.workspace_id from projects p join workspaces w on w.id = p.workspace_id
             where w.type = 'PERSONAL' and w.owner_id = :userId
            union
            select p.id, p.workspace_id from project_members pm
              join projects p on p.id = pm.project_id
              join workspaces w on w.id = p.workspace_id
             where pm.user_id = :userId
               and (w.type = 'PERSONAL' or exists (select 1 from workspace_members wm
                                                    where wm.workspace_id = w.id and wm.user_id = :userId))
            """;

    private static final String PREVIEW_SCOPE_SQL = """
            union
            select p.id, p.workspace_id from projects p
              join workspaces w on w.id = p.workspace_id
              join workspace_members wm on wm.workspace_id = w.id and wm.user_id = :userId
             where w.type = 'TEAM' and p.access = 'TEAM' and wm.role in ('ADMIN', 'MEMBER')
            """;

    private final WorkspaceMemberRepository memberRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public AccessPolicy(WorkspaceMemberRepository memberRepository, NamedParameterJdbcTemplate jdbc) {
        this.memberRepository = memberRepository;
        this.jdbc = jdbc;
    }

    // ---- Mức workspace (M1; giữ cho tương thích) ----

    /** Thành viên workspace (mọi vai trò). Workspace cá nhân: chỉ chủ. */
    public boolean canRead(UUID userId, UUID workspaceId) {
        return memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId);
    }

    public void checkRead(UUID userId, UUID workspaceId, ErrorMessage notFoundDetail) {
        if (!canRead(userId, workspaceId)) {
            throw ApiException.notFound(notFoundDetail);
        }
    }

    public void checkWrite(UUID userId, UUID workspaceId, ErrorMessage notFoundDetail) {
        checkRead(userId, workspaceId, notFoundDetail);
    }

    /**
     * Workspace chứa các dự án user ĐÃ THAM GIA (= listScope().workspaceIds()). M8a: KHÔNG đủ để lọc dữ liệu — một
     * workspace cá nhân có thể chỉ chia sẻ một dự án; truy vấn mới phải dùng {@link #listScope} / {@link #viewScope}.
     */
    @Deprecated
    public List<UUID> readableWorkspaceIds(UUID userId) {
        return listScope(userId).workspaceIds();
    }

    // ---- M8a: phạm vi đọc ----

    /** Dự án user đã tham gia (Today / Upcoming / tìm kiếm / filter / nhắc / danh sách dự án). */
    public ReadScope listScope(UUID userId) {
        return scope(LIST_SCOPE_SQL, userId);
    }

    /** listScope + dự án Team xem trước được (xem một dự án / một task). */
    public ReadScope viewScope(UUID userId) {
        return scope(LIST_SCOPE_SQL + PREVIEW_SCOPE_SQL, userId);
    }

    /**
     * PERF: ghi nhớ phạm vi đọc trong LUỒNG hiện tại tới khi đóng — dùng cho một đoạn CHỈ ĐỌC gọi listScope / viewScope
     * nhiều lần (full sync gọi ~6 lần, mỗi lần một câu SQL ~2 ms). KHÔNG dùng quanh đoạn có ghi (tạo / mời / xoá dự án
     * đổi phạm vi). Lồng nhau: lớp trong dùng lại bản nhớ của lớp ngoài.
     */
    public ScopeMemo memoizeScopes() {
        if (SCOPE_MEMO.get() != null) {
            return () -> { };
        }
        SCOPE_MEMO.set(new HashMap<>());
        return () -> SCOPE_MEMO.remove();
    }

    /** Đóng bản nhớ (try-with-resources). */
    @FunctionalInterface
    public interface ScopeMemo extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<Map<String, ReadScope>> SCOPE_MEMO = new ThreadLocal<>();

    private ReadScope scope(String sql, UUID userId) {
        Map<String, ReadScope> memo = SCOPE_MEMO.get();
        if (memo == null) {
            return loadScope(sql, userId);
        }
        return memo.computeIfAbsent(userId + (sql == LIST_SCOPE_SQL ? "|list" : "|view"), key -> loadScope(sql, userId));
    }

    private ReadScope loadScope(String sql, UUID userId) {
        Set<UUID> workspaceIds = new LinkedHashSet<>();
        List<UUID> projectIds = new ArrayList<>();
        jdbc.query(sql, new MapSqlParameterSource("userId", userId), rs -> {
            projectIds.add(rs.getObject(1, UUID.class));
            workspaceIds.add(rs.getObject(2, UUID.class));
        });
        return new ReadScope(new ArrayList<>(workspaceIds), projectIds);
    }

    // ---- M8a: quyền trên MỘT dự án ----

    public ProjectAccessInfo projectAccess(UUID userId, Project project) {
        return projectAccess(userId, project.getId()).orElseGet(() -> new ProjectAccessInfo(project.getId(),
                project.getWorkspace().getId(), null, null, false, false, ProjectRole.NONE, false));
    }

    /** Empty khi dự án không tồn tại. */
    public Optional<ProjectAccessInfo> projectAccess(UUID userId, UUID projectId) {
        return Optional.ofNullable(projectAccess(userId, List.of(projectId)).get(projectId));
    }

    /** Quyền trên nhiều dự án bằng MỘT câu (ProjectResponse.canManage / joined của danh sách). Thiếu khoá = không tồn tại. */
    public Map<UUID, ProjectAccessInfo> projectAccess(UUID userId, Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("projectIds", projectIds);
        List<ProjectAccessInfo> rows = jdbc.query("""
                select p.id, p.workspace_id, w.type, w.owner_id, wm.role, p.access, p.created_by,
                       exists (select 1 from project_members pm
                               where pm.project_id = p.id and pm.user_id = :userId) as joined
                  from projects p
                  join workspaces w on w.id = p.workspace_id
                  left join workspace_members wm on wm.workspace_id = w.id and wm.user_id = :userId
                 where p.id in (:projectIds)
                """, params, (rs, n) -> {
            UUID projectId = rs.getObject("id", UUID.class);
            WorkspaceType type = WorkspaceType.valueOf(rs.getString("type"));
            String roleText = rs.getString("role");
            WorkspaceRole workspaceRole = roleText == null ? null : WorkspaceRole.valueOf(roleText);
            String accessText = rs.getString("access");
            ProjectAccess access = accessText == null ? null : ProjectAccess.valueOf(accessText);
            UUID createdBy = rs.getObject("created_by", UUID.class);
            boolean joined = rs.getBoolean("joined");
            boolean owner = type == WorkspaceType.PERSONAL && userId.equals(rs.getObject("owner_id", UUID.class));
            return evaluate(projectId, rs.getObject("workspace_id", UUID.class), type, workspaceRole, owner, joined,
                    access, userId.equals(createdBy));
        });
        Map<UUID, ProjectAccessInfo> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.projectId(), row));
        return byId;
    }

    /** Bảng quyết định (thuần) — plan M8a F1. */
    public static ProjectAccessInfo evaluate(UUID projectId, UUID workspaceId, WorkspaceType type,
                                      WorkspaceRole workspaceRole, boolean owner, boolean joined,
                                      ProjectAccess access, boolean creator) {
        ProjectRole role;
        boolean manage;
        if (type == WorkspaceType.PERSONAL) {
            role = owner || joined ? ProjectRole.MEMBER : ProjectRole.NONE;
            manage = owner;
        } else if (workspaceRole == null) {
            role = ProjectRole.NONE;
            manage = false;
        } else if (joined) {
            role = ProjectRole.MEMBER;
            manage = workspaceRole == WorkspaceRole.ADMIN; // PERM: Member là người tạo cũng KHÔNG còn quản lý (chỉ Admin)
        } else if (access == ProjectAccess.TEAM && workspaceRole != WorkspaceRole.GUEST) {
            role = ProjectRole.PREVIEW;
            manage = workspaceRole == WorkspaceRole.ADMIN;
        } else {
            role = ProjectRole.NONE;
            manage = false;
        }
        return new ProjectAccessInfo(projectId, workspaceId, type, workspaceRole, owner, joined, role, manage);
    }

    /** Không thấy → 404 (notFoundDetail). */
    public ProjectAccessInfo checkProjectRead(UUID userId, Project project, ErrorMessage notFoundDetail) {
        ProjectAccessInfo info = projectAccess(userId, project);
        if (!info.canRead()) {
            throw ApiException.notFound(notFoundDetail);
        }
        return info;
    }

    /**
     * Mọi ghi trừ hoàn thành / mở lại: không thấy → 404; xem trước (chưa tham gia dự án Team) → 403 join-required;
     * PERM: Member / Guest của dự án NHÓM → 403 code team-admin-only.
     */
    public ProjectAccessInfo checkProjectWrite(UUID userId, Project project, ErrorMessage notFoundDetail) {
        ProjectAccessInfo info = checkProjectParticipant(userId, project, notFoundDetail);
        if (!info.canEdit()) {
            throw teamAdminOnly();
        }
        return info;
    }

    /** PERM: đánh dấu hoàn thành / mở lại / Undo task — mọi người đã tham gia (404 / 403 join-required). */
    public ProjectAccessInfo checkTaskComplete(UUID userId, Project project, ErrorMessage notFoundDetail) {
        return checkProjectParticipant(userId, project, notFoundDetail);
    }

    /** Kết quả tick hoàn thành: hoàn thành thật hay chỉ gửi yêu cầu duyệt (Member / Guest dự án nhóm). */
    public enum CompleteMode {
        COMPLETE,
        REQUEST_REVIEW
    }

    public static CompleteMode completeMode(ProjectAccessInfo info) {
        return info.mustRequestReview() ? CompleteMode.REQUEST_REVIEW : CompleteMode.COMPLETE;
    }

    /**
     * Chờ duyệt: duyệt / trả lại — chỉ Admin của workspace nhóm. Không thấy → 404; Member / Guest hoặc dự án không phải
     * nhóm → 403 team-admin-only.
     */
    public ProjectAccessInfo checkReviewDecision(UUID userId, Project project, ErrorMessage notFoundDetail) {
        ProjectAccessInfo info = checkProjectRead(userId, project, notFoundDetail);
        if (!info.canReview()) {
            throw teamAdminOnly();
        }
        return info;
    }

    /**
     * Chờ duyệt: mở lại việc ĐÃ HOÀN THÀNH — Member / Guest dự án nhóm không được (hoàn thành thật chỉ do Admin duyệt /
     * tick); Admin và dự án cá nhân như cũ.
     */
    public static void checkReopenCompleted(ProjectAccessInfo info) {
        if (info.mustRequestReview()) {
            throw teamAdminOnly();
        }
    }

    /**
     * Đã tham gia dự án (không xét vai trò nhóm): không thấy → 404; xem trước → 403 join-required. Dùng cho hoàn thành
     * task và các việc về NGƯỜI của dự án (mời / quản lý lời mời) — không phải chỉnh nội dung.
     */
    public ProjectAccessInfo checkProjectParticipant(UUID userId, Project project, ErrorMessage notFoundDetail) {
        ProjectAccessInfo info = checkProjectRead(userId, project, notFoundDetail);
        if (!info.canWrite()) {
            throw ApiException.forbidden(ErrorMessage.PROJECT_JOIN_REQUIRED);
        }
        return info;
    }

    /**
     * PERM: tạo / nhân bản / chuyển dự án VÀO workspace nhóm — chỉ Admin. Người ngoài → 404; Member / Guest → 403
     * team-admin-only (dự án tạo ra họ cũng không sửa được).
     */
    public void requireTeamEditor(UUID userId, UUID workspaceId) {
        if (requireWorkspaceRole(userId, workspaceId) != WorkspaceRole.ADMIN) {
            throw teamAdminOnly();
        }
    }

    public static ApiException teamAdminOnly() {
        return ApiException.forbidden(ErrorMessage.TEAM_ADMIN_ONLY).withCode(ProblemCodes.TEAM_ADMIN_ONLY);
    }

    /** Lưu trữ / khôi phục / xoá / đổi cha / chuyển vào team / quyền truy cập: không thấy → 404; thiếu quyền → 403. */
    public ProjectAccessInfo checkProjectManage(UUID userId, Project project) {
        ProjectAccessInfo info = checkProjectRead(userId, project, ErrorMessage.PROJECT_NOT_FOUND);
        if (!info.canManage()) {
            throw ApiException.forbidden(ErrorMessage.NOT_PROJECT_OWNER);
        }
        return info;
    }

    // ---- M8a: quyền trên workspace nhóm ----

    /** Vai trò của user trong workspace; không phải thành viên → 404 WORKSPACE_NOT_FOUND. */
    public WorkspaceRole requireWorkspaceRole(UUID userId, UUID workspaceId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .map(m -> m.getRole())
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.WORKSPACE_NOT_FOUND));
    }

    /** Admin / Member (không phải Guest) → vai trò; Guest → 403; người ngoài → 404. */
    public WorkspaceRole requireWorkspaceMember(UUID userId, UUID workspaceId) {
        WorkspaceRole role = requireWorkspaceRole(userId, workspaceId);
        if (role == WorkspaceRole.GUEST) {
            throw ApiException.forbidden(ErrorMessage.GUEST_NOT_ALLOWED);
        }
        return role;
    }

    /** Chỉ Admin; Member / Guest → 403; người ngoài → 404. */
    public void requireWorkspaceAdmin(UUID userId, UUID workspaceId) {
        if (requireWorkspaceRole(userId, workspaceId) != WorkspaceRole.ADMIN) {
            throw ApiException.forbidden(ErrorMessage.WORKSPACE_ADMIN_REQUIRED);
        }
    }
}
