package com.taskly.controller;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.Texts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import com.taskly.repository.TaskLabelRepository;
import com.taskly.support.M2ApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** POST /api/v1/tasks/batch ADD_LABELS / REMOVE_LABELS (spec 12.5). */
class TaskBatchLabelsApiTest extends M2ApiTestBase {

    @Autowired
    private TaskLabelRepository taskLabelRepository;

    private RegisteredUser user;
    private String token;
    private UUID inbox;
    private UUID home;
    private UUID urgent;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser(uniqueEmail());
        token = user.accessToken();
        inbox = data.inboxOf(user.id());
        home = data.label(user.id(), "nha");
        urgent = data.label(user.id(), "gap");
    }

    private ResultActions batch(String action, List<UUID> taskIds, String extra) throws Exception {
        return apiPost(token, "/api/v1/tasks/batch", body(action, taskIds, extra));
    }

    private String body(String action, List<UUID> taskIds, String extra) {
        StringBuilder ids = new StringBuilder();
        for (UUID id : taskIds) {
            ids.append(ids.length() == 0 ? "" : ", ").append('"').append(id).append('"');
        }
        return "{\"action\": \"" + action + "\", \"taskIds\": [" + ids + "]" + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }

    private List<UUID> labelsOf(UUID task) {
        return jdbcTemplate.queryForList("select label_id from task_labels where task_id = ?", UUID.class, task);
    }

    private long version(UUID task) {
        return jdbcTemplate.queryForObject("select version from tasks where id = ?", Long.class, task);
    }

    @Test
    void addLabels_addsToEveryTask_returnsAllIds_andBumpsOnlyChangedTasks() throws Exception {
        UUID tagged = data.task(inbox, "Đã có nhãn");
        UUID plain = data.task(inbox, "Chưa có");
        data.tag(tagged, home);
        long taggedVersion = version(tagged);
        long plainVersion = version(plain);

        batch("ADD_LABELS", List.of(tagged, plain), "\"labelIds\": [\"" + home + "\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", contains(tagged.toString(), plain.toString())));

        assertThat(labelsOf(tagged)).containsExactly(home);
        assertThat(labelsOf(plain)).containsExactly(home);
        assertThat(version(tagged)).isEqualTo(taggedVersion);
        assertThat(version(plain)).isEqualTo(plainVersion + 1);

        // gửi lại (retry sau timeout): idempotent — không đổi gì thêm, version không tăng nữa
        batch("ADD_LABELS", List.of(tagged, plain), "\"labelIds\": [\"" + home + "\"]").andExpect(status().isOk());
        assertThat(version(plain)).isEqualTo(plainVersion + 1);
    }

    @Test
    void addLabels_twoLabelsAtOnce_bumpsVersionOnlyOncePerTask() throws Exception {
        UUID task = data.task(inbox, "Việc");
        long before = version(task);

        batch("ADD_LABELS", List.of(task), "\"labelIds\": [\"" + home + "\", \"" + urgent + "\", \"" + home + "\"]")
                .andExpect(status().isOk());

        assertThat(labelsOf(task)).containsExactlyInAnyOrder(home, urgent);
        assertThat(version(task)).isEqualTo(before + 1);
    }

    @Test
    void removeLabels_removesOnlyGivenLabels_andBumpsOnlyChangedTasks() throws Exception {
        UUID task = data.task(inbox, "Việc");
        UUID untouched = data.task(inbox, "Không có nhãn nhà");
        data.tag(task, home, urgent);
        data.tag(untouched, urgent);
        long untouchedVersion = version(untouched);
        long taskVersion = version(task);

        batch("REMOVE_LABELS", List.of(task, untouched), "\"labelIds\": [\"" + home + "\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", contains(task.toString(), untouched.toString())));

        assertThat(labelsOf(task)).containsExactly(urgent);
        assertThat(labelsOf(untouched)).containsExactly(urgent);
        assertThat(version(task)).isEqualTo(taskVersion + 1);
        assertThat(version(untouched)).isEqualTo(untouchedVersion);
    }

    @Test
    void addLabels_overLimitOnAnyTask_rollsBackWholeBatch() throws Exception {
        UUID third = data.label(user.id(), "ba");
        UUID fourth = data.label(user.id(), "bon");
        UUID full = data.task(inbox, "Đủ 3 nhãn");
        UUID empty = data.task(inbox, "Trống");
        data.tag(full, home, urgent, third);
        long emptyVersion = version(empty);

        batch("ADD_LABELS", List.of(empty, full), "\"labelIds\": [\"" + fourth + "\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABELS_PER_TASK)));

        assertThat(labelsOf(empty)).isEmpty();
        assertThat(labelsOf(full)).hasSize(3);
        assertThat(version(empty)).isEqualTo(emptyVersion);
    }

    @Test
    void labelIds_areRequiredForLabelActions_andForbiddenForOthers() throws Exception {
        UUID task = data.task(inbox, "Việc");

        batch("ADD_LABELS", List.of(task), "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.BATCH_LABEL_IDS)));
        batch("REMOVE_LABELS", List.of(task), "\"labelIds\": []").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.BATCH_LABEL_IDS)));
        batch("ADD_LABELS", List.of(task), "\"labelIds\": null").andExpect(status().isBadRequest());
        batch("PRIORITY", List.of(task), "\"priority\": 1, \"labelIds\": [\"" + home + "\"]")
                .andExpect(status().isBadRequest());
        batch("ADD_LABELS", List.of(task), "\"labelIds\": [\"" + home + "\"], \"priority\": 1")
                .andExpect(status().isBadRequest());
        assertThat(labelsOf(task)).isEmpty();
    }

    @Test
    void emptyTaskIds_is400_andDuplicateTaskIds_is400() throws Exception {
        UUID task = data.task(inbox, "Việc");

        batch("ADD_LABELS", List.of(), "\"labelIds\": [\"" + home + "\"]").andExpect(status().isBadRequest());
        batch("ADD_LABELS", List.of(task, task), "\"labelIds\": [\"" + home + "\"]").andExpect(status().isBadRequest());
        assertThat(labelsOf(task)).isEmpty();
    }

    @Test
    void otherUsersLabel_is404_andNothingChanges() throws Exception {
        UUID task = data.task(inbox, "Việc");
        UUID foreign = data.label(registerUser(uniqueEmail()).id(), "x");
        long before = version(task);

        batch("ADD_LABELS", List.of(task), "\"labelIds\": [\"" + home + "\", \"" + foreign + "\"]")
                .andExpect(status().isNotFound());
        batch("REMOVE_LABELS", List.of(task), "\"labelIds\": [\"" + foreign + "\"]").andExpect(status().isNotFound());

        assertThat(labelsOf(task)).isEmpty();
        assertThat(version(task)).isEqualTo(before);
    }

    @Test
    void unknownTask_is404_andNothingChanges() throws Exception {
        UUID task = data.task(inbox, "Việc");

        batch("ADD_LABELS", List.of(task, UUID.randomUUID()), "\"labelIds\": [\"" + home + "\"]")
                .andExpect(status().isNotFound());

        assertThat(labelsOf(task)).isEmpty();
    }

    @Test
    void taskInArchivedProject_is409_forBothActions() throws Exception {
        UUID project = data.project(user.id(), "Lưu trữ");
        UUID task = data.task(project, "Việc cũ");
        data.tag(task, urgent);
        data.archiveProject(project);

        batch("ADD_LABELS", List.of(task), "\"labelIds\": [\"" + home + "\"]").andExpect(status().isConflict());
        batch("REMOVE_LABELS", List.of(task), "\"labelIds\": [\"" + urgent + "\"]").andExpect(status().isConflict());
        assertThat(labelsOf(task)).containsExactly(urgent);
    }

    @Test
    void sameIdempotencyKey_replaysWithoutBumpingVersionAgain() throws Exception {
        UUID task = data.task(inbox, "Việc");
        long before = version(task);
        String json = body("ADD_LABELS", List.of(task), "\"labelIds\": [\"" + home + "\"]");

        apiPostWithKey(token, "/api/v1/tasks/batch", "k-13-1", json).andExpect(status().isOk());
        apiPostWithKey(token, "/api/v1/tasks/batch", "k-13-1", json).andExpect(status().isOk());

        assertThat(version(task)).isEqualTo(before + 1);
    }

    @Test
    void repository_emptyCollections_doNothingInsteadOfFailing() {
        UUID task = data.task(inbox, "Việc");

        assertThat(taskLabelRepository.addToTasks(List.of(), List.of(home))).isEmpty();
        assertThat(taskLabelRepository.addToTasks(List.of(task), List.of())).isEmpty();
        assertThat(taskLabelRepository.removeFromTasks(List.of(), List.of(home))).isEmpty();
        assertThat(taskLabelRepository.removeFromTasks(List.of(task), List.of())).isEmpty();
    }
}
