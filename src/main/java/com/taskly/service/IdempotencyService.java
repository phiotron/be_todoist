package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ErrorMessage;
import com.taskly.entity.IdempotencyKey;
import com.taskly.repository.IdempotencyKeyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chống gửi trùng (spec 8.2 #10, 12.11). Khoá theo (user, key, method, path).
 * <ul>
 *   <li>begin(): CHÈN hàng khoá TRƯỚC khi xử lý, ở transaction riêng (REQUIRES_NEW, commit ngay) — ràng buộc unique
 *       bắt đua. Hàng chưa có status = "đang xử lý", expires_at = now + 60 giây.</li>
 *   <li>complete(): 2xx → ghi status / body, expires_at = now + 24 giờ (app.idempotency.ttl-hours).</li>
 *   <li>abandon(): lỗi (4xx / 5xx / ngoại lệ) → XOÁ hàng để gửi lại được.</li>
 *   <li>Hàng quá hạn (đang xử lý quá 60 giây vì server chết, hoặc kết quả quá 24 giờ) → request mới chiếm lại.</li>
 *   <li>Cùng key khác hash → 422; đang xử lý chưa hết hạn → 409; xong → phát lại nguyên status + body.</li>
 * </ul>
 * Không dùng @Transactional trên method (gọi nội bộ không qua proxy): mọi ghi đi qua TransactionTemplate
 * REQUIRES_NEW nên độc lập với transaction của request chính (và commit ngay cả khi request chính lỗi).
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private static final int MAX_ATTEMPTS = 3;

    /** Kết quả của begin(): chạy tiếp request, hoặc phát lại kết quả cũ. */
    public sealed interface Outcome permits Proceed, Replay {
    }

    public record Proceed(UUID recordId) implements Outcome {
    }

    public record Replay(int status, String body) implements Outcome {
    }

    private final IdempotencyKeyRepository repository;
    private final Clock clock;
    private final Duration resultTtl;
    /** Hạn hàng "đang xử lý" (mặc định 60 giây; taskly.idempotency.in-progress-ttl — chỉ test rút ngắn). */
    private final Duration inProgressTtl;
    private final TransactionTemplate requiresNew;
    /** Hàng khoá "đang xử lý" mà request trong tiến trình này đang giữ (renewLeases gia hạn). */
    private final Set<UUID> activeLeases = ConcurrentHashMap.newKeySet();

    public IdempotencyService(IdempotencyKeyRepository repository, Clock clock,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.idempotency.ttl-hours:24}") long ttlHours,
                              @Value("${taskly.idempotency.in-progress-ttl:PT60S}") Duration inProgressTtl) {
        this.repository = repository;
        this.clock = clock;
        this.resultTtl = Duration.ofHours(ttlHours);
        this.inProgressTtl = inProgressTtl;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Outcome begin(UUID userId, String key, String method, String path, String requestHash) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            try {
                UUID id = requiresNew.execute(tx -> repository.saveAndFlush(
                        new IdempotencyKey(userId, key, method, path, requestHash, now.plus(inProgressTtl))).getId());
                activeLeases.add(id); // request này đang chạy → renewLeases() giữ hàng khoá không hết hạn
                return new Proceed(id);
            } catch (DataIntegrityViolationException alreadyThere) {
                // unique (user, key, method, path): đã có hàng khoá → xem nó đang ở trạng thái nào
            }
            Optional<IdempotencyKey> existing = repository.findByUserIdAndIdemKeyAndMethodAndPath(userId, key, method, path);
            if (existing.isEmpty()) {
                continue; // hàng vừa bị xoá (request kia lỗi) → chèn lại
            }
            IdempotencyKey record = existing.get();
            if (record.getExpiresAt().isBefore(now)) {
                // quá hạn: server chết khi đang xử lý, hoặc kết quả đã quá 24 giờ → chiếm lại
                requiresNew.executeWithoutResult(tx -> repository.deleteIfExpired(record.getId(), now));
                continue;
            }
            if (!record.getRequestHash().equals(requestHash)) {
                throw ApiException.unprocessable(ErrorMessage.IDEMPOTENCY_MISMATCH)
                        .withCode(ProblemCodes.IDEMPOTENCY_MISMATCH);
            }
            if (record.isInProgress()) {
                throw inProgress();
            }
            return new Replay(record.getStatus(), record.getResponseBody());
        }
        throw inProgress(); // thua đua nhiều lần liên tiếp: coi như đang xử lý
    }

    /**
     * 2xx → ghi kết quả, đổi hạn sang 24 giờ. Trả false khi KHÔNG cập nhật được dòng nào: hàng đã bị chiếm lại (đang xử
     * lý quá 60 giây) hoặc bị dọn → request này đã mất quyền sở hữu khoá, thao tác có thể đã chạy hai lần (log cảnh
     * báo; response vẫn trả cho client vì thao tác đã ghi).
     */
    public boolean complete(UUID recordId, int status, String body) {
        activeLeases.remove(recordId);
        Instant expiresAt = clock.instant().plus(resultTtl);
        Integer updated = requiresNew.execute(tx -> repository.markDone(recordId, status, body, expiresAt));
        if (updated == null || updated == 0) {
            log.warn("Idempotency: hàng khoá {} không còn (đã bị chiếm lại / dọn) khi ghi kết quả", recordId);
            return false;
        }
        return true;
    }

    /**
     * HEARTBEAT: gia hạn expires_at (= now + 60 giây) của mọi hàng "đang xử lý" mà request trong tiến trình NÀY còn đang
     * chạy. IdempotencyCleanupJob gọi định kỳ (15 giây) → thao tác chạy lâu hơn 60 giây (chờ khoá, cây lớn) KHÔNG bị request
     * trùng chiếm lại rồi chạy lần hai (nhận 409 idempotency-in-progress). Server chết → không ai gia hạn → hàng quá hạn
     * sau 60 giây và được chiếm lại như cũ. Hàng đã bị xoá / đã xong → 0 dòng → bỏ khỏi danh sách theo dõi.
     */
    public void renewLeases() {
        for (UUID id : List.copyOf(activeLeases)) {
            Instant expiresAt = clock.instant().plus(inProgressTtl);
            Integer renewed = requiresNew.execute(tx -> repository.renewInProgress(id, expiresAt));
            if (renewed == null || renewed == 0) {
                activeLeases.remove(id);
            }
        }
    }

    /** Request lỗi (4xx / 5xx / exception) → xoá hàng khoá để client gửi lại được. */
    public void abandon(UUID recordId) {
        activeLeases.remove(recordId);
        requiresNew.executeWithoutResult(tx -> repository.deleteRow(recordId));
    }

    public int purgeExpired() {
        Instant now = clock.instant();
        Integer deleted = requiresNew.execute(tx -> repository.deleteExpired(now));
        return deleted == null ? 0 : deleted;
    }

    private static ApiException inProgress() {
        return ApiException.conflict(ErrorMessage.IDEMPOTENCY_IN_PROGRESS).withCode(ProblemCodes.IDEMPOTENCY_IN_PROGRESS);
    }
}
