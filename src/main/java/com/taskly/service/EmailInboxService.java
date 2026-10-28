package com.taskly.service;

import com.taskly.config.EmailInboxProperties;
import com.taskly.dto.email.EmailAddressResponse;
import com.taskly.entity.EmailInbox;
import com.taskly.repository.EmailInboxRepository;
import com.taskly.security.EmailTokens;
import com.taskly.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Địa chỉ nhận thư (M13a) của dự án (kể cả Inbox) / task — mỗi người dùng một địa chỉ cho mỗi đích (như Todoist).
 * PUT = lấy hoặc tạo (ổn định qua các lần gọi), regenerate = token mới (địa chỉ cũ hết hiệu lực). Quyền: đọc được dự án /
 * task (không thì 404 như M1). Token chỉ lưu dạng SHA-256 + bản mã AES-GCM bằng KHOÁ CON của TOTP_ENCRYPTION_KEY
 * (SecretCipher.deriveKey) — không dùng lại chính khoá AES của 2FA; aad = id hàng.
 */
@Service
@Transactional
public class EmailInboxService {

    static final String KEY_LABEL = "taskly:email-inbox-token:v1";

    private final EmailInboxRepository repository;
    private final ProjectService projectService;
    private final TaskService taskService;
    private final EmailInboxProperties properties;
    private final SecretCipher cipher;
    private final Clock clock;

    public EmailInboxService(EmailInboxRepository repository, ProjectService projectService, TaskService taskService,
                             EmailInboxProperties properties, SecretCipher masterCipher, Clock clock) {
        this.repository = repository;
        this.projectService = projectService;
        this.taskService = taskService;
        this.properties = properties;
        this.cipher = new SecretCipher(Base64.getEncoder().encodeToString(masterCipher.deriveKey(KEY_LABEL)));
        this.clock = clock;
    }

    public EmailAddressResponse projectAddress(UUID userId, UUID projectId) {
        projectService.requireReadable(userId, projectId);
        return toResponse(getOrCreate(userId, projectId, null));
    }

    public EmailAddressResponse regenerateProjectAddress(UUID userId, UUID projectId) {
        projectService.requireReadable(userId, projectId);
        return toResponse(rotate(getOrCreate(userId, projectId, null).inbox()));
    }

    public EmailAddressResponse taskAddress(UUID userId, UUID taskId) {
        taskService.requireReadable(userId, taskId);
        return toResponse(getOrCreate(userId, null, taskId));
    }

    public EmailAddressResponse regenerateTaskAddress(UUID userId, UUID taskId) {
        taskService.requireReadable(userId, taskId);
        return toResponse(rotate(getOrCreate(userId, null, taskId).inbox()));
    }

    /** Thư tới: tra theo token (phần trước @, đã đổi chữ thường). Sai định dạng → rỗng, khỏi tra DB. */
    @Transactional(readOnly = true)
    public Optional<EmailInbox> findByToken(String token) {
        return EmailTokens.isWellFormed(token) ? repository.findByTokenHash(EmailTokens.hash(token)) : Optional.empty();
    }

    public String domain() {
        return properties.domain();
    }

    // ---- nội bộ ----

    private record Issued(EmailInbox inbox, String token) {
    }

    private Issued getOrCreate(UUID userId, UUID projectId, UUID taskId) {
        Optional<EmailInbox> existing = find(userId, projectId, taskId);
        if (existing.isEmpty()) {
            String token = EmailTokens.generate();
            UUID id = UUID.randomUUID();
            // ON CONFLICT DO NOTHING: request song song đã tạo trước thì dùng hàng của nó
            repository.insertIfAbsent(id, userId, projectId, taskId, EmailTokens.hash(token), encrypt(id, token),
                    clock.instant());
            existing = find(userId, projectId, taskId);
        }
        EmailInbox inbox = existing.orElseThrow();
        try {
            return new Issued(inbox, new String(cipher.decrypt(inbox.getId(), inbox.getTokenCipher()),
                    StandardCharsets.UTF_8));
        } catch (IllegalStateException e) {
            // khoá đã đổi / mất: không hiện lại được địa chỉ cũ → cấp địa chỉ mới (cũ hết hiệu lực)
            return rotate(inbox);
        }
    }

    private Issued rotate(EmailInbox inbox) {
        String token = EmailTokens.generate();
        inbox.replaceToken(EmailTokens.hash(token), encrypt(inbox.getId(), token));
        repository.flush();
        return new Issued(inbox, token);
    }

    private Optional<EmailInbox> find(UUID userId, UUID projectId, UUID taskId) {
        return projectId != null ? repository.findByUserIdAndProjectId(userId, projectId)
                : repository.findByUserIdAndTaskId(userId, taskId);
    }

    private String encrypt(UUID id, String token) {
        return cipher.encrypt(id, token.getBytes(StandardCharsets.UTF_8));
    }

    private EmailAddressResponse toResponse(Issued issued) {
        return new EmailAddressResponse(issued.token() + "@" + properties.domain(), issued.inbox().getTargetType(),
                issued.inbox().getTargetId());
    }
}
