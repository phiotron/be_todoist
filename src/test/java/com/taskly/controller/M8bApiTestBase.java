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

    /**
     * Sau tích hợp M8a (quyền theo DỰ ÁN): `member` thành cộng tác viên (project_members) của MỌI dự án đang có trong
     * workspace cá nhân của `owner` (kể cả Inbox). Dự án tạo SAU đó phải gọi shareProject.
     */
    protected void addMember(RegisteredUser owner, RegisteredUser member) {
        for (UUID projectId : jdbcTemplate.queryForList("select id from projects where workspace_id = ?",
                UUID.class, testData.workspaceOf(owner.id()))) {
            shareProject(projectId, member);
        }
    }

    /** Thêm `member` làm cộng tác viên của một dự án cá nhân (bỏ qua nếu đã có). */
    protected void shareProject(UUID projectId, RegisteredUser member) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("insert into project_members (id, project_id, user_id, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?) on conflict do nothing", UUID.randomUUID(), projectId, member.id(), now, now);
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
