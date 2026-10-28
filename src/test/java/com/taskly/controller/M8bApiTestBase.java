package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.service.StoredFileCleanupService;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Nền chung test M8b (comment, file, thông báo, ảnh đại diện). KHÔNG thêm annotation (dùng chung context).
 * addMember giả lập M8a: thêm người vào workspace của chủ để có "người thứ hai xem được task".
 */
abstract class M8bApiTestBase extends IntegrationTestBase {

    @Autowired
    protected StoredFileCleanupService cleanupService;

    /** Thêm `member` vào workspace cá nhân của `owner` (Mức 1: thành viên workspace xem / ghi được mọi dự án). */
    protected void addMember(RegisteredUser owner, RegisteredUser member) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("insert into workspace_members (id, workspace_id, user_id, role, created_at, updated_at) "
                + "values (?, ?, ?, 'MEMBER', ?, ?)", UUID.randomUUID(), testData.workspaceOf(owner.id()),
                member.id(), now, now);
    }

    protected ResultActions postComment(RegisteredUser user, String path, String json) throws Exception {
        return authPost(user.accessToken(), path, json);
    }

    protected UUID createTaskComment(RegisteredUser user, UUID taskId, String content) throws Exception {
        return readUuid(postComment(user, "/api/v1/tasks/" + taskId + "/comments",
                "{\"content\": \"%s\"}".formatted(content)).andExpect(status().isCreated()), "$.id");
    }

    protected ResultActions upload(RegisteredUser user, String path, HttpMethod method, MockMultipartFile file)
            throws Exception {
        var request = multipart(method, path);
        if (file != null) {
            request.file(file);
        }
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())));
    }

    protected UUID uploadAttachment(RegisteredUser user, String name, String contentType, byte[] bytes)
            throws Exception {
        return readUuid(upload(user, "/api/v1/attachments", HttpMethod.POST,
                new MockMultipartFile("file", name, contentType, bytes)).andExpect(status().isCreated()), "$.id");
    }

    protected String storageKeyOfAttachment(UUID attachmentId) {
        return jdbcTemplate.queryForObject("select storage_key from comment_attachments where id = ?", String.class,
                attachmentId);
    }

    protected static String json(ResultActions actions) throws Exception {
        return body(actions.andReturn());
    }

    protected static <T> T read(ResultActions actions, String path) throws Exception {
        return JsonPath.read(json(actions), path);
    }
}
