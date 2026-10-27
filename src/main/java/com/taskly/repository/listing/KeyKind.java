package com.taskly.repository.listing;

import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Kiểu của một khoá keyset: kiểu SQL để CAST tham số, cách đọc từ ResultSet ra chuỗi con trỏ và ngược lại. */
public enum KeyKind {
    INT("integer"),
    LONG("bigint"),
    DATE("date"),
    /** timestamptz (created_at, completed_at). */
    INSTANT("timestamptz"),
    /** timestamp không múi giờ (due floating). */
    LOCAL_DATETIME("timestamp"),
    /** Chữ: base64url trong con trỏ (CursorCodec không nhận xuống dòng). */
    TEXT("text"),
    ID("uuid"),
    /** bigint[] (đường dẫn sort_order của dự án): "1024,2048" trong con trỏ. */
    LONG_ARRAY("bigint[]");

    private static final Pattern LONG_LIST = Pattern.compile("^-?\\d+(,-?\\d+)*$");

    private final String sqlType;

    KeyKind(String sqlType) {
        this.sqlType = sqlType;
    }

    public String sqlType() {
        return sqlType;
    }

    /** Giá trị cột → chuỗi đưa vào con trỏ. Khoá không bao giờ null (biểu thức phải COALESCE). */
    public String read(ResultSet rs, String column) throws SQLException {
        return switch (this) {
            case INT -> Integer.toString(rs.getInt(column));
            case LONG -> Long.toString(rs.getLong(column));
            case DATE -> rs.getObject(column, LocalDate.class).toString();
            case INSTANT -> rs.getObject(column, OffsetDateTime.class).toInstant().toString();
            case LOCAL_DATETIME -> rs.getObject(column, LocalDateTime.class).toString();
            case TEXT -> encodeText(rs.getString(column));
            case ID -> rs.getObject(column, UUID.class).toString();
            case LONG_ARRAY -> {
                Array array = rs.getArray(column);
                yield Arrays.stream((Object[]) array.getArray()).map(String::valueOf).collect(Collectors.joining(","));
            }
        };
    }

    /** Chuỗi con trỏ → giá trị JDBC cho tham số :cN. Hỏng → 400 INVALID_CURSOR. */
    public Object bind(String value) {
        try {
            return switch (this) {
                case INT -> Integer.valueOf(value);
                case LONG -> Long.valueOf(value);
                case DATE -> LocalDate.parse(value);
                case INSTANT -> OffsetDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
                case LOCAL_DATETIME -> LocalDateTime.parse(value);
                case TEXT -> new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                case ID -> UUID.fromString(value);
                case LONG_ARRAY -> {
                    if (!LONG_LIST.matcher(value).matches()) {
                        throw new IllegalArgumentException(value);
                    }
                    yield "{" + value + "}";
                }
            };
        } catch (RuntimeException ex) {
            throw ApiException.badRequest(ErrorMessage.INVALID_CURSOR);
        }
    }

    public static String encodeText(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
