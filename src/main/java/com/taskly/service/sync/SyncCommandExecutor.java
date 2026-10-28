package com.taskly.service.sync;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.CommandProblems;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.sync.SyncCommand;
import com.taskly.dto.sync.SyncCommandResult;
import com.taskly.repository.SyncCommandRepository;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Chạy MỘT lệnh của POST sync trong transaction RIÊNG (lỗi một lệnh không làm hỏng cả lô — plan M9 F2):
 * 1) tìm handler; 2) thay tempId đã biết trong args bằng id thật; 3) lệnh tạo: đặt args.id từ tempId;
 * 4) trong transaction: giữ chỗ uuid ở sync_commands (đã có → trả kết quả cũ, không chạy lại) → handler → lưu kết quả;
 * 5) lỗi → ProblemDetail phẳng đã dịch. Lệnh tạo đua khoá chính → chạy lại MỘT lần (như TaskController.create).
 */
@Component
public class SyncCommandExecutor {

    private final SyncCommandRegistry registry;
    private final SyncCommandRepository commandRepository;
    private final CommandProblems problems;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public SyncCommandExecutor(SyncCommandRegistry registry, SyncCommandRepository commandRepository,
                               CommandProblems problems, ObjectMapper objectMapper, Validator validator,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.registry = registry;
        this.commandRepository = commandRepository;
        this.problems = problems;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** tempIds: tempId → id thật của request hiện tại (đọc + ghi). */
    public SyncCommandResult execute(UUID userId, SyncCommand command, Map<String, UUID> tempIds, Locale locale) {
        try {
            SyncCommandHandler handler = registry.find(command.type()).orElseThrow(() ->
                    ApiException.badRequest(ErrorMessage.SYNC_COMMAND_UNKNOWN)
                            .withCode(ProblemCodes.SYNC_COMMAND_UNKNOWN));
            ObjectNode args = prepareArgs(command, handler, tempIds);
            Map<String, UUID> mapping = runWithRetry(userId, command, handler, args);
            tempIds.putAll(mapping);
            return SyncCommandResult.ok(command.uuid());
        } catch (RuntimeException e) {
            return SyncCommandResult.error(command.uuid(), problems.toProblem(e, locale));
        }
    }

    private Map<String, UUID> runWithRetry(UUID userId, SyncCommand command, SyncCommandHandler handler, ObjectNode args) {
        try {
            return runOnce(userId, command, handler, args);
        } catch (DataIntegrityViolationException e) {
            if (!handler.creates()) {
                throw e;
            }
            // Hai request song song cùng `id` client: bên thua đã rollback; lần hai thấy id có sẵn → replay / id-conflict.
            return runOnce(userId, command, handler, args);
        }
    }

    private Map<String, UUID> runOnce(UUID userId, SyncCommand command, SyncCommandHandler handler, ObjectNode args) {
        return transactionTemplate.execute(status -> {
            if (!commandRepository.claim(userId, command.uuid(), command.type(), clock.instant())) {
                return storedMapping(userId, command.uuid());
            }
            SyncCommandOutcome outcome = handler.handle(new SyncCommandContext(userId, args, objectMapper, validator));
            Map<String, UUID> mapping = new LinkedHashMap<>();
            if (command.tempId() != null && outcome.createdId() != null) {
                mapping.put(command.tempId(), outcome.createdId());
            }
            commandRepository.saveResult(userId, command.uuid(),
                    objectMapper.writeValueAsString(Map.of("tempIdMapping", mapping)));
            return mapping;
        });
    }

    private Map<String, UUID> storedMapping(UUID userId, UUID commandUuid) {
        Map<String, UUID> mapping = new LinkedHashMap<>();
        commandRepository.result(userId, commandUuid).ifPresent(json -> {
            JsonNode stored = objectMapper.readTree(json).path("tempIdMapping");
            stored.properties().forEach(entry -> mapping.put(entry.getKey(), UUID.fromString(entry.getValue().asString())));
        });
        return mapping;
    }

    private ObjectNode prepareArgs(SyncCommand command, SyncCommandHandler handler, Map<String, UUID> tempIds) {
        JsonNode raw = command.args();
        ObjectNode args;
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            args = objectMapper.createObjectNode();
        } else if (raw.isObject()) {
            args = (ObjectNode) replaceTempIds(raw.deepCopy(), tempIds);
        } else {
            throw ApiException.badRequest(ErrorMessage.SYNC_ARGS_INVALID);
        }
        if (handler.creates() && command.tempId() != null && !args.hasNonNull("id")) {
            args.put("id", idFromTempId(command.tempId()).toString());
        }
        return args;
    }

    /** tempId là UUID → dùng luôn làm id (app Taskly luôn sinh UUID); khác → server sinh. */
    static UUID idFromTempId(String tempId) {
        try {
            return UUID.fromString(tempId);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }

    /** Mọi giá trị chuỗi (mọi độ sâu) trùng một tempId đã biết → id thật. */
    private static JsonNode replaceTempIds(JsonNode node, Map<String, UUID> tempIds) {
        if (tempIds.isEmpty()) {
            return node;
        }
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                JsonNode replaced = replaceTempIds(entry.getValue(), tempIds);
                if (replaced != entry.getValue()) {
                    object.set(entry.getKey(), replaced);
                }
            }
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode replaced = replaceTempIds(array.get(i), tempIds);
                if (replaced != array.get(i)) {
                    array.set(i, replaced);
                }
            }
            return array;
        }
        if (node.isString() && tempIds.containsKey(node.stringValue())) {
            return StringNode.valueOf(tempIds.get(node.stringValue()).toString());
        }
        return node;
    }
}
