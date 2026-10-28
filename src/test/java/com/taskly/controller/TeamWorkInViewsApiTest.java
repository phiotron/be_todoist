package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.M8aApiTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Yêu cầu người dùng "Hộp thư đến, Hôm nay, Sắp tới là tất cả công việc cả cá nhân lẫn nhóm":
 * <ul>
 *   <li>Hôm nay / Sắp tới / Quá hạn gồm task có hạn của MỌI dự án đã tham gia: cá nhân, dự án cá nhân được chia sẻ,
 *       dự án Team đã Join, Restricted được mời — KHÔNG gồm dự án Team chưa Join / Restricted không được mời / dự án
 *       người khác không chia sẻ.</li>
 *   <li>GET tasks?view=inbox_team: task GỐC chưa có ngày, chưa xong của các dự án NHÓM đã tham gia (dự án của workspace
 *       Team + dự án cá nhân có người cộng tác), loại Inbox, dự án lưu trữ, dự án cá nhân không chia sẻ.</li>
 * </ul>
 * Giờ đóng băng 2026-11-04T03:00Z = 10:00 ngày 04/11 ở Asia/Ho_Chi_Minh.
 */
class TeamWorkInViewsApiTest extends M8aApiTestBase {

    private static final String OVERDUE = "2026-11-01";
    private static final String TODAY = "2026-11-04";
    private static final String LATER = "2026-11-08";

    private RegisteredUser me;
    private RegisteredUser owner;
    private RegisteredUser admin;
    private UUID ownProject;
    private UUID sharedWithMe;
    private UUID notShared;
    private UUID team;
    private UUID joinedTeamProject;
    private UUID notJoinedTeamProject;
    private UUID invitedRestricted;
    private UUID hiddenRestricted;

    @BeforeEach
    void fixture() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        me = newUser();
        owner = newUser();
        admin = newUser();
        ownProject = createProject(me, "Của tôi");
        sharedWithMe = createProject(owner, "Chia sẻ cho tôi");
        notShared = createProject(owner, "Riêng của owner");
        addToProject(owner, sharedWithMe, me);
        team = createTeam(admin, "Nhóm A");
        addToTeam(admin, team, me, "MEMBER");
        joinedTeamProject = createTeamProject(admin, team, "Team đã vào", "TEAM");
        authPost(me.accessToken(), "/api/v1/projects/" + joinedTeamProject + "/join").andExpect(status().isOk());
        notJoinedTeamProject = createTeamProject(admin, team, "Team chưa vào", "TEAM");
        invitedRestricted = createTeamProject(admin, team, "Kín được mời", "RESTRICTED");
        addToProject(admin, invitedRestricted, me);
        hiddenRestricted = createTeamProject(admin, team, "Kín không mời", "RESTRICTED");
    }

    private UUID task(RegisteredUser user, UUID project, String content, String dueDate) throws Exception {
        String due = dueDate == null ? "" : ", \"dueDate\": \"" + dueDate + "\"";
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", "{\"content\": \"" + content
                + "\", \"projectId\": \"" + project + "\"" + due + "}").andExpect(status().isCreated()), "$.id");
    }

    private List<String> contents(RegisteredUser user, String uri) throws Exception {
        ResultActions actions = authGet(user.accessToken(), uri).andExpect(status().isOk());
        return JsonPath.read(body(actions.andReturn()), "$.items[*].content");
    }

    /** Mỗi dự án một task với cùng hạn; trả về nội dung theo thứ tự dự án trong fixture. */
    private void dueEverywhere(String prefix, String dueDate) throws Exception {
        task(me, ownProject, prefix + " cá nhân", dueDate);
        task(owner, sharedWithMe, prefix + " được chia sẻ", dueDate);
        task(owner, notShared, prefix + " không chia sẻ", dueDate);
        task(admin, joinedTeamProject, prefix + " team đã vào", dueDate);
        task(admin, notJoinedTeamProject, prefix + " team chưa vào", dueDate);
        task(admin, invitedRestricted, prefix + " kín được mời", dueDate);
        task(admin, hiddenRestricted, prefix + " kín không mời", dueDate);
    }

    private static void assertPersonalAndJoinedTeam(List<String> items, String prefix) {
        assertThat(items).contains(prefix + " cá nhân", prefix + " được chia sẻ", prefix + " team đã vào",
                        prefix + " kín được mời")
                .doesNotContain(prefix + " không chia sẻ", prefix + " team chưa vào", prefix + " kín không mời");
    }

    // ---- Hôm nay / Sắp tới / Quá hạn: cả cá nhân lẫn nhóm đã tham gia -------------------------------------------

    @Test
    void today_includesOverdueAndTodayTasksOfEveryJoinedProject_personalAndTeam() throws Exception {
        dueEverywhere("Quá hạn", OVERDUE);
        dueEverywhere("Hôm nay", TODAY);

        List<String> today = contents(me, "/api/v1/tasks?view=today");
        assertPersonalAndJoinedTeam(today, "Quá hạn");
        assertPersonalAndJoinedTeam(today, "Hôm nay");
        // Display (nhánh M2) dùng cùng phạm vi
        List<String> withDisplay = contents(me, "/api/v1/tasks?view=today&sort=priority&group=project");
        assertPersonalAndJoinedTeam(withDisplay, "Quá hạn");
        assertPersonalAndJoinedTeam(withDisplay, "Hôm nay");
        assertPersonalAndJoinedTeam(contents(me, "/api/v1/tasks?view=overdue"), "Quá hạn");
    }

    @Test
    void upcoming_includesTasksOfEveryJoinedProject_personalAndTeam() throws Exception {
        dueEverywhere("Sắp tới", LATER);

        assertPersonalAndJoinedTeam(contents(me, "/api/v1/tasks?view=upcoming"), "Sắp tới");
        assertPersonalAndJoinedTeam(contents(me, "/api/v1/tasks?view=upcoming&from=" + LATER + "&to=" + LATER
                + "&sort=date"), "Sắp tới");
    }

    @Test
    void today_teamProjectAppearsAfterJoining_andDisappearsAfterLeaving() throws Exception {
        task(admin, notJoinedTeamProject, "Việc team sau khi vào", TODAY);
        assertThat(contents(me, "/api/v1/tasks?view=today")).doesNotContain("Việc team sau khi vào");

        authPost(me.accessToken(), "/api/v1/projects/" + notJoinedTeamProject + "/join").andExpect(status().isOk());
        assertThat(contents(me, "/api/v1/tasks?view=today")).contains("Việc team sau khi vào");

        authPost(me.accessToken(), "/api/v1/projects/" + notJoinedTeamProject + "/leave")
                .andExpect(status().isNoContent());
        assertThat(contents(me, "/api/v1/tasks?view=today")).doesNotContain("Việc team sau khi vào");
    }

    // ---- view=inbox_team ----------------------------------------------------------------------------------------

    @Test
    void inboxTeam_listsUndatedOpenRootTasksOfJoinedGroupProjectsOnly() throws Exception {
        dueEverywhere("Chưa hẹn", null);
        task(me, testData.inboxOf(me.id()), "Chưa hẹn inbox", null);
        task(admin, joinedTeamProject, "Có hạn team", TODAY);
        UUID done = task(admin, joinedTeamProject, "Đã xong team", null);
        authPost(admin.accessToken(), "/api/v1/tasks/" + done + "/close").andExpect(status().isOk());
        UUID parent = task(admin, joinedTeamProject, "Cha team", null);
        authPost(admin.accessToken(), "/api/v1/tasks", "{\"content\": \"Con team\", \"projectId\": \""
                + joinedTeamProject + "\", \"parentId\": \"" + parent + "\"}").andExpect(status().isCreated());
        UUID archivedTeamProject = createTeamProject(admin, team, "Team lưu trữ", "TEAM");
        authPost(me.accessToken(), "/api/v1/projects/" + archivedTeamProject + "/join").andExpect(status().isOk());
        task(admin, archivedTeamProject, "Chưa hẹn lưu trữ", null);
        authPost(admin.accessToken(), "/api/v1/projects/" + archivedTeamProject + "/archive")
                .andExpect(status().isOk());

        List<String> items = contents(me, "/api/v1/tasks?view=inbox_team");
        assertThat(items).containsExactlyInAnyOrder("Chưa hẹn được chia sẻ", "Chưa hẹn team đã vào",
                "Chưa hẹn kín được mời", "Cha team");
        // dự án cá nhân KHÔNG chia sẻ của chính mình, Inbox, việc có hạn / đã xong / con / lưu trữ: không có
        assertThat(items).doesNotContain("Chưa hẹn cá nhân", "Chưa hẹn inbox", "Có hạn team", "Đã xong team",
                "Con team", "Chưa hẹn lưu trữ");
    }

    @Test
    void inboxTeam_ownerOfASharedPersonalProjectSeesIt_collaboratorSeesOnlyShared_outsiderSeesNothing()
            throws Exception {
        dueEverywhere("Chưa hẹn", null);
        RegisteredUser outsider = newUser();

        List<String> ownerItems = contents(owner, "/api/v1/tasks?view=inbox_team");
        assertThat(ownerItems).contains("Chưa hẹn được chia sẻ").doesNotContain("Chưa hẹn không chia sẻ",
                "Chưa hẹn team đã vào", "Chưa hẹn cá nhân");
        assertThat(contents(admin, "/api/v1/tasks?view=inbox_team")).contains("Chưa hẹn team đã vào",
                "Chưa hẹn team chưa vào", "Chưa hẹn kín được mời", "Chưa hẹn kín không mời")
                .doesNotContain("Chưa hẹn được chia sẻ");
        assertThat(contents(outsider, "/api/v1/tasks?view=inbox_team")).isEmpty();

        // bị bỏ khỏi dự án chia sẻ → mất luôn khỏi Hộp thư đến nhóm
        authDelete(owner.accessToken(), "/api/v1/projects/" + sharedWithMe + "/members/" + me.id())
                .andExpect(status().isNoContent());
        assertThat(contents(me, "/api/v1/tasks?view=inbox_team")).doesNotContain("Chưa hẹn được chia sẻ");
    }

    @Test
    void inboxTeam_pagesWithCursor_withoutDuplicatesOrGaps_groupedByProject() throws Exception {
        for (int i = 1; i <= 3; i++) {
            task(admin, joinedTeamProject, "Team " + i, null);
            task(owner, sharedWithMe, "Chia sẻ " + i, null);
        }
        List<String> all = new ArrayList<>();
        List<String> projects = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String uri = "/api/v1/tasks?view=inbox_team&limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            String json = body(authGet(me.accessToken(), uri).andExpect(status().isOk()).andReturn());
            all.addAll(JsonPath.read(json, "$.items[*].content"));
            projects.addAll(JsonPath.read(json, "$.items[*].projectId"));
            cursor = JsonPath.read(json, "$.nextCursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(all).containsExactlyInAnyOrder("Team 1", "Team 2", "Team 3", "Chia sẻ 1", "Chia sẻ 2", "Chia sẻ 3");
        // nhóm Team trước, rồi dự án được chia sẻ; trong một dự án theo thứ tự thủ công
        assertThat(all).containsExactly("Team 1", "Team 2", "Team 3", "Chia sẻ 1", "Chia sẻ 2", "Chia sẻ 3");
        assertThat(projects.subList(0, 3)).containsOnly(joinedTeamProject.toString());
    }

    @Test
    void inboxTeam_rejectsDisplayAndDateWindowParameters() throws Exception {
        authGet(me.accessToken(), "/api/v1/tasks?view=inbox_team&sort=priority").andExpect(status().isBadRequest());
        authGet(me.accessToken(), "/api/v1/tasks?view=inbox_team&from=" + TODAY).andExpect(status().isBadRequest());
        authGet(me.accessToken(), "/api/v1/tasks?view=inbox_team&projectId=" + joinedTeamProject)
                .andExpect(status().isBadRequest());
    }
}
