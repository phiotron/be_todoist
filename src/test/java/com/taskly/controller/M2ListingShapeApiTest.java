package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mọi danh sách M2 trả TaskResponse đầy đủ: labelIds theo thứ tự label, projectName, parentContent. */
class M2ListingShapeApiTest extends M2ApiTestBase {

    @Test
    void everyListingCarriesLabelIdsInLabelOrder() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID first = data.label(user.id(), "gap", 1_000);
        UUID second = data.label(user.id(), "nha", 2_000);
        UUID work = data.project(user.id(), "Work");
        UUID task = data.insert(TaskSpec.of(work, "Họp tuần").dueDate(LocalDate.of(2026, 11, 4)));
        data.tag(task, second, first);
        String labels = "$.items[0].labelIds";

        apiGet(token, "/api/v1/search", "q", "hop").andExpect(status().isOk())
                .andExpect(jsonPath(labels, contains(first.toString(), second.toString())))
                .andExpect(jsonPath("$.items[0].projectName").value("Work"));
        apiGet(token, "/api/v1/tasks", "labelId", second.toString())
                .andExpect(jsonPath(labels, contains(first.toString(), second.toString())));
        apiGet(token, "/api/v1/tasks", "filter", "@gap")
                .andExpect(jsonPath(labels, contains(first.toString(), second.toString())));
        apiGet(token, "/api/v1/tasks", "view", "today", "sort", "name")
                .andExpect(jsonPath(labels, contains(first.toString(), second.toString())));
        apiGet(token, "/api/v1/tasks", "projectId", work.toString(), "sort", "name")
                .andExpect(jsonPath(labels, contains(first.toString(), second.toString())));
        apiGet(token, "/api/v1/tasks/" + task).andExpect(jsonPath("$.labelIds",
                contains(first.toString(), second.toString())));
    }

    @Test
    void completedSearchAndDeletedLabelsAreReflected() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID label = data.label(user.id(), "nha");
        UUID done = data.insert(TaskSpec.of(data.inboxOf(user.id()), "Việc xong")
                .completedAt(Instant.parse("2026-10-01T00:00:00Z")));
        data.tag(done, label);

        apiGet(token, "/api/v1/search", "q", "viec", "completed", "true")
                .andExpect(jsonPath("$.items[0].labelIds", contains(label.toString())));
        apiDelete(token, "/api/v1/labels/" + label).andExpect(status().isNoContent());
        apiGet(token, "/api/v1/search", "q", "viec", "completed", "true")
                .andExpect(jsonPath("$.items[0].labelIds").isEmpty());
    }
}
