package com.taskly.service.sync;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.sync.SyncCommand;
import com.taskly.dto.sync.SyncCommandResult;
import com.taskly.dto.sync.SyncDeletedItem;
import com.taskly.dto.sync.SyncRequest;
import com.taskly.dto.sync.SyncResponse;
import com.taskly.repository.SyncChangeRepository;
import com.taskly.repository.SyncChangeRepository.ChangeRow;
import com.taskly.security.AccessPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * POST sync (plan M9 F2 / F3): chạy lệnh theo thứ tự (mỗi lệnh một transaction — SyncCommandExecutor), RỒI mới lấy token
 * mới và đọc thay đổi từ token cũ (app nhận lại cả bản server của chính các lệnh vừa gửi). Cố ý KHÔNG @Transactional:
 * lệnh phải commit trước khi đọc thay đổi.
 */
@Service
public class SyncService {

    private final SyncCommandExecutor executor;
    private final SyncChangeRepository changeRepository;
    private final AccessPolicy accessPolicy;
    private final Map<String, SyncEntitySource> sourcesByType = new LinkedHashMap<>();
    private final Map<String, SyncEntitySource> sourcesByKey = new LinkedHashMap<>();
    private final Clock clock;
    private final Duration retention;

    public SyncService(SyncCommandExecutor executor, SyncChangeRepository changeRepository, AccessPolicy accessPolicy,
                       List<SyncEntitySource> sources, Clock clock,
                       @Value("${taskly.sync.retention-days:30}") int retentionDays) {
        this.executor = executor;
        this.changeRepository = changeRepository;
        this.accessPolicy = accessPolicy;
        for (SyncEntitySource source : sources) {
            if (sourcesByType.putIfAbsent(source.entityType(), source) != null
                    || sourcesByKey.putIfAbsent(source.changesKey(), source) != null) {
                throw new IllegalStateException("Trùng loại thực thể đồng bộ: " + source.entityType());
            }
        }
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
    }

    public SyncResponse sync(UUID userId, SyncRequest request, Locale locale) {
        SyncToken since = SyncToken.parse(request.syncToken()); // 400 trước khi chạy lệnh nào
        Set<String> keys = resourceKeys(request.resourceTypes());

        Map<String, UUID> tempIds = new LinkedHashMap<>();
        List<SyncCommandResult> results = new ArrayList<>();
        for (SyncCommand command : request.commands() == null ? List.<SyncCommand>of() : request.commands()) {
            results.add(executor.execute(userId, command, tempIds, locale));
        }

        Instant now = clock.instant();
        long token = changeRepository.currentToken();
        boolean full = since == null || since.issuedAt().isBefore(now.minus(retention));
        Map<String, List<Object>> changes = new LinkedHashMap<>();
        keys.forEach(key -> changes.put(key, new ArrayList<>()));
        List<SyncDeletedItem> deleted = new ArrayList<>();
        if (!keys.isEmpty()) {
            // PERF: đoạn đọc này không ghi → nhớ phạm vi đọc (full sync gọi listScope ~6 lần, mỗi lần một câu SQL)
            try (AccessPolicy.ScopeMemo memo = accessPolicy.memoizeScopes()) {
                if (full) {
                    keys.forEach(key -> changes.get(key).addAll(sourcesByKey.get(key).full(userId)));
                } else {
                    collectChanges(userId, since.xmin(), keys, changes, deleted);
                }
            }
        }
        return new SyncResponse(new SyncToken(token, now).format(), full, changes, deleted, results, tempIds);
    }

    private void collectChanges(UUID userId, long since, Set<String> keys, Map<String, List<Object>> changes,
                                List<SyncDeletedItem> deleted) {
        List<ChangeRow> rows = changeRepository.changedSince(accessPolicy.listScope(userId), userId, since);
        Map<String, List<UUID>> liveIds = new HashMap<>();
        for (ChangeRow row : rows) {
            SyncEntitySource source = sourcesByType.get(row.entityType());
            if (source == null || !keys.contains(source.changesKey())) {
                continue; // loại của mốc chưa gộp / app không hỏi
            }
            if (row.deleted()) {
                deleted.add(new SyncDeletedItem(row.entityType(), row.entityId()));
            } else {
                liveIds.computeIfAbsent(source.changesKey(), k -> new ArrayList<>()).add(row.entityId());
            }
        }
        liveIds.forEach((key, ids) -> changes.get(key).addAll(sourcesByKey.get(key).load(userId, ids)));
    }

    /** vắng = mọi loại (theo thứ tự đăng ký); có giá trị lạ → 400. */
    private Set<String> resourceKeys(List<String> requested) {
        if (requested == null) {
            return new LinkedHashSet<>(sourcesByKey.keySet());
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String key : requested) {
            if (!sourcesByKey.containsKey(key)) {
                throw ApiException.badRequest(ErrorMessage.SYNC_RESOURCE_TYPE).withProperty("resourceType", key);
            }
            keys.add(key);
        }
        return keys;
    }
}
