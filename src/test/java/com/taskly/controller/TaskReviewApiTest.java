package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.M8aApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Chờ duyệt (review): Member / Guest tick hoàn thành ở dự án NHÓM → PENDING; Admin duyệt / trả lại. Dự án cá nhân /
 * chia sẻ cá nhân giữ nguyên hành vi cũ.
 */
class TaskReviewApiTest extends M8aApiTestBase {

    private static final String ADMIN_ONLY = "team-admin-only";

    private record Fx(RegisteredUser admin, RegisteredUser member, RegisteredUser guest, UUID team, UUID project,
                      UUID task) {
    }

    private Fx fixture() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        RegisteredUser guest = newUser();
        UUID team = createTeam(admin, "Nhóm");
        addToTeam(admin, team, member, "MEMBER");
        UUID project = createTeamProject(admin, team, "Dự án nhóm", null);
        authPost(member.accessToken(), "/api/v1/projects/" + project + "/join").andExpect(status().isOk());
        addToProject(admin, project, guest);
        UUID task = createTask(admin, project, "Việc nhóm");
        return new Fx(admin, member, guest, team, project, task);
    }

    private String t(UUID id) {
        return "/api/v1/tasks/" + id;
    }

    private void close(RegisteredUser u, UUID task) throws Exception {
        authPost(u.accessToken(), t(task) + "/close").andExpect(status().isOk());
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String sync(RegisteredUser u, String json) throws Exception {
        return body(authPost(u.accessToken(), "/api/v1/sync", json).andExpect(status().isOk()).andReturn());
    }

    private String cmd(String type, UUID id) {
        return "{\"uuid\": \"" + UUID.randomUUID() + "\", \"type\": \"" + type + "\", \"args\": {\"id\": \"" + id
                + "\"}}";
    }

    @Test
    void memberAndGuestTick_becomesPending_notCompleted() throws Exception {
        Fx fx = fixture();
        for (RegisteredUser u : List.of(fx.member(), fx.guest())) {
            authPost(u.accessToken(), t(fx.task()) + "/close").andExpect(status().isOk())
                    .andExpect(jsonPath("$.task.reviewStatus").value("PENDING"))
                    .andExpect(jsonPath("$.task.reviewRequestedBy").value(u.id().toString()))
                    .andExpect(jsonPath("$.task.reviewRequestedAt").isNotEmpty())
                    .andExpect(jsonPath("$.task.completedAt").isEmpty());
            authPost(u.accessToken(), t(fx.task()) + "/close"); // người yêu cầu tick lại = huỷ → về NONE cho vòng sau
        }
        authGet(fx.admin().accessToken(), t(fx.task())).andExpect(jsonPath("$.reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.reviewRequestedBy").isEmpty()).andExpect(jsonPath("$.reviewRequestedAt").isEmpty());
    }

    @Test
    void adminTick_completesImmediately() throws Exception {
        Fx fx = fixture();
        authPost(fx.admin().accessToken(), t(fx.task()) + "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"));
    }

    @Test
    void memberTickAgain_cancelsRequest_andOtherMemberTickIsNoOp() throws Exception {
        Fx fx = fixture();
        close(fx.member(), fx.task());
        authPost(fx.guest().accessToken(), t(fx.task()) + "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.task.reviewRequestedBy").value(fx.member().id().toString()));
        authPost(fx.member().accessToken(), t(fx.task()) + "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"));
        assertThat(count("select count(*) from activity_log where object_id = ? and event_type = 'REVIEW_CANCELLED'",
                fx.task())).isEqualTo(1);
    }

    @Test
    void reopenOnPending_byRequester_cancels() throws Exception {
        Fx fx = fixture();
        close(fx.member(), fx.task());
        authPost(fx.member().accessToken(), t(fx.task()) + "/reopen", "{\"undo\": true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"));
    }

    @Test
    void memberCannotApproveOrReject_403_adminCan() throws Exception {
        Fx fx = fixture();
        close(fx.member(), fx.task());
        for (String action : List.of("approve", "reject")) {
            authPost(fx.member().accessToken(), t(fx.task()) + "/review/" + action).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ADMIN_ONLY));
            authPost(fx.guest().accessToken(), t(fx.task()) + "/review/" + action).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ADMIN_ONLY));
        }
        authGet(fx.admin().accessToken(), t(fx.task())).andExpect(jsonPath("$.reviewStatus").value("PENDING"));
    }

    @Test
    void adminApprove_completesTask_creditsRequester() throws Exception {
        Fx fx = fixture();
        close(fx.member(), fx.task());
        assertThat(count("select count(*) from karma_events where task_id = ?", fx.task())).isZero();
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/approve").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.task.reviewRequestedBy").isEmpty());
        assertThat(count("select count(*) from karma_events where task_id = ? and user_id = ?", fx.task(),
                fx.member().id())).isEqualTo(1);
        assertThat(count("select count(*) from karma_events where task_id = ? and user_id = ?", fx.task(),
                fx.admin().id())).isZero();
    }

    @Test
    void adminReject_backToNone_stillOpen() throws Exception {
        Fx fx = fixture();
        close(fx.member(), fx.task());
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/reject").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").isEmpty())
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"));
        assertThat(count("select count(*) from karma_events where task_id = ?", fx.task())).isZero();
    }

    @Test
    void approveOrRejectWhenNotPending_409() throws Exception {
        Fx fx = fixture();
        for (String action : List.of("approve", "reject")) {
            authPost(fx.admin().accessToken(), t(fx.task()) + "/review/" + action).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("review-not-pending"));
        }
    }

    @Test
    void recurringTask_staysPendingUntilApproved_thenAdvances() throws Exception {
        Fx fx = fixture();
        UUID rec = readUuid(authPost(fx.admin().accessToken(), "/api/v1/tasks", "{\"content\": \"Họp tuần\", "
                + "\"projectId\": \"" + fx.project() + "\", \"dueDate\": \"2026-10-27\", "
                + "\"recurrenceRule\": \"FREQ=WEEKLY\"}").andExpect(status().isCreated()), "$.id");
        authPost(fx.member().accessToken(), t(rec) + "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-27"));
        authPost(fx.admin().accessToken(), t(rec) + "/review/approve").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.task.completedAt").isEmpty())
                .andExpect(jsonPath("$.task.dueDate").value(not("2026-10-27")));
        assertThat(count("select count(*) from karma_events where task_id = ? and user_id = ?", rec,
                fx.member().id())).isEqualTo(1);
    }

    @Test
    void reopenCompletedTask_memberForbidden_adminOk() throws Exception {
        Fx fx = fixture();
        close(fx.admin(), fx.task());
        for (RegisteredUser u : List.of(fx.member(), fx.guest())) {
            authPost(u.accessToken(), t(fx.task()) + "/reopen").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ADMIN_ONLY));
        }
        authPost(fx.admin().accessToken(), t(fx.task()) + "/reopen").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").isEmpty());
    }

    @Test
    void batchClose_byMember_requestsReview() throws Exception {
        Fx fx = fixture();
        authPost(fx.member().accessToken(), "/api/v1/tasks/batch", "{\"action\": \"CLOSE\", \"taskIds\": [\""
                + fx.task() + "\"]}").andExpect(status().isOk());
        authGet(fx.admin().accessToken(), t(fx.task())).andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.completedAt").isEmpty());
    }

    @Test
    void personalAndSharedPersonalProjects_keepOldBehaviour() throws Exception {
        RegisteredUser owner = newUser();
        RegisteredUser collaborator = newUser();
        UUID project = createProject(owner, "Cá nhân");
        addToProject(owner, project, collaborator);
        UUID task = createTask(owner, project, "Việc");
        authPost(collaborator.accessToken(), t(task) + "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"));
        authPost(collaborator.accessToken(), t(task) + "/reopen").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").isEmpty());
        UUID mine = createTask(owner, project, "Việc 2");
        authPost(owner.accessToken(), t(mine) + "/review/approve").andExpect(status().isForbidden()); // không phải nhóm
        authGet(owner.accessToken(), "/api/v1/tasks?view=review").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void viewReview_scopedPerAdminWorkspace_andMemberSeesOwn() throws Exception {
        Fx a = fixture();
        Fx b = fixture();
        UUID second = createTask(a.admin(), a.project(), "Việc 2");
        close(a.member(), a.task());
        close(a.guest(), second);
        close(b.member(), b.task());
        authGet(a.admin().accessToken(), "/api/v1/tasks?view=review").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].id", hasItem(a.task().toString())))
                .andExpect(jsonPath("$.items[*].id", not(hasItem(b.task().toString()))));
        authGet(a.admin().accessToken(), "/api/v1/tasks?view=review&limit=1").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
        authGet(a.member().accessToken(), "/api/v1/tasks?view=review").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(a.task().toString()));
        authGet(a.admin().accessToken(), "/api/v1/tasks/review/count").andExpect(jsonPath("$.count").value(2));
        authGet(a.member().accessToken(), "/api/v1/tasks/review/count").andExpect(jsonPath("$.count").value(1));
        authGet(b.admin().accessToken(), "/api/v1/tasks/review/count").andExpect(jsonPath("$.count").value(1));
        // PENDING vẫn hiện trong list thường của dự án
        authGet(a.admin().accessToken(), "/api/v1/tasks?projectId=" + a.project())
                .andExpect(jsonPath("$.items[?(@.id=='" + a.task() + "')].reviewStatus").value("PENDING"));
        authPost(a.admin().accessToken(), t(a.task()) + "/review/approve").andExpect(status().isOk());
        authGet(a.admin().accessToken(), "/api/v1/tasks/review/count").andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void notificationsAndActivity_areRecorded() throws Exception {
        Fx fx = fixture();
        close(fx.member(), fx.task());
        authGet(fx.admin().accessToken(), "/api/v1/notifications")
                .andExpect(jsonPath("$.items[0].type").value("REVIEW_REQUESTED"))
                .andExpect(jsonPath("$.items[0].taskId").value(fx.task().toString()))
                .andExpect(jsonPath("$.items[0].actor.id").value(fx.member().id().toString()));
        authGet(fx.member().accessToken(), "/api/v1/notifications").andExpect(jsonPath("$.items", hasSize(0)));
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/reject").andExpect(status().isOk());
        authGet(fx.member().accessToken(), "/api/v1/notifications")
                .andExpect(jsonPath("$.items[0].type").value("REVIEW_REJECTED"))
                .andExpect(jsonPath("$.items[0].actor.id").value(fx.admin().id().toString()));
        close(fx.member(), fx.task());
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/approve").andExpect(status().isOk());
        authGet(fx.member().accessToken(), "/api/v1/notifications")
                .andExpect(jsonPath("$.items[0].type").value("REVIEW_APPROVED"));
        for (String event : List.of("REVIEW_REQUESTED", "REVIEW_APPROVED", "REVIEW_REJECTED")) {
            assertThat(count("select count(*) from activity_log where object_id = ? and event_type = ? "
                    + "and actor_id is not null", fx.task(), event)).as(event).isPositive();
        }
    }

    @Test
    void capabilities_canReview_onlyAdminOfTeamProject() throws Exception {
        Fx fx = fixture();
        String p = "/api/v1/projects/" + fx.project();
        authGet(fx.admin().accessToken(), p).andExpect(jsonPath("$.capabilities.canReview").value(true));
        authGet(fx.member().accessToken(), p).andExpect(jsonPath("$.capabilities.canReview").value(false));
        UUID personal = createProject(fx.admin(), "Riêng");
        authGet(fx.admin().accessToken(), "/api/v1/projects/" + personal)
                .andExpect(jsonPath("$.capabilities.canReview").value(false));
    }

    @Test
    void sync_commandsApproveReject_andSnapshotCarriesFields() throws Exception {
        Fx fx = fixture();
        UUID second = createTask(fx.admin(), fx.project(), "Việc 2");
        close(fx.member(), fx.task());
        close(fx.member(), second);
        String snapshot = sync(fx.admin(), "{\"syncToken\": null}");
        List<String> status = JsonPath.read(snapshot, "$..tasks[?(@.id=='" + fx.task() + "')].reviewStatus");
        assertThat(status).containsExactly("PENDING");
        List<String> by = JsonPath.read(snapshot, "$..tasks[?(@.id=='" + fx.task() + "')].reviewRequestedBy");
        assertThat(by).containsExactly(fx.member().id().toString());

        String body = sync(fx.admin(), "{\"syncToken\": null, \"commands\": [" + cmd("task_review_approve", fx.task())
                + "," + cmd("task_review_reject", second) + "]}");
        assertThat((String) JsonPath.read(body, "$.results[0].status")).isEqualTo("ok");
        assertThat((String) JsonPath.read(body, "$.results[1].status")).isEqualTo("ok");
        authGet(fx.admin().accessToken(), t(fx.task())).andExpect(jsonPath("$.completedAt").isNotEmpty());
        authGet(fx.admin().accessToken(), t(second)).andExpect(jsonPath("$.reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.completedAt").isEmpty());

        // không phải Admin → 403; không PENDING → 409
        String memberCmd = "{\"syncToken\": null, \"commands\": [" + cmd("task_review_approve", second) + "]}";
        String mBody = sync(fx.member(), memberCmd);
        assertThat((Integer) JsonPath.read(mBody, "$.results[0].error.status")).isEqualTo(403);
        assertThat((String) JsonPath.read(mBody, "$.results[0].error.code")).isEqualTo(ADMIN_ONLY);
        String notPending = sync(fx.admin(), memberCmd);
        assertThat((Integer) JsonPath.read(notPending, "$.results[0].error.status")).isEqualTo(409);
        assertThat((String) JsonPath.read(notPending, "$.results[0].error.code")).isEqualTo("review-not-pending");
    }

    @Test
    void sync_memberCloseViaSync_becomesPending_deltaCarriesIt() throws Exception {
        Fx fx = fixture();
        String token = JsonPath.read(sync(fx.admin(), "{\"syncToken\": null}"), "$.syncToken");
        sync(fx.member(), "{\"syncToken\": null, \"commands\": [" + cmd("task_close", fx.task()) + "]}");
        String delta = sync(fx.admin(), "{\"syncToken\": \"" + token + "\"}");
        List<String> status = JsonPath.read(delta, "$..tasks[?(@.id=='" + fx.task() + "')].reviewStatus");
        assertThat(status).containsExactly("PENDING");
    }

    // ---- Hoàn tác "Trả lại" (restore)

    private static final String AT = "2026-10-29T01:02:03Z";

    private String restoreBody(UUID by, String at) {
        return "{\"requestedBy\": \"" + by + "\", \"requestedAt\": \"" + at + "\"}";
    }

    private Fx rejectedFixture() throws Exception {
        Fx fx = fixture();
        close(fx.member(), fx.task());
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/reject").andExpect(status().isOk());
        return fx;
    }

    @Test
    void restore_afterReject_backToPending_logsActivity_removesUnreadRejectedNotification() throws Exception {
        Fx fx = rejectedFixture();
        authGet(fx.member().accessToken(), "/api/v1/notifications")
                .andExpect(jsonPath("$.items[0].type").value("REVIEW_REJECTED"));
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/restore", restoreBody(fx.member().id(), AT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.task.reviewRequestedBy").value(fx.member().id().toString()))
                .andExpect(jsonPath("$.task.reviewRequestedAt").value(AT))
                .andExpect(jsonPath("$.task.completedAt").isEmpty());
        assertThat(count("select count(*) from activity_log where object_id = ? and event_type = 'REVIEW_RESTORED' "
                + "and actor_id = ?", fx.task(), fx.admin().id())).isEqualTo(1);
        assertThat(count("select count(*) from notifications where task_id = ? and type = 'REVIEW_REJECTED'",
                fx.task())).isZero();
        authGet(fx.member().accessToken(), "/api/v1/notifications").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void restore_keepsReadRejectedNotification() throws Exception {
        Fx fx = rejectedFixture();
        jdbcTemplate.update("update notifications set read_at = now() where task_id = ? and type = 'REVIEW_REJECTED'",
                fx.task());
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/restore", restoreBody(fx.member().id(), AT))
                .andExpect(status().isOk());
        assertThat(count("select count(*) from notifications where task_id = ? and type = 'REVIEW_REJECTED'",
                fx.task())).isEqualTo(1);
    }

    @Test
    void restore_memberForbidden() throws Exception {
        Fx fx = rejectedFixture();
        authPost(fx.member().accessToken(), t(fx.task()) + "/review/restore", restoreBody(fx.member().id(), AT))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(ADMIN_ONLY));
    }

    @Test
    void restore_otherWorkspaceAdminForbidden() throws Exception {
        Fx fx = rejectedFixture();
        Fx other = fixture();
        int status = authPost(other.admin().accessToken(), t(fx.task()) + "/review/restore",
                restoreBody(fx.member().id(), AT)).andReturn().getResponse().getStatus();
        assertThat(status).isIn(403, 404);
    }

    @Test
    void restore_completedTask_conflict() throws Exception {
        Fx fx = rejectedFixture();
        close(fx.admin(), fx.task());
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/restore", restoreBody(fx.member().id(), AT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("review-not-restorable"));
    }

    @Test
    void restore_alreadyPending_conflict() throws Exception {
        Fx fx = fixture();
        close(fx.member(), fx.task());
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/restore", restoreBody(fx.member().id(), AT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("review-not-restorable"));
    }

    @Test
    void restore_requesterOutsideWorkspace_orMissing_badRequest() throws Exception {
        Fx fx = rejectedFixture();
        RegisteredUser stranger = newUser();
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/restore", restoreBody(stranger.id(), AT))
                .andExpect(status().isBadRequest());
        authPost(fx.admin().accessToken(), t(fx.task()) + "/review/restore", "{\"requestedAt\": \"" + AT + "\"}")
                .andExpect(status().isBadRequest());
        authGet(fx.admin().accessToken(), t(fx.task())).andExpect(jsonPath("$.reviewStatus").value("NONE"));
    }

    @Test
    void sync_restoreCommand_works_andIsIdempotent() throws Exception {
        Fx fx = rejectedFixture();
        String uuid = UUID.randomUUID().toString();
        String json = "{\"syncToken\": null, \"commands\": [{\"uuid\": \"" + uuid
                + "\", \"type\": \"task_review_restore\", \"args\": {\"taskId\": \"" + fx.task()
                + "\", \"requestedBy\": \"" + fx.member().id() + "\", \"requestedAt\": \"" + AT + "\"}}]}";
        String body = sync(fx.admin(), json);
        assertThat((String) JsonPath.read(body, "$.results[0].status")).isEqualTo("ok");
        authGet(fx.admin().accessToken(), t(fx.task())).andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.reviewRequestedBy").value(fx.member().id().toString()));
        String again = sync(fx.admin(), json);
        assertThat((String) JsonPath.read(again, "$.results[0].status")).isEqualTo("ok");
        assertThat(count("select count(*) from activity_log where object_id = ? and event_type = 'REVIEW_RESTORED'",
                fx.task())).isEqualTo(1);
        String memberBody = sync(fx.member(), json.replace(uuid, UUID.randomUUID().toString()));
        assertThat((Integer) JsonPath.read(memberBody, "$.results[0].error.status")).isEqualTo(403);
    }
}
