package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.support.M8aApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Giới hạn 5 người / dự án chia sẻ (M8a, PROJECT_MEMBERS) khi nhiều request SONG SONG cùng muốn lấy chỗ cuối: mời email
 * (InvitationService.inviteToProject) và tham gia bằng link (MembershipService.addToProject) đều khoá hàng dự án
 * (UsageLimits.lockProject) rồi mới đếm chỗ giữ (chủ + cộng tác viên + lời mời còn hiệu lực) → đúng MỘT thành công, còn
 * lại 422 limit-reached; không bao giờ vượt 5 chỗ.
 */
class ProjectMemberLimitRaceApiTest extends M8aApiTestBase {

    private RegisteredUser owner;
    private UUID project;

    @BeforeEach
    void projectWithFourSeatsTaken() throws Exception {
        owner = newUser();
        project = createProject(owner, "Chung");
        for (int i = 0; i < 3; i++) {
            addToProject(owner, project, newUser()); // chủ + 3 cộng tác viên = 4 chỗ
        }
    }

    @FunctionalInterface
    private interface IndexedCall {
        int status(int index) throws Exception;
    }

    private static List<Integer> parallel(int threads, IndexedCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.status(index);
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdown();
        }
    }

    private static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    private int inviteStatus(String email) throws Exception {
        return authPost(owner.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + email + "\"}").andReturn().getResponse().getStatus();
    }

    private int seats() {
        Integer members = jdbcTemplate.queryForObject("select count(*) from project_members where project_id = ?",
                Integer.class, project);
        Integer pending = jdbcTemplate.queryForObject(
                "select count(*) from invitations where project_id = ? and accepted_at is null and declined_at is null", Integer.class, project);
        return 1 + members + pending;
    }

    @Test
    void twoParallelEmailInvitations_atFourSeats_exactlyOneSucceedsAndOneIs422() throws Exception {
        String a = uniqueEmail();
        String b = uniqueEmail();

        List<Integer> statuses = parallel(2, i -> inviteStatus(i == 0 ? a : b));

        assertThat(count(statuses, 201)).isEqualTo(1);
        assertThat(count(statuses, 422)).isEqualTo(1);
        assertThat(seats()).isEqualTo(5);
    }

    @Test
    void twoParallelLinkJoins_atFourSeats_exactlyOneSucceedsAndOneIs422() throws Exception {
        String link = readString(put(owner, "/api/v1/projects/" + project + "/invite-link").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()), "$.token");
        List<RegisteredUser> joiners = List.of(newUser(), newUser());

        List<Integer> statuses = parallel(2,
                i -> accept(joiners.get(i), link).andReturn().getResponse().getStatus());

        assertThat(count(statuses, 200)).isEqualTo(1);
        assertThat(count(statuses, 422)).isEqualTo(1);
        assertThat(seats()).isEqualTo(5);
    }

    @Test
    void anEmailInvitationRacingALinkJoin_neverExceedFiveSeats() throws Exception {
        String link = readString(put(owner, "/api/v1/projects/" + project + "/invite-link").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()), "$.token");
        RegisteredUser joiner = newUser();
        String email = uniqueEmail();

        List<Integer> statuses = parallel(2, i -> i == 0
                ? inviteStatus(email)
                : accept(joiner, link).andReturn().getResponse().getStatus());

        assertThat(count(statuses, 422)).isEqualTo(1);
        assertThat(statuses).filteredOn(s -> s == 201 || s == 200).hasSize(1);
        assertThat(seats()).isEqualTo(5);
    }

    @Test
    void manyParallelRequests_atFourSeats_stillFillOnlyTheLastSeat() throws Exception {
        int threads = 6;
        List<RegisteredUser> joiners = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            joiners.add(newUser());
        }
        String link = readString(put(owner, "/api/v1/projects/" + project + "/invite-link").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()), "$.token");

        List<Integer> statuses = parallel(threads,
                i -> accept(joiners.get(i), link).andReturn().getResponse().getStatus());

        assertThat(count(statuses, 200)).isEqualTo(1);
        assertThat(count(statuses, 422)).isEqualTo(threads - 1);
        assertThat(seats()).isEqualTo(5);
    }
}
