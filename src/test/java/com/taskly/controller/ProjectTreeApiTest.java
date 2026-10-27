package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Cây mẫu (tạo qua API):  A ─── A1 ─── A11 ;  B ;  C ─── C1 */
class ProjectTreeApiTest extends IntegrationTestBase {

    private static final String PROJECTS = "/api/v1/projects";

    private RegisteredUser user;
    private UUID a;
    private UUID a1;
    private UUID a11;
    private UUID b;
    private UUID c1;

    private UUID create(String name, UUID parentId) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        return readUuid(authPost(user.accessToken(), PROJECTS, "{\"name\": \"%s\", \"parentId\": %s}"
                .formatted(name, parent)).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions move(String token, UUID projectId, String json) throws Exception {
        return authPost(token, PROJECTS + "/" + projectId + "/move", json);
    }

    @BeforeEach
    void tree() throws Exception {
        user = registerUser(uniqueEmail());
        a = create("A", null);
        a1 = create("A1", a);
        a11 = create("A11", a1);
        b = create("B", null);
        UUID c = create("C", null);
        c1 = create("C1", c);
    }

    @Test
    void move_changesParent_appendsToNewContainer_andSubtreeFollows() throws Exception {
        move(user.accessToken(), a1, "{\"parentId\": \"%s\"}".formatted(b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(b.toString()))
                .andExpect(jsonPath("$.sortOrder").value(1024));
        authGet(user.accessToken(), PROJECTS + "/" + a11).andExpect(jsonPath("$.parentId").value(a1.toString()));
        authGet(user.accessToken(), PROJECTS)
                .andExpect(jsonPath("$[*].name", contains("Inbox", "A", "B", "A1", "A11", "C", "C1")));
    }

    @Test
    void move_toRootAfterSibling() throws Exception {
        move(user.accessToken(), a11, "{\"parentId\": null, \"afterId\": \"%s\"}".formatted(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(nullValue()));
        authGet(user.accessToken(), PROJECTS)
                .andExpect(jsonPath("$[*].name", contains("Inbox", "A", "A1", "A11", "B", "C", "C1")));
        long after = readLong(authGet(user.accessToken(), PROJECTS + "/" + a11), "$.sortOrder");
        assertThat(after).isGreaterThan(readLong(authGet(user.accessToken(), PROJECTS + "/" + a), "$.sortOrder"))
                .isLessThan(readLong(authGet(user.accessToken(), PROJECTS + "/" + b), "$.sortOrder"));
    }

    @Test
    void move_parentIdKeyIsRequired_andAfterIdNullGoesToTop() throws Exception {
        move(user.accessToken(), a11, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("parentId"))
                .andExpect(jsonPath("$.errors[0].message").value("Thiếu parentId (gửi null để chuyển thành dự án gốc)"));
        move(user.accessToken(), b, "{\"parentId\": null, \"afterId\": null}") // null tường minh = đầu vùng chứa gốc
                .andExpect(status().isOk());
        authGet(user.accessToken(), PROJECTS)
                .andExpect(jsonPath("$[0].name").value("Inbox"))
                .andExpect(jsonPath("$[1].name").value("B"));
    }

    @Test
    void move_rules_depthCycleInboxArchivedForeign() throws Exception {
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        String under = "{\"parentId\": \"%s\"}";

        move(user.accessToken(), a1, under.formatted(c1)) // A1 cao 2, C1 ở cấp 2 → cấp 4
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Dự án con tối đa 3 cấp"));
        move(user.accessToken(), a, under.formatted(a11))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Không thể đặt dự án làm con của chính nó hoặc của dự án con của nó"));
        move(user.accessToken(), b, under.formatted(inbox))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Inbox không thể làm dự án cha hay dự án con"));
        move(user.accessToken(), inbox, under.formatted(b)).andExpect(status().isForbidden());
        move(user.accessToken(), b, under.formatted(testData.project(bob.id(), "Của Bob"))).andExpect(status().isNotFound());
        move(bob.accessToken(), b, "{\"parentId\": null}").andExpect(status().isNotFound());

        UUID archived = testData.project(user.id(), "Lưu trữ");
        testData.archiveProject(archived);
        move(user.accessToken(), b, under.formatted(archived))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));
        move(user.accessToken(), archived, "{\"parentId\": null}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }
}
