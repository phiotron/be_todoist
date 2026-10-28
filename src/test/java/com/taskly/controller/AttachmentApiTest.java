package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.integration.FileStorage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

/** M8b F2 + F7: file đính kèm (1 / comment, ≤ cỡ cấu hình), link ký, dọn file khi xoá. */
class AttachmentApiTest extends M8bApiTestBase {

    @Autowired
    private FileStorage fileStorage;

    private static final byte[] TEXT = "xin chào".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void uploadAttachAndDownloadWithSignedLink() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID taskId = testData.task(user.id(), testData.inboxOf(user.id()), "Việc");

        UUID attachmentId = readUuid(upload(user, "/api/v1/attachments", HttpMethod.POST,
                new MockMultipartFile("file", "..\\bao cao.txt", "text/plain; charset=utf-8", TEXT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("bao cao.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(TEXT.length))
                .andExpect(jsonPath("$.isImage").value(false)), "$.id");

        postComment(user, "/api/v1/tasks/" + taskId + "/comments",
                "{\"content\": \"\", \"attachmentId\": \"%s\"}".formatted(attachmentId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachment.id").value(attachmentId.toString()))
                .andExpect(jsonPath("$.content").value(""));
        authGet(user.accessToken(), "/api/v1/tasks/" + taskId + "/comments")
                .andExpect(jsonPath("$[0].attachment.fileName").value("bao cao.txt"));

        String url = read(authGet(user.accessToken(), "/api/v1/attachments/" + attachmentId + "/link")
                .andExpect(status().isOk()), "$.url");
        // link ký: KHÔNG cần Bearer
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().bytes(TEXT))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void imageIsInline() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = uploadAttachment(user, "a.png", "image/png", new byte[] {1, 2, 3});
        String url = read(authGet(user.accessToken(), "/api/v1/attachments/" + id + "/link"), "$.url");
        mockMvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", containsString("inline")));
    }

    @Test
    void badSignatureOrExpired_403() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = uploadAttachment(user, "a.txt", "text/plain", TEXT);
        String url = read(authGet(user.accessToken(), "/api/v1/attachments/" + id + "/link"), "$.url");

        mockMvc.perform(get(url.replaceAll("sig=.", "sig=x"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Link tải file không hợp lệ hoặc đã hết hạn"));
        mockMvc.perform(get("/api/v1/attachments/" + id + "/download?expires=1&sig=abc"))
                .andExpect(status().isForbidden());
        clock.advance(Duration.ofMinutes(6));
        mockMvc.perform(get(url)).andExpect(status().isForbidden());
    }

    @Test
    void tooLarge_empty_missing_400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        upload(user, "/api/v1/attachments", HttpMethod.POST,
                new MockMultipartFile("file", "big.bin", "application/octet-stream", new byte[2049]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("File vượt quá 100 MB"))
                .andExpect(jsonPath("$.max").value(2048));
        upload(user, "/api/v1/attachments", HttpMethod.POST,
                new MockMultipartFile("file", "e.txt", "text/plain", new byte[0]))
                .andExpect(status().isBadRequest());
        upload(user, "/api/v1/attachments", HttpMethod.POST, null).andExpect(status().isBadRequest());
        upload(user, "/api/v1/attachments", HttpMethod.POST,
                new MockMultipartFile("file", "ok.bin", "application/octet-stream", new byte[2048]))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyUploaderAttaches_oneFilePerComment() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser member = registerUser(uniqueEmail());
        addMember(owner, member);
        UUID taskId = testData.task(owner.id(), testData.inboxOf(owner.id()), "Việc");
        UUID id = uploadAttachment(owner, "a.txt", "text/plain", TEXT);
        String path = "/api/v1/tasks/" + taskId + "/comments";
        String body = "{\"content\": \"x\", \"attachmentId\": \"%s\"}".formatted(id);

        postComment(member, path, body).andExpect(status().isNotFound());
        postComment(owner, path, body).andExpect(status().isCreated());
        postComment(owner, path, body).andExpect(status().isConflict());
        postComment(owner, path, "{\"content\": \"x\", \"attachmentId\": \"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());

        // người xem được task thì lấy được link; người ngoài không
        RegisteredUser stranger = registerUser(uniqueEmail());
        authGet(member.accessToken(), "/api/v1/attachments/" + id + "/link").andExpect(status().isOk());
        authGet(stranger.accessToken(), "/api/v1/attachments/" + id + "/link").andExpect(status().isNotFound());
    }

    @Test
    void unattachedFile_linkOnlyForUploader() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        UUID id = uploadAttachment(owner, "a.txt", "text/plain", TEXT);
        authGet(owner.accessToken(), "/api/v1/attachments/" + id + "/link").andExpect(status().isOk());
        authGet(other.accessToken(), "/api/v1/attachments/" + id + "/link").andExpect(status().isNotFound());
    }

    @Test
    void deletingCommentOrTask_queuesFileThenCleanupRemovesIt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID projectId = testData.project(user.id(), "Dọn file");
        UUID t1 = testData.task(user.id(), projectId, "Một");
        UUID t2 = testData.task(user.id(), projectId, "Hai");
        UUID a1 = uploadAttachment(user, "1.txt", "text/plain", TEXT);
        UUID a2 = uploadAttachment(user, "2.txt", "text/plain", TEXT);
        String k1 = storageKeyOfAttachment(a1);
        String k2 = storageKeyOfAttachment(a2);
        UUID c1 = readUuid(postComment(user, "/api/v1/tasks/" + t1 + "/comments",
                "{\"content\": \"a\", \"attachmentId\": \"%s\"}".formatted(a1)), "$.id");
        postComment(user, "/api/v1/tasks/" + t2 + "/comments",
                "{\"content\": \"b\", \"attachmentId\": \"%s\"}".formatted(a2)).andExpect(status().isCreated());
        assertThat(fileStorage.exists(k1)).isTrue();

        authDelete(user.accessToken(), "/api/v1/comments/" + c1).andExpect(status().isNoContent());
        authDelete(user.accessToken(), "/api/v1/projects/" + projectId).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForList("select storage_key from stored_file_deletions where storage_key in (?, ?)",
                String.class, k1, k2)).containsExactlyInAnyOrder(k1, k2);

        cleanupService.run();

        assertThat(fileStorage.exists(k1)).isFalse();
        assertThat(fileStorage.exists(k2)).isFalse();
        assertThat(jdbcTemplate.queryForList("select storage_key from stored_file_deletions where storage_key in (?, ?)",
                String.class, k1, k2)).isEmpty();
    }

    @Test
    void unattachedUploadOlderThanTtl_isCleaned() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = uploadAttachment(user, "a.txt", "text/plain", TEXT);
        String key = storageKeyOfAttachment(id);
        cleanupService.run();
        assertThat(fileStorage.exists(key)).isTrue();

        clock.advance(Duration.ofHours(25));
        cleanupService.run();

        assertThat(fileStorage.exists(key)).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from comment_attachments where id = ?", Integer.class,
                id)).isZero();
    }

    @Test
    void accountDeletion_dropsUploadedFiles() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = uploadAttachment(user, "a.txt", "text/plain", TEXT);
        String key = storageKeyOfAttachment(id);
        authDeleteAccount(user);
        cleanupService.run();
        assertThat(fileStorage.exists(key)).isFalse();
    }

    private void authDeleteAccount(RegisteredUser user) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"%s\"}".formatted(FIXTURE_PASSWORD)))
                .andExpect(status().isNoContent());
    }
}
