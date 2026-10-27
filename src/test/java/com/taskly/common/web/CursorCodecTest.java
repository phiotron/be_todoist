package com.taskly.common.web;

import com.taskly.common.error.ErrorMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

    private static void assertBadRequest(Runnable action, ErrorMessage detail) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getErrorMessage()).isEqualTo(detail);
        });
    }

    @Test
    void roundTripWithTypedValues() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-23T16:59:59.123456Z");
        String cursor = CursorCodec.encode("today",
                List.of("0", at.toString(), "4", id.toString(), "2026-10-25", "9000000000", "2026-10-25T09:30"));

        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        CursorCodec.Cursor decoded = CursorCodec.decode("today", cursor, 7);
        assertThat(decoded.intValue(0)).isZero();
        assertThat(decoded.instant(1)).isEqualTo(at);
        assertThat(decoded.intValue(2)).isEqualTo(4);
        assertThat(decoded.uuid(3)).isEqualTo(id);
        assertThat(decoded.date(4)).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(decoded.longValue(5)).isEqualTo(9_000_000_000L);
        assertThat(decoded.localDateTime(6)).isEqualTo(LocalDateTime.of(2026, 10, 25, 9, 30)); // giờ floating
    }

    @Test
    void cursorOfAnotherKindOrWrongArity_isRejected() {
        String cursor = CursorCodec.encode("today", List.of("a", "b"));
        assertBadRequest(() -> CursorCodec.decode("upcoming", cursor, 2), ErrorMessage.INVALID_CURSOR);
        assertBadRequest(() -> CursorCodec.decode("today", cursor, 3), ErrorMessage.INVALID_CURSOR);
    }

    @Test
    void garbageCursor_isRejected() {
        assertBadRequest(() -> CursorCodec.decode("today", "%%%not-base64%%%", 1), ErrorMessage.INVALID_CURSOR);
        assertBadRequest(() -> CursorCodec.decode("today", "", 1), ErrorMessage.INVALID_CURSOR);
        assertBadRequest(() -> CursorCodec.decode("today", "a".repeat(5000), 1), ErrorMessage.INVALID_CURSOR);
    }

    @Test
    void valueOfWrongType_isRejected() {
        CursorCodec.Cursor decoded = CursorCodec.decode("k", CursorCodec.encode("k", List.of("abc")), 1);
        assertBadRequest(() -> decoded.uuid(0), ErrorMessage.INVALID_CURSOR);
        assertBadRequest(() -> decoded.instant(0), ErrorMessage.INVALID_CURSOR);
        assertBadRequest(() -> decoded.longValue(0), ErrorMessage.INVALID_CURSOR);
    }

    @Test
    void limit_defaultsTo50_andMustBe1To200() {
        assertThat(CursorCodec.limit(null)).isEqualTo(50);
        assertThat(CursorCodec.limit(1)).isEqualTo(1);
        assertThat(CursorCodec.limit(200)).isEqualTo(200);
        assertBadRequest(() -> CursorCodec.limit(0), ErrorMessage.INVALID_LIMIT);
        assertBadRequest(() -> CursorCodec.limit(201), ErrorMessage.INVALID_LIMIT);
    }

    @Test
    void hasCursor_treatsNullAndBlankAsFirstPage() {
        assertThat(CursorCodec.hasCursor(null)).isFalse();
        assertThat(CursorCodec.hasCursor("  ")).isFalse();
        assertThat(CursorCodec.hasCursor("abc")).isTrue();
    }
}
