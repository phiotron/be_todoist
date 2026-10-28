package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.M8aApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Nối cuối M8a: sắp xếp lại folder (POST reorder type FOLDER, chỉ Admin) và avatarUrl trong danh sách thành viên
 * (workspace + dự án) để app hiện ảnh.
 */
class FolderReorderAndMemberAvatarApiTest extends M8aApiTestBase {

    private UUID folder(RegisteredUser user, UUID team, String name) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/workspaces/" + team + "/folders",
                "{\"name\": \"" + name + "\"}").andExpect(status().isCreated()), "$.id");
    }

    private List<String> folderNames(RegisteredUser user, UUID team) throws Exception {
        String body = authGet(user.accessToken(), "/api/v1/workspaces/" + team + "/folders")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].name");
    }

    private static String reorderBody(UUID id, UUID afterId) {
        return "{\"type\": \"FOLDER\", \"id\": \"" + id + "\", \"afterId\": "
                + (afterId == null ? "null" : "\"" + afterId + "\"") + "}";
    }

    private static String syncReorder(UUID id, UUID afterId) {
        return "{\"syncToken\": \"*\", \"commands\": [{\"uuid\": \"" + UUID.randomUUID()
                + "\", \"type\": \"reorder\", \"args\": " + reorderBody(id, afterId) + "}]}";
    }

    @Test
    void reorderFolder_roleMatrix_adminOkMemberForbiddenOutsiderNotFound() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        RegisteredUser outsider = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, member, "MEMBER");
        UUID a = folder(admin, team, "A");
        UUID b = folder(admin, team, "B");
        UUID c = folder(admin, team, "C");

        authPost(admin.accessToken(), "/api/v1/reorder", reorderBody(c, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("FOLDER"))
                .andExpect(jsonPath("$.id").value(c.toString()));
        assertThat(folderNames(admin, team)).containsExactly("C", "A", "B");

        authPost(admin.accessToken(), "/api/v1/reorder", reorderBody(a, b)).andExpect(status().isOk());
        assertThat(folderNames(admin, team)).containsExactly("C", "B", "A");

        authPost(member.accessToken(), "/api/v1/reorder", reorderBody(a, null)).andExpect(status().isForbidden());
        authPost(outsider.accessToken(), "/api/v1/reorder", reorderBody(a, null)).andExpect(status().isNotFound());
        assertThat(folderNames(admin, team)).containsExactly("C", "B", "A"); // lệnh bị từ chối không đổi gì
    }

    @Test
    void reorderFolder_validatesTheContainer_andSurvivesRenumbering() throws Exception {
        RegisteredUser admin = newUser();
        UUID team = createTeam(admin, "Công ty");
        UUID other = createTeam(admin, "Team khác");
        UUID a = folder(admin, team, "A");
        UUID b = folder(admin, team, "B");
        UUID foreign = folder(admin, other, "Ngoài");

        authPost(admin.accessToken(), "/api/v1/reorder", reorderBody(a, foreign)).andExpect(status().isBadRequest());
        authPost(admin.accessToken(), "/api/v1/reorder", reorderBody(UUID.randomUUID(), null)).andExpect(status().isNotFound());

        // chèn liên tiếp vào cùng khe đến lúc hết chỗ trống → đánh số lại, thứ tự vẫn đúng
        for (int i = 0; i < 14; i++) {
            authPost(admin.accessToken(), "/api/v1/reorder", reorderBody(b, a)).andExpect(status().isOk());
            authPost(admin.accessToken(), "/api/v1/reorder", reorderBody(a, b)).andExpect(status().isOk());
            authPost(admin.accessToken(), "/api/v1/reorder", reorderBody(b, null)).andExpect(status().isOk());
            authPost(admin.accessToken(), "/api/v1/reorder", reorderBody(a, null)).andExpect(status().isOk());
        }
        assertThat(folderNames(admin, team)).containsExactly("A", "B");
        assertThat(folderNames(admin, other)).containsExactly("Ngoài");
    }

    @Test
    void reorderFolder_viaSyncCommand_alsoEnforcesAdmin() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, member, "MEMBER");
        folder(admin, team, "A");
        UUID b = folder(admin, team, "B");

        authPost(member.accessToken(), "/api/v1/sync", syncReorder(b, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("error"))
                .andExpect(jsonPath("$.results[0].error.status").value(403));
        assertThat(folderNames(admin, team)).containsExactly("A", "B");

        authPost(admin.accessToken(), "/api/v1/sync", syncReorder(b, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ok"));
        assertThat(folderNames(admin, team)).containsExactly("B", "A");
    }

    @Test
    void memberLists_includeAvatarUrl_nullWhenNoPhoto_andVersionedPathWhenSet() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Công ty");
        addToTeam(admin, team, member, "MEMBER");
        UUID project = createTeamProject(admin, team, "Dự án", "TEAM");
        authPost(member.accessToken(), "/api/v1/projects/" + project + "/join").andExpect(status().isOk());
        jdbcTemplate.update("update users set avatar_key = 'test-avatar', avatar_content_type = 'image/png', avatar_updated_at = now() where id = ?", member.id());

        String people = authGet(admin.accessToken(), "/api/v1/workspaces/" + team + "/members")
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        assertThat(avatarOf(people, admin.id())).isNull();
        String memberAvatar = avatarOf(people, member.id());
        assertThat(memberAvatar).startsWith("/api/v1/users/" + member.id() + "/avatar?v=");

        String inProject = authGet(admin.accessToken(), "/api/v1/projects/" + project + "/members")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(avatarOf(inProject, member.id())).isEqualTo(memberAvatar);
        assertThat(avatarOf(inProject, admin.id())).isNull();
    }

    private static String avatarOf(String json, UUID userId) {
        List<String> found = JsonPath.read(json, "$[?(@.userId == '" + userId + "')].avatarUrl");
        assertThat(found).hasSize(1);
        return found.get(0);
    }
}
