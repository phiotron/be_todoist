package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.Project;
import com.taskly.entity.User;
import com.taskly.entity.Workspace;
import com.taskly.repository.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PersonalWorkspaceProvisioningTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private ProjectService projectService;

    @Test
    void createsPersonalWorkspaceWithAdminMembershipAndInbox() {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Test"));

        Workspace workspace = workspaceService.createPersonalWorkspace(user);
        Project inbox = projectService.createInbox(workspace);

        Map<String, Object> ws = jdbcTemplate.queryForMap(
                "select name, type, owner_id from workspaces where id = ?", workspace.getId());
        assertThat(ws).containsEntry("name", "Cá nhân")
                .containsEntry("type", "PERSONAL")
                .containsEntry("owner_id", user.getId());

        List<Map<String, Object>> members = jdbcTemplate.queryForList(
                "select user_id, role from workspace_members where workspace_id = ?", workspace.getId());
        assertThat(members).hasSize(1);
        assertThat(members.get(0)).containsEntry("user_id", user.getId()).containsEntry("role", "ADMIN");

        Map<String, Object> project = jdbcTemplate.queryForMap(
                "select name, is_inbox, view_style, sort_order, color, archived_at from projects where id = ?",
                inbox.getId());
        assertThat(project).containsEntry("name", "Inbox")
                .containsEntry("is_inbox", true)
                .containsEntry("view_style", "LIST")
                .containsEntry("sort_order", 0);
        assertThat(project.get("color")).isNull();
        assertThat(project.get("archived_at")).isNull();
    }
}
