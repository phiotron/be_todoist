package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** M8b F1 / F3 / F4: comment task + dự án, xoá của mình, reaction, @mention, số comment, tìm comment. */
class CommentApiTest extends M8bApiTestBase {

    @Test
    void createAndListTaskComments_oldestFirst_withAuthorAndCount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = testData.task(user.id(), testData.inboxOf(user.id()), "Viết báo cáo");

        postComment(user, "/api/v1/tasks/" + taskId + "/comments", """
                {"content": "  Bản nháp đầu  "}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andExpect(jsonPath("$.content").value("Bản nháp đầu"))
                .andExpect(jsonPath("$.author.id").value(user.id().toString()))
                .andExpect(jsonPath("$.author.name").value("Người dùng thử"))
                .andExpect(jsonPath("$.author.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.attachment").doesNotExist())
                .andExpect(jsonPath("$.reactions", hasSize(0)))
                .andExpect(jsonPath("$.isMine").value(true));
        clock.advance(java.time.Duration.ofMinutes(1));
        createTaskComment(user, taskId, "Bản thứ hai");

        authGet(user.accessToken(), "/api/v1/tasks/" + taskId + "/comments")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].content", contains("Bản nháp đầu", "Bản thứ hai")));
        authGet(user.accessToken(), "/api/v1/tasks/" + taskId)
                .andExpect(jsonPath("$.commentCount").value(2));
    }

    @Test
    void projectComments_separateFromTaskComments() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID projectId = testData.project(user.id(), "Ra mắt");
        UUID taskId = testData.task(user.id(), projectId, "Việc");
        createTaskComment(user, taskId, "Của task");

        postComment(user, "/api/v1/projects/" + projectId + "/comments", "{\"content\": \"Của dự án\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.taskId").doesNotExist());

        authGet(user.accessToken(), "/api/v1/projects/" + projectId + "/comments")
                .andExpect(jsonPath("$[*].content", contains("Của dự án")));
    }

    @Test
    void emptyOrTooLong_400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = testData.task(user.id(), testData.inboxOf(user.id()), "Việc");
        String path = "/api/v1/tasks/" + taskId + "/comments";

        postComment(user, path, "{\"content\": \"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Comment cần có nội dung hoặc một file"));
        postComment(user, path, "{\"content\": \"%s\"}".formatted("a".repeat(15_001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        postComment(user, path, "{\"content\": \"%s\"}".formatted("a".repeat(15_000)))
                .andExpect(status().isCreated());
    }

    @Test
    void outsider_404_andArchivedProject_409() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser stranger = registerUser(uniqueEmail());
        UUID projectId = testData.project(owner.id(), "Riêng");
        UUID taskId = testData.task(owner.id(), projectId, "Việc");
        UUID commentId = createTaskComment(owner, taskId, "Bí mật");

        authGet(stranger.accessToken(), "/api/v1/tasks/" + taskId + "/comments").andExpect(status().isNotFound());
        postComment(stranger, "/api/v1/tasks/" + taskId + "/comments", "{\"content\": \"x\"}")
                .andExpect(status().isNotFound());
        authDelete(stranger.accessToken(), "/api/v1/comments/" + commentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy comment"));

        authPost(owner.accessToken(), "/api/v1/projects/" + projectId + "/archive").andExpect(status().isOk());
        postComment(owner, "/api/v1/tasks/" + taskId + "/comments", "{\"content\": \"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        authGet(owner.accessToken(), "/api/v1/tasks/" + taskId + "/comments")
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void completedTask_canStillBeCommented() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = testData.task(user.id(), testData.inboxOf(user.id()), "Xong rồi");
        authPost(user.accessToken(), "/api/v1/tasks/" + taskId + "/close").andExpect(status().isOk());
        createTaskComment(user, taskId, "Ghi chú sau khi xong");
    }

    @Test
    void onlyAuthorDeletes_othersGet403() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser member = registerUser(uniqueEmail());
        addMember(owner, member);
        UUID taskId = testData.task(owner.id(), testData.inboxOf(owner.id()), "Việc chung");
        UUID ownerComment = createTaskComment(owner, taskId, "Của chủ");

        authGet(member.accessToken(), "/api/v1/tasks/" + taskId + "/comments")
                .andExpect(jsonPath("$[0].isMine").value(false));
        authDelete(member.accessToken(), "/api/v1/comments/" + ownerComment)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Bạn chỉ xoá được comment của mình"));
        authDelete(owner.accessToken(), "/api/v1/comments/" + ownerComment).andExpect(status().isNoContent());
        authDelete(owner.accessToken(), "/api/v1/comments/" + ownerComment).andExpect(status().isNotFound());
        authGet(owner.accessToken(), "/api/v1/tasks/" + taskId).andExpect(jsonPath("$.commentCount").value(0));
    }

    @Test
    void clientId_replayReturnsSame_conflictWhenDifferent() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = testData.task(user.id(), testData.inboxOf(user.id()), "Việc");
        UUID id = UUID.randomUUID();
        String path = "/api/v1/tasks/" + taskId + "/comments";
        String body = "{\"id\": \"%s\", \"content\": \"Một lần\"}".formatted(id);

        postComment(user, path, body).andExpect(status().isCreated());
        postComment(user, path, body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id.toString()));
        postComment(user, path, "{\"id\": \"%s\", \"content\": \"Khác\"}".formatted(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from comments where task_id = ?", Integer.class,
                taskId)).isEqualTo(1);
    }

    @Test
    void idempotencyKeyHeader_coversCommentPost() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = testData.task(user.id(), testData.inboxOf(user.id()), "Việc");
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/tasks/" + taskId + "/comments")
                            .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"Một\"}"))
                    .andExpect(status().is2xxSuccessful());
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from comments where task_id = ?", Integer.class,
                taskId)).isEqualTo(1);
    }

    @Test
    void deletingTaskOrProject_removesComments() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID projectId = testData.project(user.id(), "Xoá");
        UUID parent = testData.task(user.id(), projectId, "Cha");
        UUID child = testData.insert(com.taskly.support.TestData.TaskRow.of(user.id(), projectId, "Con").parent(parent));
        createTaskComment(user, child, "Comment của con");
        UUID other = testData.task(user.id(), projectId, "Khác");
        createTaskComment(user, other, "Comment khác");
        postComment(user, "/api/v1/projects/" + projectId + "/comments", "{\"content\": \"dự án\"}")
                .andExpect(status().isCreated());

        authDelete(user.accessToken(), "/api/v1/tasks/" + parent).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("select count(*) from comments where task_id = ?", Integer.class,
                child)).isZero();
        authDelete(user.accessToken(), "/api/v1/projects/" + projectId).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from comments where task_id = ? or project_id = ?", Integer.class, other, projectId))
                .isZero();
    }

    @Test
    void reactions_addIdempotent_removeOwnOnly() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser member = registerUser(uniqueEmail());
        addMember(owner, member);
        UUID taskId = testData.task(owner.id(), testData.inboxOf(owner.id()), "Việc");
        UUID commentId = createTaskComment(owner, taskId, "Hay");
        String path = "/api/v1/comments/" + commentId + "/reactions";

        authPost(owner.accessToken(), path, "{\"emoji\": \"👍\"}").andExpect(status().isOk());
        authPost(owner.accessToken(), path, "{\"emoji\": \"👍\"}").andExpect(status().isOk());
        authPost(member.accessToken(), path, "{\"emoji\": \"👍\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions[0].emoji").value("👍"))
                .andExpect(jsonPath("$.reactions[0].count").value(2))
                .andExpect(jsonPath("$.reactions[0].reactedByMe").value(true));
        authPost(member.accessToken(), path, "{\"emoji\": \"🎉\"}").andExpect(status().isOk());

        authDelete(member.accessToken(), path + "?emoji=👍")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions[*].emoji", contains("👍", "🎉")))
                .andExpect(jsonPath("$.reactions[0].count").value(1))
                .andExpect(jsonPath("$.reactions[0].reactedByMe").value(false));

        authPost(owner.accessToken(), path, "{\"emoji\": \"a b\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Biểu tượng cảm xúc không hợp lệ"));
        authPost(owner.accessToken(), path, "{\"emoji\": \"\"}").andExpect(status().isBadRequest());
    }

    @Test
    void mention_notifiesReaders_rejectsOutsiders_ignoresSelf() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser member = registerUser(uniqueEmail());
        RegisteredUser stranger = registerUser(uniqueEmail());
        addMember(owner, member);
        UUID taskId = testData.task(owner.id(), testData.inboxOf(owner.id()), "Việc");
        String path = "/api/v1/tasks/" + taskId + "/comments";

        postComment(owner, path, "{\"content\": \"@Ai đó xem\", \"mentionUserIds\": [\"%s\"]}"
                .formatted(stranger.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Có người được nhắc tới không xem được công việc / dự án này"));

        postComment(owner, path, "{\"content\": \"@Bạn xem\", \"mentionUserIds\": [\"%s\", \"%s\", \"%s\"]}"
                .formatted(member.id(), member.id(), owner.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mentionUserIds", containsInAnyOrder(member.id().toString(),
                        owner.id().toString())));

        assertThat(jdbcTemplate.queryForObject("select count(*) from notifications where user_id = ?", Integer.class,
                member.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from notifications where user_id = ?", Integer.class,
                owner.id())).isZero();
    }

    @Test
    void mentionable_listsWorkspaceReaders() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser member = registerUser(uniqueEmail());
        registerUser(uniqueEmail());
        addMember(owner, member);
        UUID projectId = testData.inboxOf(owner.id());
        UUID taskId = testData.task(owner.id(), projectId, "Việc");

        authGet(owner.accessToken(), "/api/v1/tasks/" + taskId + "/comments/mentionable")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(owner.id().toString(), member.id().toString())));
        authGet(member.accessToken(), "/api/v1/projects/" + projectId + "/comments/mentionable")
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void searchObjects_findsComments_notInArchivedProjects() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String word = "zebra" + UUID.randomUUID().toString().substring(0, 6);
        UUID projectId = testData.project(user.id(), "Tìm");
        UUID taskId = testData.task(user.id(), projectId, "Việc có comment");
        createTaskComment(user, taskId, "Ghi chú " + word + " quan trọng");
        UUID archived = testData.project(user.id(), "Lưu trữ");
        createTaskComment(user, testData.task(user.id(), archived, "Cũ"), "Cũ " + word);
        authPost(user.accessToken(), "/api/v1/projects/" + archived + "/archive").andExpect(status().isOk());

        authGet(user.accessToken(), "/api/v1/search/objects?q=" + word.toUpperCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments", hasSize(1)))
                .andExpect(jsonPath("$.comments[0].taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.comments[0].projectName").value("Tìm"))
                .andExpect(jsonPath("$.comments[0].taskContent").value("Việc có comment"));
    }
}
