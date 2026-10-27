package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * `id` UUID tuỳ chọn do client sinh (spec 12.11, chuẩn bị cho M9): kiểm ĐẦU TIÊN (trước 404 / 400 / 409 / 422 của bảng
 * quyết định). Chưa có → tạo, 201. Cùng người + cùng nội dung → 200 + Idempotent-Replay: true, KHÔNG ghi, KHÔNG
 * đếm lại giới hạn. Khác nội dung hoặc id của người khác → 409 id-conflict (không lộ gì thêm).
 */
class TaskCreateIdApiTest extends IntegrationTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private int rowsWithId(UUID id) {
        return jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, id);
    }

    @Test
    void withClientId_createsWithThatId_201() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = UUID.randomUUID();

        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"content\": \"Tạo offline\"}".formatted(id))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.version").value(0));
        assertThat(rowsWithId(id)).isOne();
    }

    @Test
    void sameIdSameContent_returns200WithReplayHeader_noSecondRow_noLimitCount_afterIdIgnored() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 1);
        UUID id = UUID.randomUUID();
        String body = """
                {"id": "%s", "projectId": "%s", "content": "Một", "description": "mô tả", "priority": 2,
                 "dueDatetime": "2026-10-25T09:00", "durationMinutes": 30, "deadlineDate": "2026-10-31"%s}
                """;

        authPost(user.accessToken(), TASKS, body.formatted(id, project, ""))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"));
        // dự án đã đầy (1/1), afterId khác đi cũng không sao: phát lại không đếm lại giới hạn, không ghi
        authPost(user.accessToken(), TASKS, body.formatted(id, project, ", \"afterId\": null"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.content").value("Một"));

        assertThat(rowsWithId(id)).isOne();
        authPost(user.accessToken(), TASKS, "{\"projectId\": \"%s\", \"content\": \"Khác\"}".formatted(project))
                .andExpect(status().is(422)) // xác nhận dự án thật sự đã đầy
                .andExpect(jsonPath("$.code").value("limit-reached"));
    }

    @Test
    void sameIdDifferentContent_is409IdConflict() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID id = UUID.randomUUID();
        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"content\": \"Gốc\", \"priority\": 2}".formatted(id))
                .andExpect(status().isCreated());

        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"content\": \"Khác\", \"priority\": 2}".formatted(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict"));
        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"content\": \"Gốc\", \"priority\": 3}".formatted(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict"));
        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"projectId\": \"%s\", \"content\": \"Gốc\", \"priority\": 2}"
                .formatted(id, project))
                .andExpect(status().isConflict()) // cùng nội dung nhưng khác dự án
                .andExpect(jsonPath("$.code").value("id-conflict"));
        assertThat(rowsWithId(id)).isOne();
    }

    @Test
    void idOfAnotherUser_is409IdConflict_withTheSameAnswerAsDifferentContent_noLeak() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID id = UUID.randomUUID();
        authPost(bob.accessToken(), TASKS, "{\"id\": \"%s\", \"content\": \"Của Bob\"}".formatted(id))
                .andExpect(status().isCreated());

        String sameContent = readString(authPost(alice.accessToken(), TASKS,
                "{\"id\": \"%s\", \"content\": \"Của Bob\"}".formatted(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict")), "$.detail");
        String otherContent = readString(authPost(alice.accessToken(), TASKS,
                "{\"id\": \"%s\", \"content\": \"Nội dung khác\"}".formatted(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("id-conflict")), "$.detail");

        assertThat(sameContent).isEqualTo(otherContent); // không phân biệt "id của người khác" với "khác nội dung"
        assertThat(rowsWithId(id)).isOne();
    }

    @Test
    void idMustBeAUuid_otherwise400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPost(user.accessToken(), TASKS, "{\"id\": \"khong-phai-uuid\", \"content\": \"x\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void replayMatchesDefaultsAndSubtasks_projectAndSectionDerivedFromParentOrInbox() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        UUID project = testData.project(user.id(), "P");
        UUID section = testData.section(project, "S");
        UUID parent = testData.insert(TaskRow.of(user.id(), project, "Cha").section(section));
        UUID inboxTaskId = UUID.randomUUID();
        UUID subtaskId = UUID.randomUUID();

        // vắng projectId = Inbox: phát lại bằng projectId Inbox tường minh vẫn là CÙNG nội dung
        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"content\": \"Inbox\"}".formatted(inboxTaskId))
                .andExpect(status().isCreated());
        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"projectId\": \"%s\", \"content\": \"Inbox\"}"
                .formatted(inboxTaskId, inbox))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        // sub-task: project / section suy ra từ cha
        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"parentId\": \"%s\", \"content\": \"Con\"}"
                .formatted(subtaskId, parent))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sectionId").value(section.toString()));
        authPost(user.accessToken(), TASKS, "{\"id\": \"%s\", \"parentId\": \"%s\", \"content\": \"Con\"}"
                .formatted(subtaskId, parent))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
    }

    @Test
    void idCheckComesFirst_replayStillWorksAfterTheProjectWasArchived() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID id = UUID.randomUUID();
        String body = "{\"id\": \"%s\", \"projectId\": \"%s\", \"content\": \"Offline\"}".formatted(id, project);
        authPost(user.accessToken(), TASKS, body).andExpect(status().isCreated());
        testData.archiveProject(project);

        authPost(user.accessToken(), TASKS, body) // dòng 1 của bảng quyết định đứng trước 422 target-archived
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id.toString()));
    }
}
