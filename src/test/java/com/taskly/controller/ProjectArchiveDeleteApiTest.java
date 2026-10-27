package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectArchiveDeleteApiTest extends IntegrationTestBase {

    private static final String PROJECTS = "/api/v1/projects";

    private UUID create(RegisteredUser user, String name, UUID parentId) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        return readUuid(authPost(user.accessToken(), PROJECTS, "{\"name\": \"%s\", \"parentId\": %s}"
                .formatted(name, parent)).andExpect(status().isCreated()), "$.id");
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void archive_cascadesToDescendantsWithSameTimestamp_hidesThem_andRepeatIs200() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = create(user, "A", null);
        UUID a1 = create(user, "A1", a);
        UUID a11 = create(user, "A11", a1);

        String archivedAt = readString(authPost(user.accessToken(), PROJECTS + "/" + a + "/archive")
                .andExpect(status().isOk()), "$.archivedAt");
        assertThat(archivedAt).isNotNull();
        clock.advance(Duration.ofMinutes(1));
        authPost(user.accessToken(), PROJECTS + "/" + a + "/archive")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(archivedAt)); // lặp lại không ghi đè

        authGet(user.accessToken(), PROJECTS).andExpect(jsonPath("$[*].name", contains("Inbox")));
        authGet(user.accessToken(), PROJECTS + "?archived=true")
                .andExpect(jsonPath("$[*].name", contains("A", "A1", "A11")))
                .andExpect(jsonPath("$[*].archivedAt", contains(archivedAt, archivedAt, archivedAt)));
        authGet(user.accessToken(), PROJECTS + "/" + a11).andExpect(status().isOk()); // mở xem chỉ đọc được
        authPatch(user.accessToken(), PROJECTS + "/" + a1, "{\"name\": \"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    @Test
    void unarchive_restoresOnlyDescendantsArchivedTogether() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = create(user, "A", null);
        UUID a1 = create(user, "A1", a);
        UUID a2 = create(user, "A2", a);
        authPost(user.accessToken(), PROJECTS + "/" + a2 + "/archive").andExpect(status().isOk()); // lưu trữ riêng trước
        clock.advance(Duration.ofMinutes(1));
        authPost(user.accessToken(), PROJECTS + "/" + a + "/archive").andExpect(status().isOk());

        authPost(user.accessToken(), PROJECTS + "/" + a + "/unarchive")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(nullValue()));
        authGet(user.accessToken(), PROJECTS).andExpect(jsonPath("$[*].name", contains("Inbox", "A", "A1")));
        authGet(user.accessToken(), PROJECTS + "?archived=true").andExpect(jsonPath("$[*].name", contains("A2")));
        authPost(user.accessToken(), PROJECTS + "/" + a + "/unarchive").andExpect(status().isOk()); // lặp lại vẫn 200
        assertThat(a1).isNotNull();
    }

    @Test
    void unarchiveChildWhoseParentIsStillArchived_becomesRootAtEnd() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = create(user, "A", null);
        UUID a1 = create(user, "A1", a);
        UUID b = create(user, "B", null);
        authPost(user.accessToken(), PROJECTS + "/" + a + "/archive").andExpect(status().isOk());

        long newOrder = readLong(authPost(user.accessToken(), PROJECTS + "/" + a1 + "/unarchive")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.archivedAt").value(nullValue())), "$.sortOrder");
        assertThat(newOrder).isGreaterThan(readLong(authGet(user.accessToken(), PROJECTS + "/" + b), "$.sortOrder"));
        authGet(user.accessToken(), PROJECTS).andExpect(jsonPath("$[*].name", contains("Inbox", "B", "A1")));
    }

    @Test
    void unarchive_overActiveProjectLimit_is422() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = create(user, "A", null);
        create(user, "A1", a);
        authPost(user.accessToken(), PROJECTS + "/" + a + "/archive").andExpect(status().isOk());
        create(user, "B", null);
        usageLimits.overrideMax(LimitKind.ACTIVE_PROJECTS, 2);

        authPost(user.accessToken(), PROJECTS + "/" + a + "/unarchive") // 1 đang hoạt động + 2 khôi phục > 2
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("ACTIVE_PROJECTS"));
    }

    @Test
    void inbox_cannotBeArchivedOrDeleted_returns403() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String inbox = PROJECTS + "/" + testData.inboxOf(user.id());

        authPost(user.accessToken(), inbox + "/archive").andExpect(status().isForbidden());
        authPost(user.accessToken(), inbox + "/unarchive").andExpect(status().isForbidden());
        authDelete(user.accessToken(), inbox)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Không thể xoá, lưu trữ, sửa, chuyển hay yêu thích Inbox"));
    }

    @Test
    void delete_removesSubProjectsSectionsTasksAndFavoritesPermanently_onlyThem() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = create(user, "A", null);
        UUID a1 = create(user, "A1", a);
        UUID section = testData.section(a1, "S");
        UUID root = testData.insert(TaskRow.of(user.id(), a1, "Gốc").section(section));
        testData.insert(TaskRow.of(user.id(), a1, "Con").section(section).parent(root));
        testData.task(user.id(), a, "Việc của A");
        authPut(user.accessToken(), PROJECTS + "/" + a + "/favorite").andExpect(status().isOk());
        authPut(user.accessToken(), PROJECTS + "/" + a1 + "/favorite").andExpect(status().isOk());
        UUID keep = create(user, "Giữ", null);
        UUID keptTask = testData.task(user.id(), keep, "Không đụng");
        authPut(user.accessToken(), PROJECTS + "/" + keep + "/favorite").andExpect(status().isOk());

        authDelete(user.accessToken(), PROJECTS + "/" + a).andExpect(status().isNoContent());

        authGet(user.accessToken(), PROJECTS + "/" + a).andExpect(status().isNotFound());
        authGet(user.accessToken(), PROJECTS + "/" + a1).andExpect(status().isNotFound());
        assertThat(count("select count(*) from sections where id = ?", section)).isZero();
        assertThat(count("select count(*) from tasks where project_id in (?, ?)", a, a1)).isZero();
        assertThat(count("select count(*) from favorites where target_id in (?, ?)", a, a1)).isZero();
        assertThat(count("select count(*) from tasks where id = ?", keptTask)).isOne();
        authGet(user.accessToken(), "/api/v1/favorites")
                .andExpect(jsonPath("$[*].targetId", containsInAnyOrder(keep.toString())));
        authDelete(user.accessToken(), PROJECTS + "/" + a).andExpect(status().isNotFound()); // xoá lại → 404
    }

    @Test
    void archivedProject_canStillBeDeleted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = create(user, "A", null);
        authPost(user.accessToken(), PROJECTS + "/" + a + "/archive").andExpect(status().isOk());

        authDelete(user.accessToken(), PROJECTS + "/" + a).andExpect(status().isNoContent());
        authGet(user.accessToken(), PROJECTS + "?archived=true").andExpect(jsonPath("$[*].name").isEmpty());
    }

    @Test
    void otherUser_gets404OnArchiveUnarchiveMoveDelete() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        String path = PROJECTS + "/" + create(alice, "Bí mật", null);

        authPost(bob.accessToken(), path + "/archive").andExpect(status().isNotFound());
        authPost(bob.accessToken(), path + "/unarchive").andExpect(status().isNotFound());
        authPost(bob.accessToken(), path + "/move", "{\"parentId\": null}").andExpect(status().isNotFound());
        authDelete(bob.accessToken(), path).andExpect(status().isNotFound());
        authGet(alice.accessToken(), path).andExpect(status().isOk());
    }
}
