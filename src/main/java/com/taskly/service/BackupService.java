package com.taskly.service;

import com.taskly.dto.backup.BackupResponse;
import com.taskly.entity.LimitKind;
import com.taskly.repository.BackupRepository;
import com.taskly.security.AccessPolicy;
import com.taskly.security.OpaqueTokens;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M11 F8 — backup hằng ngày phía server (như Todoist Pro): người dùng "tới hạn" khi lần chạy trước (hoặc lúc tạo tài
 * khoản) trước nửa đêm hôm nay theo múi giờ tài khoản. Mỗi người một transaction riêng, khoá hàng users rồi kiểm lại:
 * chụp JSON, so SHA-256 với lần chạy trước — chỉ tạo bản mới khi nội dung ĐỔI; giữ LimitKind.BACKUPS (21) bản mới nhất.
 * Không có khôi phục / tải về trên mobile (ADR 0006). Log không chứa nội dung (chỉ số lượng).
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final BackupRepository backupRepository;
    private final UsageLimits usageLimits;
    private final AccessPolicy accessPolicy;
    private final TransactionTemplate transactionTemplate;

    public BackupService(BackupRepository backupRepository, UsageLimits usageLimits,
                         PlatformTransactionManager transactionManager, AccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
        this.backupRepository = backupRepository;
        this.usageLimits = usageLimits;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<BackupResponse> list(UUID userId) {
        return backupRepository.list(userId);
    }

    @Transactional(readOnly = true)
    public List<UUID> findDueUserIds(Instant now) {
        return backupRepository.findDueUserIds(now);
    }

    @Transactional(readOnly = true)
    public boolean isDue(UUID userId, Instant now) {
        return backupRepository.isDue(userId, now);
    }

    /** Job gọi: mọi người dùng tới hạn lúc now. Trả số bản backup đã tạo. */
    public int runDue(Instant now) {
        return runDue(now, findDueUserIds(now));
    }

    /** Lỗi của một người không chặn người khác (ghi log, lần chạy sau thử lại vì backup_runs chưa ghi). */
    public int runDue(Instant now, Collection<UUID> userIds) {
        int created = 0;
        for (UUID userId : userIds) {
            try {
                if (backupIfDue(userId, now)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.warn("Backup thất bại cho một người dùng: {}", e.getClass().getSimpleName());
            }
        }
        if (!userIds.isEmpty()) {
            log.info("Backup hằng ngày: {} người tới hạn, {} bản mới", userIds.size(), created);
        }
        return created;
    }

    /** Một người dùng, transaction riêng. true = vừa tạo bản backup mới. */
    public boolean backupIfDue(UUID userId, Instant now) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            usageLimits.lockUser(userId); // hai lần chạy chồng nhau (nhiều instance) không tạo trùng
            if (!backupRepository.isDue(userId, now)) {
                return false;
            }
            BackupRepository.Snapshot snapshot = backupRepository.snapshot(userId, accessPolicy.listScope(userId).projectIds()); // M8a: dự án đã tham gia
            byte[] bytes = snapshot.content().getBytes(StandardCharsets.UTF_8);
            String hash = OpaqueTokens.sha256Hex(snapshot.content());
            boolean changed = !Objects.equals(hash, backupRepository.lastHash(userId).orElse(null));
            if (changed) {
                backupRepository.insert(userId, snapshot, hash, bytes.length, now);
                backupRepository.prune(userId, usageLimits.max(LimitKind.BACKUPS));
            }
            backupRepository.recordRun(userId, now, hash);
            return changed;
        }));
    }
}
