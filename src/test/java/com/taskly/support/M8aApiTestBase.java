package com.taskly.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.integration.OutgoingMail;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Nền cho test API M8a: dựng dự án / team / lời mời qua API và lấy token lời mời từ hộp thư giả. */
public abstract class M8aApiTestBase extends IntegrationTestBase {

    private static final Pattern LINK_TOKEN = Pattern.compile("taskly://join/([A-Za-z0-9_-]+)");

    protected RegisteredUser newUser() throws Exception {
        return registerUser(uniqueEmail());
    }

    /** POST projects (dự án cá nhân) → id. */
    protected UUID createProject(RegisteredUser user, String name) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/projects", "{\"name\": \"" + name + "\"}")
                .andExpect(status().isCreated()), "$.id");
    }

    protected UUID createTask(RegisteredUser user, UUID projectId, String content) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks",
                "{\"content\": \"" + content + "\", \"projectId\": \"" + projectId + "\"}")
                .andExpect(status().isCreated()), "$.id");
    }

    /** Task quá hạn (hiện ở Today). */
    protected UUID createOverdueTask(RegisteredUser user, UUID projectId, String content) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", "{\"content\": \"" + content
                + "\", \"projectId\": \"" + projectId + "\", \"dueDate\": \"2020-01-01\"}")
                .andExpect(status().isCreated()), "$.id");
    }

    /** POST workspaces → id (người tạo là ADMIN). */
    protected UUID createTeam(RegisteredUser admin, String name) throws Exception {
        return readUuid(authPost(admin.accessToken(), "/api/v1/workspaces", "{\"name\": \"" + name + "\"}")
                .andExpect(status().isCreated()), "$.id");
    }

    /** POST projects trong workspace nhóm; access null = mặc định (TEAM). */
    protected UUID createTeamProject(RegisteredUser user, UUID workspaceId, String name, String access)
            throws Exception {
        String accessJson = access == null ? "" : ", \"access\": \"" + access + "\"";
        return readUuid(authPost(user.accessToken(), "/api/v1/projects", "{\"name\": \"" + name
                + "\", \"workspaceId\": \"" + workspaceId + "\"" + accessJson + "}")
                .andExpect(status().isCreated()), "$.id");
    }

    /** Mời vào workspace nhóm rồi người được mời chấp nhận qua id (không đi qua thư). */
    protected void addToTeam(RegisteredUser admin, UUID workspaceId, RegisteredUser invitee, String role)
            throws Exception {
        UUID invitationId = readUuid(authPost(admin.accessToken(), "/api/v1/workspaces/" + workspaceId
                + "/invitations", "{\"email\": \"" + invitee.email() + "\", \"role\": \"" + role + "\"}")
                .andExpect(status().isCreated()), "$.id");
        authPost(invitee.accessToken(), "/api/v1/invitations/" + invitationId + "/accept")
                .andExpect(status().isOk());
    }

    /** Mời vào dự án rồi người được mời chấp nhận qua id. */
    protected void addToProject(RegisteredUser inviter, UUID projectId, RegisteredUser invitee) throws Exception {
        UUID invitationId = invite(inviter, projectId, invitee.email());
        authPost(invitee.accessToken(), "/api/v1/invitations/" + invitationId + "/accept").andExpect(status().isOk());
    }

    /** POST projects/{id}/invitations → id lời mời (đòi 201). */
    protected UUID invite(RegisteredUser inviter, UUID projectId, String email) throws Exception {
        return readUuid(authPost(inviter.accessToken(), "/api/v1/projects/" + projectId + "/invitations",
                "{\"email\": \"" + email + "\"}").andExpect(status().isCreated()), "$.id");
    }

    /** Token trong thư gần nhất gửi tới email (link taskly://join/&lt;token&gt;). */
    protected String lastMailToken(String email) {
        List<OutgoingMail> mails = mailbox.sentTo(email);
        if (mails.isEmpty()) {
            throw new AssertionError("Không có thư nào gửi tới " + email);
        }
        Matcher matcher = LINK_TOKEN.matcher(mails.get(mails.size() - 1).body());
        if (!matcher.find()) {
            throw new AssertionError("Thư không có link mời");
        }
        return matcher.group(1);
    }

    protected ResultActions accept(RegisteredUser user, String token) throws Exception {
        return authPost(user.accessToken(), "/api/v1/invitations/accept", "{\"token\": \"" + token + "\"}");
    }

    protected ResultActions put(RegisteredUser user, String uri) throws Exception {
        return authPut(user.accessToken(), uri);
    }

    protected ResultActions patchJson(RegisteredUser user, String uri, String json) throws Exception {
        return authPatch(user.accessToken(), uri, json);
    }

    /** Request với method + body tuỳ ý (bảng ma trận). */
    protected ResultActions call(RegisteredUser user, String method, String uri, String json) throws Exception {
        var builder = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), uri)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()));
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(builder);
    }
}
