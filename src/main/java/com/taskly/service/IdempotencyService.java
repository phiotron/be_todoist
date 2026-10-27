package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ErrorMessage;
import com.taskly.entity.IdempotencyKey;
import com.taskly.repository.IdempotencyKeyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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

    static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(60);
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
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyKeyRepository repository, Clock clock,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.idempotency.ttl-hours:24}") long ttlHours) {
        this.repository = repository;
        this.clock = clock;
        this.resultTtl = Duration.ofHours(ttlHours);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Outcome begin(UUID userId, String key, String method, String path, String requestHash) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Instant now = clock.instant();
            try {
                UUID id = requiresNew.execute(tx -> repository.saveAndFlush(
                        new IdempotencyKey(userId, key, method, path, requestHash, now.plus(IN_PROGRESS_TTL))).getId());
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

    /** 2xx → ghi kết quả, đổi hạn sang 24 giờ. */
    public void complete(UUID recordId, int status, String body) {
        Instant expiresAt = clock.instant().plus(resultTtl);
        requiresNew.executeWithoutResult(tx -> repository.markDone(recordId, status, body, expiresAt));
    }

    /** Request lỗi (4xx / 5xx / exception) → xoá hàng khoá để client gửi lại được. */
    public void abandon(UUID recordId) {
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
