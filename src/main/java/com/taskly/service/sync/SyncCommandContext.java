package com.taskly.service.sync;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.FieldErrorItem;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Đầu vào của SyncCommandHandler: người gọi + args của lệnh (tempId đã được thay bằng id thật). body(...) đọc args thành
 * DTO request của REST (cùng ObjectMapper của API: Optional vắng / null phân biệt) rồi chạy Bean Validation như @Valid —
 * lỗi → SyncArgumentsException (400 kèm errors[] cùng key với REST).
 */
public final class SyncCommandContext {

    private final UUID userId;
    private final ObjectNode args;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public SyncCommandContext(UUID userId, ObjectNode args, ObjectMapper objectMapper, Validator validator) {
        this.userId = userId;
        this.args = args;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public UUID userId() {
        return userId;
    }

    /** Bản sao args để handler tự chỉnh (vd chèn version) — không đụng bản gốc. */
    public ObjectNode argsCopy() {
        return args.deepCopy();
    }

    public boolean has(String field) {
        return args.has(field) && !args.get(field).isNull();
    }

    /** args.id bắt buộc (sửa / xoá / đổi trạng thái). */
    public UUID requireId() {
        return requireUuid("id");
    }

    public UUID requireUuid(String field) {
        UUID value = optionalUuid(field);
        if (value == null) {
            throw ApiException.badRequest(ErrorMessage.SYNC_ID_REQUIRED).withProperty("field", field);
        }
        return value;
    }

    /** Vắng / null → null; không phải UUID → 400 (errors[] theo field). */
    public UUID optionalUuid(String field) {
        if (!has(field)) {
            return null;
        }
        JsonNode node = args.get(field);
        try {
            return UUID.fromString(node.isString() ? node.stringValue() : "");
        } catch (IllegalArgumentException e) {
            throw new SyncArgumentsException(List.of(new FieldErrorItem(field, ErrorMessage.INVALID_VALUE.key())));
        }
    }

    /** args bỏ các field định tuyến (id, projectId…) → DTO request REST, đã validate. */
    public <T> T body(Class<T> type, String... routingFields) {
        ObjectNode node = argsCopy();
        node.remove(List.of(routingFields));
        return read(node, type);
    }

    /** Đọc node bất kỳ (handler đã chỉnh) thành DTO request REST, đã validate. */
    public <T> T read(ObjectNode node, Class<T> type) {
        T value = objectMapper.treeToValue(node, type);
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new SyncArgumentsException(violations.stream()
                    .map(v -> new FieldErrorItem(v.getPropertyPath().toString(), messageKey(v)))
                    .sorted(Comparator.comparing(FieldErrorItem::field).thenComparing(FieldErrorItem::message))
                    .toList());
        }
        return value;
    }

    /** Như GlobalExceptionHandler.messageKey: template "{key}" → key. */
    private static String messageKey(ConstraintViolation<?> violation) {
        String template = violation.getMessageTemplate();
        if (template.length() > 2 && template.startsWith("{") && template.endsWith("}")) {
            return template.substring(1, template.length() - 1);
        }
        return template;
    }
}
