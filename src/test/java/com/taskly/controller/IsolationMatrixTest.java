package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Mọi endpoint có id: người ngoài nhận 404 (không lộ id có tồn tại) và dữ liệu của chủ không đổi (BR-01). */
class IsolationMatrixTest extends IntegrationTestBase {

    @FunctionalInterface
    private interface Call {
        ResultActions perform() throws Exception;
    }

    @Test
    void outsiderGets404OnEveryEndpointWithForeignIds() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        String t = bob.accessToken();
        UUID project = testData.project(alice.id(), "Của Alice");
        UUID child = testData.childProject(alice.id(), project, "Con");
        UUID section = testData.section(project, "S");
        UUID archivableSection = testData.section(project, "S2");
        UUID task = testData.task(alice.id(), project, "T");
        UUID overdue = testData.insert(TaskRow.of(alice.id(), project, "Quá hạn").dueDate(LocalDate.of(2020, 1, 1)));
        testData.insert(TaskRow.of(alice.id(), project, "Xong của Alice").completedAt(clock.instant()));
        UUID bobInbox = testData.inboxOf(bob.id());
        String p = "/api/v1/projects/" + project;
        String s = p + "/sections/" + section;
        String tk = "/api/v1/tasks/" + task;

        Map<String, Call> calls = new LinkedHashMap<>();
        calls.put("GET project", () -> authGet(t, p));
        calls.put("PATCH project", () -> authPatch(t, p, "{\"name\": \"x\"}"));
        calls.put("DELETE project", () -> authDelete(t, p));
        calls.put("move project", () -> authPost(t, p + "/move", "{\"parentId\": null}"));
        calls.put("archive project", () -> authPost(t, p + "/archive"));
        calls.put("unarchive project", () -> authPost(t, p + "/unarchive"));
        calls.put("PUT project favorite", () -> authPut(t, p + "/favorite"));
        calls.put("DELETE project favorite", () -> authDelete(t, p + "/favorite"));
        calls.put("POST project under foreign parent", () -> authPost(t, "/api/v1/projects",
                "{\"name\": \"x\", \"parentId\": \"%s\"}".formatted(project)));
        calls.put("move sub-project", () -> authPost(t, "/api/v1/projects/" + child + "/move", "{\"parentId\": null}"));
        calls.put("GET sections", () -> authGet(t, p + "/sections"));
        calls.put("POST section", () -> authPost(t, p + "/sections", "{\"name\": \"x\"}"));
        calls.put("PATCH section", () -> authPatch(t, s, "{\"name\": \"x\"}"));
        calls.put("DELETE section", () -> authDelete(t, s));
        calls.put("archive section", () -> authPost(t, p + "/sections/" + archivableSection + "/archive"));
        calls.put("unarchive section", () -> authPost(t, p + "/sections/" + archivableSection + "/unarchive"));
        calls.put("GET task", () -> authGet(t, tk));
        calls.put("PATCH task", () -> authPatch(t, tk, "{\"version\": 0, \"content\": \"x\"}"));
        calls.put("DELETE task", () -> authDelete(t, tk));
        calls.put("close", () -> authPost(t, tk + "/close"));
        calls.put("reopen", () -> authPost(t, tk + "/reopen"));
        calls.put("reopen undo", () -> authPost(t, tk + "/reopen", "{\"undo\": true}"));
        calls.put("move task", () -> authPost(t, tk + "/move", "{\"projectId\": \"%s\"}".formatted(bobInbox)));
        calls.put("duplicate", () -> authPost(t, tk + "/duplicate"));
        calls.put("subtasks", () -> authGet(t, tk + "/subtasks"));
        calls.put("tasks?projectId", () -> authGet(t, "/api/v1/tasks?projectId=" + project));
        calls.put("tasks?projectId&completed", () -> authGet(t, "/api/v1/tasks?projectId=" + project + "&completed=true"));
        calls.put("POST task into foreign project", () -> authPost(t, "/api/v1/tasks",
                "{\"projectId\": \"%s\", \"content\": \"x\"}".formatted(project)));
        calls.put("POST task under foreign parent", () -> authPost(t, "/api/v1/tasks",
                "{\"projectId\": \"%s\", \"parentId\": \"%s\", \"content\": \"x\"}".formatted(bobInbox, task)));
        calls.put("batch CLOSE", () -> authPost(t, "/api/v1/tasks/batch",
                "{\"action\": \"CLOSE\", \"taskIds\": [\"%s\"]}".formatted(task)));
        calls.put("batch DELETE", () -> authPost(t, "/api/v1/tasks/batch",
                "{\"action\": \"DELETE\", \"taskIds\": [\"%s\"]}".formatted(task)));
        calls.put("batch MOVE", () -> authPost(t, "/api/v1/tasks/batch",
                "{\"action\": \"MOVE\", \"projectId\": \"%s\", \"taskIds\": [\"%s\"]}".formatted(bobInbox, task)));
        calls.put("reorder PROJECT", () -> authPost(t, "/api/v1/reorder",
                "{\"type\": \"PROJECT\", \"id\": \"%s\", \"afterId\": null}".formatted(project)));
        calls.put("reorder SECTION", () -> authPost(t, "/api/v1/reorder",
                "{\"type\": \"SECTION\", \"id\": \"%s\", \"afterId\": null}".formatted(section)));
        calls.put("reorder TASK", () -> authPost(t, "/api/v1/reorder",
                "{\"type\": \"TASK\", \"id\": \"%s\", \"afterId\": null}".formatted(task)));

        SoftAssertions soft = new SoftAssertions();
        for (Map.Entry<String, Call> call : calls.entrySet()) {
            int status = call.getValue().perform().andReturn().getResponse().getStatus();
            soft.assertThat(status).as(call.getKey()).isEqualTo(404);
        }
        soft.assertAll();

        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ? and content = 'T' "
                + "and completed_at is null", Integer.class, task)).isOne();
        assertThat(jdbcTemplate.queryForObject("select name from projects where id = ?", String.class, project))
                .isEqualTo("Của Alice");
        assertThat(jdbcTemplate.queryForObject("select count(*) from sections where project_id = ?", Integer.class, project))
                .isEqualTo(2);

        // Màn Completed toàn cục (view=completed) chỉ gồm task của chính người gọi (spec 12.3)
        authGet(t, "/api/v1/tasks?view=completed").andExpect(status().isOk()).andExpect(jsonPath("$.items", empty()));

        // Reschedule chỉ động tới task của chính người gọi: Bob gọi, task quá hạn của Alice không đổi.
        authPost(t, "/api/v1/tasks/overdue/reschedule", "{\"date\": \"2030-01-01\"}").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select due_date from tasks where id = ?", java.sql.Date.class, overdue))
                .hasToString("2020-01-01");
    }
}
