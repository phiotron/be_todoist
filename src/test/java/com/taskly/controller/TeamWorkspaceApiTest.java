package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M8aApiTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** M8a F6–F9: workspace nhóm, vai trò, People, dự án Team / Restricted, folder, Team Discovery, chuyển vào team. */
class TeamWorkspaceApiTest extends M8aApiTestBase {

    @Test
    void createTeam_makesCreatorAdmin_andListsPersonalAndTeam() throws Exception {
        RegisteredUser alice = newUser();
        UUID team = createTeam(alice, "Công ty");

        authGet(alice.accessToken(), "/api/v1/workspaces")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == '" + team + "')].role").value(hasItem("ADMIN")))
                .andExpect(jsonPath("$[?(@.id == '" + team + "')].type").value(hasItem("TEAM")))
                .andExpect(jsonPath("$[*].type").value(hasItem("PERSONAL")));
        authPost(alice.accessToken(), "/api/v1/workspaces", "{\"name\": \"   \"}").andExpect(status().isBadRequest());
    }

    @Test
    void personalWorkspace_isLockedForTeamOperations() throws Exception {
        RegisteredUser alice = newUser();
        UUID personal = testData.workspaceOf(alice.id());
        authPatch(alice.accessToken(), "/api/v1/workspaces/" + personal, "{\"name\": \"Đổi\"}")
                .andExpect(status().isForbidden());
        authDelete(alice.accessToken(), "/api/v1/workspaces/" + personal).andExpect(status().isForbidden());
        authPost(alice.accessToken(), "/api/v1/workspaces/" + personal + "/leave").andExpect(status().isForbidden());
        authPost(alice.accessToken(), "/api/v1/workspaces/" + personal + "/invitations",
                "{\"email\": \"" + uniqueEmail() + "\"}").andExpect(status().isForbidden());
        authGet(alice.accessToken(), "/api/v1/workspaces/" + personal).andExpect(status().isOk());
    }

    @Test
    void workspaceInvitation_roles_peopleList_andRoleChange() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Công ty");
        String path = "/api/v1/workspaces/" + team;

        authPost(admin.accessToken(), path + "/invitations",
                "{\"email\": \"" + member.email() + "\", \"role\": \"GUEST\"}").andExpect(status().isBadRequest());
        authPost(admin.accessToken(), path + "/invitations", "{\"email\": \"" + member.email() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("WORKSPACE"))
                .andExpect(jsonPath("$.role").value("MEMBER"));
        accept(member, lastMailToken(member.email())).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("WORKSPACE")).andExpect(jsonPath("$.workspaceId").value(team.toString()));

        authGet(member.accessToken(), path + "/members")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.userId == '" + member.id() + "')].role").value(hasItem("MEMBER")));
        authPost(admin.accessToken(), path + "/invitations", "{\"email\": \"" + member.email() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already-member"));

        authPatch(admin.accessToken(), path + "/members/" + member.id(), "{\"role\": \"ADMIN\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == '" + member.id() + "')].role").value(hasItem("ADMIN")));
        authPatch(admin.accessToken(), path + "/members/" + member.id(), "{\"role\": \"GUEST\"}")
                .andExpect(status().isOk());
    }

    @Test
    void lastAdmin_cannotLeaveBeDemotedOrRemoved() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, member, "MEMBER");
        String path = "/api/v1/workspaces/" + team;

        authPost(admin.accessToken(), path + "/leave").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last-admin"));
        authPatch(admin.accessToken(), path + "/members/" + admin.id(), "{\"role\": \"MEMBER\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last-admin"));
        authDelete(admin.accessToken(), path + "/members/" + admin.id()).andExpect(status().isConflict());

        authPatch(admin.accessToken(), path + "/members/" + member.id(), "{\"role\": \"ADMIN\"}")
                .andExpect(status().isOk());
        authPost(admin.accessToken(), path + "/leave").andExpect(status().isNoContent()); // đã có Admin khác
        authGet(admin.accessToken(), path).andExpect(status().isNotFound());
        authGet(member.accessToken(), path).andExpect(status().isOk());
    }

    @Test
    void workspaceInviteLink_joinsAsMember_andCanBeReset() throws Exception {
        RegisteredUser admin = newUser();
        UUID team = createTeam(admin, "Công ty");
        String path = "/api/v1/workspaces/" + team + "/invite-link";
        String token = readString(put(admin, path).andExpect(status().isOk()), "$.token");

        RegisteredUser bob = newUser();
        accept(bob, token).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("WORKSPACE"));
        authGet(bob.accessToken(), "/api/v1/workspaces/" + team)
                .andExpect(jsonPath("$.role").value("MEMBER"));
        accept(newUser(), token).andExpect(status().isOk()); // dùng nhiều lần
        put(admin, path).andExpect(status().isOk());
        accept(newUser(), token).andExpect(status().isNotFound());
        authGet(bob.accessToken(), path).andExpect(status().isForbidden()); // chỉ Admin
    }

    @Test
    void teamProject_defaultsToTeamAccess_creatorJoined_othersPreviewThenJoin() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser maker = newUser();
        RegisteredUser other = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, maker, "MEMBER");
        addToTeam(admin, team, other, "MEMBER");

        // PERM (28/10): chỉ Admin tạo dự án nhóm → "maker" là Admin (Member tạo → 403 team-admin-only)
        authPost(maker.accessToken(), "/api/v1/projects", "{\"name\": \"X\", \"workspaceId\": \"" + team + "\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("team-admin-only"));
        authPatch(admin.accessToken(), "/api/v1/workspaces/" + team + "/members/" + maker.id(), "{\"role\": \"ADMIN\"}")
                .andExpect(status().isOk());
        UUID project = createTeamProject(maker, team, "Dự án nhóm", null);
        UUID task = createTask(maker, project, "Việc nhóm");
        authGet(maker.accessToken(), "/api/v1/projects/" + project)
                .andExpect(jsonPath("$.access").value("TEAM"))
                .andExpect(jsonPath("$.workspaceId").value(team.toString()))
                .andExpect(jsonPath("$.joined").value(true))
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.memberCount").value(1));

        // xem trước: đọc được, không sửa được, không nằm trong danh sách "đã tham gia" / Today
        authGet(other.accessToken(), "/api/v1/projects/" + project)
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(false))
                .andExpect(jsonPath("$.canManage").value(false));
        authGet(other.accessToken(), "/api/v1/tasks?projectId=" + project)
                .andExpect(jsonPath("$.items[*].content").value(hasItem("Việc nhóm")));
        authGet(other.accessToken(), "/api/v1/tasks/" + task).andExpect(status().isOk());
        authGet(other.accessToken(), "/api/v1/projects")
                .andExpect(jsonPath("$[*].id").value(not(hasItem(project.toString()))));
        authPost(other.accessToken(), "/api/v1/tasks",
                "{\"content\": \"Chen ngang\", \"projectId\": \"" + project + "\"}").andExpect(status().isForbidden());
        authPatch(other.accessToken(), "/api/v1/tasks/" + task, "{\"content\": \"Sửa\", \"version\": 1}")
                .andExpect(status().isForbidden());
        authPost(other.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isForbidden());

        authPost(other.accessToken(), "/api/v1/projects/" + project + "/join")
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(true))
                .andExpect(jsonPath("$.memberCount").value(2));
        authPost(other.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk()); // PERM: chỉ tick
        authPost(other.accessToken(), "/api/v1/tasks",
                "{\"content\": \"Đã tham gia\", \"projectId\": \"" + project + "\"}").andExpect(status().isForbidden());
        authGet(other.accessToken(), "/api/v1/projects").andExpect(jsonPath("$[*].id").value(hasItem(project.toString())));
        authPost(other.accessToken(), "/api/v1/projects/" + project + "/join").andExpect(status().isOk()); // idempotent
    }

    @Test
    void teamDiscovery_splitsJoinedAndNotJoined_andHidesRestrictedProjects() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, member, "MEMBER");
        UUID open = createTeamProject(admin, team, "Mở", "TEAM");
        UUID hidden = createTeamProject(admin, team, "Kín", "RESTRICTED");
        UUID mine = createTeamProject(admin, team, "Của member", "TEAM"); // PERM: chỉ Admin tạo dự án nhóm
        authPost(member.accessToken(), "/api/v1/projects/" + mine + "/join").andExpect(status().isOk());
        createTask(admin, open, "Một");

        authGet(member.accessToken(), "/api/v1/workspaces/" + team + "/projects")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id == '" + open + "')].joined").value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.id == '" + open + "')].memberCount").value(hasItem(1)))
                .andExpect(jsonPath("$[?(@.id == '" + open + "')].activeTaskCount").value(hasItem(1)))
                .andExpect(jsonPath("$[?(@.id == '" + mine + "')].joined").value(hasItem(true)))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(hidden.toString()))));
        authGet(admin.accessToken(), "/api/v1/workspaces/" + team + "/projects")
                .andExpect(jsonPath("$[*].id").value(hasItem(hidden.toString())));
    }

    @Test
    void restrictedProject_isInvisibleToEveryoneNotInvited_evenAdmins() throws Exception {
        RegisteredUser creator = newUser();
        RegisteredUser otherAdmin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(creator, "Công ty");
        addToTeam(creator, team, otherAdmin, "ADMIN");
        addToTeam(creator, team, member, "MEMBER");
        UUID project = createTeamProject(creator, team, "Kín", "RESTRICTED");

        authGet(otherAdmin.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNotFound());
        authGet(member.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNotFound());
        authPost(member.accessToken(), "/api/v1/projects/" + project + "/join").andExpect(status().isNotFound());
        authPost(otherAdmin.accessToken(), "/api/v1/projects/" + project + "/archive").andExpect(status().isNotFound());

        addToProject(creator, project, member); // được mời → thấy + (PERM) chỉ đánh dấu hoàn thành
        authGet(member.accessToken(), "/api/v1/projects/" + project).andExpect(status().isOk());
        UUID task = createTask(creator, project, "Của creator");
        authPost(member.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk());
    }

    @Test
    void guestInvitedToOneProject_seesOnlyThatProject_andCannotBrowseTheTeam() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser guest = newUser();
        UUID team = createTeam(admin, "Công ty");
        UUID visible = createTeamProject(admin, team, "Được mời", "TEAM");
        UUID other = createTeamProject(admin, team, "Dự án khác", "TEAM");

        addToProject(admin, visible, guest); // người ngoài team → GUEST
        authGet(guest.accessToken(), "/api/v1/workspaces/" + team).andExpect(jsonPath("$.role").value("GUEST"));
        authGet(guest.accessToken(), "/api/v1/projects/" + visible).andExpect(status().isOk());
        authGet(guest.accessToken(), "/api/v1/projects/" + other).andExpect(status().isNotFound()); // kể cả access TEAM
        authGet(guest.accessToken(), "/api/v1/workspaces/" + team + "/projects").andExpect(status().isForbidden());
        authGet(guest.accessToken(), "/api/v1/workspaces/" + team + "/members").andExpect(status().isForbidden());
        authPost(guest.accessToken(), "/api/v1/projects", "{\"name\": \"X\", \"workspaceId\": \"" + team + "\"}")
                .andExpect(status().isForbidden());
        UUID task = createTask(admin, visible, "Việc"); // PERM: Guest chỉ đánh dấu hoàn thành, không tạo / sửa
        authPost(guest.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk());
        authPost(guest.accessToken(), "/api/v1/tasks", "{\"content\": \"Guest\", \"projectId\": \"" + visible + "\"}")
                .andExpect(status().isForbidden());
        authPost(guest.accessToken(), "/api/v1/projects/" + visible + "/invitations",
                "{\"email\": \"" + uniqueEmail() + "\"}").andExpect(status().isForbidden());
        authGet(guest.accessToken(), "/api/v1/projects")
                .andExpect(jsonPath("$[*].id").value(hasItem(visible.toString())))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(other.toString()))));
    }

    @Test
    void guestLeavingTheirLastProject_leavesTheTeam() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser guest = newUser();
        UUID team = createTeam(admin, "Công ty");
        UUID project = createTeamProject(admin, team, "Được mời", "TEAM");
        addToProject(admin, project, guest);

        authPost(guest.accessToken(), "/api/v1/projects/" + project + "/leave").andExpect(status().isNoContent());
        authGet(guest.accessToken(), "/api/v1/workspaces/" + team).andExpect(status().isNotFound());
    }

    @Test
    void lastMemberOfARestrictedProject_cannotLeave() throws Exception {
        RegisteredUser admin = newUser();
        UUID team = createTeam(admin, "Công ty");
        UUID project = createTeamProject(admin, team, "Kín", "RESTRICTED");
        authPost(admin.accessToken(), "/api/v1/projects/" + project + "/leave").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last-project-member"));
    }

    @Test
    void teamProjects_haveNoSubProjects() throws Exception {
        RegisteredUser admin = newUser();
        UUID team = createTeam(admin, "Công ty");
        UUID a = createTeamProject(admin, team, "A", null);
        UUID b = createTeamProject(admin, team, "B", null);
        authPost(admin.accessToken(), "/api/v1/projects", "{\"name\": \"Con\", \"workspaceId\": \"" + team
                + "\", \"parentId\": \"" + a + "\"}").andExpect(status().isBadRequest());
        authPost(admin.accessToken(), "/api/v1/projects/" + b + "/move", "{\"parentId\": \"" + a + "\"}")
                .andExpect(status().isBadRequest());
        // thiết lập chỉ của dự án nhóm: dự án cá nhân không có access / folder
        authPost(admin.accessToken(), "/api/v1/projects", "{\"name\": \"C\", \"access\": \"RESTRICTED\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void folders_groupProjects_andDeletingAFolderKeepsTheProjects() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, member, "MEMBER");
        String base = "/api/v1/workspaces/" + team + "/folders";

        UUID folder = readUuid(authPost(member.accessToken(), base, "{\"name\": \"Marketing\"}")
                .andExpect(status().isCreated()), "$.id");
        authPost(member.accessToken(), base, "{\"name\": \"\"}").andExpect(status().isBadRequest());
        UUID project = createTeamProject(admin, team, "Chiến dịch", null);
        authPatch(admin.accessToken(), "/api/v1/projects/" + project, "{\"folderId\": \"" + folder + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.folderId").value(folder.toString()));
        authGet(member.accessToken(), "/api/v1/workspaces/" + team + "/projects")
                .andExpect(jsonPath("$[0].folderId").value(folder.toString()));
        authPatch(member.accessToken(), "/api/v1/folders/" + folder, "{\"name\": \"Truyền thông\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Truyền thông"));
        authGet(admin.accessToken(), base).andExpect(jsonPath("$", hasSize(1)));

        // folder của team khác không gắn được
        UUID otherTeam = createTeam(admin, "Team khác");
        UUID foreign = readUuid(authPost(admin.accessToken(), "/api/v1/workspaces/" + otherTeam + "/folders",
                "{\"name\": \"Ngoài\"}").andExpect(status().isCreated()), "$.id");
        authPatch(admin.accessToken(), "/api/v1/projects/" + project, "{\"folderId\": \"" + foreign + "\"}")
                .andExpect(status().isBadRequest());

        authDelete(member.accessToken(), "/api/v1/folders/" + folder).andExpect(status().isNoContent());
        authGet(admin.accessToken(), "/api/v1/projects/" + project)
                .andExpect(status().isOk()).andExpect(jsonPath("$.folderId").doesNotExist());
        authPatch(admin.accessToken(), "/api/v1/projects/" + project, "{\"folderId\": null}").andExpect(status().isOk());
    }

    @Test
    void removingAMemberFromTheTeam_dropsTheirProjectMemberships() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, member, "MEMBER");
        UUID project = createTeamProject(admin, team, "Chung", null);
        authPost(member.accessToken(), "/api/v1/projects/" + project + "/join").andExpect(status().isOk());

        authDelete(member.accessToken(), "/api/v1/workspaces/" + team + "/members/" + admin.id())
                .andExpect(status().isForbidden()); // Member không xoá người khác
        authDelete(admin.accessToken(), "/api/v1/workspaces/" + team + "/members/" + member.id())
                .andExpect(status().isNoContent());
        authGet(member.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNotFound());
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from project_members where project_id = ? and user_id = ?", Integer.class, project,
                member.id());
        assertThat(rows).isZero();
    }

    @Test
    void deletingATeam_removesProjectsAndTasks_onlyForAdmins() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, member, "MEMBER");
        UUID project = createTeamProject(admin, team, "Chung", null);
        UUID task = createTask(admin, project, "Việc");

        authDelete(member.accessToken(), "/api/v1/workspaces/" + team).andExpect(status().isForbidden());
        authDelete(admin.accessToken(), "/api/v1/workspaces/" + team).andExpect(status().isNoContent());
        authGet(admin.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNotFound());
        authGet(admin.accessToken(), "/api/v1/tasks/" + task).andExpect(status().isNotFound());
        authGet(member.accessToken(), "/api/v1/workspaces/" + team).andExpect(status().isNotFound());
    }

    @Test
    void moveToWorkspace_movesTasks_makesCollaboratorsGuests_andOnlyGoesIn() throws Exception {
        RegisteredUser owner = newUser();
        RegisteredUser collaborator = newUser();
        UUID team = createTeam(owner, "Công ty");
        UUID project = createProject(owner, "Cá nhân");
        UUID task = createTask(owner, project, "Việc");
        addToProject(owner, project, collaborator);

        authPost(owner.accessToken(), "/api/v1/projects/" + project + "/move-to-workspace",
                "{\"workspaceId\": \"" + team + "\", \"access\": \"RESTRICTED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(team.toString()))
                .andExpect(jsonPath("$.access").value("RESTRICTED"))
                .andExpect(jsonPath("$.joined").value(true));
        assertThat(testData.taskWorkspace(task)).isEqualTo(team);
        authGet(collaborator.accessToken(), "/api/v1/workspaces/" + team).andExpect(jsonPath("$.role").value("GUEST"));
        authGet(collaborator.accessToken(), "/api/v1/projects/" + project).andExpect(status().isOk());
        authGet(owner.accessToken(), "/api/v1/tasks?projectId=" + project)
                .andExpect(jsonPath("$.items[*].content").value(hasItem("Việc")));
        // không có chiều ra
        UUID personal = testData.workspaceOf(owner.id());
        authPost(owner.accessToken(), "/api/v1/projects/" + project + "/move-to-workspace",
                "{\"workspaceId\": \"" + personal + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void moveToWorkspace_rejectsChildrenInboxStrangersAndGuestTargets() throws Exception {
        RegisteredUser owner = newUser();
        RegisteredUser stranger = newUser();
        RegisteredUser admin = newUser();
        UUID team = createTeam(owner, "Công ty");
        UUID foreignTeam = createTeam(admin, "Team người khác");
        UUID parent = createProject(owner, "Cha");
        authPost(owner.accessToken(), "/api/v1/projects", "{\"name\": \"Con\", \"parentId\": \"" + parent + "\"}")
                .andExpect(status().isCreated());
        UUID plain = createProject(owner, "Đơn");
        String body = "{\"workspaceId\": \"" + team + "\"}";

        authPost(owner.accessToken(), "/api/v1/projects/" + parent + "/move-to-workspace", body)
                .andExpect(status().isBadRequest());
        authPost(owner.accessToken(), "/api/v1/projects/" + testData.inboxOf(owner.id()) + "/move-to-workspace", body)
                .andExpect(status().isForbidden());
        authPost(stranger.accessToken(), "/api/v1/projects/" + plain + "/move-to-workspace", body)
                .andExpect(status().isNotFound());
        authPost(owner.accessToken(), "/api/v1/projects/" + plain + "/move-to-workspace",
                "{\"workspaceId\": \"" + foreignTeam + "\"}").andExpect(status().isNotFound());
        authPost(owner.accessToken(), "/api/v1/projects/" + plain + "/move-to-workspace",
                "{\"workspaceId\": \"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        // chỉ Guest của team đích → 403
        addToProject(admin, createTeamProject(admin, foreignTeam, "P", null), owner);
        authPost(owner.accessToken(), "/api/v1/projects/" + plain + "/move-to-workspace",
                "{\"workspaceId\": \"" + foreignTeam + "\"}").andExpect(status().isForbidden());
    }

    @Test
    void deletingTheOwnerAccount_keepsASharedTeamAlive() throws Exception {
        RegisteredUser owner = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(owner, "Công ty");
        addToTeam(owner, team, member, "MEMBER");
        UUID project = createTeamProject(owner, team, "Chung", "TEAM");
        UUID soloTeam = createTeam(owner, "Một mình");

        mockMvc.perform(delete("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        authGet(member.accessToken(), "/api/v1/workspaces/" + team)
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        authGet(member.accessToken(), "/api/v1/projects/" + project).andExpect(status().isOk());
        Integer solo = jdbcTemplate.queryForObject("select count(*) from workspaces where id = ?", Integer.class,
                soloTeam);
        assertThat(solo).isZero();
    }
}
