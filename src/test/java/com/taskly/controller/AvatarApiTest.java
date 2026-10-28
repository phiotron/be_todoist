package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.integration.FileStorage;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

/** M8b F6: ảnh đại diện JPEG / PNG ≤ giới hạn cấu hình, nhận diện bằng byte đầu. */
class AvatarApiTest extends M8bApiTestBase {

    @Autowired
    private FileStorage fileStorage;

    private static byte[] png(int size) {
        byte[] bytes = new byte[size];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(head, 0, bytes, 0, head.length);
        return bytes;
    }

    private static byte[] jpeg(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    @Test
    void uploadShowsUrlInMeAndServesImage() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authGet(user.accessToken(), "/api/v1/me").andExpect(jsonPath("$.avatarUrl").doesNotExist());
        byte[] image = png(100);

        String url = read(upload(user, "/api/v1/me/avatar", HttpMethod.POST,
                new MockMultipartFile("file", "me.png", "application/octet-stream", image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").exists()), "$.avatarUrl");
        assertThat(url).startsWith("/api/v1/users/" + user.id() + "/avatar?v=");
        authGet(user.accessToken(), "/api/v1/me").andExpect(jsonPath("$.avatarUrl").value(url));

        // người khác (đã đăng nhập) xem được; không Bearer → 401
        RegisteredUser other = registerUser(uniqueEmail());
        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(other.accessToken())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "private, max-age=86400"))
                .andExpect(content().bytes(image));
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    @Test
    void jpegAccepted_otherTypesRejectedByMagicBytes() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        upload(user, "/api/v1/me/avatar", HttpMethod.POST, new MockMultipartFile("file", "a.jpg", "image/png", jpeg(50)))
                .andExpect(status().isOk());
        authGet(user.accessToken(), "/api/v1/users/" + user.id() + "/avatar")
                .andExpect(header().string("Content-Type", "image/jpeg"));

        upload(user, "/api/v1/me/avatar", HttpMethod.POST,
                new MockMultipartFile("file", "a.png", "image/png", "<html>không phải ảnh</html>".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ảnh đại diện phải là JPEG hoặc PNG"));
        upload(user, "/api/v1/me/avatar", HttpMethod.POST, null).andExpect(status().isBadRequest());
    }

    @Test
    void tooLarge_400_andKeepsOldAvatar() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        upload(user, "/api/v1/me/avatar", HttpMethod.POST, new MockMultipartFile("file", "a.png", "image/png", png(1024)))
                .andExpect(status().isOk());
        String key = jdbcTemplate.queryForObject("select avatar_key from users where id = ?", String.class, user.id());

        upload(user, "/api/v1/me/avatar", HttpMethod.POST, new MockMultipartFile("file", "b.png", "image/png", png(1025)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ảnh đại diện vượt quá 4 MB"));
        assertThat(jdbcTemplate.queryForObject("select avatar_key from users where id = ?", String.class, user.id()))
                .isEqualTo(key);
    }

    @Test
    void replaceAndRemove_queueOldFileForCleanup() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        upload(user, "/api/v1/me/avatar", HttpMethod.POST, new MockMultipartFile("file", "1.png", "image/png", png(40)))
                .andExpect(status().isOk());
        String first = jdbcTemplate.queryForObject("select avatar_key from users where id = ?", String.class, user.id());
        clock.advance(java.time.Duration.ofSeconds(5));
        upload(user, "/api/v1/me/avatar", HttpMethod.POST, new MockMultipartFile("file", "2.png", "image/png", png(41)))
                .andExpect(status().isOk());
        String second = jdbcTemplate.queryForObject("select avatar_key from users where id = ?", String.class, user.id());
        assertThat(second).isNotEqualTo(first);

        cleanupService.run();
        assertThat(fileStorage.exists(first)).isFalse();
        assertThat(fileStorage.exists(second)).isTrue();

        authDelete(user.accessToken(), "/api/v1/me/avatar")
                .andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").doesNotExist());
        authGet(user.accessToken(), "/api/v1/users/" + user.id() + "/avatar").andExpect(status().isNotFound());
        cleanupService.run();
        assertThat(fileStorage.exists(second)).isFalse();
    }

    @Test
    void avatarShownOnCommentAuthor() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        upload(user, "/api/v1/me/avatar", HttpMethod.POST, new MockMultipartFile("file", "1.png", "image/png", png(40)))
                .andExpect(status().isOk());
        var taskId = testData.task(user.id(), testData.inboxOf(user.id()), "Việc");
        createTaskComment(user, taskId, "Xin chào");
        String url = read(authGet(user.accessToken(), "/api/v1/tasks/" + taskId + "/comments"), "$[0].author.avatarUrl");
        assertThat(url).startsWith("/api/v1/users/" + user.id() + "/avatar");
        assertThat(Arrays.asList(url.split("\\?"))).hasSize(2);
    }

    @Test
    void accountDeletion_removesAvatarFile() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        upload(user, "/api/v1/me/avatar", HttpMethod.POST, new MockMultipartFile("file", "1.png", "image/png", png(40)))
                .andExpect(status().isOk());
        String key = jdbcTemplate.queryForObject("select avatar_key from users where id = ?", String.class, user.id());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"%s\"}".formatted(FIXTURE_PASSWORD)))
                .andExpect(status().isNoContent());
        cleanupService.run();
        assertThat(fileStorage.exists(key)).isFalse();
    }
}
