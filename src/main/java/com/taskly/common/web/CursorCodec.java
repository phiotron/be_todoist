package com.taskly.common.web;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;

/**
 * Con trỏ phân trang "mờ" (mục 5.10): base64url của "1\n<kind>\n<giá trị khoá 1>\n...". kind = tên danh sách
 * (vd "today") để con trỏ của danh sách này không dùng được cho danh sách khác. Hàm thuần, không phụ thuộc Spring.
 */
public final class CursorCodec {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private static final String VERSION = "1";
    private static final int MAX_CURSOR_LENGTH = 2048;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorCodec() {
    }

    /** null → 50; ngoài 1..200 → 400. */
    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw ApiException.badRequest(ErrorMessage.INVALID_LIMIT);
        }
        return requested;
    }

    /** null hoặc rỗng = trang đầu. */
    public static boolean hasCursor(String cursor) {
        return cursor != null && !cursor.isBlank();
    }

    public static String encode(String kind, List<String> values) {
        StringJoiner joiner = new StringJoiner("\n").add(VERSION).add(kind);
        for (String value : values) {
            if (value == null || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Giá trị khoá con trỏ không được null hay chứa xuống dòng");
            }
            joiner.add(value);
        }
        return ENCODER.encodeToString(joiner.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String kind, String cursor, int expectedValues) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalid();
        }
        String text;
        try {
            text = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        String[] parts = text.split("\n", -1);
        if (parts.length != expectedValues + 2 || !VERSION.equals(parts[0]) || !kind.equals(parts[1])) {
            throw invalid();
        }
        return new Cursor(List.copyOf(Arrays.asList(parts).subList(2, parts.length)));
    }

    private static ApiException invalid() {
        return ApiException.badRequest(ErrorMessage.INVALID_CURSOR);
    }

    /** Giá trị khoá đã giải mã; getter đổi kiểu, lỗi định dạng → 400 (con trỏ do client sửa bậy). */
    public record Cursor(List<String> values) {

        public String string(int index) {
            return parse(index, Function.identity());
        }

        public int intValue(int index) {
            return parse(index, Integer::parseInt);
        }

        public long longValue(int index) {
            return parse(index, Long::parseLong);
        }

        public UUID uuid(int index) {
            return parse(index, UUID::fromString);
        }

        public Instant instant(int index) {
            return parse(index, Instant::parse);
        }

        public LocalDate date(int index) {
            return parse(index, LocalDate::parse);
        }

        /** Giờ floating (due_datetime không múi giờ), dạng LocalDateTime.toString(). */
        public LocalDateTime localDateTime(int index) {
            return parse(index, LocalDateTime::parse);
        }

        private <T> T parse(int index, Function<String, T> parser) {
            try {
                return parser.apply(values.get(index));
            } catch (RuntimeException e) {
                throw invalid();
            }
        }
    }
}
