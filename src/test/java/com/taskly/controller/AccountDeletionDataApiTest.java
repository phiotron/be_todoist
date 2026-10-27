package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * DELETE me xoá mọi dữ liệu M1 của user (dự án, section, task, sub-task, yêu thích, khoá chống gửi trùng), không đụng user
 * khác. Test khoá khung "xoá workspace rồi xoá user, DB cascade phần còn lại" (spec 5.7, 10.2, 12.1) trên dữ liệu M1.
 */
class AccountDeletionDataApiTest extends IntegrationTestBase {

    /** Id các bản ghi M1 của một user, tạo qua API công khai (spec 12.2 / 12.3). */
    private record Data(String projectId, String sectionId, String taskId, String subtaskId, String idempotencyKey) {
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String accessToken) {
        return builder.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).contentType(MediaType.APPLICATION_JSON);
    }

    private String id(MockHttpServletRequestBuilder request) throws Exception {
        return JsonPath.read(body(mockMvc.perform(request).andExpect(status().isCreated()).andReturn()), "$.id");
    }

    private Data createData(RegisteredUser user) throws Exception {
        String key = UUID.randomUUID().toString();
        String projectId = id(authed(post("/api/v1/projects"), user.accessToken())
                .header("Idempotency-Key", key).content("{\"name\": \"Dự án xoá thử\"}"));
        String sectionId = id(authed(post("/api/v1/projects/" + projectId + "/sections"), user.accessToken())
                .content("{\"name\": \"Phần 1\"}"));
        String taskId = id(authed(post("/api/v1/tasks"), user.accessToken()).content("""
                {"projectId": "%s", "sectionId": "%s", "content": "Việc cha"}
                """.formatted(projectId, sectionId)));
        String subtaskId = id(authed(post("/api/v1/tasks"), user.accessToken()).content("""
                {"projectId": "%s", "parentId": "%s", "content": "Việc con"}
                """.formatted(projectId, taskId)));
        mockMvc.perform(authed(put("/api/v1/projects/" + projectId + "/favorite"), user.accessToken()))
                .andExpect(status().isOk());
        return new Data(projectId, sectionId, taskId, subtaskId, key);
    }

    private int count(String table, String column, String value) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?::uuid", Integer.class, value);
    }

    private int rows(Data data, UUID userId) {
        return count("projects", "id", data.projectId())
                + count("sections", "id", data.sectionId())
                + count("tasks", "id", data.taskId())
                + count("tasks", "id", data.subtaskId())
                + count("favorites", "target_id", data.projectId())
                + jdbcTemplate.queryForObject("select count(*) from idempotency_keys where user_id = ? and idem_key = ?",
                        Integer.class, userId, data.idempotencyKey());
    }

    @Test
    void deleteAccount_removesEveryM1RowOfThatUser_andLeavesOthersUntouched() throws Exception {
        RegisteredUser leaving = registerUser(uniqueEmail());
        RegisteredUser staying = registerUser(uniqueEmail());
        Data leavingData = createData(leaving);
        Data stayingData = createData(staying);
        assertThat(List.of(rows(leavingData, leaving.id()), rows(stayingData, staying.id()))).containsOnly(6);

        mockMvc.perform(authed(delete("/api/v1/me"), leaving.accessToken())
                        .content("{\"password\": \"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(rows(leavingData, leaving.id())).isZero();
        assertThat(rows(stayingData, staying.id())).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from projects p join workspaces w on w.id = p.workspace_id where w.owner_id = ?",
                Integer.class, staying.id())).isEqualTo(2); // Inbox + dự án vừa tạo
    }

    @Test
    void deleteAccount_leavesNoRowOfM1TablesPointingAtTheUser() throws Exception {
        RegisteredUser leaving = registerUser(uniqueEmail());
        createData(leaving);

        mockMvc.perform(authed(delete("/api/v1/me"), leaving.accessToken())
                        .content("{\"password\": \"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        // Quét theo cột tham chiếu users (creator_id ON DELETE SET NULL cũng không được để dòng mồ côi).
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where creator_id = ?",
                Integer.class, leaving.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from favorites where user_id = ?",
                Integer.class, leaving.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from idempotency_keys where user_id = ?",
                Integer.class, leaving.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from tasks t where not exists (select 1 from workspaces w where w.id = t.workspace_id)
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from sections s where not exists (select 1 from projects p where p.id = s.project_id)
                """, Integer.class)).isZero();
    }

    /** Id nhãn, bộ lọc, task gắn nhãn của một user (M2). */
    private record M2Data(String labelId, String filterId, String taskId) {
    }

    private M2Data createM2Data(RegisteredUser user) throws Exception {
        String projectId = id(authed(post("/api/v1/projects"), user.accessToken()).content("{\"name\": \"Dự án M2\"}"));
        String labelId = id(authed(post("/api/v1/labels"), user.accessToken()).content("{\"name\": \"gap\"}"));
        String filterId = id(authed(post("/api/v1/filters"), user.accessToken())
                .content("{\"name\": \"Gấp\", \"query\": \"p1\"}"));
        String taskId = id(authed(post("/api/v1/tasks"), user.accessToken()).content("""
                {"projectId": "%s", "content": "Việc có nhãn", "labelIds": ["%s"]}
                """.formatted(projectId, labelId)));
        mockMvc.perform(authed(put("/api/v1/view-options/TODAY"), user.accessToken())
                        .content("{\"sortBy\": \"PRIORITY\", \"sortDirection\": \"DESC\", \"groupBy\": \"NONE\"}"))
                .andExpect(status().isOk());
        return new M2Data(labelId, filterId, taskId);
    }

    private int m2Rows(M2Data data, UUID userId) {
        return count("labels", "id", data.labelId())
                + count("filters", "id", data.filterId())
                + count("task_labels", "task_id", data.taskId())
                + jdbcTemplate.queryForObject("select count(*) from view_options where user_id = ?",
                        Integer.class, userId);
    }

    @Test
    void deleteAccount_removesEveryM2RowOfThatUser_andLeavesOthersUntouched() throws Exception {
        RegisteredUser leaving = registerUser(uniqueEmail());
        RegisteredUser staying = registerUser(uniqueEmail());
        M2Data leavingData = createM2Data(leaving);
        M2Data stayingData = createM2Data(staying);
        assertThat(List.of(m2Rows(leavingData, leaving.id()), m2Rows(stayingData, staying.id()))).containsOnly(4);

        mockMvc.perform(authed(delete("/api/v1/me"), leaving.accessToken())
                        .content("{\"password\": \"" + FIXTURE_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(m2Rows(leavingData, leaving.id())).isZero();
        assertThat(m2Rows(stayingData, staying.id())).isEqualTo(4);
    }
}
