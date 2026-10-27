package com.taskly.controller;

import com.taskly.common.error.ErrorMessage;
import com.taskly.support.Texts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import com.taskly.service.LabelService;
import com.taskly.support.M2ApiTestBase;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** /api/v1/labels (spec 12.7, 2.1, 5.9, 12.10, 12.11). Ngưỡng test: tối đa 5 label / user (M2ApiTestBase hạ bằng overrideMax). */
class LabelApiTest extends M2ApiTestBase {

    private static final String LABELS = "/api/v1/labels";

    @Autowired
    private LabelService labelService;

    private UUID create(String token, String json) throws Exception {
        return readId(apiPost(token, LABELS, json).andExpect(status().isCreated()));
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void create_defaultsToCharcoal_appendsAtEnd_andListsInSortOrder() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, LABELS, "{\"name\": \"  gấp \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("gấp"))
                .andExpect(jsonPath("$.color").value("charcoal"))
                .andExpect(jsonPath("$.isFavorite").value(false));
        create(token, "{\"name\": \"nha\", \"color\": \"berry_red\"}");

        apiGet(token, LABELS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("gấp", "nha")))
                .andExpect(jsonPath("$[1].color").value("berry_red"));
        String json = getOk(token, LABELS);
        long first = ((Number) com.jayway.jsonpath.JsonPath.read(json, "$[0].sortOrder")).longValue();
        long second = ((Number) com.jayway.jsonpath.JsonPath.read(json, "$[1].sortOrder")).longValue();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void create_invalidNameOrColor_is400() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, LABELS, "{\"name\": \"hai từ\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABEL_NAME_CHARS)));
        apiPost(token, LABELS, "{\"name\": \"a@b\"}").andExpect(status().isBadRequest());
        apiPost(token, LABELS, "{\"name\": \"" + "x".repeat(61) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABEL_NAME_SIZE)));
        apiPost(token, LABELS, "{}").andExpect(status().isBadRequest());
        apiPost(token, LABELS, "{\"name\": \"ok\", \"color\": \"purple\"}").andExpect(status().isBadRequest());
        assertThat(getOk(token, LABELS)).isEqualTo("[]");
    }

    @Test
    void duplicateNameIgnoringCase_is409LabelNameTaken() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        create(token, "{\"name\": \"Gấp\"}");

        apiPost(token, LABELS, "{\"name\": \"gẤP\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("label-name-taken"))
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABEL_NAME_TAKEN)));

        String other = registerUser(uniqueEmail()).accessToken();
        create(other, "{\"name\": \"gấp\"}"); // user khác được trùng tên
    }

    @Test
    void update_renamesAndRecolors_ownNameAllowed_otherNameIs409() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID home = create(token, "{\"name\": \"nha\"}");
        create(token, "{\"name\": \"gap\"}");

        apiPatch(token, LABELS + "/" + home, "{\"name\": \"NHA\", \"color\": \"teal\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NHA"))
                .andExpect(jsonPath("$.color").value("teal"));
        apiPatch(token, LABELS + "/" + home, "{\"color\": \"red\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NHA"))
                .andExpect(jsonPath("$.color").value("red"));
        apiPatch(token, LABELS + "/" + home, "{\"name\": \"Gap\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("label-name-taken"));
        apiPatch(token, LABELS + "/" + home, "{\"name\": \"a b\"}").andExpect(status().isBadRequest());
    }

    @Test
    void update_withSameValues_keepsUpdatedAt() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID label = create(token, "{\"name\": \"nha\", \"color\": \"teal\"}");
        Timestamp before = jdbcTemplate.queryForObject("select updated_at from labels where id = ?", Timestamp.class, label);
        clock.advance(java.time.Duration.ofMinutes(5));

        apiPatch(token, LABELS + "/" + label, "{\"name\": \"nha\", \"color\": \"teal\"}").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("select updated_at from labels where id = ?", Timestamp.class, label))
                .isEqualTo(before);
    }

    @Test
    void delete_removesLabelFromTasksAndFavorites_andClearsFilterLabelReferences() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID label = create(token, "{\"name\": \"nha\"}");
        UUID task = data.task(data.inboxOf(user.id()), "Dọn nhà");
        data.tag(task, label);
        apiPut(token, LABELS + "/" + label + "/favorite", null).andExpect(status().isOk());
        data.viewOption(user.id(), "TODAY", label);

        apiDelete(token, LABELS + "/" + label).andExpect(status().isNoContent());

        assertThat(count("select count(*) from task_labels where task_id = ?", task)).isZero();
        assertThat(count("select count(*) from favorites where target_id = ?", label)).isZero();
        assertThat(count("select count(*) from view_options where user_id = ? and filter_label_id is not null",
                user.id())).isZero();   // FK ON DELETE SET NULL; Display `label:<id>` do ViewOptionsService xoá — test ở Task 17
        apiDelete(token, LABELS + "/" + label).andExpect(status().isNotFound());
    }

    @Test
    void favorite_putAndDelete_areReflectedInLabelsAndFavorites() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID label = create(token, "{\"name\": \"nha\"}");

        apiPut(token, LABELS + "/" + label + "/favorite", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFavorite").value(true));
        apiPut(token, LABELS + "/" + label + "/favorite", null).andExpect(status().isOk()); // idempotent
        apiGet(token, "/api/v1/favorites")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.targetType == 'LABEL')].targetId", contains(label.toString())));
        apiGet(token, LABELS).andExpect(jsonPath("$[0].isFavorite").value(true));

        apiDelete(token, LABELS + "/" + label + "/favorite")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFavorite").value(false));
        apiGet(token, "/api/v1/favorites")
                .andExpect(jsonPath("$[?(@.targetType == 'LABEL')]").isEmpty());
    }

    @Test
    void sixthLabel_is422LimitReached() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        for (int i = 1; i <= 5; i++) {
            create(token, "{\"name\": \"l" + i + "\"}");
        }

        apiPost(token, LABELS, "{\"name\": \"l6\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("LABELS"))
                .andExpect(jsonPath("$.max").value(5));
    }

    @Test
    void idempotencyKey_replaysTheSameLabel() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        String key = UUID.randomUUID().toString();

        UUID first = readId(apiPostWithKey(token, LABELS, key, "{\"name\": \"nha\"}").andExpect(status().isCreated()));
        UUID replay = readId(apiPostWithKey(token, LABELS, key, "{\"name\": \"nha\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true")));

        assertThat(replay).isEqualTo(first);
        apiGet(token, LABELS).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void clientId_isUsed_sameContentReplaysWith200_otherContentOrOtherUsersIdIs409IdConflict() throws Exception {
        var alice = registerUser(uniqueEmail());
        var bob = registerUser(uniqueEmail());
        UUID id = UUID.randomUUID();
        String json = "{\"id\": \"" + id + "\", \"name\": \"nha\", \"color\": \"teal\"}";

        apiPost(alice.accessToken(), LABELS, json).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
        apiPost(alice.accessToken(), LABELS, json).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.color").value("teal"));
        apiGet(alice.accessToken(), LABELS).andExpect(jsonPath("$.length()").value(1));

        apiPost(alice.accessToken(), LABELS, "{\"id\": \"" + id + "\", \"name\": \"khac\", \"color\": \"teal\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id-conflict"));
        apiPost(alice.accessToken(), LABELS, "{\"id\": \"" + id + "\", \"name\": \"nha\"}")   // vắng color = charcoal ≠ teal
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id-conflict"));
        apiPost(bob.accessToken(), LABELS, json).andExpect(status().isConflict())   // id của người khác
                .andExpect(jsonPath("$.code").value("id-conflict"));
        apiGet(bob.accessToken(), LABELS).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void clientId_notAUuid_is400() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, LABELS, "{\"id\": \"not-a-uuid\", \"name\": \"nha\"}").andExpect(status().isBadRequest());
        apiGet(token, LABELS).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void clientIdReplay_atTheLimit_returnsTheExistingLabelWithoutCountingAgain() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        String firstJson = "{\"id\": \"" + UUID.randomUUID() + "\", \"name\": \"l1\"}";
        apiPost(token, LABELS, firstJson).andExpect(status().isCreated());
        for (int i = 2; i <= 5; i++) {
            create(token, "{\"name\": \"l" + i + "\"}");
        }

        apiPost(token, LABELS, firstJson).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        apiPost(token, LABELS, "{\"name\": \"l6\"}").andExpect(status().isUnprocessableEntity());
    }

    /** Spec 8.2 #9, 14.1: hai request song song ở mức max − 1 → đúng một thành công, một 422 (khoá hàng users). */
    @Test
    void parallelCreatesAtLimitMinusOne_exactlyOneSucceeds() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        for (int i = 1; i <= 4; i++) {
            create(token, "{\"name\": \"l" + i + "\"}");
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String name : List.of("pa", "pb")) {
            results.add(pool.submit(() -> {
                start.await();
                return apiPost(token, LABELS, "{\"name\": \"" + name + "\"}").andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(count("select count(*) from labels where user_id = ?", user.id())).isEqualTo(5);
    }

    @Test
    void isReadable_trueOnlyForTheOwner_neverThrows() throws Exception {
        var alice = registerUser(uniqueEmail());
        var bob = registerUser(uniqueEmail());
        UUID label = data.label(alice.id(), "nha");

        assertThat(labelService.isReadable(alice.id(), label)).isTrue();
        assertThat(labelService.isReadable(bob.id(), label)).isFalse();
        assertThat(labelService.isReadable(alice.id(), UUID.randomUUID())).isFalse();
    }

    @Test
    void otherUsersLabel_is404OnEveryWrite() throws Exception {
        String alice = registerUser(uniqueEmail()).accessToken();
        String bob = registerUser(uniqueEmail()).accessToken();
        UUID label = create(alice, "{\"name\": \"nha\"}");

        apiPatch(bob, LABELS + "/" + label, "{\"color\": \"red\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(Texts.vi(ErrorMessage.LABEL_NOT_FOUND)));
        apiDelete(bob, LABELS + "/" + label).andExpect(status().isNotFound());
        apiPut(bob, LABELS + "/" + label + "/favorite", null).andExpect(status().isNotFound());
        apiDelete(bob, LABELS + "/" + label + "/favorite").andExpect(status().isNotFound());
        apiGet(bob, LABELS).andExpect(jsonPath("$.length()").value(0));
        apiGet(alice, LABELS).andExpect(jsonPath("$[0].color").value("charcoal"));
    }
}
