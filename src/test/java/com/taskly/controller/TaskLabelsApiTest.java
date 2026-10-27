package com.taskly.controller;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.Texts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import com.taskly.support.M2ApiTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** labelIds trên task (spec 12.3, 10.1, 5.6). Profile test: tối đa 3 label / task. */
class TaskLabelsApiTest extends M2ApiTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private RegisteredUser user;
    private String token;
    private UUID a;
    private UUID b;
    private UUID c;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser(uniqueEmail());
        token = user.accessToken();
        b = data.label(user.id(), "b", 2_000);
        a = data.label(user.id(), "a", 1_000);
        c = data.label(user.id(), "c", 3_000);
    }

    private String createJson(String content, String extra) {
        return "{\"content\": \"" + content + "\"" + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }

    private UUID create(String extra) throws Exception {
        return readId(apiPost(token, TASKS, createJson("Việc", extra)).andExpect(status().isCreated()));
    }

    private long version(UUID task) {
        return jdbcTemplate.queryForObject("select version from tasks where id = ?", Long.class, task);
    }

    private ResultActions patch(UUID task, String fields) throws Exception {
        return apiPatch(token, TASKS + "/" + task, "{\"version\": " + version(task) + ", " + fields + "}");
    }

    private static String ids(UUID... labels) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < labels.length; i++) {
            json.append(i == 0 ? "" : ", ").append('"').append(labels[i]).append('"');
        }
        return json.append(']').toString();
    }

    @Test
    void create_withLabels_dedupesAndOrdersByLabelSortOrder() throws Exception {
        apiPost(token, TASKS, createJson("Việc", "\"labelIds\": " + ids(b, a, b)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.labelIds", contains(a.toString(), b.toString())));
        UUID plain = create("");

        apiGet(token, TASKS + "/" + plain).andExpect(jsonPath("$.labelIds", empty()));
    }

    @Test
    void create_withOtherUsersLabel_is404_andCreatesNothing() throws Exception {
        UUID foreign = data.label(registerUser(uniqueEmail()).id(), "x");
        int before = jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ?", Integer.class,
                data.inboxOf(user.id()));

        apiPost(token, TASKS, createJson("Việc", "\"labelIds\": " + ids(a, foreign)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABEL_NOT_FOUND)));

        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ?", Integer.class,
                data.inboxOf(user.id()))).isEqualTo(before);
    }

    @Test
    void create_overLabelsPerTaskLimit_is400() throws Exception {
        UUID d = data.label(user.id(), "d");

        apiPost(token, TASKS, createJson("Việc", "\"labelIds\": " + ids(a, b, c, d)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABELS_PER_TASK)));
    }

    @Test
    void create_withClientIdReplay_returnsTheOldTask_andDoesNotReattachLabels() throws Exception {
        UUID id = UUID.randomUUID();
        String base = "\"id\": \"" + id + "\", \"content\": \"Việc\"";

        apiPost(token, TASKS, "{" + base + ", \"labelIds\": " + ids(a) + "}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.labelIds", contains(a.toString())));
        apiPost(token, TASKS, "{" + base + ", \"labelIds\": " + ids(b) + "}").andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.labelIds", contains(a.toString())));   // labelIds không thuộc field so khớp (spec 12.11)

        assertThat(jdbcTemplate.queryForObject("select count(*) from task_labels where task_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void patch_replacesLabels_andBumpsVersionExactlyOnce() throws Exception {
        UUID task = create("\"labelIds\": " + ids(a));
        long v0 = version(task);
        String updatedBefore = JsonPath.read(okBody(apiGet(token, TASKS + "/" + task)), "$.updatedAt");
        clock.advance(Duration.ofMinutes(1));

        patch(task, "\"labelIds\": " + ids(c, b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labelIds", contains(b.toString(), c.toString())))
                .andExpect(jsonPath("$.version").value(v0 + 1));
        assertThat((String) JsonPath.read(okBody(apiGet(token, TASKS + "/" + task)), "$.updatedAt"))
                .isNotEqualTo(updatedBefore);

        patch(task, "\"content\": \"Đổi tên\", \"labelIds\": " + ids(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Đổi tên"))
                .andExpect(jsonPath("$.labelIds", contains(a.toString())))
                .andExpect(jsonPath("$.version").value(v0 + 2));
    }

    @Test
    void patch_sameLabelSetInAnotherOrder_keepsVersion() throws Exception {
        UUID task = create("\"labelIds\": " + ids(a, b));
        long v0 = version(task);

        patch(task, "\"labelIds\": " + ids(b, a)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(v0));
    }

    @Test
    void patch_withoutLabelIdsKeeps_emptyListClears_foreignLabelIs404() throws Exception {
        UUID task = create("\"labelIds\": " + ids(a, b));
        UUID foreign = data.label(registerUser(uniqueEmail()).id(), "x");

        patch(task, "\"content\": \"Khác\"").andExpect(jsonPath("$.labelIds", contains(a.toString(), b.toString())));
        patch(task, "\"labelIds\": " + ids(foreign)).andExpect(status().isNotFound());
        apiGet(token, TASKS + "/" + task).andExpect(jsonPath("$.labelIds", contains(a.toString(), b.toString())));
        patch(task, "\"labelIds\": []").andExpect(status().isOk()).andExpect(jsonPath("$.labelIds", empty()));
    }

    @Test
    void duplicate_copiesLabelsOfTaskAndCopiedSubtasks() throws Exception {
        UUID parent = create("\"labelIds\": " + ids(a));
        readId(apiPost(token, TASKS, createJson("Con", "\"parentId\": \"" + parent + "\", \"labelIds\": " + ids(b)))
                .andExpect(status().isCreated()));

        UUID copy = readId(apiPost(token, TASKS + "/" + parent + "/duplicate", null).andExpect(status().isCreated()));

        apiGet(token, TASKS + "/" + copy).andExpect(jsonPath("$.labelIds", contains(a.toString())));
        apiGet(token, TASKS + "/" + copy + "/subtasks").andExpect(jsonPath("$[0].labelIds", contains(b.toString())));
    }

    @Test
    void deletingLabel_removesItFromTaskResponses_withoutBumpingVersion() throws Exception {
        UUID task = create("\"labelIds\": " + ids(a, b));
        long v0 = version(task);

        apiDelete(token, "/api/v1/labels/" + a).andExpect(status().isNoContent());

        apiGet(token, TASKS + "/" + task).andExpect(jsonPath("$.labelIds", contains(b.toString())));
        assertThat(version(task)).as("xoá label không phải thay đổi nội dung task (spec 5.6)").isEqualTo(v0);
    }

    @Test
    void listEndpoints_carryLabelIds() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-24T03:00:00Z")); // 10:00 ngày 24/10 (quá khứ: JWT không hết hạn) ở Asia/Ho_Chi_Minh
        create("\"dueDate\": \"2026-10-24\", \"labelIds\": " + ids(c, a));

        apiGet(token, TASKS, "view", "today")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].labelIds", contains(a.toString(), c.toString())));
    }

    /** Quyết định chưa được người dùng chốt: PATCH `"labelIds": null` = giữ nguyên (như vắng), KHÔNG phải xoá hết. */
    @Test
    void patch_explicitNullLabelIds_keepsLabels_likeAbsent() throws Exception {
        UUID task = create("\"labelIds\": " + ids(a, b));
        long v0 = version(task);

        patch(task, "\"labelIds\": null")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labelIds", contains(a.toString(), b.toString())))
                .andExpect(jsonPath("$.version").value(v0));
    }

    @Test
    void patch_overLabelsPerTaskLimit_is400_andChangesNothing() throws Exception {
        UUID task = create("\"labelIds\": " + ids(a));
        UUID d = data.label(user.id(), "d");

        patch(task, "\"labelIds\": " + ids(a, b, c, d))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABELS_PER_TASK)));
        apiGet(token, TASKS + "/" + task).andExpect(jsonPath("$.labelIds", contains(a.toString())));
    }

    @Test
    void patch_staleVersionWithLabelIds_is409_andDoesNotChangeLabels() throws Exception {
        UUID task = create("\"labelIds\": " + ids(a));
        long v0 = version(task);

        apiPatch(token, TASKS + "/" + task, "{\"version\": " + (v0 + 5) + ", \"labelIds\": " + ids(b) + "}")
                .andExpect(status().isConflict());
        apiGet(token, TASKS + "/" + task).andExpect(jsonPath("$.labelIds", contains(a.toString())));
    }
}
