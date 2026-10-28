package com.taskly.service;

import com.taskly.config.StorageProperties;
import com.taskly.integration.FileStorage;
import com.taskly.repository.AttachmentRepository;
import com.taskly.repository.StoredFileDeletionRepository;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Dọn file trên đĩa (M8b, plan F7). Xoá task / cây task / dự án / workspace / tài khoản / comment đều đi qua FK cascade
 * → trigger V12 ghi storage_key vào stored_file_deletions trong CÙNG transaction (rollback thì không dọn). Ở đây:
 * (1) xoá hàng file tải lên chưa gắn comment quá taskly.storage.unattached-ttl (trigger lại xếp hàng đợi);
 * (2) xoá file của từng khoá trong hàng đợi rồi xoá hàng. Xoá file lỗi → giữ hàng, lần sau thử lại.
 * @Scheduled mỗi 10 phút (job nền tắt trong test — test gọi run() trực tiếp).
 */
@Service
public class StoredFileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StoredFileCleanupService.class);
    private static final int BATCH = 200;

    private final AttachmentRepository attachmentRepository;
    private final StoredFileDeletionRepository deletionRepository;
    private final FileStorage fileStorage;
    private final StorageProperties storageProperties;
    private final Clock clock;

    public StoredFileCleanupService(AttachmentRepository attachmentRepository,
                                    StoredFileDeletionRepository deletionRepository, FileStorage fileStorage,
                                    StorageProperties storageProperties, Clock clock) {
        this.attachmentRepository = attachmentRepository;
        this.deletionRepository = deletionRepository;
        this.fileStorage = fileStorage;
        this.storageProperties = storageProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    public void scheduledRun() {
        int deleted = run();
        if (deleted > 0) {
            log.info("Đã dọn {} file không còn dùng", deleted);
        }
    }

    /** Trả số file đã xoá khỏi hàng đợi. */
    public int run() {
        attachmentRepository.deleteUnattachedBefore(clock.instant().minus(storageProperties.unattachedTtl()));
        int done = 0;
        while (true) {
            List<String> keys = deletionRepository.nextBatch(BATCH);
            int handled = 0;
            for (String key : keys) {
                try {
                    if (!deletionRepository.isReferenced(key)) {
                        fileStorage.delete(key);
                    }
                    deletionRepository.remove(key);
                    handled++;
                } catch (IOException | RuntimeException e) {
                    log.warn("Chưa dọn được file (thử lại lần sau): {}", e.getClass().getSimpleName());
                }
            }
            done += handled;
            if (keys.size() < BATCH || handled == 0) {
                return done;
            }
        }
    }
}
