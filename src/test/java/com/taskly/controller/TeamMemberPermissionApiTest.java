package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.M8aApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PERM — quy tắc người dùng chốt 28/10 (KHÁC Todoist): trong dự án thuộc workspace NHÓM (Team / Restricted), chỉ Quản
 * trị (ADMIN) được sửa; Thành viên (MEMBER) và Khách (GUEST) chỉ xem + đánh dấu hoàn thành / mở lại. Mọi ghi khác → 403
 * code team-admin-only. Dự án cá nhân được chia sẻ giữ nguyên (cộng tác viên sửa được). Người ngoài → 404.
 */
class TeamMemberPermissionApiTest extends M8aApiTestBase {

    private static final String TEAM_ADMIN_ONLY = "team-admin-only";

    /** Team T: admin (tạo dự án P, task, section, comment), member đã Join P, guest được mời vào P, outsider. */
    private record Fx(RegisteredUser admin, RegisteredUser member, RegisteredUser guest, RegisteredUser outsider,
                      UUID team, UUID project, UUID task, UUID section, UUID comment) {
        List<RegisteredUser> limited() {
            return List.of(member, guest);
        }
    }

    private Fx fixture() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        RegisteredUser guest = newUser();
        RegisteredUser outsider = newUser();
        UUID team = createTeam(admin, "Nhóm");
        addToTeam(admin, team, member, "MEMBER");
        UUID project = createTeamProject(admin, team, "Dự án nhóm", null);
        authPost(member.accessToken(), "/api/v1/projects/" + project + "/join").andExpect(status().isOk());
        addToProject(admin, project, guest); // người ngoài team → GUEST
        UUID task = createTask(admin, project, "Việc nhóm");
        UUID section = readUuid(authPost(admin.accessToken(), "/api/v1/projects/" + project + "/sections",
                "{\"name\": \"Mục\"}").andExpect(status().isCreated()), "$.id");
        UUID comment = readUuid(authPost(admin.accessToken(), "/api/v1/tasks/" + task + "/comments",
                "{\"content\": \"Ghi chú\"}").andExpect(status().isCreated()), "$.id");
        return new Fx(admin, member, guest, outsider, team, project, task, section, comment);
    }

    private void expectAdminOnly(RegisteredUser user, String method, String uri, String json) throws Exception {
        call(user, method, uri, json)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(TEAM_ADMIN_ONLY));
    }

    @Test
    void memberAndGuest_completeAndReopen_ok_adminEdits_outsider404() throws Exception {
        Fx fx = fixture();
        String task = "/api/v1/tasks/" + fx.task();
        for (RegisteredUser user : fx.limited()) {
            // chờ duyệt: tick = gửi yêu cầu (PENDING, vẫn mở); Undo = huỷ yêu cầu
            authPost(user.accessToken(), task + "/close").andExpect(status().isOk())
                    .andExpect(jsonPath("$.task.completedAt").isEmpty())
                    .andExpect(jsonPath("$.task.reviewStatus").value("PENDING"));
            authPost(user.accessToken(), task + "/reopen", "{\"undo\": true}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.task.completedAt").isEmpty())
                    .andExpect(jsonPath("$.task.reviewStatus").value("NONE"));
        }
        authPatch(fx.admin().accessToken(), task, "{\"version\": " + version(fx.admin(), fx.task())
                + ", \"content\": \"Admin sửa\"}").andExpect(status().isOk());
        authPost(fx.outsider().accessToken(), task + "/close").andExpect(status().isNotFound());
        authPatch(fx.outsider().accessToken(), task, "{\"version\": 0, \"content\": \"x\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void memberAndGuest_everyOtherWrite_is403TeamAdminOnly() throws Exception {
        Fx fx = fixture();
        String p = "/api/v1/projects/" + fx.project();
        String t = "/api/v1/tasks/" + fx.task();
        for (RegisteredUser u : fx.limited()) {
            long version = version(u, fx.task());
            expectAdminOnly(u, "POST", "/api/v1/tasks", "{\"content\": \"Mới\", \"projectId\": \"" + fx.project() + "\"}");
            expectAdminOnly(u, "POST", "/api/v1/tasks", "{\"content\": \"Con\", \"parentId\": \"" + fx.task() + "\"}");
            expectAdminOnly(u, "PATCH", t, "{\"version\": " + version + ", \"content\": \"Đổi\"}");
            expectAdminOnly(u, "PATCH", t, "{\"version\": " + version + ", \"dueDate\": \"2030-01-02\"}");
            expectAdminOnly(u, "PATCH", t, "{\"version\": " + version + ", \"priority\": 1}");
            expectAdminOnly(u, "DELETE", t, null);
            expectAdminOnly(u, "POST", t + "/duplicate", null);
            expectAdminOnly(u, "POST", t + "/move", "{\"projectId\": \"" + fx.project() + "\", \"sectionId\": \""
                    + fx.section() + "\"}");
            expectAdminOnly(u, "POST", t + "/reminders", "{\"type\": \"ABSOLUTE\", \"at\": \"2030-01-01T09:00\"}");
            expectAdminOnly(u, "POST", "/api/v1/reorder", "{\"type\": \"TASK\", \"id\": \"" + fx.task()
                    + "\", \"afterId\": null}");
            expectAdminOnly(u, "POST", "/api/v1/reorder", "{\"type\": \"SECTION\", \"id\": \"" + fx.section()
                    + "\", \"afterId\": null}");
            expectAdminOnly(u, "POST", "/api/v1/tasks/batch", batch("SCHEDULE", fx.task(), ", \"date\": \"2030-01-02\""));
            expectAdminOnly(u, "POST", "/api/v1/tasks/batch", batch("PRIORITY", fx.task(), ", \"priority\": 2"));
            expectAdminOnly(u, "POST", "/api/v1/tasks/batch", batch("DELETE", fx.task(), ""));
            expectAdminOnly(u, "POST", p + "/sections", "{\"name\": \"Mục mới\"}");
            expectAdminOnly(u, "PATCH", p + "/sections/" + fx.section(), "{\"name\": \"Đổi\"}");
            expectAdminOnly(u, "POST", p + "/sections/" + fx.section() + "/archive", null);
            expectAdminOnly(u, "DELETE", p + "/sections/" + fx.section(), null);
            expectAdminOnly(u, "PATCH", p, "{\"name\": \"Đổi tên\"}");
            expectAdminOnly(u, "POST", t + "/comments", "{\"content\": \"Bình luận\"}");
            expectAdminOnly(u, "POST", p + "/comments", "{\"content\": \"Bình luận\"}");
            expectAdminOnly(u, "POST", "/api/v1/comments/" + fx.comment() + "/reactions", "{\"emoji\": \"👍\"}");
            expectAdminOnly(u, "POST", "/api/v1/templates/library/weekly-planning/apply",
                    "{\"projectId\": \"" + fx.project() + "\"}");
            expectAdminOnly(u, "POST", p + "/duplicate", null);
            expectAdminOnly(u, "POST", "/api/v1/projects", "{\"name\": \"Dự án của tôi\", \"workspaceId\": \""
                    + fx.team() + "\"}");
        }
        // không có gì đổi
        authGet(fx.admin().accessToken(), t).andExpect(jsonPath("$.content").value("Việc nhóm"))
                .andExpect(jsonPath("$.priority").value(4));
        authGet(fx.admin().accessToken(), p).andExpect(jsonPath("$.name").value("Dự án nhóm"));
    }

    @Test
    void batch_closeAndReopenAllowed_forMember() throws Exception {
        Fx fx = fixture();
        authPost(fx.member().accessToken(), "/api/v1/tasks/batch", batch("CLOSE", fx.task(), ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.affectedTaskIds[0]").value(fx.task().toString()));
        authPost(fx.member().accessToken(), "/api/v1/tasks/batch", batch("REOPEN", fx.task(), ", \"undo\": true"))
                .andExpect(status().isOk());
        authGet(fx.admin().accessToken(), "/api/v1/tasks/" + fx.task()).andExpect(jsonPath("$.completedAt").isEmpty());
    }

    @Test
    void member_ticksRecurringTask_pendingUntilAdminApproves() throws Exception {
        Fx fx = fixture();
        UUID recurring = readUuid(authPost(fx.admin().accessToken(), "/api/v1/tasks", "{\"content\": \"Họp tuần\", "
                + "\"projectId\": \"" + fx.project() + "\", \"dueDate\": \"2026-10-27\", "
                + "\"recurrenceRule\": \"FREQ=WEEKLY\"}").andExpect(status().isCreated()), "$.id");
        // chờ duyệt: Member tick việc lặp = PENDING, due CHƯA nhảy; Admin duyệt mới nhảy kỳ
        authPost(fx.member().accessToken(), "/api/v1/tasks/" + recurring + "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").isEmpty())
                .andExpect(jsonPath("$.task.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.task.dueDate").value("2026-10-27"));
        authPost(fx.admin().accessToken(), "/api/v1/tasks/" + recurring + "/review/approve").andExpect(status().isOk())
                .andExpect(jsonPath("$.task.dueDate").value(not("2026-10-27")));
    }

    @Test
    void capabilities_inProjectResponse_followRole() throws Exception {
        Fx fx = fixture();
        String p = "/api/v1/projects/" + fx.project();
        authGet(fx.admin().accessToken(), p)
                .andExpect(jsonPath("$.capabilities.canEditTasks").value(true))
                .andExpect(jsonPath("$.capabilities.canCompleteTasks").value(true))
                .andExpect(jsonPath("$.capabilities.canManageSections").value(true))
                .andExpect(jsonPath("$.capabilities.canComment").value(true));
        for (RegisteredUser u : fx.limited()) {
            authGet(u.accessToken(), p)
                    .andExpect(jsonPath("$.capabilities.canEditTasks").value(false))
                    .andExpect(jsonPath("$.capabilities.canCompleteTasks").value(true))
                    .andExpect(jsonPath("$.capabilities.canManageSections").value(false))
                    .andExpect(jsonPath("$.capabilities.canComment").value(false))
                    .andExpect(jsonPath("$.canManage").value(false));
        }
        // danh sách dự án mang cùng capabilities
        authGet(fx.member().accessToken(), "/api/v1/projects")
                .andExpect(jsonPath("$[?(@.id == '" + fx.project() + "')].capabilities.canEditTasks").value(hasItem(false)));
        // Member chưa Join (xem trước): không hoàn thành được
        RegisteredUser viewer = newUser();
        addToTeam(fx.admin(), fx.team(), viewer, "MEMBER");
        authGet(viewer.accessToken(), p)
                .andExpect(jsonPath("$.capabilities.canCompleteTasks").value(false))
                .andExpect(jsonPath("$.capabilities.canEditTasks").value(false));
        // dự án cá nhân: mọi quyền
        UUID inbox = readUuid(authGet(viewer.accessToken(), "/api/v1/projects"), "$[0].id");
        authGet(viewer.accessToken(), "/api/v1/projects/" + inbox)
                .andExpect(jsonPath("$.capabilities.canEditTasks").value(true))
                .andExpect(jsonPath("$.capabilities.canComment").value(true));
    }

    @Test
    void sharedPersonalProject_collaboratorStillEditsEverything() throws Exception {
        RegisteredUser owner = newUser();
        RegisteredUser collaborator = newUser();
        UUID project = createProject(owner, "Cá nhân chia sẻ");
        addToProject(owner, project, collaborator);
        UUID task = createTask(collaborator, project, "Việc của cộng tác viên");
        authPatch(collaborator.accessToken(), "/api/v1/tasks/" + task, "{\"version\": " + version(collaborator, task)
                + ", \"content\": \"Sửa\"}").andExpect(status().isOk());
        authPost(collaborator.accessToken(), "/api/v1/tasks/" + task + "/comments", "{\"content\": \"Ok\"}")
                .andExpect(status().isCreated());
        authPost(collaborator.accessToken(), "/api/v1/projects/" + project + "/sections", "{\"name\": \"Mục\"}")
                .andExpect(status().isCreated());
        authPost(collaborator.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk());
        authGet(collaborator.accessToken(), "/api/v1/projects/" + project)
                .andExpect(jsonPath("$.capabilities.canEditTasks").value(true));
    }

    @Test
    void restrictedProject_invitedMember_onlyCompletes() throws Exception {
        RegisteredUser admin = newUser();
        RegisteredUser member = newUser();
        UUID team = createTeam(admin, "Nhóm");
        addToTeam(admin, team, member, "MEMBER");
        UUID project = createTeamProject(admin, team, "Kín", "RESTRICTED");
        addToProject(admin, project, member);
        UUID task = createTask(admin, project, "Việc kín");
        authPost(member.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk());
        expectAdminOnly(member, "PATCH", "/api/v1/tasks/" + task, "{\"version\": " + version(member, task)
                + ", \"content\": \"x\"}");
    }

    @Test
    void member_cannotMovePersonalProjectIntoTeam_butCanStillInviteAndLeave() throws Exception {
        Fx fx = fixture();
        UUID personal = createProject(fx.member(), "Riêng");
        expectAdminOnly(fx.member(), "POST", "/api/v1/projects/" + personal + "/move-to-workspace",
                "{\"workspaceId\": \"" + fx.team() + "\"}");
        // mời người khác + rời dự án không đổi (không thuộc "chỉnh sửa nội dung")
        invite(fx.member(), fx.project(), uniqueEmail());
        authPost(fx.member().accessToken(), "/api/v1/projects/" + fx.project() + "/leave")
                .andExpect(status().isNoContent());
    }

    @Test
    void rescheduleOverdue_skipsTeamTasksMemberCannotEdit() throws Exception {
        Fx fx = fixture();
        UUID teamOverdue = createOverdueTask(fx.admin(), fx.project(), "Quá hạn nhóm");
        UUID mine = createOverdueTask(fx.member(), readUuid(authGet(fx.member().accessToken(), "/api/v1/projects"),
                "$[0].id"), "Quá hạn của tôi");
        String body = body(authPost(fx.member().accessToken(), "/api/v1/tasks/overdue/reschedule",
                "{\"date\": \"2030-01-01\"}").andExpect(status().isOk()).andReturn());
        List<String> ids = JsonPath.read(body, "$.affectedTaskIds");
        assertThat(ids).contains(mine.toString()).doesNotContain(teamOverdue.toString());
        authGet(fx.admin().accessToken(), "/api/v1/tasks/" + teamOverdue)
                .andExpect(jsonPath("$.dueDate").value("2020-01-01"));
    }

    @Test
    void sync_memberUpdateErrorsInResults_closeOk() throws Exception {
        Fx fx = fixture();
        String json = "{\"syncToken\": null, \"commands\": ["
                + "{\"uuid\": \"" + UUID.randomUUID() + "\", \"type\": \"task_update\", \"args\": {\"id\": \""
                + fx.task() + "\", \"content\": \"Đổi qua sync\"}},"
                + "{\"uuid\": \"" + UUID.randomUUID() + "\", \"type\": \"task_close\", \"args\": {\"id\": \""
                + fx.task() + "\"}}]}";
        String body = body(authPost(fx.member().accessToken(), "/api/v1/sync", json).andExpect(status().isOk())
                .andReturn());
        assertThat((String) JsonPath.read(body, "$.results[0].status")).isEqualTo("error");
        assertThat((Integer) JsonPath.read(body, "$.results[0].error.status")).isEqualTo(403);
        assertThat((String) JsonPath.read(body, "$.results[0].error.code")).isEqualTo(TEAM_ADMIN_ONLY);
        assertThat((String) JsonPath.read(body, "$.results[1].status")).isEqualTo("ok");
    }

    /** Nối PERM × DND: reorder TASK_DAY (kéo-thả trong Hôm nay / Sắp tới) là ghi → chỉ Admin của dự án nhóm. */
    @Test
    void reorderTaskDay_memberAndGuest403TeamAdminOnly_restAndSync_adminOk() throws Exception {
        Fx fx = fixture();
        UUID dated = readUuid(authPost(fx.admin().accessToken(), "/api/v1/tasks", "{\"content\": \"Có hạn\", "
                + "\"projectId\": \"" + fx.project() + "\", \"dueDate\": \"2030-01-02\"}")
                .andExpect(status().isCreated()), "$.id");
        String reorder = "{\"type\": \"TASK_DAY\", \"id\": \"" + dated + "\", \"afterId\": null}";
        for (RegisteredUser u : fx.limited()) {
            expectAdminOnly(u, "POST", "/api/v1/reorder", reorder);
            String json = "{\"syncToken\": null, \"commands\": [{\"uuid\": \"" + UUID.randomUUID()
                    + "\", \"type\": \"reorder\", \"args\": " + reorder + "}]}";
            String body = body(authPost(u.accessToken(), "/api/v1/sync", json).andExpect(status().isOk()).andReturn());
            assertThat((String) JsonPath.read(body, "$.results[0].status")).isEqualTo("error");
            assertThat((Integer) JsonPath.read(body, "$.results[0].error.status")).isEqualTo(403);
            assertThat((String) JsonPath.read(body, "$.results[0].error.code")).isEqualTo(TEAM_ADMIN_ONLY);
        }
        authPost(fx.admin().accessToken(), "/api/v1/reorder", reorder).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TASK_DAY"));
    }

    /**
     * Nối PERM × DND: Thành viên kéo task CÁ NHÂN trong Hôm nay có lẫn task nhóm → chỉ ghi day_order của task mình sửa
     * được (chèn vào khoảng giữa), day_order của task nhóm giữ nguyên; Admin vẫn sắp được task nhóm.
     */
    @Test
    void reorderTaskDay_memberDragsOwnTask_teamTasksDayOrderUntouched() throws Exception {
        clock.freezeAt(java.time.Instant.parse("2026-10-23T03:00:00Z")); // 10:00 ngày 23 giờ Việt Nam
        Fx fx = fixture();
        UUID ta = createDated(fx.admin(), fx.project(), "Nhóm A");
        UUID tb = createDated(fx.admin(), fx.project(), "Nhóm B");
        // Admin sắp task nhóm: B lên đầu
        authPost(fx.admin().accessToken(), "/api/v1/reorder", dayReorder(tb, null)).andExpect(status().isOk());
        authGet(fx.admin().accessToken(), "/api/v1/tasks?view=today")
                .andExpect(jsonPath("$.items[*].content", org.hamcrest.Matchers.contains("Nhóm B", "Nhóm A")));
        Long orderA = dayOrder(ta);
        Long orderB = dayOrder(tb);
        assertThat(orderA).isNotNull();
        assertThat(orderB).isNotNull();

        UUID inbox = readUuid(authGet(fx.member().accessToken(), "/api/v1/projects"), "$[0].id");
        UUID mine = createDated(fx.member(), inbox, "Của tôi");
        // Thành viên kéo task của mình lên đầu, rồi vào giữa B và A
        authPost(fx.member().accessToken(), "/api/v1/reorder", dayReorder(mine, null)).andExpect(status().isOk());
        authGet(fx.member().accessToken(), "/api/v1/tasks?view=today")
                .andExpect(jsonPath("$.items[*].content", org.hamcrest.Matchers.contains("Của tôi", "Nhóm B", "Nhóm A")));
        assertThat(dayOrder(ta)).isEqualTo(orderA);
        assertThat(dayOrder(tb)).isEqualTo(orderB);

        authPost(fx.member().accessToken(), "/api/v1/reorder", dayReorder(mine, tb)).andExpect(status().isOk());
        authGet(fx.member().accessToken(), "/api/v1/tasks?view=today")
                .andExpect(jsonPath("$.items[*].content", org.hamcrest.Matchers.contains("Nhóm B", "Của tôi", "Nhóm A")));
        assertThat(dayOrder(ta)).isEqualTo(orderA);
        assertThat(dayOrder(tb)).isEqualTo(orderB);

        // Admin vẫn sắp được: A lên đầu
        authPost(fx.admin().accessToken(), "/api/v1/reorder", dayReorder(ta, null)).andExpect(status().isOk());
        authGet(fx.admin().accessToken(), "/api/v1/tasks?view=today")
                .andExpect(jsonPath("$.items[*].content", org.hamcrest.Matchers.contains("Nhóm A", "Nhóm B")));
    }

    private UUID createDated(RegisteredUser user, UUID project, String content) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", "{\"content\": \"" + content
                + "\", \"projectId\": \"" + project + "\", \"dueDate\": \"2026-10-23\"}")
                .andExpect(status().isCreated()), "$.id");
    }

    private static String dayReorder(UUID id, UUID afterId) {
        return "{\"type\": \"TASK_DAY\", \"id\": \"" + id + "\", \"afterId\": "
                + (afterId == null ? "null" : "\"" + afterId + "\"") + "}";
    }

    private Long dayOrder(UUID taskId) {
        return jdbcTemplate.queryForObject("SELECT day_order FROM tasks WHERE id = ?", Long.class, taskId);
    }

    @Test
    void errorDetail_isVietnameseByDefault_andEnglishOnRequest() throws Exception {
        Fx fx = fixture();
        String uri = "/api/v1/projects/" + fx.project() + "/sections";
        String vi = readString(authPost(fx.member().accessToken(), uri, "{\"name\": \"M\"}"), "$.detail");
        assertThat(vi).contains("quản trị viên");
        String en = readString(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(uri).header("Authorization", bearer(fx.member().accessToken()))
                .header("Accept-Language", "en").contentType("application/json").content("{\"name\": \"M\"}")),
                "$.detail");
        assertThat(en).containsIgnoringCase("admin");
    }

    private long version(RegisteredUser user, UUID taskId) throws Exception {
        return readLong(authGet(user.accessToken(), "/api/v1/tasks/" + taskId), "$.version");
    }

    private static String batch(String action, UUID taskId, String extra) {
        return "{\"action\": \"" + action + "\", \"taskIds\": [\"" + taskId + "\"]" + extra + "}";
    }
}
