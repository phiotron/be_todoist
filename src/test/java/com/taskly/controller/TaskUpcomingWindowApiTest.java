package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * view=upcoming theo CỬA SỔ NGÀY (spec 12.3, I9): from mặc định hôm nay, to mặc định from + 13, cửa sổ ≤ 31 ngày
 * (to − from ≤ 30), tầm xem ≤ 2 năm. Giờ đóng băng 2026-10-20T03:00:00Z = 10:00 ngày 20/10 ở Việt Nam. Mỗi ngày từ
 * 20/10 tới 30/11 có đúng một task tên "d" + yyyy-MM-dd.
 */
class TaskUpcomingWindowApiTest extends IntegrationTestBase {

    private static final String UPCOMING = "/api/v1/tasks?view=upcoming";
    private static final String WINDOW_DETAIL =
            "view=upcoming chỉ xem tối đa 31 ngày mỗi lần và không quá 2 năm kể từ hôm nay";

    private RegisteredUser user;

    private static List<String> names(LocalDate from, LocalDate to) {
        List<String> names = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            names.add("d" + day);
        }
        return names;
    }

    @BeforeEach
    void data() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-20T03:00:00Z"));
        user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        for (String name : names(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 30))) {
            testData.insert(TaskRow.of(user.id(), project, name).dueDate(LocalDate.parse(name.substring(1))));
        }
    }

    @Test
    void defaultWindow_isFourteenDaysFromToday_andFromBeforeTodayIsClamped() throws Exception {
        List<String> expected = names(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 2)); // from + 13
        authGet(user.accessToken(), UPCOMING)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].content", contains(expected.toArray())))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-01") // from < hôm nay → tính từ hôm nay
                .andExpect(jsonPath("$.items[*].content", contains(expected.toArray())));
    }

    @Test
    void explicitTo_limitsTheWindow_andConsecutiveWindowsDoNotOverlap() throws Exception {
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-27&to=2026-10-29")
                .andExpect(jsonPath("$.items[*].content", contains("d2026-10-27", "d2026-10-28", "d2026-10-29")));
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-30&to=2026-11-01") // cửa sổ kế tiếp: from = to + 1
                .andExpect(jsonPath("$.items[*].content", contains("d2026-10-30", "d2026-10-31", "d2026-11-01")));
    }

    @Test
    void toWithoutFrom_startsAtToday() throws Exception {
        authGet(user.accessToken(), UPCOMING + "&to=2026-10-22")
                .andExpect(jsonPath("$.items[*].content", contains("d2026-10-20", "d2026-10-21", "d2026-10-22")));
    }

    @Test
    void windowOfThirtyOneDaysIsAllowed_thirtyTwoIs400() throws Exception {
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-20&to=2026-11-19") // to − from = 30
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(31)));
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-20&to=2026-11-20") // to − from = 31
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(WINDOW_DETAIL));
    }

    @Test
    void toBeforeFrom_andBeyondTwoYears_are400() throws Exception {
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-25&to=2026-10-24").andExpect(status().isBadRequest());
        // hôm nay + 2 năm = 2028-10-20
        authGet(user.accessToken(), UPCOMING + "&from=2028-10-21").andExpect(status().isBadRequest());
        authGet(user.accessToken(), UPCOMING + "&from=2028-10-20&to=2028-10-21").andExpect(status().isBadRequest());
        authGet(user.accessToken(), UPCOMING + "&from=2028-10-20&to=2028-10-20")
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", empty()));
    }

    /**
     * CHỜ NGƯỜI DÙNG QUYẾT (review-notes Task 21): plan kẹp from về hôm nay rồi mới kiểm to ≥ from, nên cả from và to
     * đều trước hôm nay → 400. Spec 12.3 có thể đọc là cửa sổ hợp lệ nhưng rỗng (200 + items rỗng). Test này khoá hành
     * vi THEO PLAN; đổi quyết định thì sửa test + UpcomingWindow trong TaskViewService.
     */
    @Test
    void upcoming_bothBeforeToday_is400PerPlan_pendingDecision() throws Exception {
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-01&to=2026-10-05")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(WINDOW_DETAIL));
        // to đúng bằng hôm qua cũng vậy; to = hôm nay thì hợp lệ (chỉ hôm nay)
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-10&to=2026-10-19").andExpect(status().isBadRequest());
        authGet(user.accessToken(), UPCOMING + "&from=2026-10-10&to=2026-10-20")
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].content", contains("d2026-10-20")));
    }
}
