package com.taskly.common.web;

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

    private static void assertBadRequest(Runnable action, String detail) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getBody().getDetail()).isEqualTo(detail);
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
        assertBadRequest(() -> CursorCodec.decode("upcoming", cursor, 2), "Con trỏ phân trang không hợp lệ");
        assertBadRequest(() -> CursorCodec.decode("today", cursor, 3), "Con trỏ phân trang không hợp lệ");
    }

    @Test
    void garbageCursor_isRejected() {
        assertBadRequest(() -> CursorCodec.decode("today", "%%%not-base64%%%", 1), "Con trỏ phân trang không hợp lệ");
        assertBadRequest(() -> CursorCodec.decode("today", "", 1), "Con trỏ phân trang không hợp lệ");
        assertBadRequest(() -> CursorCodec.decode("today", "a".repeat(5000), 1), "Con trỏ phân trang không hợp lệ");
    }

    @Test
    void valueOfWrongType_isRejected() {
        CursorCodec.Cursor decoded = CursorCodec.decode("k", CursorCodec.encode("k", List.of("abc")), 1);
        assertBadRequest(() -> decoded.uuid(0), "Con trỏ phân trang không hợp lệ");
        assertBadRequest(() -> decoded.instant(0), "Con trỏ phân trang không hợp lệ");
        assertBadRequest(() -> decoded.longValue(0), "Con trỏ phân trang không hợp lệ");
    }

    @Test
    void limit_defaultsTo50_andMustBe1To200() {
        assertThat(CursorCodec.limit(null)).isEqualTo(50);
        assertThat(CursorCodec.limit(1)).isEqualTo(1);
        assertThat(CursorCodec.limit(200)).isEqualTo(200);
        assertBadRequest(() -> CursorCodec.limit(0), "limit phải từ 1 đến 200");
        assertBadRequest(() -> CursorCodec.limit(201), "limit phải từ 1 đến 200");
    }

    @Test
    void hasCursor_treatsNullAndBlankAsFirstPage() {
        assertThat(CursorCodec.hasCursor(null)).isFalse();
        assertThat(CursorCodec.hasCursor("  ")).isFalse();
        assertThat(CursorCodec.hasCursor("abc")).isTrue();
    }
}
