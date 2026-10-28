package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import com.taskly.support.Texts;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** M7: view_options.layout, showCompleted Today / Upcoming, showFutureOccurrences, Grouping "Added date". */
class M7LayoutApiTest extends M2ApiTestBase {

    private static final String VIEW_OPTIONS = "/api/v1/view-options";

    private RegisteredUser user;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        user = registerUser(uniqueEmail());
        token = user.accessToken();
    }

    private ResultActions put(String viewKey, String extra) throws Exception {
        String body = "{\"sortBy\": \"DATE\", \"sortDirection\": \"ASC\", \"groupBy\": \"NONE\""
                + (extra.isEmpty() ? "" : ", " + extra) + "}";
        return apiPut(token, VIEW_OPTIONS + "/" + viewKey, body);
    }

    @Test
    void layout_defaultsToList_isSaved_andAbsentKeepsTheSavedValue() throws Exception {
        put("TODAY", "").andExpect(status().isOk()).andExpect(jsonPath("$.layout").value("LIST"))
                .andExpect(jsonPath("$.showFutureOccurrences").value(false));
        put("TODAY", "\"layout\": \"BOARD\"").andExpect(status().isOk()).andExpect(jsonPath("$.layout").value("BOARD"));
        // PUT của app M2 (không có layout) không làm mất layout đã chọn.
        put("TODAY", "\"filterPriority\": 1").andExpect(status().isOk()).andExpect(jsonPath("$.layout").value("BOARD"));
        put("TODAY", "\"layout\": \"CALENDAR\"").andExpect(status().isOk());

        apiGet(token, VIEW_OPTIONS).andExpect(jsonPath("$[0].layout").value("CALENDAR"));
        assertThat(jdbcTemplate.queryForObject("select layout from view_options where user_id = ?", String.class,
                user.id())).isEqualTo("CALENDAR");
    }

    @Test
    void layout_everyViewKindAcceptsAllThreeLayouts() throws Exception {
        UUID project = data.project(user.id(), "Việc");
        UUID label = data.label(user.id(), "nha");
        UUID filter = data.filter(user.id(), "Gấp", "p1");
        for (String key : new String[] {"TODAY", "UPCOMING", "project:" + project, "label:" + label,
                "filter:" + filter}) {
            for (String layout : new String[] {"LIST", "BOARD", "CALENDAR"}) {
                String sort = key.startsWith("project:") ? "MANUAL" : "DATE";
                apiPut(token, VIEW_OPTIONS + "/" + key, "{\"sortBy\": \"" + sort + "\", \"sortDirection\": \"ASC\","
                        + " \"groupBy\": \"NONE\", \"layout\": \"" + layout + "\"}")
                        .andExpect(status().isOk()).andExpect(jsonPath("$.layout").value(layout));
            }
        }
    }

    @Test
    void layout_unknownValue_is400_andResetGoesBackToList() throws Exception {
        put("TODAY", "\"layout\": \"TIMELINE\"").andExpect(status().isBadRequest());
        put("TODAY", "\"layout\": \"board\"").andExpect(status().isBadRequest());
        put("TODAY", "\"layout\": \"BOARD\"").andExpect(status().isOk());

        apiDelete(token, VIEW_OPTIONS + "/TODAY").andExpect(status().isNoContent());
        put("TODAY", "").andExpect(jsonPath("$.layout").value("LIST"));
    }

    @Test
    void layout_checkConstraintRejectsUnknownValuesInTheDatabase() {
        data.viewOption(user.id(), "TODAY", null);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbcTemplate.update(
                "update view_options set layout = 'TIMELINE' where user_id = ?", user.id()));
        assertThat(jdbcTemplate.queryForObject("select layout from view_options where user_id = ?", String.class,
                user.id())).isEqualTo("LIST");
    }

    @Test
    void showCompleted_isAllowedForTodayUpcomingAndProjects_notForLabelsOrFilters() throws Exception {
        UUID label = data.label(user.id(), "nha");
        UUID filter = data.filter(user.id(), "Gấp", "p1");
        put("TODAY", "\"showCompleted\": true").andExpect(status().isOk())
                .andExpect(jsonPath("$.showCompleted").value(true));
        put("UPCOMING", "\"showCompleted\": true").andExpect(status().isOk())
                .andExpect(jsonPath("$.showCompleted").value(true));
        put("label:" + label, "\"showCompleted\": true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.DISPLAY_INVALID)));
        put("filter:" + filter, "\"showCompleted\": true").andExpect(status().isBadRequest());
    }

    @Test
    void showFutureOccurrences_onlyOnUpcoming_andAbsentKeepsTheSavedValue() throws Exception {
        put("UPCOMING", "\"showFutureOccurrences\": true").andExpect(status().isOk())
                .andExpect(jsonPath("$.showFutureOccurrences").value(true));
        put("UPCOMING", "").andExpect(jsonPath("$.showFutureOccurrences").value(true));
        put("UPCOMING", "\"showFutureOccurrences\": false").andExpect(jsonPath("$.showFutureOccurrences").value(false));
        put("TODAY", "\"showFutureOccurrences\": true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.DISPLAY_INVALID)));
        put("TODAY", "\"showFutureOccurrences\": false").andExpect(status().isOk());
    }

    @Test
    void groupAdded_canBeSavedForTodayAndProjects_butNotUpcoming() throws Exception {
        UUID project = data.project(user.id(), "Việc");
        apiPut(token, VIEW_OPTIONS + "/TODAY",
                "{\"sortBy\": \"DATE\", \"sortDirection\": \"ASC\", \"groupBy\": \"ADDED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupBy").value("ADDED"));
        apiPut(token, VIEW_OPTIONS + "/project:" + project,
                "{\"sortBy\": \"MANUAL\", \"sortDirection\": \"ASC\", \"groupBy\": \"ADDED\"}")
                .andExpect(status().isOk());
        apiPut(token, VIEW_OPTIONS + "/UPCOMING",
                "{\"sortBy\": \"DATE\", \"sortDirection\": \"ASC\", \"groupBy\": \"ADDED\"}")
                .andExpect(status().isBadRequest());
    }

    /**
     * group=added: khoá nhóm = ngày của created_at theo MÚI GIỜ TÀI KHOẢN, ngày mới trước. 03/11 17:30Z = 04/11
     * 00:30 giờ VN → cùng nhóm 04/11 với task tạo 04/11 02:00Z, không phải nhóm 03/11.
     */
    @Test
    void groupAdded_ordersByTheAccountZoneDayOfCreation_newestDayFirst() throws Exception {
        UUID inbox = data.inboxOf(user.id());
        data.insert(TaskSpec.of(inbox, "b-old").createdAt(Instant.parse("2026-11-01T05:00:00Z")).sortOrder(1_000));
        data.insert(TaskSpec.of(inbox, "c-vn-midnight").createdAt(Instant.parse("2026-11-03T17:30:00Z"))
                .sortOrder(3_000));
        data.insert(TaskSpec.of(inbox, "a-today").createdAt(Instant.parse("2026-11-04T02:00:00Z")).sortOrder(2_000));
        data.insert(TaskSpec.of(inbox, "d-utc-3rd").createdAt(Instant.parse("2026-11-03T05:00:00Z"))
                .sortOrder(4_000));

        List<String> byName = contents(getOk(token, "/api/v1/tasks", "projectId", inbox.toString(), "group",
                "added", "sort", "name", "limit", "200"));
        assertThat(byName).containsExactly("a-today", "c-vn-midnight", "d-utc-3rd", "b-old");

        // Phân trang khi đang nhóm: không trùng, không sót.
        assertThat(collectAllContents(token, "/api/v1/tasks", 1, "projectId", inbox.toString(), "group", "added",
                "sort", "name")).isEqualTo(byName);
    }

    @Test
    void groupAdded_worksOnTodayLabelAndFilterLists() throws Exception {
        UUID inbox = data.inboxOf(user.id());
        UUID label = data.label(user.id(), "nha");
        UUID older = data.insert(TaskSpec.of(inbox, "older").dueDate(LocalDate.of(2026, 11, 4))
                .createdAt(Instant.parse("2026-10-01T05:00:00Z")));
        UUID newer = data.insert(TaskSpec.of(inbox, "newer").dueDate(LocalDate.of(2026, 11, 4))
                .createdAt(Instant.parse("2026-11-02T05:00:00Z")));
        data.tag(older, label);
        data.tag(newer, label);

        assertThat(contents(getOk(token, "/api/v1/tasks", "view", "today", "group", "added")))
                .containsExactly("newer", "older");
        assertThat(contents(getOk(token, "/api/v1/tasks", "labelId", label.toString(), "group", "added")))
                .containsExactly("newer", "older");
        assertThat(contents(getOk(token, "/api/v1/tasks", "filter", "today", "group", "added")))
                .containsExactly("newer", "older");
        apiGet(token, "/api/v1/tasks", "view", "upcoming", "group", "added").andExpect(status().isBadRequest());
    }
}
