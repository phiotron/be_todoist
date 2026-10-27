package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.common.error.ProblemTexts;
import com.taskly.support.M2ApiTestBase;
import com.taskly.service.LabelDeletedEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** /api/v1/view-options (spec 12.7, 5.7). */
class ViewOptionsApiTest extends M2ApiTestBase {

    private static final String VIEW_OPTIONS = "/api/v1/view-options";

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private RegisteredUser user;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser(uniqueEmail());
        token = user.accessToken();
    }

    private ResultActions put(String viewKey, String json) throws Exception {
        return apiPut(token, VIEW_OPTIONS + "/" + viewKey, json);
    }

    private static String display(String sortBy, String groupBy, String extra) {
        return "{\"sortBy\": \"" + sortBy + "\", \"sortDirection\": \"DESC\", \"groupBy\": \"" + groupBy + "\""
                + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }

    @Test
    void put_savesDisplay_andListsIt() throws Exception {
        put("TODAY", display("PRIORITY", "PROJECT", "\"filterPriority\": 1, \"filterDue\": \"OVERDUE\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewKey").value("TODAY"))
                .andExpect(jsonPath("$.sortBy").value("PRIORITY"))
                .andExpect(jsonPath("$.sortDirection").value("DESC"))
                .andExpect(jsonPath("$.groupBy").value("PROJECT"))
                .andExpect(jsonPath("$.filterPriority").value(1))
                .andExpect(jsonPath("$.filterLabelId").doesNotExist())
                .andExpect(jsonPath("$.filterDue").value("OVERDUE"))
                .andExpect(jsonPath("$.showCompleted").value(false));

        apiGet(token, VIEW_OPTIONS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].viewKey").value("TODAY"));
    }

    @Test
    void putTwice_replacesTheSameRow() throws Exception {
        put("UPCOMING", display("NAME", "NONE", "")).andExpect(status().isOk());
        put("UPCOMING", display("ADDED", "NONE", "\"filterDue\": \"NEXT_7_DAYS\"")).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select count(*) from view_options where user_id = ?", Integer.class,
                user.id())).isEqualTo(1);
        apiGet(token, VIEW_OPTIONS).andExpect(jsonPath("$[0].sortBy").value("ADDED"))
                .andExpect(jsonPath("$[0].filterDue").value("NEXT_7_DAYS"));
    }

    @Test
    void valuesNotAllowedForTheView_are400() throws Exception {
        put("TODAY", display("MANUAL", "NONE", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.DISPLAY_INVALID));
        put("UPCOMING", display("DATE", "PRIORITY", "")).andExpect(status().isBadRequest());
        put("TODAY", display("DATE", "NONE", "\"showCompleted\": true")).andExpect(status().isBadRequest());
        put("TODAY", display("DATE", "NONE", "\"filterPriority\": 5")).andExpect(status().isBadRequest());
        put("TODAY", display("DATE", "NONE", "\"filterDue\": \"SOON\"")).andExpect(status().isBadRequest());
        put("TODAY", "{\"sortDirection\": \"ASC\", \"groupBy\": \"NONE\"}").andExpect(status().isBadRequest());
        apiGet(token, VIEW_OPTIONS).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void projectKey_ownOrArchivedIsOk_foreignOrMissingIs404() throws Exception {
        UUID project = data.project(user.id(), "Việc");
        UUID archived = data.project(user.id(), "Cũ");
        data.archiveProject(archived);
        UUID foreign = data.project(registerUser(uniqueEmail()).id(), "Của người khác");

        put("project:" + project, display("MANUAL", "LABEL", "\"showCompleted\": true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showCompleted").value(true));
        put("project:" + archived, display("NAME", "NONE", "")).andExpect(status().isOk());
        put("project:" + foreign, display("NAME", "NONE", "")).andExpect(status().isNotFound());
        put("project:" + UUID.randomUUID(), display("NAME", "NONE", "")).andExpect(status().isNotFound());
        put("project:" + project, display("NAME", "PROJECT", "")).andExpect(status().isBadRequest());
    }

    @Test
    void labelAndFilterKeys_andMalformedKeys() throws Exception {
        UUID label = data.label(user.id(), "nha");
        UUID filter = data.filter(user.id(), "Gấp", "p1");
        UUID foreignLabel = data.label(registerUser(uniqueEmail()).id(), "x");

        put("label:" + label, display("PRIORITY", "PROJECT", "")).andExpect(status().isOk());
        put("filter:" + filter, display("DEADLINE", "LABEL", "")).andExpect(status().isOk());
        put("label:" + foreignLabel, display("DATE", "NONE", "")).andExpect(status().isNotFound());
        put("today", display("DATE", "NONE", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.VIEW_KEY_INVALID));
        put("project:abc", display("DATE", "NONE", "")).andExpect(status().isBadRequest());
    }

    @Test
    void filterLabelId_mustBeOwnLabel() throws Exception {
        UUID label = data.label(user.id(), "nha");
        UUID foreign = data.label(registerUser(uniqueEmail()).id(), "x");

        put("TODAY", display("DATE", "NONE", "\"filterLabelId\": \"" + label + "\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.filterLabelId").value(label.toString()));
        put("TODAY", display("DATE", "NONE", "\"filterLabelId\": \"" + foreign + "\""))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_resetsToDefault_andIsIdempotent() throws Exception {
        put("TODAY", display("NAME", "NONE", "")).andExpect(status().isOk());

        apiDelete(token, VIEW_OPTIONS + "/TODAY").andExpect(status().isNoContent());
        apiDelete(token, VIEW_OPTIONS + "/TODAY").andExpect(status().isNoContent());
        apiGet(token, VIEW_OPTIONS).andExpect(jsonPath("$.length()").value(0));
        apiDelete(token, VIEW_OPTIONS + "/project:" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void deletingProjectOrLabelThroughApi_cleansViewOptions() throws Exception {
        UUID project = data.project(user.id(), "Sẽ xoá");
        UUID label = data.label(user.id(), "nha");
        put("project:" + project, display("NAME", "NONE", "")).andExpect(status().isOk());
        put("label:" + label, display("NAME", "NONE", "")).andExpect(status().isOk());
        put("TODAY", display("DATE", "NONE", "\"filterLabelId\": \"" + label + "\"")).andExpect(status().isOk());

        apiDelete(token, "/api/v1/projects/" + project).andExpect(status().isNoContent());
        apiDelete(token, "/api/v1/labels/" + label).andExpect(status().isNoContent());

        apiGet(token, VIEW_OPTIONS)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].viewKey").value("TODAY"))
                .andExpect(jsonPath("$[0].filterLabelId").doesNotExist());
    }

    @Test
    void deletingParentProject_cleansViewOptionsOfItsChildProjectsToo() throws Exception {
        UUID parent = data.project(user.id(), "Cha");
        UUID child = data.project(user.id(), parent, "Con");
        UUID keep = data.project(user.id(), "Giữ lại");
        put("project:" + parent, display("NAME", "NONE", "")).andExpect(status().isOk());
        put("project:" + child, display("NAME", "NONE", "")).andExpect(status().isOk());
        put("project:" + keep, display("NAME", "NONE", "")).andExpect(status().isOk());

        apiDelete(token, "/api/v1/projects/" + parent).andExpect(status().isNoContent());   // M1 phát ProjectsDeletedEvent(cha + con)

        apiGet(token, VIEW_OPTIONS)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].viewKey").value("project:" + keep));
    }

    @Test
    void deletingFilterThroughApi_cleansItsViewOptions() throws Exception {
        UUID filter = data.filter(user.id(), "Gấp", "p1");
        put("filter:" + filter, display("DEADLINE", "LABEL", "")).andExpect(status().isOk());
        put("TODAY", display("DATE", "NONE", "")).andExpect(status().isOk());

        apiDelete(token, "/api/v1/filters/" + filter).andExpect(status().isNoContent());

        apiGet(token, VIEW_OPTIONS)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].viewKey").value("TODAY"));
    }

    /** Spec 12.7, 12.10, 18.1 m6: Today luôn có due → no_date vô nghĩa; Upcoming không có task quá hạn / không ngày. */
    @Test
    void filterDueNoDateOrOverdue_isRejectedForTodayAndUpcoming() throws Exception {
        put("TODAY", display("DATE", "NONE", "\"filterDue\": \"NO_DATE\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.DISPLAY_INVALID));
        put("UPCOMING", display("DATE", "NONE", "\"filterDue\": \"NO_DATE\"")).andExpect(status().isBadRequest());
        put("UPCOMING", display("DATE", "NONE", "\"filterDue\": \"OVERDUE\"")).andExpect(status().isBadRequest());
        put("TODAY", display("DATE", "NONE", "\"filterDue\": \"OVERDUE\"")).andExpect(status().isOk());
        put("UPCOMING", display("DATE", "NONE", "\"filterDue\": \"NEXT_7_DAYS\"")).andExpect(status().isOk());
    }

    @Test
    void cleanup_removesOnlyTheDeletedEntitysKeys_andNeverOtherUsersOtherKeys() throws Exception {
        RegisteredUser other = registerUser(uniqueEmail());
        UUID label = data.label(user.id(), "nha");
        UUID otherLabel = data.label(user.id(), "khac");
        UUID filter = data.filter(user.id(), "Gấp", "p1");
        UUID project = data.project(user.id(), "Dự án");
        UUID otherProject = data.project(user.id(), "Dự án khác");
        UUID othersLabel = data.label(other.id(), "cua-nguoi-khac");
        for (String key : new String[] {"label:" + label, "label:" + otherLabel, "filter:" + filter,
                "project:" + project, "project:" + otherProject, "TODAY"}) {
            data.viewOption(user.id(), key, null);
        }
        data.viewOption(other.id(), "TODAY", null);
        data.viewOption(other.id(), "label:" + othersLabel, null);

        apiDelete(token, "/api/v1/labels/" + label).andExpect(status().isNoContent());
        apiDelete(token, "/api/v1/filters/" + filter).andExpect(status().isNoContent());
        apiDelete(token, "/api/v1/projects/" + project).andExpect(status().isNoContent());

        assertThat(keysOf(user.id())).containsExactlyInAnyOrder("label:" + otherLabel, "project:" + otherProject,
                "TODAY");
        assertThat(keysOf(other.id())).containsExactlyInAnyOrder("TODAY", "label:" + othersLabel);
    }

    /** Sự kiện đồng bộ cùng transaction: xoá view_options hoàn tác khi transaction của lệnh xoá rollback. */
    @Test
    void cleanup_isSameTransaction_rollsBackWithTheDelete() {
        UUID label = data.label(user.id(), "nha");
        data.viewOption(user.id(), "label:" + label, null);

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            eventPublisher.publishEvent(new LabelDeletedEvent(user.id(), label));
            assertThat(keysOf(user.id())).isEmpty();
            tx.setRollbackOnly();
        });

        assertThat(keysOf(user.id())).containsExactly("label:" + label);
    }

    private List<String> keysOf(UUID userId) {
        return jdbcTemplate.queryForList("select view_key from view_options where user_id = ?", String.class, userId);
    }
}
