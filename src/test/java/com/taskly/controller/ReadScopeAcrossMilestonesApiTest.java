package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.entity.NotificationType;
import com.taskly.integration.OutgoingMail;
import com.taskly.repository.BackupRepository;
import com.taskly.repository.NotificationRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.security.EmailInboundRateLimiter;
import com.taskly.security.InboundSignatureVerifier;
import com.taskly.support.M8aApiTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Kiểm quyền ĐỌC sau khi gộp M8a (quyền theo DỰ ÁN) với các mốc M7 / M9 / M10 / M8b / M11 / M12 / M13: chủ (alice) có hai
 * dự án, chỉ MỘT được chia sẻ cho cộng tác viên (bob); carol là người ngoài. Bob KHÔNG được thấy dự án riêng của alice ở
 * bất kỳ endpoint / loại dữ liệu nào, carol không thấy gì của alice.
 */
class ReadScopeAcrossMilestonesApiTest extends M8aApiTestBase {

    private static final String SECRET = "test-only-email-webhook-secret-not-real";
    private static final String DAY = "2026-11-10";

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private BackupRepository backupRepository;
    @Autowired
    private AccessPolicy accessPolicy;
    @Autowired
    private com.taskly.repository.DigestQuery digestQuery;
    @Autowired
    private EmailInboundRateLimiter rateLimiter;

    private RegisteredUser alice;
    private RegisteredUser bob;
    private RegisteredUser carol;
    private UUID shared;
    private UUID secret;
    private UUID sharedTask;
    private UUID secretTask;

    @BeforeEach
    void fixture() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        rateLimiter.clear();
        alice = newUser();
        bob = newUser();
        carol = newUser();
        shared = createProject(alice, "Dự án chia sẻ");
        secret = createProject(alice, "Dự án riêng");
        addToProject(alice, shared, bob);
        sharedTask = dueTask(alice, shared, "Việc chung");
        secretTask = dueTask(alice, secret, "Việc bí mật");
    }

    private UUID dueTask(RegisteredUser user, UUID project, String content) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", "{\"content\": \"" + content
                + "\", \"projectId\": \"" + project + "\", \"dueDate\": \"" + DAY + "\"}")
                .andExpect(status().isCreated()), "$.id");
    }

    private String json(ResultActions actions) throws Exception {
        return body(actions.andExpect(status().isOk()).andReturn());
    }

    private static List<String> read(String body, String path) {
        return JsonPath.read(body, path);
    }

    private String sync(RegisteredUser user, String requestJson) throws Exception {
        return json(authPost(user.accessToken(), "/api/v1/sync", requestJson));
    }

    private String syncToken(RegisteredUser user) throws Exception {
        return JsonPath.read(sync(user, "{\"resourceTypes\": []}"), "$.syncToken");
    }

    // ---- M7: calendar view -------------------------------------------------------------------------------------

    @Test
    void m7_calendarView_collaboratorSeesOnlySharedProject() throws Exception {
        String uri = "/api/v1/tasks?view=calendar&from=2026-11-01&to=2026-11-30";
        assertThat(read(json(authGet(bob.accessToken(), uri)), "$.items[*].id"))
                .contains(sharedTask.toString()).doesNotContain(secretTask.toString());
        assertThat(read(json(authGet(carol.accessToken(), uri)), "$.items[*].id")).isEmpty();
        // chọn thẳng dự án riêng: 404
        authGet(bob.accessToken(), uri + "&projectId=" + secret).andExpect(status().isNotFound());
        assertThat(read(json(authGet(alice.accessToken(), uri)), "$.items[*].id"))
                .contains(sharedTask.toString(), secretTask.toString());
    }

    @Test
    void m6b_dayCounts_countOnlyReadableProjects() throws Exception {
        String uri = "/api/v1/tasks/day-counts?from=2026-11-09&to=2026-11-11";
        assertThat((Integer) JsonPath.read(json(authGet(bob.accessToken(), uri)), "$.counts['" + DAY + "']"))
                .isEqualTo(1);
        assertThat((Integer) JsonPath.read(json(authGet(carol.accessToken(), uri)), "$.counts['" + DAY + "']"))
                .isEqualTo(0);
        assertThat((Integer) JsonPath.read(json(authGet(alice.accessToken(), uri)), "$.counts['" + DAY + "']"))
                .isEqualTo(2);
    }

    // ---- M9: sync (bản ghi + tombstone + lệnh ghi) -----------------------------------------------------------

    @Test
    void m9_fullSync_collaboratorGetsOnlySharedProjectEntities() throws Exception {
        String bobBody = sync(bob, "{}");
        assertThat(read(bobBody, "$.changes.projects[*].id")).contains(shared.toString())
                .doesNotContain(secret.toString());
        assertThat(read(bobBody, "$.changes.tasks[*].id")).contains(sharedTask.toString())
                .doesNotContain(secretTask.toString());
        String carolBody = sync(carol, "{}");
        assertThat(read(carolBody, "$.changes.projects[*].id")).doesNotContain(shared.toString(), secret.toString());
        assertThat(read(carolBody, "$.changes.tasks[*].id")).isEmpty();
    }

    @Test
    void m9_incrementalSync_noChangeOrTombstoneOfUnsharedProjectReachesCollaborator() throws Exception {
        UUID secretSection = readUuid(authPost(alice.accessToken(), "/api/v1/projects/" + secret + "/sections",
                "{\"name\": \"Mục riêng\"}").andExpect(status().isCreated()), "$.id");
        UUID doomedSecret = dueTask(alice, secret, "Sẽ bị xoá (riêng)");
        UUID doomedShared = dueTask(alice, shared, "Sẽ bị xoá (chung)");
        String bobToken = syncToken(bob);
        String carolToken = syncToken(carol);

        authPatch(alice.accessToken(), "/api/v1/tasks/" + secretTask, "{\"version\": 0, \"content\": \"đổi riêng\"}")
                .andExpect(status().isOk());
        authDelete(alice.accessToken(), "/api/v1/tasks/" + doomedSecret).andExpect(status().isNoContent());
        authDelete(alice.accessToken(), "/api/v1/tasks/" + doomedShared).andExpect(status().isNoContent());
        authPatch(alice.accessToken(), "/api/v1/tasks/" + sharedTask, "{\"version\": 0, \"content\": \"đổi chung\"}")
                .andExpect(status().isOk());

        String body = sync(bob, "{\"syncToken\": \"" + bobToken + "\"}");
        assertThat(read(body, "$.changes.tasks[*].id")).contains(sharedTask.toString())
                .doesNotContain(secretTask.toString(), doomedSecret.toString());
        assertThat(read(body, "$.changes.sections[*].id")).doesNotContain(secretSection.toString());
        assertThat(read(body, "$.deleted[*].id")).contains(doomedShared.toString())
                .doesNotContain(doomedSecret.toString());

        String carolBody = sync(carol, "{\"syncToken\": \"" + carolToken + "\"}");
        assertThat(read(carolBody, "$.changes.tasks[*].id")).isEmpty();
        assertThat(read(carolBody, "$.deleted[*].id")).isEmpty();
    }

    @Test
    void m9_syncCommands_writeWithTheUsersPermissions() throws Exception {
        String add = "{\"syncToken\": null, \"resourceTypes\": [], \"commands\": [{\"uuid\": \"" + UUID.randomUUID()
                + "\", \"type\": \"task_add\", \"args\": {\"content\": \"lén\", \"projectId\": \"%s\"}}]}";
        assertThat(read(sync(bob, add.formatted(secret)), "$.results[*].status")).containsExactly("error");
        assertThat(read(sync(carol, add.formatted(shared)), "$.results[*].status")).containsExactly("error");
        String ok = "{\"syncToken\": null, \"resourceTypes\": [], \"commands\": [{\"uuid\": \"" + UUID.randomUUID()
                + "\", \"type\": \"task_add\", \"args\": {\"content\": \"được\", \"projectId\": \"%s\"}}]}";
        assertThat(read(sync(bob, ok.formatted(shared)), "$.results[*].status")).containsExactly("ok");
    }

    // ---- M10: activity ------------------------------------------------------------------------------------------

    @Test
    void m10_activity_onlyReadableProjects_andProjectFilter404() throws Exception {
        String bobFeed = json(authGet(bob.accessToken(), "/api/v1/activity?limit=100"));
        assertThat(read(bobFeed, "$.items[*].objectName")).contains("Việc chung", "Dự án chia sẻ")
                .doesNotContain("Việc bí mật", "Dự án riêng");
        assertThat(read(bobFeed, "$.items[*].projectId")).doesNotContain(secret.toString());
        authGet(bob.accessToken(), "/api/v1/activity?projectId=" + secret).andExpect(status().isNotFound());
        authGet(bob.accessToken(), "/api/v1/activity?taskId=" + secretTask).andExpect(status().isNotFound());
        assertThat(read(json(authGet(carol.accessToken(), "/api/v1/activity?limit=100")), "$.items[*].objectName"))
                .doesNotContain("Việc chung", "Việc bí mật", "Dự án chia sẻ", "Dự án riêng");
        assertThat(read(json(authGet(alice.accessToken(), "/api/v1/activity?limit=100")), "$.items[*].objectName"))
                .contains("Việc chung", "Việc bí mật");
    }

    @Test
    void m10_insights_andProductivity_areNotLeakedAcrossUsers() throws Exception {
        authGet(bob.accessToken(), "/api/v1/projects/" + secret + "/insights").andExpect(status().isNotFound());
        authGet(carol.accessToken(), "/api/v1/projects/" + shared + "/insights").andExpect(status().isNotFound());
        authGet(bob.accessToken(), "/api/v1/projects/" + shared + "/insights").andExpect(status().isOk());
        // Alice hoàn thành một task: số liệu năng suất của bob không đổi (chỉ của chính mình)
        authPost(alice.accessToken(), "/api/v1/tasks/" + secretTask + "/close").andExpect(status().isOk());
        assertThat((Integer) JsonPath.read(json(authGet(bob.accessToken(), "/api/v1/productivity")),
                "$.daily.completed")).isZero();
    }

    // ---- M8b: comment, mention, thông báo, tìm kiếm ---------------------------------------------------------

    @Test
    void m8b_comments_mentionsAndSearchFollowProjectAccess() throws Exception {
        // người ngoài / cộng tác viên không đụng được comment của dự án riêng
        authGet(bob.accessToken(), "/api/v1/tasks/" + secretTask + "/comments").andExpect(status().isNotFound());
        authPost(bob.accessToken(), "/api/v1/tasks/" + secretTask + "/comments", "{\"content\": \"x\"}")
                .andExpect(status().isNotFound());
        authGet(bob.accessToken(), "/api/v1/tasks/" + secretTask + "/comments/mentionable")
                .andExpect(status().isNotFound());
        authGet(carol.accessToken(), "/api/v1/tasks/" + sharedTask + "/comments").andExpect(status().isNotFound());

        // @mention: dự án chia sẻ → alice + bob; không có carol
        List<String> mentionable = read(json(authGet(alice.accessToken(),
                "/api/v1/tasks/" + sharedTask + "/comments/mentionable")), "$[*].id");
        assertThat(mentionable).containsExactlyInAnyOrder(alice.id().toString(), bob.id().toString());
        authPost(alice.accessToken(), "/api/v1/tasks/" + sharedTask + "/comments", "{\"content\": \"hi\", "
                + "\"mentionUserIds\": [\"" + carol.id() + "\"]}").andExpect(status().isBadRequest());
        // bob được mention trong dự án chia sẻ
        authPost(alice.accessToken(), "/api/v1/tasks/" + sharedTask + "/comments", "{\"content\": \"tuyenchung hi\", "
                + "\"mentionUserIds\": [\"" + bob.id() + "\"]}").andExpect(status().isCreated());
        authPost(alice.accessToken(), "/api/v1/tasks/" + secretTask + "/comments", "{\"content\": \"tuyenbimat hi\"}")
                .andExpect(status().isCreated());
        // alice mention bob trong dự án riêng → bob không đọc được → 400
        authPost(alice.accessToken(), "/api/v1/tasks/" + secretTask + "/comments", "{\"content\": \"hi\", "
                + "\"mentionUserIds\": [\"" + bob.id() + "\"]}").andExpect(status().isBadRequest());

        // search/objects.comments lọc theo dự án đọc được
        assertThat(read(json(authGet(bob.accessToken(), "/api/v1/search/objects?q=tuyenchung")),
                "$.comments[*].content")).hasSize(1);
        assertThat(read(json(authGet(bob.accessToken(), "/api/v1/search/objects?q=tuyenbimat")),
                "$.comments[*].content")).isEmpty();
        assertThat(read(json(authGet(carol.accessToken(), "/api/v1/search/objects?q=tuyenchung")),
                "$.comments[*].content")).isEmpty();
        assertThat(read(json(authGet(alice.accessToken(), "/api/v1/search/objects?q=tuyenbimat")),
                "$.comments[*].content")).hasSize(1);
    }

    @Test
    void m8b_notifications_ofProjectsTheUserCannotReadAreHidden() throws Exception {
        authPost(alice.accessToken(), "/api/v1/tasks/" + sharedTask + "/comments", "{\"content\": \"nhắc\", "
                + "\"mentionUserIds\": [\"" + bob.id() + "\"]}").andExpect(status().isCreated());
        // thông báo trỏ vào task của dự án riêng (vd còn sót sau khi bị bỏ khỏi dự án) không được hiện / đếm
        UUID leaked = UUID.randomUUID();
        notificationRepository.insert(leaked, bob.id(), NotificationType.COMMENT_MENTION, alice.id(), null,
                secretTask, null, clock.instant());
        String list = json(authGet(bob.accessToken(), "/api/v1/notifications?limit=50"));
        assertThat(read(list, "$.items[*].id")).hasSize(1).doesNotContain(leaked.toString());
        assertThat((Integer) JsonPath.read(json(authGet(bob.accessToken(), "/api/v1/notifications/unread-count")),
                "$.count")).isEqualTo(1);
        authPost(bob.accessToken(), "/api/v1/notifications/" + leaked + "/read").andExpect(status().isNotFound());

        // bị bỏ khỏi dự án chung → thông báo của dự án đó cũng ẩn
        authDelete(alice.accessToken(), "/api/v1/projects/" + shared + "/members/" + bob.id())
                .andExpect(status().isNoContent());
        assertThat(read(json(authGet(bob.accessToken(), "/api/v1/notifications?limit=50")), "$.items[*].id")).isEmpty();
    }

    @Test
    void m8b_attachmentsAndAvatar_followCommentAccess() throws Exception {
        UUID comment = readUuid(authPost(alice.accessToken(), "/api/v1/tasks/" + secretTask + "/comments",
                "{\"content\": \"có file\"}").andExpect(status().isCreated()), "$.id");
        authDelete(bob.accessToken(), "/api/v1/comments/" + comment).andExpect(status().isNotFound());
        authPost(bob.accessToken(), "/api/v1/comments/" + comment + "/reactions", "{\"emoji\": \"👍\"}")
                .andExpect(status().isNotFound());
    }

    // ---- M11: bản sao dự án, template, backup ----------------------------------------------------------------

    @Test
    void m11_duplicateAndTemplate_needReadableProject() throws Exception {
        authPost(bob.accessToken(), "/api/v1/projects/" + secret + "/duplicate").andExpect(status().isNotFound());
        authPost(carol.accessToken(), "/api/v1/projects/" + shared + "/duplicate").andExpect(status().isNotFound());
        authPost(bob.accessToken(), "/api/v1/templates", "{\"projectId\": \"" + secret + "\", \"name\": \"t\"}")
                .andExpect(status().isNotFound());
        authPost(bob.accessToken(), "/api/v1/tasks/" + secretTask + "/duplicate").andExpect(status().isNotFound());
    }

    @Test
    void m11_backupSnapshot_containsOnlyProjectsTheUserJoined() throws Exception {
        String bobSnapshot = backupRepository.snapshot(bob.id(), accessPolicy.listScope(bob.id()).projectIds())
                .content();
        assertThat(bobSnapshot).contains("Dự án chia sẻ", "Việc chung").doesNotContain("Dự án riêng", "Việc bí mật");
        String carolSnapshot = backupRepository.snapshot(carol.id(), accessPolicy.listScope(carol.id()).projectIds())
                .content();
        assertThat(carolSnapshot).doesNotContain("Dự án chia sẻ", "Dự án riêng", "Việc chung", "Việc bí mật");
        String aliceSnapshot = backupRepository.snapshot(alice.id(), accessPolicy.listScope(alice.id()).projectIds())
                .content();
        assertThat(aliceSnapshot).contains("Dự án chia sẻ", "Dự án riêng");
    }

    // ---- M12: nhắc theo vị trí / nhắc ----------------------------------------------------------------------------

    @Test
    void m12_locationReminders_disappearWhenUserLosesProjectAccess_andLocationsArePrivate() throws Exception {
        UUID location = readUuid(authPost(bob.accessToken(), "/api/v1/locations",
                "{\"name\": \"Nhà\", \"latitude\": 10.77, \"longitude\": 106.7, \"radius\": 200}")
                .andExpect(status().isCreated()), "$.id");
        authPost(bob.accessToken(), "/api/v1/tasks/" + sharedTask + "/reminders", "{\"type\": \"LOCATION\", "
                + "\"locationId\": \"" + location + "\", \"trigger\": \"ON_ENTER\"}").andExpect(status().isCreated());
        // bob không đặt được nhắc cho task dự án riêng; alice không dùng được địa điểm của bob
        authPost(bob.accessToken(), "/api/v1/tasks/" + secretTask + "/reminders", "{\"type\": \"LOCATION\", "
                + "\"locationId\": \"" + location + "\", \"trigger\": \"ON_ENTER\"}").andExpect(status().isNotFound());
        authPost(alice.accessToken(), "/api/v1/tasks/" + sharedTask + "/reminders", "{\"type\": \"LOCATION\", "
                + "\"locationId\": \"" + location + "\", \"trigger\": \"ON_ENTER\"}").andExpect(status().isNotFound());
        assertThat(read(json(authGet(bob.accessToken(), "/api/v1/tasks/location-reminders")), "$[*].taskId"))
                .containsExactly(sharedTask.toString());
        assertThat(read(json(authGet(alice.accessToken(), "/api/v1/tasks/location-reminders")), "$[*].taskId"))
                .isEmpty();

        authDelete(alice.accessToken(), "/api/v1/projects/" + shared + "/members/" + bob.id())
                .andExpect(status().isNoContent());
        assertThat(read(json(authGet(bob.accessToken(), "/api/v1/tasks/location-reminders")), "$[*].taskId"))
                .isEmpty();
    }

    // ---- M13a: email → task -------------------------------------------------------------------------------------

    private ResultActions deliver(String address, String subject) throws Exception {
        byte[] bytes = ("{\"messageId\": \"<" + UUID.randomUUID() + "@x.example>\", \"from\": \"s@example.com\", "
                + "\"to\": \"" + address + "\", \"subject\": \"" + subject + "\", \"text\": \"nội dung\"}")
                .getBytes(StandardCharsets.UTF_8);
        long ts = clock.instant().getEpochSecond();
        return mockMvc.perform(post("/api/v1/integrations/email/inbound")
                .with(request -> {
                    request.setRemoteAddr("10.88." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250));
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Taskly-Timestamp", Long.toString(ts))
                .header("X-Taskly-Signature", InboundSignatureVerifier.sign(SECRET, ts, bytes))
                .content(bytes));
    }

    private int countTasks(UUID project, String content) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ? and content = ?",
                Integer.class, project, content);
        return count == null ? 0 : count;
    }

    @Test
    void m13a_emailToTask_usesOwnersCurrentWritePermission() throws Exception {
        String address = readString(authPut(bob.accessToken(), "/api/v1/projects/" + shared + "/email-address")
                .andExpect(status().isOk()), "$.address");
        // không tạo được địa chỉ cho dự án riêng của alice
        authPut(bob.accessToken(), "/api/v1/projects/" + secret + "/email-address").andExpect(status().isNotFound());

        deliver(address, "Mail khi còn trong dự án").andExpect(status().isOk());
        assertThat(countTasks(shared, "Mail khi còn trong dự án")).isEqualTo(1);

        authDelete(alice.accessToken(), "/api/v1/projects/" + shared + "/members/" + bob.id())
                .andExpect(status().isNoContent());
        deliver(address, "Mail sau khi rời dự án").andExpect(status().isOk());
        assertThat(countTasks(shared, "Mail sau khi rời dự án")).isZero();
    }

    // ---- M13b: calendar feed -----------------------------------------------------------------------------------

    private String feedBody(String url) throws Exception {
        String path = url.substring(url.indexOf("/api/v1/"));
        return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void m13b_calendarFeed_onlyReadableProjects_andStopsAfterLosingAccess() throws Exception {
        String accountUrl = readString(authPost(bob.accessToken(), "/api/v1/me/calendar-feeds", "{}")
                .andExpect(status().isOk()), "$.url");
        String ics = feedBody(accountUrl);
        assertThat(ics).contains("Việc chung").doesNotContain("Việc bí mật");
        // feed riêng cho dự án không đọc được: 404
        authPost(bob.accessToken(), "/api/v1/me/calendar-feeds", "{\"projectId\": \"" + secret + "\"}")
                .andExpect(status().isNotFound());
        String projectUrl = readString(authPost(bob.accessToken(), "/api/v1/me/calendar-feeds",
                "{\"projectId\": \"" + shared + "\"}").andExpect(status().isOk()), "$.url");
        assertThat(feedBody(projectUrl)).contains("Việc chung");

        authDelete(alice.accessToken(), "/api/v1/projects/" + shared + "/members/" + bob.id())
                .andExpect(status().isNoContent());
        assertThat(feedBody(accountUrl)).doesNotContain("Việc chung", "Việc bí mật");
        String path = projectUrl.substring(projectUrl.indexOf("/api/v1/"));
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    // ---- M13c: email nhắc / digest ------------------------------------------------------------------------------

    @Test
    void m13c_reminderOccurrences_andDigestScope_followProjects() throws Exception {
        assertThat(accessPolicy.listScope(bob.id()).projectIds()).contains(shared).doesNotContain(secret);
        assertThat(accessPolicy.listScope(carol.id()).projectIds()).doesNotContain(shared, secret);
        // email tóm tắt (Morning / Evening): đếm task quá hạn / đến hạn chỉ trong dự án đọc được
        java.time.LocalDate due = java.time.LocalDate.parse(DAY);
        assertThat(digestQuery.countOpenDueUpTo(accessPolicy.listScope(bob.id()), due)).isEqualTo(1);
        assertThat(digestQuery.countOpenDueUpTo(accessPolicy.listScope(carol.id()), due)).isZero();
        assertThat(digestQuery.countOpenDueUpTo(accessPolicy.listScope(alice.id()), due)).isEqualTo(2);
        assertThat(digestQuery.openDueUpTo(accessPolicy.listScope(bob.id()), due, 10)).extracting("content")
                .containsExactly("Việc chung");
        // nhắc (GET tasks/reminders): bob đặt nhắc cho task dự án chia sẻ, không đặt được cho dự án riêng
        authPost(bob.accessToken(), "/api/v1/tasks/" + sharedTask + "/reminders",
                "{\"type\": \"ABSOLUTE\", \"at\": \"2026-11-05T09:00\"}").andExpect(status().isCreated());
        authPost(bob.accessToken(), "/api/v1/tasks/" + secretTask + "/reminders",
                "{\"type\": \"ABSOLUTE\", \"at\": \"2026-11-05T09:00\"}").andExpect(status().isNotFound());
    }

    // ---- Workspace nhóm: dự án Restricted -------------------------------------------------------------------------

    @Test
    void team_restrictedProject_isHiddenFromWorkspaceMembersWhoWereNotInvited() throws Exception {
        RegisteredUser dan = newUser();
        UUID team = createTeam(alice, "Nhóm");
        addToTeam(alice, team, dan, "MEMBER");
        UUID restricted = createTeamProject(alice, team, "Hạn chế", "RESTRICTED");
        UUID restrictedTask = dueTask(alice, restricted, "Việc hạn chế");
        authPost(alice.accessToken(), "/api/v1/tasks/" + restrictedTask + "/comments",
                "{\"content\": \"timkiemhanche\"}").andExpect(status().isCreated());

        authGet(dan.accessToken(), "/api/v1/projects/" + restricted).andExpect(status().isNotFound());
        authGet(dan.accessToken(), "/api/v1/tasks/" + restrictedTask).andExpect(status().isNotFound());
        assertThat(read(sync(dan, "{}"), "$.changes.tasks[*].id")).doesNotContain(restrictedTask.toString());
        assertThat(read(sync(dan, "{}"), "$.changes.projects[*].id")).doesNotContain(restricted.toString());
        assertThat(read(json(authGet(dan.accessToken(), "/api/v1/activity?limit=100")), "$.items[*].objectName"))
                .doesNotContain("Việc hạn chế", "Hạn chế");
        assertThat(read(json(authGet(dan.accessToken(), "/api/v1/search/objects?q=timkiemhanche")),
                "$.comments[*].content")).isEmpty();
        assertThat(read(json(authGet(dan.accessToken(),
                "/api/v1/tasks?view=calendar&from=2026-11-01&to=2026-11-30")), "$.items[*].id"))
                .doesNotContain(restrictedTask.toString());
        assertThat(digestQuery.countOpenDueUpTo(accessPolicy.listScope(dan.id()), java.time.LocalDate.parse(DAY)))
                .isZero();
        assertThat(backupRepository.snapshot(dan.id(), accessPolicy.listScope(dan.id()).projectIds()).content())
                .doesNotContain("Hạn chế", "Việc hạn chế");
        // tombstone của task trong dự án Restricted cũng không tới Dan
        String token = syncToken(dan);
        authDelete(alice.accessToken(), "/api/v1/tasks/" + restrictedTask).andExpect(status().isNoContent());
        assertThat(read(sync(dan, "{\"syncToken\": \"" + token + "\"}"), "$.deleted[*].id"))
                .doesNotContain(restrictedTask.toString());
    }

    @SuppressWarnings("unused")
    private List<OutgoingMail> mailsTo(RegisteredUser user) {
        return mailbox.sentTo(user.email());
    }
}
