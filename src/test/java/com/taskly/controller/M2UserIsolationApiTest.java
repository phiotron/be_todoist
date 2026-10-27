package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.springframework.test.web.servlet.ResultActions;

class M2UserIsolationApiTest extends M2ApiTestBase {

    private record Attempt(String name, ThrowingSupplier<ResultActions> call) {
    }

    private static final String DISPLAY = "{\"sortBy\": \"NAME\", \"sortDirection\": \"ASC\", \"groupBy\": \"NONE\"}";

    @Test
    void otherUsersObjects_are404OnEveryM2Endpoint() throws Throwable {
        var alice = registerUser(uniqueEmail());
        var bob = registerUser(uniqueEmail());
        String b = bob.accessToken();
        UUID label = data.label(alice.id(), "nha");
        UUID filter = data.filter(alice.id(), "Gấp", "p1");
        UUID project = data.project(alice.id(), "Việc riêng");
        UUID task = data.task(project, "Bí mật");
        UUID bobLabel = data.label(bob.id(), "cua-bob");
        UUID bobTask = data.task(data.inboxOf(bob.id()), "Của Bob");

        List<Attempt> attempts = List.of(
                new Attempt("PATCH labels", () -> apiPatch(b, "/api/v1/labels/" + label, "{\"color\": \"red\"}")),
                new Attempt("DELETE labels", () -> apiDelete(b, "/api/v1/labels/" + label)),
                new Attempt("PUT label favorite", () -> apiPut(b, "/api/v1/labels/" + label + "/favorite", null)),
                new Attempt("DELETE label favorite", () -> apiDelete(b, "/api/v1/labels/" + label + "/favorite")),
                new Attempt("PATCH filters", () -> apiPatch(b, "/api/v1/filters/" + filter, "{\"name\": \"x\"}")),
                new Attempt("DELETE filters", () -> apiDelete(b, "/api/v1/filters/" + filter)),
                new Attempt("PUT filter favorite", () -> apiPut(b, "/api/v1/filters/" + filter + "/favorite", null)),
                new Attempt("DELETE filter favorite", () -> apiDelete(b, "/api/v1/filters/" + filter + "/favorite")),
                new Attempt("GET tasks?labelId", () -> apiGet(b, "/api/v1/tasks", "labelId", label.toString())),
                new Attempt("GET tasks?projectId + Display",
                        () -> apiGet(b, "/api/v1/tasks", "projectId", project.toString(), "sort", "name")),
                new Attempt("GET tasks?filterLabelId",
                        () -> apiGet(b, "/api/v1/tasks", "view", "today", "filterLabelId", label.toString())),
                new Attempt("PUT view-options label", () -> apiPut(b, "/api/v1/view-options/label:" + label, DISPLAY)),
                new Attempt("PUT view-options filter", () -> apiPut(b, "/api/v1/view-options/filter:" + filter, DISPLAY)),
                new Attempt("PUT view-options project", () -> apiPut(b, "/api/v1/view-options/project:" + project, DISPLAY)),
                new Attempt("PUT view-options filterLabelId", () -> apiPut(b, "/api/v1/view-options/TODAY",
                        "{\"sortBy\": \"DATE\", \"sortDirection\": \"ASC\", \"groupBy\": \"NONE\", \"filterLabelId\": \""
                                + label + "\"}")),
                new Attempt("DELETE view-options label", () -> apiDelete(b, "/api/v1/view-options/label:" + label)),
                new Attempt("reorder LABEL", () -> apiPost(b, "/api/v1/reorder",
                        "{\"type\": \"LABEL\", \"id\": \"" + label + "\", \"afterId\": null}")),
                new Attempt("reorder FILTER", () -> apiPost(b, "/api/v1/reorder",
                        "{\"type\": \"FILTER\", \"id\": \"" + filter + "\", \"afterId\": null}")),
                new Attempt("batch ADD_LABELS on foreign task", () -> apiPost(b, "/api/v1/tasks/batch",
                        "{\"action\": \"ADD_LABELS\", \"taskIds\": [\"" + task + "\"], \"labelIds\": [\"" + bobLabel + "\"]}")),
                new Attempt("batch ADD_LABELS with foreign label", () -> apiPost(b, "/api/v1/tasks/batch",
                        "{\"action\": \"ADD_LABELS\", \"taskIds\": [\"" + bobTask + "\"], \"labelIds\": [\"" + label + "\"]}")),
                new Attempt("POST tasks with foreign label", () -> apiPost(b, "/api/v1/tasks",
                        "{\"content\": \"x\", \"labelIds\": [\"" + label + "\"]}")));

        for (Attempt attempt : attempts) {
            attempt.call().get().andExpect(status().isNotFound());
        }

        // dữ liệu của Alice nguyên vẹn
        apiGet(alice.accessToken(), "/api/v1/labels").andExpect(jsonPath("$[0].name").value("nha"))
                .andExpect(jsonPath("$[0].color").value("charcoal")).andExpect(jsonPath("$[0].isFavorite").value(false));
        apiGet(alice.accessToken(), "/api/v1/filters").andExpect(jsonPath("$[0].name").value("Gấp"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from task_labels where task_id in (?, ?)",
                Integer.class, task, bobTask)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from view_options where user_id in (?, ?)",
                Integer.class, alice.id(), bob.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ?", Integer.class,
                data.inboxOf(bob.id()))).isEqualTo(1);
    }

    @Test
    void searchAndListings_neverLeakOtherUsersData() throws Exception {
        var alice = registerUser(uniqueEmail());
        var bob = registerUser(uniqueEmail());
        String b = bob.accessToken();
        UUID aliceLabel = data.label(alice.id(), "nha");
        UUID aliceProject = data.project(alice.id(), "Dự án bí mật");
        data.tag(data.insert(TaskSpec.of(aliceProject, "Họp bí mật")), aliceLabel);
        data.filter(alice.id(), "Họp bí mật", "p1");
        data.section(aliceProject, "Section bí mật");
        UUID bobLabel = data.label(bob.id(), "nha");   // cùng tên label với Alice
        UUID bobTask = data.task(data.inboxOf(bob.id()), "Họp của Bob");
        data.tag(bobTask, bobLabel);

        assertThat(contents(getOk(b, "/api/v1/search", "q", "hop"))).containsExactly("Họp của Bob");
        assertThat(contents(getOk(b, "/api/v1/search", "q", "bi mat"))).isEmpty();
        apiGet(b, "/api/v1/search/objects", "q", "bi mat").andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").isEmpty()).andExpect(jsonPath("$.sections").isEmpty())
                .andExpect(jsonPath("$.labels").isEmpty()).andExpect(jsonPath("$.filters").isEmpty());
        assertThat(contents(getOk(b, "/api/v1/tasks", "filter", "search: bi mat"))).isEmpty();
        assertThat(contents(getOk(b, "/api/v1/tasks", "filter", "#Dự án bí mật"))).isEmpty();
        assertThat(contents(getOk(b, "/api/v1/tasks", "filter", "##Dự án*"))).isEmpty();
        assertThat(contents(getOk(b, "/api/v1/tasks", "filter", "@nha"))).containsExactly("Họp của Bob");
        assertThat(contents(getOk(b, "/api/v1/tasks", "labelId", bobLabel.toString()))).containsExactly("Họp của Bob");
        assertThat(contents(getOk(b, "/api/v1/tasks", "filter", "no date", "limit", "200"))).containsExactly("Họp của Bob");
    }

    /** Spec 12.11, 18.1 C8: `id` của người khác → 409 id-conflict, không lộ gì về bản ghi đang giữ id. */
    @Test
    void clientIdOfAnotherUser_is409IdConflict_andNeverLeaksTheOwnersRecord() throws Exception {
        var alice = registerUser(uniqueEmail());
        var bob = registerUser(uniqueEmail());
        UUID label = data.label(alice.id(), "nha");
        UUID filter = data.filter(alice.id(), "Gấp", "p1");

        apiPost(bob.accessToken(), "/api/v1/labels", "{\"id\": \"" + label + "\", \"name\": \"nha\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id-conflict"))
                .andExpect(jsonPath("$.name").doesNotExist());
        apiPost(bob.accessToken(), "/api/v1/filters",
                "{\"id\": \"" + filter + "\", \"name\": \"Gấp\", \"query\": \"p1\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id-conflict"))
                .andExpect(jsonPath("$.query").doesNotExist());
        apiGet(bob.accessToken(), "/api/v1/labels").andExpect(jsonPath("$.length()").value(0));
        apiGet(bob.accessToken(), "/api/v1/filters").andExpect(jsonPath("$.length()").value(0));
        apiGet(alice.accessToken(), "/api/v1/labels").andExpect(jsonPath("$[0].name").value("nha"));
    }
}
