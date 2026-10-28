package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M8b F5: hộp thông báo All / Unread, đếm, đọc một / đọc hết, cài đặt, quyền. */
class NotificationApiTest extends M8bApiTestBase {

    private record Setup(RegisteredUser owner, RegisteredUser member, UUID taskId, UUID projectId) {
    }

    private Setup setup() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser member = registerUser(uniqueEmail());
        addMember(owner, member);
        UUID projectId = testData.project(owner.id(), "Chung");
        UUID taskId = testData.task(owner.id(), projectId, "Việc chung");
        return new Setup(owner, member, taskId, projectId);
    }

    private UUID mention(Setup s, String text) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        return readUuid(postComment(s.owner(), "/api/v1/tasks/" + s.taskId() + "/comments",
                "{\"content\": \"%s\", \"mentionUserIds\": [\"%s\"]}".formatted(text, s.member().id()))
                .andExpect(status().isCreated()), "$.id");
    }

    @Test
    void mentionAppearsInAllAndUnread_newestFirst_withShape() throws Exception {
        Setup s = setup();
        UUID first = mention(s, "một");
        UUID second = mention(s, "hai");

        authGet(s.member().accessToken(), "/api/v1/notifications")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.items[0].commentId").value(second.toString()))
                .andExpect(jsonPath("$.items[1].commentId").value(first.toString()))
                .andExpect(jsonPath("$.items[0].type").value("COMMENT_MENTION"))
                .andExpect(jsonPath("$.items[0].actor.id").value(s.owner().id().toString()))
                .andExpect(jsonPath("$.items[0].taskId").value(s.taskId().toString()))
                .andExpect(jsonPath("$.items[0].taskContent").value("Việc chung"))
                .andExpect(jsonPath("$.items[0].projectId").value(s.projectId().toString()))
                .andExpect(jsonPath("$.items[0].projectName").value("Chung"))
                .andExpect(jsonPath("$.items[0].commentExcerpt").value("hai"))
                .andExpect(jsonPath("$.items[0].isRead").value(false));
        authGet(s.member().accessToken(), "/api/v1/notifications?unread=true")
                .andExpect(jsonPath("$.items", hasSize(2)));
        authGet(s.member().accessToken(), "/api/v1/notifications/unread-count")
                .andExpect(jsonPath("$.count").value(2));
        // chủ (người viết) không có thông báo
        authGet(s.owner().accessToken(), "/api/v1/notifications").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void markReadAndReadAll() throws Exception {
        Setup s = setup();
        mention(s, "một");
        mention(s, "hai");
        String id = read(authGet(s.member().accessToken(), "/api/v1/notifications"), "$.items[0].id");

        authPost(s.member().accessToken(), "/api/v1/notifications/" + id + "/read").andExpect(status().isNoContent());
        authPost(s.member().accessToken(), "/api/v1/notifications/" + id + "/read").andExpect(status().isNoContent());
        authGet(s.member().accessToken(), "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(1));
        authGet(s.member().accessToken(), "/api/v1/notifications?unread=true")
                .andExpect(jsonPath("$.items", hasSize(1)));
        authGet(s.member().accessToken(), "/api/v1/notifications")
                .andExpect(jsonPath("$.items[0].isRead").value(true));

        authPost(s.member().accessToken(), "/api/v1/notifications/read-all").andExpect(status().isNoContent());
        authGet(s.member().accessToken(), "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(0));
        authGet(s.member().accessToken(), "/api/v1/notifications").andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void otherUsersNotification_404() throws Exception {
        Setup s = setup();
        mention(s, "một");
        String id = read(authGet(s.member().accessToken(), "/api/v1/notifications"), "$.items[0].id");

        authPost(s.owner().accessToken(), "/api/v1/notifications/" + id + "/read")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy thông báo"));
        authPost(s.member().accessToken(), "/api/v1/notifications/" + UUID.randomUUID() + "/read")
                .andExpect(status().isNotFound());
    }

    @Test
    void paging_byCursor_noDuplicates() throws Exception {
        Setup s = setup();
        for (int i = 0; i < 5; i++) {
            mention(s, "n" + i);
        }
        String token = s.member().accessToken();
        String cursor = read(authGet(token, "/api/v1/notifications?limit=2")
                .andExpect(jsonPath("$.items", hasSize(2))), "$.nextCursor");
        String second = read(authGet(token, "/api/v1/notifications?limit=2&cursor=" + cursor)
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].commentExcerpt").value("n2")), "$.nextCursor");
        authGet(token, "/api/v1/notifications?limit=2&cursor=" + second)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        authGet(token, "/api/v1/notifications?cursor=rac").andExpect(status().isBadRequest());
        authGet(token, "/api/v1/notifications?unread=true&cursor=" + cursor).andExpect(status().isBadRequest());
    }

    @Test
    void settingOff_stopsNewMentions_defaultsOn() throws Exception {
        Setup s = setup();
        String token = s.member().accessToken();
        authGet(token, "/api/v1/me/notification-settings")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(6)))
                .andExpect(jsonPath("$.items[?(@.enabled == false)]", hasSize(0)))
                .andExpect(jsonPath("$.items[0].scope").value("PROJECT"))
                .andExpect(jsonPath("$.items[0].event").value("COMMENT_MENTION"));

        mockMvcPut(token, "{\"items\": [{\"scope\": \"PROJECT\", \"event\": \"COMMENT_MENTION\", \"enabled\": false}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].enabled").value(false))
                .andExpect(jsonPath("$.items[1].enabled").value(true));
        mention(s, "bị tắt");
        authGet(token, "/api/v1/notifications").andExpect(jsonPath("$.items", hasSize(0)));

        mockMvcPut(token, "{\"items\": [{\"scope\": \"PROJECT\", \"event\": \"COMMENT_MENTION\", \"enabled\": true}]}")
                .andExpect(status().isOk());
        mention(s, "đã bật lại");
        authGet(token, "/api/v1/notifications").andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void settingValidation_400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String token = user.accessToken();
        mockMvcPut(token, "{\"items\": [{\"scope\": \"WORKSPACE\", \"event\": \"COMMENT_MENTION\", \"enabled\": true}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cài đặt thông báo không hợp lệ"));
        mockMvcPut(token, "{\"items\": [{\"scope\": \"PROJECT\", \"event\": \"LA\", \"enabled\": true}]}")
                .andExpect(status().isBadRequest());
        mockMvcPut(token, "{\"items\": [{\"scope\": \"PROJECT\", \"event\": \"COMMENT_MENTION\"}]}")
                .andExpect(status().isBadRequest());
        mockMvcPut(token, "{}").andExpect(status().isBadRequest());
        // lỗi một phần tử → không ghi gì
        mockMvcPut(token, "{\"items\": [{\"scope\": \"PROJECT\", \"event\": \"COMMENT_ADDED\", \"enabled\": false},"
                + "{\"scope\": \"WORKSPACE\", \"event\": \"COMMENT_ADDED\", \"enabled\": false}]}")
                .andExpect(status().isBadRequest());
        authGet(token, "/api/v1/me/notification-settings").andExpect(jsonPath("$.items[?(@.enabled == false)]", hasSize(0)));
    }

    @Test
    void lostAccess_hidesNotification() throws Exception {
        Setup s = setup();
        mention(s, "một");
        authGet(s.member().accessToken(), "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(1));

        jdbcTemplate.update("delete from workspace_members where user_id = ?", s.member().id());

        authGet(s.member().accessToken(), "/api/v1/notifications").andExpect(jsonPath("$.items", hasSize(0)));
        authGet(s.member().accessToken(), "/api/v1/notifications/unread-count").andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void deletingComment_removesNotification() throws Exception {
        Setup s = setup();
        UUID comment = mention(s, "sẽ xoá");
        authDelete(s.owner().accessToken(), "/api/v1/comments/" + comment).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("select count(*) from notifications where user_id = ?", Integer.class,
                s.member().id())).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions mockMvcPut(String token, String json) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/me/notification-settings")
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json));
    }
}
