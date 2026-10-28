package com.taskly.repository;

import com.taskly.entity.EmailInbox;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailInboxRepository extends JpaRepository<EmailInbox, UUID> {

    Optional<EmailInbox> findByUserIdAndProjectId(UUID userId, UUID projectId);

    Optional<EmailInbox> findByUserIdAndTaskId(UUID userId, UUID taskId);

    Optional<EmailInbox> findByTokenHash(String tokenHash);

    /**
     * Chèn địa chỉ mới; đã có địa chỉ cho (user, dự án | task) — hai request song song — thì không làm gì (trả 0). Không
     * nêu đích ON CONFLICT: mọi ràng buộc unique (kể cả token_hash, gần như không thể trùng) đều cho "bỏ qua".
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO email_inboxes (id, user_id, project_id, task_id, token_hash, token_cipher, created_at,"
            + " updated_at) VALUES (:id, :userId, :projectId, :taskId, :tokenHash, :tokenCipher, :now, :now)"
            + " ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("projectId") UUID projectId,
                       @Param("taskId") UUID taskId, @Param("tokenHash") String tokenHash,
                       @Param("tokenCipher") String tokenCipher, @Param("now") Instant now);

    /**
     * Ghi dấu "đã xử lý thư này cho địa chỉ này" (chống trùng message-id). Trả 0 nếu đã có — request song song cùng thư
     * chờ ở chỉ mục unique tới khi bên kia commit rồi nhận 0.
     */
    @Modifying
    @Query(value = "INSERT INTO email_inbound_messages (inbox_id, message_id_hash, received_at)"
            + " VALUES (:inboxId, :messageIdHash, :now) ON CONFLICT DO NOTHING", nativeQuery = true)
    int markReceived(@Param("inboxId") UUID inboxId, @Param("messageIdHash") String messageIdHash,
                     @Param("now") Instant now);
}
