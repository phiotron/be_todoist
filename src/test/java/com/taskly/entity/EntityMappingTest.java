package com.taskly.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.repository.ProjectRepository;
import com.taskly.repository.RefreshTokenRepository;
import com.taskly.repository.UserRepository;
import com.taskly.repository.WorkspaceMemberRepository;
import com.taskly.repository.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ddl-auto=validate đã kiểm mapping lúc khởi động; test này kiểm thêm id sinh tự động và auditing theo Clock. */
class EntityMappingTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

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
    void savesWorkspaceMemberProjectAndRefreshToken() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));
        Workspace workspace = workspaceRepository.saveAndFlush(
                new Workspace("Cá nhân", WorkspaceType.PERSONAL, user));
        WorkspaceMember member = workspaceMemberRepository.saveAndFlush(
                new WorkspaceMember(workspace, user, WorkspaceRole.ADMIN));
        Project project = projectRepository.saveAndFlush(
                new Project(workspace, "Inbox", null, true, ViewStyle.LIST, 0));
        String tokenHash = "a".repeat(64);
        RefreshToken token = refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, tokenHash, clock.instant().plus(Duration.ofDays(30))));

        assertThat(member.getId()).isNotNull();
        assertThat(token.isActive(clock.instant())).isTrue();

        Map<String, Object> ws = jdbcTemplate.queryForMap(
                "select type, owner_id from workspaces where id = ?", workspace.getId());
        assertThat(ws).containsEntry("type", "PERSONAL").containsEntry("owner_id", user.getId());

        Map<String, Object> pr = jdbcTemplate.queryForMap(
                "select is_inbox, view_style from projects where id = ?", project.getId());
        assertThat(pr).containsEntry("is_inbox", true).containsEntry("view_style", "LIST");

        Integer tokens = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ? and user_id = ?",
                Integer.class, tokenHash, user.getId());
        assertThat(tokens).isEqualTo(1);
    }
}
