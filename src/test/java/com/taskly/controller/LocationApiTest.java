package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** GET / POST / DELETE /api/v1/locations (M12 — địa điểm đã lưu cho nhắc theo vị trí). */
class LocationApiTest extends IntegrationTestBase {

    private static final String LOCATIONS = "/api/v1/locations";
    private static final String HOME = """
            {"name": "  Nhà  ", "latitude": 10.7769, "longitude": 106.7009, "radius": 150}
            """;

    private ResultActions create(RegisteredUser user, String json) throws Exception {
        return authPost(user.accessToken(), LOCATIONS, json);
    }

    private UUID createId(RegisteredUser user, String json) throws Exception {
        return readUuid(create(user, json).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void create_returns201WithTrimmedNameAndIsListed() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        create(user, HOME)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Nhà"))
                .andExpect(jsonPath("$.latitude").value(10.7769))
                .andExpect(jsonPath("$.longitude").value(106.7009))
                .andExpect(jsonPath("$.radius").value(150))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        authGet(user.accessToken(), LOCATIONS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Nhà"));
    }

    @Test
    void list_isInCreationOrderAndOnlyMine() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        createId(user, """
                {"name": "Một", "latitude": 1, "longitude": 1, "radius": 100}
                """);
        clock.advance(Duration.ofSeconds(1));
        createId(user, """
                {"name": "Hai", "latitude": 2, "longitude": 2, "radius": 100}
                """);
        createId(other, """
                {"name": "Của người khác", "latitude": 3, "longitude": 3, "radius": 100}
                """);

        authGet(user.accessToken(), LOCATIONS)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Một"))
                .andExpect(jsonPath("$[1].name").value("Hai"));
    }

    @Test
    void delete_returns204_otherUserGets404() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        UUID id = createId(user, HOME);

        authDelete(other.accessToken(), LOCATIONS + "/" + id)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Không tìm thấy địa điểm"));
        authDelete(user.accessToken(), LOCATIONS + "/" + id).andExpect(status().isNoContent());
        authDelete(user.accessToken(), LOCATIONS + "/" + id).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("select count(*) from locations where id = ?", Integer.class, id))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"name\": \"   \", \"latitude\": 1, \"longitude\": 1, \"radius\": 100}",
        "{\"latitude\": 1, \"longitude\": 1, \"radius\": 100}",
        "{\"name\": \"A\", \"latitude\": 90.1, \"longitude\": 1, \"radius\": 100}",
        "{\"name\": \"A\", \"latitude\": 1, \"longitude\": -180.1, \"radius\": 100}",
        "{\"name\": \"A\", \"longitude\": 1, \"radius\": 100}",
        "{\"name\": \"A\", \"latitude\": 1, \"longitude\": 1, \"radius\": 99}",
        "{\"name\": \"A\", \"latitude\": 1, \"longitude\": 1, \"radius\": 5001}",
        "{\"name\": \"A\", \"latitude\": 1, \"longitude\": 1}"
    })
    void create_invalidBody_returns400WithFieldErrors(String json) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        create(user, json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").isNotEmpty());
        authGet(user.accessToken(), LOCATIONS).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_nameLongerThan100_returns400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        create(user, """
                {"name": "%s", "latitude": 1, "longitude": 1, "radius": 100}
                """.formatted("x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void create_overTheLimit_returns422LimitReached() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        usageLimits.overrideMax(LimitKind.LOCATIONS, 1);
        createId(user, HOME);

        create(user, HOME)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("LOCATIONS"))
                .andExpect(jsonPath("$.max").value(1));
    }

    @Test
    void defaultLimitIs100() {
        assertThat(usageLimits.max(LimitKind.LOCATIONS)).isEqualTo(100);
    }

    @Test
    void endpointsNeedAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(LOCATIONS))
                .andExpect(status().isUnauthorized());
    }
}
