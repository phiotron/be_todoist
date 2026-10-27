package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GET favorites (spec 12.2): theo thứ tự thêm (cũ trước), ẩn dự án lưu trữ nhưng giữ trạng thái yêu thích. */
class FavoriteApiTest extends IntegrationTestBase {

    private void favorite(RegisteredUser user, UUID projectId) throws Exception {
        authPut(user.accessToken(), "/api/v1/projects/" + projectId + "/favorite").andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(1)); // created_at khác nhau → thứ tự xác định
    }

    @Test
    void list_isInsertionOrder_hidesArchivedProjects_andUnfavoriteRemoves() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p1 = testData.project(user.id(), "P1");
        UUID p2 = testData.project(user.id(), "P2");
        UUID p3 = testData.project(user.id(), "P3");
        favorite(user, p2);
        favorite(user, p1);
        favorite(user, p3);
        testData.archiveProject(p3);

        authGet(user.accessToken(), "/api/v1/favorites")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].targetId", contains(p2.toString(), p1.toString())))
                .andExpect(jsonPath("$[*].targetType", contains("PROJECT", "PROJECT")))
                .andExpect(jsonPath("$[0].createdAt").isString());
        authGet(user.accessToken(), "/api/v1/projects/" + p3).andExpect(jsonPath("$.isFavorite").value(true));

        authDelete(user.accessToken(), "/api/v1/projects/" + p2 + "/favorite").andExpect(status().isOk());
        authGet(user.accessToken(), "/api/v1/favorites").andExpect(jsonPath("$[*].targetId", contains(p1.toString())));
    }

    @Test
    void otherUsersFavoritesAreNotVisible() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        favorite(alice, testData.project(alice.id(), "Của Alice"));

        authGet(bob.accessToken(), "/api/v1/favorites").andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }
}
