package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M8aApiTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M8a F2–F5: chia sẻ dự án cá nhân — mời email / link, ≤ 5 người, ngang quyền, rời / xoá người, Auto accept. */
class ProjectSharingApiTest extends M8aApiTestBase {

    @Test
    void emailInvitation_sendsMail_inviteeAccepts_andBothEditTheSameProject() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID project = createProject(alice, "Chung");
        UUID task = createTask(alice, project, "Việc của Alice");

        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + bob.email() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PROJECT"))
                .andExpect(jsonPath("$.email").value(bob.email()))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.token").doesNotExist());
        assertThat(mailbox.sentTo(bob.email())).hasSize(1);
        // Bob chưa thấy dự án cho tới khi chấp nhận
        authGet(bob.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNotFound());

        accept(bob, lastMailToken(bob.email()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PROJECT"))
                .andExpect(jsonPath("$.projectId").value(project.toString()));

        // Bob thấy dự án + task, thêm task, hoàn thành task của Alice
        authGet(bob.accessToken(), "/api/v1/projects/" + project)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.joined").value(true))
                .andExpect(jsonPath("$.canManage").value(false));
        createTask(bob, project, "Việc của Bob");
        authPost(bob.accessToken(), "/api/v1/tasks/" + task + "/close").andExpect(status().isOk());
        authGet(alice.accessToken(), "/api/v1/projects/" + project)
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.memberCount").value(2));
        authGet(alice.accessToken(), "/api/v1/tasks?projectId=" + project)
                .andExpect(jsonPath("$.items[*].content").value(hasItem("Việc của Bob")));
        authGet(bob.accessToken(), "/api/v1/projects/" + project + "/members")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].userId").value(alice.id().toString()))
                .andExpect(jsonPath("$[0].isOwner").value(true))
                .andExpect(jsonPath("$[1].isOwner").value(false));
    }

    @Test
    void invitationToUnregisteredEmail_worksAfterRegisteringWithThatEmail() throws Exception {
        RegisteredUser alice = newUser();
        UUID project = createProject(alice, "Chung");
        String email = uniqueEmail();
        invite(alice, project, email);
        String token = lastMailToken(email);

        RegisteredUser bob = registerUser(email);
        accept(bob, token).andExpect(status().isOk());
        authGet(bob.accessToken(), "/api/v1/projects/" + project).andExpect(status().isOk());
    }

    @Test
    void collaboratorSeesOnlySharedProject_notOwnersOtherProjects_inListsAndTodayAndSearch() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID shared = createProject(alice, "Dự án chia sẻ");
        UUID secret = createProject(alice, "Dự án riêng");
        createOverdueTask(alice, shared, "Việc chung");
        createOverdueTask(alice, secret, "Việc riêng");
        addToProject(alice, shared, bob);

        authGet(bob.accessToken(), "/api/v1/projects")
                .andExpect(jsonPath("$[*].id").value(hasItem(shared.toString())))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(secret.toString()))));
        authGet(bob.accessToken(), "/api/v1/projects/" + secret).andExpect(status().isNotFound());
        authGet(bob.accessToken(), "/api/v1/tasks?view=today")
                .andExpect(jsonPath("$.items[*].content").value(hasItem("Việc chung")))
                .andExpect(jsonPath("$.items[*].content").value(not(hasItem("Việc riêng"))));
        authGet(bob.accessToken(), "/api/v1/search?q=Viec")
                .andExpect(jsonPath("$.items[*].content").value(hasItem("Việc chung")))
                .andExpect(jsonPath("$.items[*].content").value(not(hasItem("Việc riêng"))));
        // Alice vẫn thấy cả hai
        authGet(alice.accessToken(), "/api/v1/tasks?view=today")
                .andExpect(jsonPath("$.items[*].content").value(hasItem("Việc riêng")));
    }

    @Test
    void collaboratorCannotManageProject_butCanInvite() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID project = createProject(alice, "Chung");
        addToProject(alice, project, bob);

        authPost(bob.accessToken(), "/api/v1/projects/" + project + "/archive").andExpect(status().isForbidden());
        authDelete(bob.accessToken(), "/api/v1/projects/" + project).andExpect(status().isForbidden());
        authDelete(bob.accessToken(), "/api/v1/projects/" + project + "/members/" + alice.id())
                .andExpect(status().isForbidden());
        // ngang quyền: đổi tên + mời thêm người được
        authPatch(bob.accessToken(), "/api/v1/projects/" + project, "{\"name\": \"Đổi tên\"}")
                .andExpect(status().isOk());
        invite(bob, project, uniqueEmail());
    }

    @Test
    void invitationLimit_countsOwnerMembersAndPendingInvitations_max5() throws Exception {
        RegisteredUser alice = newUser();
        UUID project = createProject(alice, "Chung");
        for (int i = 0; i < 4; i++) {
            invite(alice, project, uniqueEmail()); // chủ + 4 lời mời = 5 chỗ
        }
        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + uniqueEmail() + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("PROJECT_MEMBERS"))
                .andExpect(jsonPath("$.max").value(5));
    }

    @Test
    void invitationLimit_appliesToLinkJoinsAndMembersToo() throws Exception {
        RegisteredUser alice = newUser();
        UUID project = createProject(alice, "Chung");
        String link = readString(put(alice, "/api/v1/projects/" + project + "/invite-link")
                .andExpect(status().isOk()), "$.token");
        for (int i = 0; i < 4; i++) {
            accept(newUser(), link).andExpect(status().isOk());
        }
        accept(newUser(), link).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.limit").value("PROJECT_MEMBERS"));
        authGet(alice.accessToken(), "/api/v1/projects/" + project).andExpect(jsonPath("$.memberCount").value(5));
    }

    @Test
    void invitingYourself_is400_andInvitingAMember_is409AlreadyMember() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID project = createProject(alice, "Chung");
        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + alice.email() + "\"}").andExpect(status().isBadRequest());
        addToProject(alice, project, bob);
        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + bob.email() + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already-member"));
        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations", "{\"email\": \"khong-phai-email\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void reinvitingTheSameEmail_replacesTheOldInvitation() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID project = createProject(alice, "Chung");
        invite(alice, project, bob.email());
        String oldToken = lastMailToken(bob.email());
        invite(alice, project, bob.email());
        String newToken = lastMailToken(bob.email());

        assertThat(newToken).isNotEqualTo(oldToken);
        authGet(alice.accessToken(), "/api/v1/projects/" + project + "/invitations").andExpect(jsonPath("$", hasSize(1)));
        accept(bob, oldToken).andExpect(status().isNotFound());
        accept(bob, newToken).andExpect(status().isOk());
    }

    @Test
    void emailInvitation_isBoundToTheInvitedEmail_oneShot_andExpiresAfter7Days() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        RegisteredUser carol = newUser();
        UUID project = createProject(alice, "Chung");
        invite(alice, project, bob.email());
        String token = lastMailToken(bob.email());

        accept(carol, token).andExpect(status().isForbidden()); // email khác
        accept(bob, token).andExpect(status().isOk());
        accept(bob, token).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("invitation-expired"));

        invite(alice, project, carol.email());
        String carolToken = lastMailToken(carol.email());
        // access JWT chỉ sống 15 phút nên không dời đồng hồ 7 ngày: đẩy hạn lời mời về quá khứ trong DB
        jdbcTemplate.update("update invitations set expires_at = ? where project_id = ?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))), project);
        accept(carol, carolToken).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("invitation-expired"));
        authGet(carol.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNotFound());
        accept(carol, "token-khong-ton-tai").andExpect(status().isNotFound());
    }

    @Test
    void invitationsAreStoredAsHashOnly() throws Exception {
        RegisteredUser alice = newUser();
        UUID project = createProject(alice, "Chung");
        String email = uniqueEmail();
        invite(alice, project, email);
        String token = lastMailToken(email);
        Integer rawMatches = jdbcTemplate.queryForObject(
                "select count(*) from invitations where token_hash = ?", Integer.class, token);
        assertThat(rawMatches).isZero();
    }

    @Test
    void mailFailure_is503_andNoInvitationIsCreated() throws Exception {
        RegisteredUser alice = newUser();
        UUID project = createProject(alice, "Chung");
        mailbox.setFailing(true);
        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + uniqueEmail() + "\"}").andExpect(status().isServiceUnavailable());
        Integer count = jdbcTemplate.queryForObject("select count(*) from invitations where project_id = ?",
                Integer.class, project);
        assertThat(count).isZero();
    }

    @Test
    void inviteeCanListAcceptAndDeclineTheirInvitations() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID p1 = createProject(alice, "Một");
        UUID p2 = createProject(alice, "Hai");
        UUID inv1 = invite(alice, p1, bob.email());
        UUID inv2 = invite(alice, p2, bob.email());

        authGet(bob.accessToken(), "/api/v1/invitations")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].targetName").value(hasItem("Một")));
        authGet(alice.accessToken(), "/api/v1/invitations").andExpect(jsonPath("$", hasSize(0)));

        authPost(bob.accessToken(), "/api/v1/invitations/" + inv1 + "/accept").andExpect(status().isOk());
        authPost(bob.accessToken(), "/api/v1/invitations/" + inv2 + "/decline").andExpect(status().isNoContent());
        authPost(bob.accessToken(), "/api/v1/invitations/" + inv2 + "/accept").andExpect(status().isGone());
        authGet(bob.accessToken(), "/api/v1/invitations").andExpect(jsonPath("$", hasSize(0)));
        authGet(bob.accessToken(), "/api/v1/projects/" + p2).andExpect(status().isNotFound());
        // lời mời của người khác không lộ: 404
        authPost(alice.accessToken(), "/api/v1/invitations/" + inv1 + "/accept").andExpect(status().isNotFound());
    }

    @Test
    void owner_canRevokePendingInvitation_butAnOutsiderCannot() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        RegisteredUser mallory = newUser();
        UUID project = createProject(alice, "Chung");
        UUID invitation = invite(alice, project, bob.email());
        String token = lastMailToken(bob.email());

        authDelete(mallory.accessToken(), "/api/v1/invitations/" + invitation).andExpect(status().isNotFound());
        authDelete(alice.accessToken(), "/api/v1/invitations/" + invitation).andExpect(status().isNoContent());
        accept(bob, token).andExpect(status().isNotFound());
    }

    @Test
    void inviteLink_reusable_resettable_andCanBeDisabled() throws Exception {
        RegisteredUser alice = newUser();
        UUID project = createProject(alice, "Chung");
        authGet(alice.accessToken(), "/api/v1/projects/" + project + "/invite-link")
                .andExpect(jsonPath("$.token").doesNotExist());

        String link = readString(put(alice, "/api/v1/projects/" + project + "/invite-link")
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").exists()), "$.token");
        RegisteredUser bob = newUser();
        RegisteredUser carol = newUser();
        accept(bob, link).andExpect(status().isOk());
        accept(carol, link).andExpect(status().isOk()); // dùng nhiều lần
        accept(bob, link).andExpect(status().isOk()); // đã là thành viên → idempotent

        String reset = readString(put(alice, "/api/v1/projects/" + project + "/invite-link")
                .andExpect(status().isOk()), "$.token");
        assertThat(reset).isNotEqualTo(link);
        accept(newUser(), link).andExpect(status().isNotFound());

        authDelete(alice.accessToken(), "/api/v1/projects/" + project + "/invite-link").andExpect(status().isNoContent());
        accept(newUser(), reset).andExpect(status().isNotFound());
    }

    @Test
    void outsiderCannotSeeOrManageTheShareSurface() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser mallory = newUser();
        UUID project = createProject(alice, "Chung");
        authGet(mallory.accessToken(), "/api/v1/projects/" + project + "/members").andExpect(status().isNotFound());
        authGet(mallory.accessToken(), "/api/v1/projects/" + project + "/invitations").andExpect(status().isNotFound());
        authGet(mallory.accessToken(), "/api/v1/projects/" + project + "/invite-link").andExpect(status().isNotFound());
        put(mallory, "/api/v1/projects/" + project + "/invite-link").andExpect(status().isNotFound());
        authPost(mallory.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + uniqueEmail() + "\"}").andExpect(status().isNotFound());
        authPost(mallory.accessToken(), "/api/v1/projects/" + project + "/leave").andExpect(status().isNotFound());
    }

    @Test
    void inboxCannotBeShared_andArchivedProjectCannotInvite() throws Exception {
        RegisteredUser alice = newUser();
        UUID inbox = testData.inboxOf(alice.id());
        authPost(alice.accessToken(), "/api/v1/projects/" + inbox + "/invitations",
                "{\"email\": \"" + uniqueEmail() + "\"}").andExpect(status().isForbidden());
        UUID project = createProject(alice, "Chung");
        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/archive").andExpect(status().isOk());
        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + uniqueEmail() + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    @Test
    void collaboratorLeavesProject_losesAccessAndFavorite_ownerCannotLeave() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID project = createProject(alice, "Chung");
        addToProject(alice, project, bob);
        authPut(bob.accessToken(), "/api/v1/projects/" + project + "/favorite").andExpect(status().isOk());

        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/leave").andExpect(status().isForbidden());
        authPost(bob.accessToken(), "/api/v1/projects/" + project + "/leave").andExpect(status().isNoContent());
        authGet(bob.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNotFound());
        Integer favorites = jdbcTemplate.queryForObject(
                "select count(*) from favorites where user_id = ? and target_id = ?", Integer.class, bob.id(), project);
        assertThat(favorites).isZero();
        authGet(alice.accessToken(), "/api/v1/projects/" + project).andExpect(jsonPath("$.memberCount").value(1));
        // rời rồi vào lại được (mời lại)
        addToProject(alice, project, bob);
    }

    @Test
    void owner_removesCollaborator_andFreesTheSeat() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID project = createProject(alice, "Chung");
        addToProject(alice, project, bob);

        authDelete(alice.accessToken(), "/api/v1/projects/" + project + "/members/" + alice.id())
                .andExpect(status().isForbidden()); // chủ tự rời = 403
        authDelete(alice.accessToken(), "/api/v1/projects/" + project + "/members/" + UUID.randomUUID())
                .andExpect(status().isNotFound());
        authDelete(alice.accessToken(), "/api/v1/projects/" + project + "/members/" + bob.id())
                .andExpect(status().isNoContent());
        authGet(bob.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNotFound());
    }

    @Test
    void autoAcceptInvites_addsTheInviteeImmediately_andMailsANotice() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID project = createProject(alice, "Chung");
        authGet(bob.accessToken(), "/api/v1/me/settings").andExpect(jsonPath("$.autoAcceptInvites").value(false));
        authPatch(bob.accessToken(), "/api/v1/me/settings", "{\"autoAcceptInvites\": true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.autoAcceptInvites").value(true));

        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + bob.email() + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.accepted").value(true));
        authGet(bob.accessToken(), "/api/v1/projects/" + project).andExpect(status().isOk());
        authGet(alice.accessToken(), "/api/v1/projects/" + project + "/invitations").andExpect(jsonPath("$", hasSize(0)));
        assertThat(mailbox.sentTo(bob.email())).hasSize(1);
        assertThat(mailbox.sentTo(bob.email()).get(0).body()).doesNotContain("taskly://join/");
    }

    @Test
    void autoAcceptInvites_stillRespectsTheSeatLimit() throws Exception {
        RegisteredUser alice = newUser();
        UUID project = createProject(alice, "Chung");
        for (int i = 0; i < 4; i++) {
            invite(alice, project, uniqueEmail());
        }
        RegisteredUser bob = newUser();
        authPatch(bob.accessToken(), "/api/v1/me/settings", "{\"autoAcceptInvites\": true}").andExpect(status().isOk());
        authPost(alice.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + bob.email() + "\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void acceptedCollaboratorsDoNotMakeProjectChildrenShared() throws Exception {
        RegisteredUser alice = newUser();
        RegisteredUser bob = newUser();
        UUID parent = createProject(alice, "Cha");
        UUID child = readUuid(authPost(alice.accessToken(), "/api/v1/projects",
                "{\"name\": \"Con\", \"parentId\": \"" + parent + "\"}").andExpect(status().isCreated()), "$.id");
        addToProject(alice, parent, bob);
        authGet(bob.accessToken(), "/api/v1/projects/" + parent).andExpect(status().isOk());
        authGet(bob.accessToken(), "/api/v1/projects/" + child).andExpect(status().isNotFound());
    }
}
