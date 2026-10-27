package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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

class SectionApiTest extends IntegrationTestBase {

    private static String sections(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/sections";
    }

    private UUID createSection(RegisteredUser user, UUID projectId, String name) throws Exception {
        return readUuid(authPost(user.accessToken(), sections(projectId), """
                {"name": "%s"}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void create_appendsToEnd_andListIsOrderedWithDescriptionAndOpenTaskCount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "Dự án");

        UUID first = readUuid(authPost(user.accessToken(), sections(project), """
                {"name": "  Tuần này ", "description": "Việc gấp"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Tuần này"))
                .andExpect(jsonPath("$.description").value("Việc gấp"))
                .andExpect(jsonPath("$.projectId").value(project.toString()))
                .andExpect(jsonPath("$.archivedAt").value(nullValue()))
                .andExpect(jsonPath("$.activeTaskCount").value(0))
                .andExpect(jsonPath("$.sortOrder").value(1024)), "$.id");
        UUID second = createSection(user, project, "Tuần sau");
        testData.insert(TaskRow.of(user.id(), project, "Mở").section(first));
        testData.insert(TaskRow.of(user.id(), project, "Xong").section(first).completedAt(clock.instant()));

        authGet(user.accessToken(), sections(project))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Tuần này", "Tuần sau")))
                .andExpect(jsonPath("$[0].activeTaskCount").value(1))
                .andExpect(jsonPath("$[1].id").value(second.toString()))
                .andExpect(jsonPath("$[1].description").value(""))
                .andExpect(jsonPath("$[1].sortOrder").value(2048));
    }

    @Test
    void sectionsWorkInInbox_andInvalidNameOrDescriptionIs400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());

        createSection(user, inbox, "Nhóm A");
        createSection(user, inbox, "b".repeat(2048));
        authPost(user.accessToken(), sections(inbox), """
                {"name": " "}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("Tên section phải từ 1 đến 2048 ký tự"));
        authPost(user.accessToken(), sections(inbox), "{\"name\": \"%s\"}".formatted("b".repeat(2049)))
                .andExpect(status().isBadRequest());
        authPost(user.accessToken(), sections(inbox), "{\"name\": \"x\", \"description\": \"%s\"}"
                .formatted("d".repeat(16384)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Mô tả tối đa 16383 ký tự"));
    }

    @Test
    void patch_updatesNameAndDescription_andSectionOfAnotherProjectIs404() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P1");
        UUID other = testData.project(user.id(), "P2");
        UUID section = createSection(user, project, "Cũ");

        authPatch(user.accessToken(), sections(project) + "/" + section, """
                {"name": "Mới"}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mới"))
                .andExpect(jsonPath("$.description").value(""));
        authPatch(user.accessToken(), sections(project) + "/" + section, """
                {"description": "Ghi chú"}
                """).andExpect(jsonPath("$.name").value("Mới")).andExpect(jsonPath("$.description").value("Ghi chú"));
        authPatch(user.accessToken(), sections(other) + "/" + section, """
                {"name": "Mới"}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy section"));
    }

    @Test
    void list_putsArchivedSectionsLast_newestArchivedFirst() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID s1 = createSection(user, project, "S1");
        UUID s2 = createSection(user, project, "S2");
        createSection(user, project, "S3");
        testData.archiveSection(s1);
        clock.advance(Duration.ofMinutes(1));
        testData.archiveSection(s2);

        authGet(user.accessToken(), sections(project))
                .andExpect(jsonPath("$[*].name", contains("S3", "S2", "S1")))
                .andExpect(jsonPath("$[0].archivedAt").value(nullValue()))
                .andExpect(jsonPath("$[1].archivedAt").isString());
    }

    @Test
    void archivedSection_canStillBeRenamedAndDeleted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID section = createSection(user, project, "S");
        testData.archiveSection(section);

        authPatch(user.accessToken(), sections(project) + "/" + section, "{\"name\": \"S đổi\", \"description\": \"x\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("S đổi"));
        authDelete(user.accessToken(), sections(project) + "/" + section).andExpect(status().isNoContent());
    }

    @Test
    void archivedProject_sectionsAreReadOnly409_butListable() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "Lưu trữ");
        UUID section = createSection(user, project, "S");
        testData.archiveProject(project);

        authGet(user.accessToken(), sections(project)).andExpect(status().isOk());
        authPost(user.accessToken(), sections(project), """
                {"name": "S2"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Dự án đã lưu trữ"))
                .andExpect(jsonPath("$.code").value("project-archived"));
        authPatch(user.accessToken(), sections(project) + "/" + section, "{\"name\": \"S3\"}")
                .andExpect(status().isConflict());
        authDelete(user.accessToken(), sections(project) + "/" + section).andExpect(status().isConflict());
    }

    @Test
    void limit_activeSectionsPerProject_archivedOnesDoNotCount() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 2);
        UUID first = createSection(user, project, "1");
        createSection(user, project, "2");

        authPost(user.accessToken(), sections(project), "{\"name\": \"3\"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("PROJECT_SECTIONS"))
                .andExpect(jsonPath("$.max").value(2));
        testData.archiveSection(first);
        createSection(user, project, "3");
    }

    @Test
    void limit_totalSectionsIncludingArchived_returns422WithTotalKind() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS_TOTAL, 2);
        UUID first = createSection(user, project, "1");
        createSection(user, project, "2");
        testData.archiveSection(first); // 1 hoạt động + 1 lưu trữ = 2 tổng (spec 2.1, I1)

        authPost(user.accessToken(), sections(project), "{\"name\": \"3\"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("PROJECT_SECTIONS_TOTAL"))
                .andExpect(jsonPath("$.max").value(2));
    }

    @Test
    void create_afterIdAbsentGoesToEnd_afterIdNullGoesToTop() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        createSection(user, project, "A");
        createSection(user, project, "B");

        authPost(user.accessToken(), sections(project), "{\"name\": \"Đầu\", \"afterId\": null}")
                .andExpect(status().isCreated());
        authPost(user.accessToken(), sections(project), "{\"name\": \"Cuối\"}").andExpect(status().isCreated());

        authGet(user.accessToken(), sections(project))
                .andExpect(jsonPath("$[*].name", contains("Đầu", "A", "B", "Cuối")));
    }

    @Test
    void create_withClientId_replaysSameContentAs200_andConflictsOtherwiseOrForOtherUsers() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID project = testData.project(alice.id(), "P");
        UUID bobProject = testData.project(bob.id(), "Của Bob");
        UUID id = UUID.randomUUID();
        String body = "{\"id\": \"%s\", \"name\": \"Mã client\"}".formatted(id);

        authPost(alice.accessToken(), sections(project), body)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.id").value(id.toString()));
        authPost(alice.accessToken(), sections(project), body)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        authGet(alice.accessToken(), sections(project)).andExpect(jsonPath("$[*].name", contains("Mã client")));

        authPost(alice.accessToken(), sections(project), "{\"id\": \"%s\", \"name\": \"Khác tên\"}".formatted(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict"));
        authPost(bob.accessToken(), sections(bobProject), body) // id của người khác
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict"));

        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 1); // replay không bị 422 dù đã chạm trần
        authPost(alice.accessToken(), sections(project), body).andExpect(status().isOk());
    }

    @Test
    void delete_removesSectionAndAllItsTasksPermanently_only() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID doomed = createSection(user, project, "Xoá");
        UUID kept = createSection(user, project, "Giữ");
        UUID root = testData.insert(TaskRow.of(user.id(), project, "Mở").section(doomed));
        testData.insert(TaskRow.of(user.id(), project, "Con").section(doomed).parent(root));
        testData.insert(TaskRow.of(user.id(), project, "Xong").section(doomed).completedAt(clock.instant()));
        UUID keptTask = testData.insert(TaskRow.of(user.id(), project, "Giữ").section(kept));
        UUID loose = testData.task(user.id(), project, "Không section");

        authDelete(user.accessToken(), sections(project) + "/" + doomed).andExpect(status().isNoContent());

        assertThat(count("select count(*) from sections where id = ?", doomed)).isZero();
        assertThat(count("select count(*) from tasks where section_id = ?", doomed)).isZero();
        assertThat(count("select count(*) from tasks where id in (?, ?)", keptTask, loose)).isEqualTo(2);
        authDelete(user.accessToken(), sections(project) + "/" + doomed).andExpect(status().isNotFound());
    }

    @Test
    void otherUser_gets404() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID project = testData.project(alice.id(), "Của Alice");
        UUID section = createSection(alice, project, "S");

        authGet(bob.accessToken(), sections(project)).andExpect(status().isNotFound());
        authPost(bob.accessToken(), sections(project), "{\"name\": \"x\"}").andExpect(status().isNotFound());
        authPatch(bob.accessToken(), sections(project) + "/" + section, "{\"name\": \"x\"}")
                .andExpect(status().isNotFound());
        authDelete(bob.accessToken(), sections(project) + "/" + section).andExpect(status().isNotFound());
    }
}
