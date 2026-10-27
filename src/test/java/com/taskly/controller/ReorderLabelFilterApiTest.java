package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M2ApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class ReorderLabelFilterApiTest extends M2ApiTestBase {

    private ResultActions reorder(String token, String type, UUID id, UUID afterId) throws Exception {
        String after = afterId == null ? "null" : "\"" + afterId + "\"";
        return apiPost(token, "/api/v1/reorder",
                "{\"type\": \"" + type + "\", \"id\": \"" + id + "\", \"afterId\": " + after + "}");
    }

    @Test
    void labels_moveToTopAndAfter_andRenumberWhenGapIsExhausted() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID one = data.label(user.id(), "mot", 1);
        UUID two = data.label(user.id(), "hai", 2);
        UUID three = data.label(user.id(), "ba", 3);

        reorder(token, "LABEL", three, one)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("LABEL"))
                .andExpect(jsonPath("$.id").value(three.toString()));
        apiGet(token, "/api/v1/labels").andExpect(jsonPath("$[*].name", contains("mot", "ba", "hai")));

        reorder(token, "LABEL", two, null).andExpect(status().isOk());
        apiGet(token, "/api/v1/labels").andExpect(jsonPath("$[*].name", contains("hai", "mot", "ba")));
        List<Long> orders = jdbcTemplate.queryForList("select sort_order from labels where user_id = ?", Long.class,
                user.id());
        assertThat(orders).doesNotHaveDuplicates();
    }

    @Test
    void labelReorder_usesNativeUpdates_andNeverTouchesTasks() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID one = data.label(user.id(), "mot", 1);
        UUID two = data.label(user.id(), "hai", 2);
        UUID three = data.label(user.id(), "ba", 3);
        UUID task = data.task(data.inboxOf(user.id()), "Việc");
        data.tag(task, one, three);
        Long version = jdbcTemplate.queryForObject("select version from tasks where id = ?", Long.class, task);
        Object updatedAt = jdbcTemplate.queryForObject("select updated_at from tasks where id = ?", Object.class, task);

        reorder(token, "LABEL", three, null).andExpect(status().isOk());   // sort_order sát nhau → đánh số lại cả vùng chứa
        reorder(token, "LABEL", one, two).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select version from tasks where id = ?", Long.class, task))
                .as("sắp xếp label không tăng tasks.version (spec 5.6, 8.2 #11)").isEqualTo(version);
        assertThat(jdbcTemplate.queryForObject("select updated_at from tasks where id = ?", Object.class, task))
                .isEqualTo(updatedAt);
    }

    @Test
    void filters_moveAfterAnother() throws Exception {
        var user = registerUser(uniqueEmail());
        UUID first = data.filter(user.id(), "Một", "p1");
        UUID second = data.filter(user.id(), "Hai", "p2");

        reorder(user.accessToken(), "FILTER", first, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FILTER"));
        apiGet(user.accessToken(), "/api/v1/filters").andExpect(jsonPath("$[*].name", contains("Hai", "Một")));
    }

    @Test
    void foreignIdIs404_foreignAfterIdIs400() throws Exception {
        var alice = registerUser(uniqueEmail());
        var bob = registerUser(uniqueEmail());
        UUID aliceLabel = data.label(alice.id(), "a");
        UUID bobLabel = data.label(bob.id(), "b");
        UUID aliceFilter = data.filter(alice.id(), "A", "p1");

        reorder(bob.accessToken(), "LABEL", aliceLabel, null).andExpect(status().isNotFound());
        reorder(bob.accessToken(), "LABEL", bobLabel, aliceLabel).andExpect(status().isBadRequest());
        reorder(bob.accessToken(), "FILTER", aliceFilter, null).andExpect(status().isNotFound());
    }
}
