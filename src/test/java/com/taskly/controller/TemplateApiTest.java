package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** M11 F5–F7: Save as template, Browse (của mình + thư viện), Apply (dự án mới / dự án có sẵn), xoá, giới hạn 100. */
class TemplateApiTest extends IntegrationTestBase {

    private static final String TEMPLATES = "/api/v1/templates";
    private static final Instant DONE = Instant.parse("2026-10-20T03:00:00Z");

    private RegisteredUser user;
    private UUID project;

    @BeforeEach
    void data() throws Exception {
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "Ra mắt");
        jdbcTemplate.update("update projects set color = 'violet' where id = ?", project);
        UUID r = testData.insert(TaskRow.of(user.id(), project, "Viết thông cáo").sortOrder(1024).priority(1)
                .dueDate(LocalDate.of(2026, 11, 5)));
        testData.insert(TaskRow.of(user.id(), project, "Duyệt bản nháp").parent(r));
        testData.insert(TaskRow.of(user.id(), project, "Đã xong").completedAt(DONE));
        UUID s = testData.section(project, "Tuần 1");
        testData.insert(TaskRow.of(user.id(), project, "Họp khởi động").section(s));
        UUID archived = testData.section(project, "Cũ");
        testData.archiveSection(archived);
    }

    private UUID save(String body) throws Exception {
        return readUuid(authPost(user.accessToken(), TEMPLATES, body).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void saveAsTemplate_snapshotsOpenContent_withoutDates() throws Exception {
        UUID id = readUuid(authPost(user.accessToken(), TEMPLATES, "{\"projectId\": \"%s\"}".formatted(project))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ra mắt"))
                .andExpect(jsonPath("$.color").value("violet"))
                .andExpect(jsonPath("$.sectionCount").value(1))
                .andExpect(jsonPath("$.taskCount").value(3)), "$.id");

        authGet(user.accessToken(), TEMPLATES + "/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MINE"))
                .andExpect(jsonPath("$.tasks", hasSize(1)))
                .andExpect(jsonPath("$.tasks[0].content").value("Viết thông cáo"))
                .andExpect(jsonPath("$.tasks[0].priority").value(1))
                .andExpect(jsonPath("$.tasks[0].children[0].content").value("Duyệt bản nháp"))
                .andExpect(jsonPath("$.sections", hasSize(1)))
                .andExpect(jsonPath("$.sections[0].name").value("Tuần 1"))
                .andExpect(jsonPath("$.sections[0].tasks[0].content").value("Họp khởi động"));

        authGet(user.accessToken(), TEMPLATES)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    @Test
    void saveAsTemplate_customName_andErrors() throws Exception {
        authPost(user.accessToken(), TEMPLATES, "{\"projectId\": \"%s\", \"name\": \"  Mẫu ra mắt  \"}".formatted(project))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Mẫu ra mắt"));
        authPost(user.accessToken(), TEMPLATES, "{\"projectId\": \"%s\", \"name\": \"%s\"}".formatted(project, "n".repeat(121)))
                .andExpect(status().isBadRequest());
        authPost(user.accessToken(), TEMPLATES, "{}").andExpect(status().isBadRequest());
        authPost(user.accessToken(), TEMPLATES, "{\"projectId\": \"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
        authPost(user.accessToken(), TEMPLATES, "{\"projectId\": \"%s\"}".formatted(testData.inboxOf(user.id())))
                .andExpect(status().isForbidden());
        UUID archived = testData.project(user.id(), "Cũ");
        testData.archiveProject(archived);
        authPost(user.accessToken(), TEMPLATES, "{\"projectId\": \"%s\"}".formatted(archived))
                .andExpect(status().isConflict());
    }

    @Test
    void saveAsTemplate_limitReached() throws Exception {
        usageLimits.overrideMax(LimitKind.TEMPLATES, 1);
        save("{\"projectId\": \"%s\"}".formatted(project));
        authPost(user.accessToken(), TEMPLATES, "{\"projectId\": \"%s\"}".formatted(project))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("TEMPLATES"))
                .andExpect(jsonPath("$.max").value(1));
    }

    @Test
    void templatesAreIsolated_andDeletable() throws Exception {
        UUID id = save("{\"projectId\": \"%s\"}".formatted(project));
        RegisteredUser other = registerUser(uniqueEmail());
        authGet(other.accessToken(), TEMPLATES + "/" + id).andExpect(status().isNotFound());
        authGet(other.accessToken(), TEMPLATES).andExpect(jsonPath("$", hasSize(0)));
        authPost(other.accessToken(), TEMPLATES + "/" + id + "/apply", "{}").andExpect(status().isNotFound());
        authDelete(other.accessToken(), TEMPLATES + "/" + id).andExpect(status().isNotFound());

        authDelete(user.accessToken(), TEMPLATES + "/" + id).andExpect(status().isNoContent());
        authGet(user.accessToken(), TEMPLATES + "/" + id).andExpect(status().isNotFound());
    }

    @Test
    void applyMine_createsNewProject() throws Exception {
        UUID id = save("{\"projectId\": \"%s\"}".formatted(project));
        UUID created = readUuid(authPost(user.accessToken(), TEMPLATES + "/" + id + "/apply", "{\"name\": \"Ra mắt 2\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ra mắt 2"))
                .andExpect(jsonPath("$.color").value("violet"))
                .andExpect(jsonPath("$.parentId").isEmpty())
                .andExpect(jsonPath("$.activeTaskCount").value(3)), "$.id");
        List<String> contents = jdbcTemplate.queryForList("select content from tasks where project_id = ? order by content",
                String.class, created);
        assertThat(contents).containsExactly("Duyệt bản nháp", "Họp khởi động", "Viết thông cáo");
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ? and due_date is not null",
                Integer.class, created)).isZero();
        assertThat(jdbcTemplate.queryForObject("select priority from tasks where project_id = ? and content = 'Viết thông cáo'",
                Integer.class, created)).isEqualTo(1);
    }

    @Test
    void applyMine_intoExistingProject_appendsSectionsAndTasks() throws Exception {
        UUID id = save("{\"projectId\": \"%s\"}".formatted(project));
        UUID target = testData.project(user.id(), "Đích");
        UUID existing = testData.section(target, "Có sẵn");
        testData.insert(TaskRow.of(user.id(), target, "Việc cũ").sortOrder(5000));
        authPost(user.accessToken(), TEMPLATES + "/" + id + "/apply", "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.toString()))
                .andExpect(jsonPath("$.activeTaskCount").value(4));
        assertThat(jdbcTemplate.queryForList("select name from sections where project_id = ? order by sort_order",
                String.class, target)).containsExactly("Có sẵn", "Tuần 1");
        assertThat(jdbcTemplate.queryForList("select content from tasks where project_id = ? and section_id is null "
                + "and parent_id is null order by sort_order", String.class, target))
                .containsExactly("Việc cũ", "Viết thông cáo");
        assertThat(existing).isNotNull();

        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 2);
        authPost(user.accessToken(), TEMPLATES + "/" + id + "/apply", "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_SECTIONS"));
        testData.archiveProject(target);
        usageLimits.resetOverrides();
        authPost(user.accessToken(), TEMPLATES + "/" + id + "/apply", "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    @Test
    void library_listsThreeCategories_inRequestLanguage() throws Exception {
        String vi = body(authGet(user.accessToken(), TEMPLATES + "/library").andExpect(status().isOk()).andReturn());
        List<String> categories = JsonPath.read(vi, "$[*].category");
        assertThat(categories).contains("FEATURED", "PRODUCTIVITY_METHODS", "POPULAR");
        String key = JsonPath.read(vi, "$[0].key");
        String viName = JsonPath.read(vi, "$[0].name");

        String en = body(mockMvc.perform(get(TEMPLATES + "/library")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk()).andReturn());
        assertThat((String) JsonPath.read(en, "$[0].key")).isEqualTo(key);
        assertThat((String) JsonPath.read(en, "$[0].name")).isNotEqualTo(viName);

        authGet(user.accessToken(), TEMPLATES + "/library/" + key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("LIBRARY"))
                .andExpect(jsonPath("$.id").value(key))
                .andExpect(jsonPath("$.name").value(viName));
        authGet(user.accessToken(), TEMPLATES + "/library/khong-co").andExpect(status().isNotFound());
    }

    @Test
    void applyLibrary_createsProjectInRequestLanguage() throws Exception {
        String list = body(authGet(user.accessToken(), TEMPLATES + "/library").andReturn());
        String key = JsonPath.read(list, "$[0].key");
        int taskCount = JsonPath.read(list, "$[0].taskCount");
        String enName = JsonPath.read(body(mockMvc.perform(get(TEMPLATES + "/library/" + key)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en")).andReturn()), "$.name");

        mockMvc.perform(post(TEMPLATES + "/library/" + key + "/apply")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(enName))
                .andExpect(jsonPath("$.activeTaskCount").value(taskCount));
        authPost(user.accessToken(), TEMPLATES + "/library/khong-co/apply", "{}").andExpect(status().isNotFound());
    }

    @Test
    void applyNewProject_respectsProjectLimit() throws Exception {
        UUID id = save("{\"projectId\": \"%s\"}".formatted(project));
        usageLimits.overrideMax(LimitKind.ACTIVE_PROJECTS, 1);
        authPost(user.accessToken(), TEMPLATES + "/" + id + "/apply", "{}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("ACTIVE_PROJECTS"));
    }

    @Test
    void deletingAccountRemovesTemplates() throws Exception {
        UUID id = save("{\"projectId\": \"%s\"}".formatted(project));
        jdbcTemplate.update("delete from workspaces where owner_id = ?", user.id());
        jdbcTemplate.update("delete from users where id = ?", user.id());
        assertThat(jdbcTemplate.queryForObject("select count(*) from templates where id = ?", Integer.class, id)).isZero();
    }
}
