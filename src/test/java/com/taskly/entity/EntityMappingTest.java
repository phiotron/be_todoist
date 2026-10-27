package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.repository.FavoriteRepository;
import com.taskly.repository.IdempotencyKeyRepository;
import com.taskly.repository.ProjectRepository;
import com.taskly.repository.RefreshTokenRepository;
import com.taskly.repository.SectionRepository;
import com.taskly.repository.TaskRepository;
import com.taskly.repository.UserRepository;
import com.taskly.repository.WorkspaceMemberRepository;
import com.taskly.repository.WorkspaceRepository;
import com.taskly.security.OpaqueTokens;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ddl-auto=validate đã kiểm mapping lúc khởi động; test này kiểm thêm id, auditing theo Clock, @Version, giờ floating. */
class EntityMappingTest extends IntegrationTestBase {

    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private FavoriteRepository favoriteRepository;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;

    @Test
    void savesUserWithGeneratedIdAndTimestampsFromClock() {
        clock.advance(Duration.ofDays(2)); // chứng minh auditing đọc Clock chứ không đọc đồng hồ hệ thống
        Instant before = clock.instant();

        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));

        Instant after = clock.instant();
        assertThat(user.getId()).isNotNull();
        assertThat(user.getCreatedAt()).isBetween(before, after);
        assertThat(user.getUpdatedAt()).isBetween(before, after);
        assertThat(user.getLocale()).isEqualTo("vi");
        assertThat(user.getTimeZone()).isEqualTo("Asia/Ho_Chi_Minh");
    }

    @Test
    void savesWorkspaceMemberInboxAndRefreshToken() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        Workspace workspace = workspaceRepository.saveAndFlush(
                new Workspace("Cá nhân", WorkspaceType.PERSONAL, user));
        WorkspaceMember member = workspaceMemberRepository.saveAndFlush(
                new WorkspaceMember(workspace, user, WorkspaceRole.ADMIN));
        Project inbox = projectRepository.saveAndFlush(Project.inbox(workspace));
        String tokenHash = OpaqueTokens.sha256Hex(UUID.randomUUID().toString()); // ngẫu nhiên, không đụng unique
        RefreshToken token = refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, tokenHash, clock.instant().plus(Duration.ofDays(30))));

        assertThat(member.getId()).isNotNull();
        assertThat(token.isActive(clock.instant())).isTrue();

        Map<String, Object> ws = jdbcTemplate.queryForMap(
                "select type, owner_id from workspaces where id = ?", workspace.getId());
        assertThat(ws).containsEntry("type", "PERSONAL").containsEntry("owner_id", user.getId());

        Map<String, Object> pr = jdbcTemplate.queryForMap(
                "select is_inbox, view_style, color, parent_id, description from projects where id = ?", inbox.getId());
        assertThat(pr).containsEntry("is_inbox", true).containsEntry("view_style", "LIST")
                .containsEntry("description", "");
        assertThat(pr.get("color")).isNull();
        assertThat(pr.get("parent_id")).isNull();

        Integer tokens = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ? and user_id = ?",
                Integer.class, tokenHash, user.getId());
        assertThat(tokens).isEqualTo(1);
    }

    @Test
    void savesM1EntitiesWithVersionEnumsAndFloatingDue() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        Workspace workspace = workspaceRepository.saveAndFlush(
                new Workspace("Cá nhân", WorkspaceType.PERSONAL, user));
        Project parent = projectRepository.saveAndFlush(
                new Project(workspace, null, "Việc nhà", "", ProjectColor.blue, false, ViewStyle.LIST, 1024));
        Project child = projectRepository.saveAndFlush(
                new Project(workspace, parent.getId(), "Bếp", "Mô tả", ProjectColor.charcoal, false, ViewStyle.LIST, 1024));
        Section section = sectionRepository.saveAndFlush(new Section(child.getId(), "Tuần này", "", 1024));
        LocalDateTime nine = LocalDateTime.of(2026, 10, 25, 9, 0);
        Task task = taskRepository.saveAndFlush(new Task(workspace.getId(), child.getId(), section.getId(), null,
                user.getId(), "Mua sữa", "", 4, null, nine, 30, LocalDate.of(2026, 10, 31), 1024));
        assertThat(task.getVersion()).isZero();
        assertThat(task.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(task.getCompletionBatchId()).isNull();

        task.setContent("Mua sữa tươi");
        Task updated = taskRepository.saveAndFlush(task);
        assertThat(updated.getVersion()).isEqualTo(1);

        favoriteRepository.saveAndFlush(new Favorite(user.getId(), FavoriteTargetType.PROJECT, parent.getId()));
        IdempotencyKey key = idempotencyKeyRepository.saveAndFlush(new IdempotencyKey(user.getId(), "k-1", "POST",
                "/api/v1/tasks", "a".repeat(64), clock.instant().plus(Duration.ofHours(24))));
        assertThat(key.isInProgress()).isTrue();

        Map<String, Object> pr = jdbcTemplate.queryForMap(
                "select color, parent_id, sort_order, description from projects where id = ?", child.getId());
        assertThat(pr).containsEntry("color", "charcoal").containsEntry("parent_id", parent.getId())
                .containsEntry("sort_order", 1024L).containsEntry("description", "Mô tả");

        // Giờ floating: lưu và đọc lại đúng 09:00, không bị đổi theo múi giờ của JVM / DB.
        Map<String, Object> tk = jdbcTemplate.queryForMap(
                "select to_char(due_datetime, 'YYYY-MM-DD HH24:MI') as due, due_day, duration_minutes, deadline_date "
                        + "from tasks where id = ?", task.getId());
        assertThat(tk).containsEntry("due", "2026-10-25 09:00").containsEntry("duration_minutes", 30);
        assertThat(tk.get("due_day")).hasToString("2026-10-25");
        assertThat(tk.get("deadline_date")).hasToString("2026-10-31");
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getDueDatetime()).isEqualTo(nine);
    }

    @Test
    void entityWithClientAssignedId_isPersistedWithThatId_notMerged() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        Workspace workspace = workspaceRepository.saveAndFlush(new Workspace("Cá nhân", WorkspaceType.PERSONAL, user));
        UUID clientId = UUID.randomUUID();
        Project project = new Project(workspace, null, "Mã client", "", ProjectColor.teal, false, ViewStyle.LIST, 1024);
        assertThat(project.isNew()).isTrue();
        project.assignId(clientId);

        Project saved = projectRepository.saveAndFlush(project);

        assertThat(saved.getId()).isEqualTo(clientId);
        assertThat(saved.isNew()).isFalse();
        assertThat(projectRepository.findById(clientId)).isPresent();
        assertThatThrownBy(() -> saved.assignId(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        // entity không gán id riêng vẫn có id (server sinh)
        assertThat(projectRepository.saveAndFlush(
                new Project(workspace, null, "Server sinh", "", ProjectColor.blue, false, ViewStyle.LIST, 2048)).getId())
                .isNotNull();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional // @Modifying(flushAutomatically) cần transaction
    void nativeSortOrderUpdateAndRelocate_doNotBumpVersion_butTouchUpdatedAt() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        Workspace workspace = workspaceRepository.saveAndFlush(new Workspace("Cá nhân", WorkspaceType.PERSONAL, user));
        Project p1 = projectRepository.saveAndFlush(
                new Project(workspace, null, "P1", "", ProjectColor.blue, false, ViewStyle.LIST, 1024));
        Project p2 = projectRepository.saveAndFlush(
                new Project(workspace, null, "P2", "", ProjectColor.red, false, ViewStyle.LIST, 2048));
        Task task = taskRepository.saveAndFlush(new Task(workspace.getId(), p1.getId(), null, null, user.getId(),
                "T", "", 4, null, null, null, null, 1024));
        clock.advance(Duration.ofMinutes(5));
        Instant later = clock.instant();

        taskRepository.updateSortOrder(task.getId(), 4096, later);
        taskRepository.relocate(java.util.List.of(task.getId()), p2.getId(), null, workspace.getId(), later);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select version, sort_order, project_id, updated_at from tasks where id = ?", task.getId());
        assertThat(row).containsEntry("version", 0L).containsEntry("sort_order", 4096L).containsEntry("project_id", p2.getId());
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant()).isEqualTo(later);
    }

    @Test
    void calendarViewStyle_roundTripsThroughEntity() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        Workspace workspace = workspaceRepository.saveAndFlush(new Workspace("Cá nhân", WorkspaceType.PERSONAL, user));
        Project saved = projectRepository.saveAndFlush(
                new Project(workspace, null, "Cal", "", ProjectColor.blue, false, ViewStyle.CALENDAR, 1024));

        assertThat(projectRepository.findById(saved.getId())).get()
                .extracting(Project::getViewStyle).isEqualTo(ViewStyle.CALENDAR);
    }
}
