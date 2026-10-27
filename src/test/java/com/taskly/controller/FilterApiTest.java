package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.common.error.ProblemTexts;
import com.taskly.service.FilterDeletedEvent;
import com.taskly.service.FilterService;
import com.taskly.support.M2ApiTestBase;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** /api/v1/filters + filters/validate (spec 12.7, 12.10, 12.11). Ngưỡng test: tối đa 3 filter / user (M2ApiTestBase). */
@RecordApplicationEvents
class FilterApiTest extends M2ApiTestBase {

    private static final String FILTERS = "/api/v1/filters";

    @Autowired
    private FilterService filterService;

    private UUID create(String token, String json) throws Exception {
        return readId(apiPost(token, FILTERS, json).andExpect(status().isCreated()));
    }

    @Test
    void create_storesQueryVerbatim_andListsInSortOrder() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, FILTERS, "{\"name\": \" Gấp hôm nay \", \"query\": \"today & p1, overdue\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Gấp hôm nay"))
                .andExpect(jsonPath("$.query").value("today & p1, overdue"))
                .andExpect(jsonPath("$.color").value("charcoal"))
                .andExpect(jsonPath("$.isFavorite").value(false));
        create(token, "{\"name\": \"Chưa lên lịch\", \"query\": \"no date\", \"color\": \"grey\"}");

        apiGet(token, FILTERS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Gấp hôm nay", "Chưa lên lịch")))
                .andExpect(jsonPath("$[1].color").value("grey"));
    }

    @Test
    void create_invalidQuery_is400FilterSyntaxWithPosition_andSavesNothing() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, FILTERS, "{\"name\": \"Sai\", \"query\": \"today, (p1 | \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"))
                .andExpect(jsonPath("$.position").value(12))
                .andExpect(jsonPath("$.detail").value(ProblemTexts.FILTER_SYNTAX));
        apiGet(token, FILTERS).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_nameAndQuerySizes_are400() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, FILTERS, "{\"name\": \"  \", \"query\": \"p1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.FILTER_NAME_SIZE));
        apiPost(token, FILTERS, "{\"name\": \"" + "n".repeat(121) + "\", \"query\": \"p1\"}")
                .andExpect(status().isBadRequest());
        apiPost(token, FILTERS, "{\"name\": \"ok\", \"query\": \"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.FILTER_QUERY_SIZE));
        apiPost(token, FILTERS, "{\"name\": \"ok\", \"query\": \"" + "p1|".repeat(342) + "p1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.FILTER_QUERY_SIZE));
        apiPost(token, FILTERS, "{\"name\": \"ok\"}").andExpect(status().isBadRequest());
    }

    @Test
    void update_changesFields_invalidQueryKeepsOldValue() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID filter = create(token, "{\"name\": \"A\", \"query\": \"p1\"}");

        apiPatch(token, FILTERS + "/" + filter, "{\"name\": \"B\", \"query\": \"p2 | p3\", \"color\": \"blue\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("B"))
                .andExpect(jsonPath("$.query").value("p2 | p3"))
                .andExpect(jsonPath("$.color").value("blue"));
        apiPatch(token, FILTERS + "/" + filter, "{\"query\": \"p2 |\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"))
                .andExpect(jsonPath("$.position").value(4));
        apiGet(token, FILTERS).andExpect(jsonPath("$[0].query").value("p2 | p3"));
    }

    @Test
    void delete_removesFavorite_andIsGoneAfterwards() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID filter = create(token, "{\"name\": \"A\", \"query\": \"p1\"}");
        apiPut(token, FILTERS + "/" + filter + "/favorite", null).andExpect(status().isOk());

        apiDelete(token, FILTERS + "/" + filter).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("select count(*) from favorites where target_id = ?", Integer.class,
                filter)).isZero();   // Display `filter:<id>` do ViewOptionsService xoá khi nghe FilterDeletedEvent — test ở Task 17
        apiDelete(token, FILTERS + "/" + filter).andExpect(status().isNotFound());
    }

    @Test
    void favorite_isReflectedInFavoritesList() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID filter = create(token, "{\"name\": \"A\", \"query\": \"p1\"}");

        apiPut(token, FILTERS + "/" + filter + "/favorite", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFavorite").value(true));
        apiGet(token, "/api/v1/favorites")
                .andExpect(jsonPath("$[?(@.targetType == 'FILTER')].targetId", contains(filter.toString())));
        apiDelete(token, FILTERS + "/" + filter + "/favorite").andExpect(jsonPath("$.isFavorite").value(false));
    }

    @Test
    void fourthFilter_is422LimitReached() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        for (int i = 1; i <= 3; i++) {
            create(token, "{\"name\": \"F" + i + "\", \"query\": \"p1\"}");
        }

        apiPost(token, FILTERS, "{\"name\": \"F4\", \"query\": \"p1\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("FILTERS"))
                .andExpect(jsonPath("$.max").value(3));
    }

    @Test
    void validate_returnsTrimmedTopLevelParts() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, FILTERS + "/validate", "{\"query\": \" today | od ,  (p1 | p2) & @nha , #Dự án\\\\, mới\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts", contains("today | od", "(p1 | p2) & @nha", "#Dự án\\, mới")));
    }

    @Test
    void validate_reportsPositionInWholeQuery() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, FILTERS + "/validate", "{\"query\": \"today, p1 & bogus\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"))
                .andExpect(jsonPath("$.position").value(12))
                .andExpect(jsonPath("$.reason").value("unknown-keyword"));
        apiPost(token, FILTERS + "/validate", "{\"query\": \"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"))
                .andExpect(jsonPath("$.position").value(0));
        apiPost(token, FILTERS + "/validate", "{\"query\": \"" + "p1|".repeat(342) + "p1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.FILTER_QUERY_SIZE));
    }

    @Test
    void idempotencyKey_replaysTheSameFilter() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        String key = UUID.randomUUID().toString();
        String json = "{\"name\": \"A\", \"query\": \"p1\"}";

        UUID first = readId(apiPostWithKey(token, FILTERS, key, json).andExpect(status().isCreated()));
        UUID replay = readId(apiPostWithKey(token, FILTERS, key, json)
                .andExpect(header().string("Idempotent-Replay", "true")));

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void clientId_isUsed_sameContentReplaysWith200_otherContentOrOtherUsersIdIs409IdConflict() throws Exception {
        var alice = registerUser(uniqueEmail());
        var bob = registerUser(uniqueEmail());
        UUID id = UUID.randomUUID();
        String json = "{\"id\": \"" + id + "\", \"name\": \"A\", \"query\": \"p1\", \"color\": \"blue\"}";

        apiPost(alice.accessToken(), FILTERS, json).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
        apiPost(alice.accessToken(), FILTERS, json).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.query").value("p1"));
        apiGet(alice.accessToken(), FILTERS).andExpect(jsonPath("$.length()").value(1));

        apiPost(alice.accessToken(), FILTERS,
                "{\"id\": \"" + id + "\", \"name\": \"A\", \"query\": \"p2\", \"color\": \"blue\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id-conflict"));
        apiPost(bob.accessToken(), FILTERS, json).andExpect(status().isConflict())   // id của người khác
                .andExpect(jsonPath("$.code").value("id-conflict"));
        apiGet(bob.accessToken(), FILTERS).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void clientId_notAUuid_is400() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, FILTERS, "{\"id\": \"not-a-uuid\", \"name\": \"A\", \"query\": \"p1\"}")
                .andExpect(status().isBadRequest());
        apiGet(token, FILTERS).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void clientIdReplay_atTheLimit_returnsTheExistingFilterWithoutCountingAgain() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        String firstJson = "{\"id\": \"" + UUID.randomUUID() + "\", \"name\": \"F1\", \"query\": \"p1\"}";
        apiPost(token, FILTERS, firstJson).andExpect(status().isCreated());
        create(token, "{\"name\": \"F2\", \"query\": \"p1\"}");
        create(token, "{\"name\": \"F3\", \"query\": \"p1\"}");

        apiPost(token, FILTERS, firstJson).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        apiPost(token, FILTERS, "{\"name\": \"F4\", \"query\": \"p1\"}").andExpect(status().isUnprocessableEntity());
    }

    /** Spec 8.2 #9, 14.1: hai request song song ở mức max − 1 → đúng một thành công, một 422 (khoá hàng users). */
    @Test
    void parallelCreatesAtLimitMinusOne_exactlyOneSucceeds() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        create(token, "{\"name\": \"F1\", \"query\": \"p1\"}");
        create(token, "{\"name\": \"F2\", \"query\": \"p1\"}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String name : List.of("Fa", "Fb")) {
            results.add(pool.submit(() -> {
                start.await();
                return apiPost(token, FILTERS, "{\"name\": \"" + name + "\", \"query\": \"p1\"}")
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(jdbcTemplate.queryForObject("select count(*) from filters where user_id = ?", Integer.class,
                user.id())).isEqualTo(3);
    }

    @Test
    void isReadable_trueOnlyForTheOwner_neverThrows() throws Exception {
        var alice = registerUser(uniqueEmail());
        var bob = registerUser(uniqueEmail());
        UUID filter = data.filter(alice.id(), "Gấp", "p1");

        assertThat(filterService.isReadable(alice.id(), filter)).isTrue();
        assertThat(filterService.isReadable(bob.id(), filter)).isFalse();
        assertThat(filterService.isReadable(alice.id(), UUID.randomUUID())).isFalse();
    }

    @Test
    void otherUsersFilter_is404() throws Exception {
        String alice = registerUser(uniqueEmail()).accessToken();
        String bob = registerUser(uniqueEmail()).accessToken();
        UUID filter = create(alice, "{\"name\": \"A\", \"query\": \"p1\"}");

        apiPatch(bob, FILTERS + "/" + filter, "{\"name\": \"B\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.FILTER_NOT_FOUND));
        apiDelete(bob, FILTERS + "/" + filter).andExpect(status().isNotFound());
        apiPut(bob, FILTERS + "/" + filter + "/favorite", null).andExpect(status().isNotFound());
        apiGet(bob, FILTERS).andExpect(jsonPath("$.length()").value(0));
    }

    @Autowired
    private ApplicationEvents events;

    @Test
    void delete_publishesFilterDeletedEvent() throws Exception {
        var user = registerUser(uniqueEmail());
        UUID filter = create(user.accessToken(), "{\"name\": \"A\", \"query\": \"p1\"}");

        apiDelete(user.accessToken(), FILTERS + "/" + filter).andExpect(status().isNoContent());

        assertThat(events.stream(FilterDeletedEvent.class).map(FilterDeletedEvent::filterId)).containsExactly(filter);
    }

    @Test
    void create_secondTopLevelPartInvalid_isRejected_notOnlyTheFirst() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();

        apiPost(token, FILTERS, "{\"name\": \"Sai\", \"query\": \"p1, p2 & bogus\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"))
                .andExpect(jsonPath("$.position").value(9))
                .andExpect(jsonPath("$.reason").value("unknown-keyword"));
        apiGet(token, FILTERS).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_sameClientIdInParallel_oneCreatedOthersReplay_onlyOneRow() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        String json = "{\"id\": \"" + UUID.randomUUID() + "\", \"name\": \"A\", \"query\": \"p1\"}";
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return apiPost(token, FILTERS, json).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(201, 200, 200, 200);
        assertThat(jdbcTemplate.queryForObject("select count(*) from filters where user_id = ?", Integer.class,
                user.id())).isEqualTo(1);
    }
}
