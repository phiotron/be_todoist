package com.taskly.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec I3 / Q29: với field Optional, khoá VẮNG phải khác "x": null. Mặc định Jackson 3 trả Optional.empty() cho cả hai
 * (đã kiểm bằng spike) nên cấu hình riêng: vắng → null của Java, null tường minh → Optional.empty().
 */
class OptionalPresenceJsonTest extends IntegrationTestBase {

    record Req(String name, Optional<UUID> afterId) {
    }

    record Timed(@com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
                 Optional<java.time.LocalDateTime> at, Optional<java.time.LocalDate> day, Optional<Integer> n) {
    }

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void absentKey_isJavaNull() {
        assertThat(jsonMapper.readValue("{\"name\":\"a\"}", Req.class).afterId()).isNull();
    }

    @Test
    void explicitNull_isOptionalEmpty() {
        assertThat(jsonMapper.readValue("{\"name\":\"a\",\"afterId\":null}", Req.class).afterId()).isEmpty();
    }

    @Test
    void value_isOptionalOfValue() {
        UUID id = UUID.randomUUID();
        assertThat(jsonMapper.readValue("{\"name\":\"a\",\"afterId\":\"" + id + "\"}", Req.class).afterId())
                .contains(id);
    }

    @Test
    void optionalOfDatesAndNumbers_keepFormatAndPresenceSemantics() {
        Timed full = jsonMapper.readValue(
                "{\"at\":\"2026-10-25T09:30:15\",\"day\":\"2026-10-25\",\"n\":45}", Timed.class);
        assertThat(full.at()).contains(java.time.LocalDateTime.of(2026, 10, 25, 9, 30, 15));
        assertThat(full.day()).contains(java.time.LocalDate.of(2026, 10, 25));
        assertThat(full.n()).contains(45);

        Timed cleared = jsonMapper.readValue("{\"at\":null,\"n\":null}", Timed.class);
        assertThat(cleared.at()).isEmpty();
        assertThat(cleared.n()).isEmpty();
        assertThat(cleared.day()).isNull();
    }

    @Test
    void invalidUuid_isStillRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jsonMapper.readValue("{\"name\":\"a\",\"afterId\":\"khong-phai-uuid\"}", Req.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }
}
