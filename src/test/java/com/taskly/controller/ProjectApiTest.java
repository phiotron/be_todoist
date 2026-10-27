package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectApiTest extends IntegrationTestBase {

    private static final String PROJECTS = "/api/v1/projects";

    private UUID createProject(RegisteredUser user, String json) throws Exception {
        return readUuid(authPost(user.accessToken(), PROJECTS, json).andExpect(status().isCreated()), "$.id");
    }

    private UUID createProject(RegisteredUser user, String name, UUID parentId) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        return createProject(user, "{\"name\": \"%s\", \"parentId\": %s}".formatted(name, parent));
    }

    @Test
    void create_appliesDefaultsTrimsNameAndAppendsToEnd() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPost(user.accessToken(), PROJECTS, """
                {"name": "  Việc nhà  "}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Việc nhà"))
                .andExpect(jsonPath("$.color").value("charcoal"))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.isInbox").value(false))
                .andExpect(jsonPath("$.isFavorite").value(false))
                .andExpect(jsonPath("$.archivedAt").value(nullValue()))
                .andExpect(jsonPath("$.viewStyle").value("LIST"))
                .andExpect(jsonPath("$.sortOrder").value(1024))
                .andExpect(jsonPath("$.activeTaskCount").value(0))
                .andExpect(jsonPath("$.descendantProjectCount").value(0))
                .andExpect(jsonPath("$.descendantActiveTaskCount").value(0));

        authPost(user.accessToken(), PROJECTS, """
                {"name": "Công việc", "color": "berry_red", "description": "Việc ở công ty"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.color").value("berry_red"))
                .andExpect(jsonPath("$.description").value("Việc ở công ty"))
                .andExpect(jsonPath("$.sortOrder").value(2048));
    }

    @Test
    void create_invalidNameColorOrDescription_returns400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPost(user.accessToken(), PROJECTS, """
                {"name": "   "}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("Tên dự án phải từ 1 đến 120 ký tự"));
        authPost(user.accessToken(), PROJECTS, """
                {"name": "%s"}
                """.formatted("a".repeat(121))).andExpect(status().isBadRequest());
        createProject(user, "{\"name\": \"%s\"}".formatted("a".repeat(120)));
        authPost(user.accessToken(), PROJECTS, """
                {"name": "X", "color": "pink"}
                """).andExpect(status().isBadRequest());
        authPost(user.accessToken(), PROJECTS, """
                {"name": "X", "description": "%s"}
                """.formatted("d".repeat(16384)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].message").value("Mô tả tối đa 16383 ký tự"));
    }

    @Test
    void create_subProjectsUpToThreeLevels_fourthLevelIs400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID level1 = createProject(user, "Cấp 1", null);
        UUID level2 = createProject(user, "Cấp 2", level1);
        UUID level3 = createProject(user, "Cấp 3", level2);

        authGet(user.accessToken(), PROJECTS + "/" + level3)
                .andExpect(jsonPath("$.parentId").value(level2.toString()))
                .andExpect(jsonPath("$.sortOrder").value(1024)); // vùng chứa riêng = (workspace, cha)
        authPost(user.accessToken(), PROJECTS, "{\"name\": \"Cấp 4\", \"parentId\": \"%s\"}".formatted(level3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Dự án con tối đa 3 cấp"));
    }

    @Test
    void create_parentRules_inbox400_foreign404_archived422_afterId() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID archived = testData.project(user.id(), "Lưu trữ");
        testData.archiveProject(archived);
        String withParent = "{\"name\": \"Con\", \"parentId\": \"%s\"}";

        authPost(user.accessToken(), PROJECTS, withParent.formatted(testData.inboxOf(user.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Inbox không thể làm dự án cha hay dự án con"));
        authPost(user.accessToken(), PROJECTS, withParent.formatted(testData.project(bob.id(), "Của Bob")))
                .andExpect(status().isNotFound());
        authPost(user.accessToken(), PROJECTS, withParent.formatted(archived))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));

        UUID first = createProject(user, "Đầu", null);
        UUID second = createProject(user, "Sau", null);
        long secondOrder = readLong(authGet(user.accessToken(), PROJECTS + "/" + second), "$.sortOrder");
        long firstOrder = readLong(authGet(user.accessToken(), PROJECTS + "/" + first), "$.sortOrder");
        long between = readLong(authPost(user.accessToken(), PROJECTS,
                "{\"name\": \"Giữa\", \"afterId\": \"%s\"}".formatted(first)).andExpect(status().isCreated()), "$.sortOrder");
        assertThat(between).isGreaterThan(firstOrder).isLessThan(secondOrder);
        UUID child = createProject(user, "Con của Đầu", first);
        authPost(user.accessToken(), PROJECTS, "{\"name\": \"X\", \"afterId\": \"%s\"}".formatted(child))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("afterId phải cùng vùng chứa"));
    }

    @Test
    void create_afterIdAbsentGoesToEnd_afterIdNullGoesToTop() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        createProject(user, "A", null);
        createProject(user, "B", null);

        authPost(user.accessToken(), PROJECTS, "{\"name\": \"Đầu\", \"afterId\": null}") // null tường minh = đầu (spec I3)
                .andExpect(status().isCreated());
        authPost(user.accessToken(), PROJECTS, "{\"name\": \"Cuối\"}") // vắng khoá = cuối
                .andExpect(status().isCreated());

        authGet(user.accessToken(), PROJECTS)
                .andExpect(jsonPath("$[*].name", contains("Inbox", "Đầu", "A", "B", "Cuối")));
    }

    @Test
    void create_withClientId_usesIt_replaysSameContentAs200_andRejectsDifferentContentOrOtherUsersAs409() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID id = UUID.randomUUID();
        String body = "{\"id\": \"%s\", \"name\": \"Mã client\", \"color\": \"teal\"}".formatted(id);

        authPost(alice.accessToken(), PROJECTS, body)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.id").value(id.toString()));
        // cùng id + cùng nội dung (afterId bị bỏ qua) → 200 + Idempotent-Replay: true, không tạo bản thứ hai
        authPost(alice.accessToken(), PROJECTS,
                "{\"id\": \"%s\", \"name\": \"Mã client\", \"color\": \"teal\", \"afterId\": null}".formatted(id))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id.toString()));
        authGet(alice.accessToken(), PROJECTS).andExpect(jsonPath("$[*].name", contains("Inbox", "Mã client")));

        authPost(alice.accessToken(), PROJECTS, "{\"id\": \"%s\", \"name\": \"Khác tên\"}".formatted(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict"));
        authPost(bob.accessToken(), PROJECTS, body) // id của người khác: cùng câu trả lời, không lộ gì thêm
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict"));
        authPost(alice.accessToken(), PROJECTS, "{\"id\": \"khong-phai-uuid\", \"name\": \"X\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void replayOfAClientId_isNotCountedAgainstTheLimit() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        usageLimits.overrideMax(LimitKind.ACTIVE_PROJECTS, 1);
        String body = "{\"id\": \"%s\", \"name\": \"Duy nhất\"}".formatted(UUID.randomUUID());

        authPost(user.accessToken(), PROJECTS, body).andExpect(status().isCreated());
        authPost(user.accessToken(), PROJECTS, body).andExpect(status().isOk()); // replay không bị 422 limit-reached
    }

    @Test
    void descendantCounts_coverAllLevelsIncludingArchivedChildren_forTheDeleteConfirmation() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = createProject(user, "A", null);
        UUID a1 = createProject(user, "A1", a);
        UUID a11 = createProject(user, "A11", a1);
        testData.task(user.id(), a, "Của A");
        testData.task(user.id(), a1, "Của A1");
        testData.task(user.id(), a11, "Của A11");
        testData.insert(TaskRow.of(user.id(), a11, "Xong").completedAt(clock.instant())); // đã xong: không đếm
        testData.archiveProject(a11); // dự án con lưu trữ VẪN đếm (xoá A xoá cả nó)

        authGet(user.accessToken(), PROJECTS + "/" + a)
                .andExpect(jsonPath("$.activeTaskCount").value(1))
                .andExpect(jsonPath("$.descendantProjectCount").value(2))
                .andExpect(jsonPath("$.descendantActiveTaskCount").value(2)); // A1 + A11, không gồm chính A
        authGet(user.accessToken(), PROJECTS + "/" + a1)
                .andExpect(jsonPath("$.descendantProjectCount").value(1))
                .andExpect(jsonPath("$.descendantActiveTaskCount").value(1));
        authGet(user.accessToken(), PROJECTS + "/" + testData.inboxOf(user.id()))
                .andExpect(jsonPath("$.descendantProjectCount").value(0))
                .andExpect(jsonPath("$.descendantActiveTaskCount").value(0));
    }

    @Test
    void list_inboxFirstThenTreeOrder_withFavoriteFlagAndOpenTaskCounts() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = createProject(user, "A", null);
        UUID b = createProject(user, "B", null);
        UUID a1 = createProject(user, "A1", a);
        UUID root = testData.task(user.id(), a, "Gốc");
        testData.insert(TaskRow.of(user.id(), a, "Con").parent(root));
        testData.insert(TaskRow.of(user.id(), a, "Đã xong").completedAt(clock.instant()));
        testData.task(user.id(), a1, "Việc của A1");
        authPut(user.accessToken(), PROJECTS + "/" + b + "/favorite").andExpect(status().isOk());

        authGet(user.accessToken(), PROJECTS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Inbox", "A", "A1", "B")))
                .andExpect(jsonPath("$[0].isInbox").value(true))
                .andExpect(jsonPath("$[0].color").value(nullValue()))
                .andExpect(jsonPath("$[1].activeTaskCount").value(2)) // chỉ task đang mở (gồm sub-task)
                .andExpect(jsonPath("$[1].descendantProjectCount").value(1)) // A1
                .andExpect(jsonPath("$[1].descendantActiveTaskCount").value(1)) // task của A1, không gồm task của chính A
                .andExpect(jsonPath("$[2].activeTaskCount").value(1))
                .andExpect(jsonPath("$[2].descendantProjectCount").value(0))
                .andExpect(jsonPath("$[3].descendantActiveTaskCount").value(0))
                .andExpect(jsonPath("$[2].parentId").value(a.toString()))
                .andExpect(jsonPath("$[3].isFavorite").value(true));
        assertThat(a1).isNotNull();
    }

    @Test
    void patch_updatesFields_andNoopKeepsUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = createProject(user, "Cũ", null);
        String path = PROJECTS + "/" + id;
        String createdUpdatedAt = readString(authGet(user.accessToken(), path), "$.updatedAt");

        clock.advance(Duration.ofMinutes(5));
        authPatch(user.accessToken(), path, """
                {"name": "Cũ"}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.updatedAt").value(createdUpdatedAt));

        clock.advance(Duration.ofMinutes(5));
        String changed = readString(authPatch(user.accessToken(), path, """
                {"name": "Mới", "color": "teal", "description": "Ghi chú"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mới"))
                .andExpect(jsonPath("$.color").value("teal"))
                .andExpect(jsonPath("$.description").value("Ghi chú")), "$.updatedAt");
        assertThat(changed).isNotEqualTo(createdUpdatedAt);
        authPatch(user.accessToken(), path, "{\"description\": \"\"}").andExpect(jsonPath("$.description").value(""));
    }

    @Test
    void inbox_cannotBeEditedOrFavorited_returns403() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String inbox = PROJECTS + "/" + testData.inboxOf(user.id());

        authPatch(user.accessToken(), inbox, """
                {"name": "Hộp thư"}
                """)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Không thể xoá, lưu trữ, sửa, chuyển hay yêu thích Inbox"));
        authPut(user.accessToken(), inbox + "/favorite").andExpect(status().isForbidden());
        authGet(user.accessToken(), inbox).andExpect(status().isOk());
    }

    @Test
    void archivedProject_isReadOnly409WithCode_butFavoriteStillAllowed() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = testData.project(user.id(), "Lưu trữ");
        testData.archiveProject(id);
        String path = PROJECTS + "/" + id;

        authGet(user.accessToken(), path).andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").isString());
        authPatch(user.accessToken(), path, "{\"name\": \"Đổi\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Dự án đã lưu trữ"))
                .andExpect(jsonPath("$.code").value("project-archived"));
        authPut(user.accessToken(), path + "/favorite").andExpect(status().isOk())
                .andExpect(jsonPath("$.isFavorite").value(true));
    }

    @Test
    void favorite_putAndDelete_areIdempotent() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String path = PROJECTS + "/" + createProject(user, "Yêu thích", null) + "/favorite";

        authPut(user.accessToken(), path).andExpect(status().isOk()).andExpect(jsonPath("$.isFavorite").value(true));
        authPut(user.accessToken(), path).andExpect(status().isOk()).andExpect(jsonPath("$.isFavorite").value(true));
        authDelete(user.accessToken(), path).andExpect(status().isOk()).andExpect(jsonPath("$.isFavorite").value(false));
        authDelete(user.accessToken(), path).andExpect(status().isOk()).andExpect(jsonPath("$.isFavorite").value(false));
    }

    @Test
    void limits_activeProjectsAndTotalProjects_return422LimitReached() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        usageLimits.overrideMax(LimitKind.ACTIVE_PROJECTS, 2);
        createProject(user, "1", null);
        UUID second = createProject(user, "2", null);
        authPost(user.accessToken(), PROJECTS, "{\"name\": \"3\"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("ACTIVE_PROJECTS"))
                .andExpect(jsonPath("$.max").value(2));

        testData.archiveProject(second); // dự án lưu trữ không tính vào "đang hoạt động" nhưng vẫn tính vào tổng
        usageLimits.overrideMax(LimitKind.PROJECTS, 2);
        authPost(user.accessToken(), PROJECTS, "{\"name\": \"3\"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECTS"));
    }

    @Test
    void otherUser_gets404OnEveryProjectEndpoint() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        String path = PROJECTS + "/" + createProject(alice, "Bí mật", null);

        authGet(bob.accessToken(), path).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy dự án"));
        authPatch(bob.accessToken(), path, "{\"name\": \"x\"}").andExpect(status().isNotFound());
        authPut(bob.accessToken(), path + "/favorite").andExpect(status().isNotFound());
        authDelete(bob.accessToken(), path + "/favorite").andExpect(status().isNotFound());
        authGet(bob.accessToken(), PROJECTS).andExpect(jsonPath("$", hasSize(1))); // chỉ Inbox của bob
        authGet(bob.accessToken(), PROJECTS + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
