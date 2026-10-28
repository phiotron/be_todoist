package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.config.StorageProperties;
import com.taskly.dto.comment.AttachmentLinkResponse;
import com.taskly.dto.comment.AttachmentResponse;
import com.taskly.integration.FileStorage;
import com.taskly.mapper.CommentMapper;
import com.taskly.repository.AttachmentRepository;
import com.taskly.repository.AttachmentRepository.AttachmentRow;
import com.taskly.security.AttachmentLinkSigner;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * File đính kèm comment (M8b, plan F2): tải lên trước (chưa gắn), CommentService gắn khi tạo comment. Ghi file vào
 * FileStorage TRƯỚC, chèn hàng SAU; chèn lỗi → xoá file vừa ghi. Tải về bằng link ký HMAC (không cần Bearer).
 * Không @Transactional ở mức lớp: tải lên 100 MB không được giữ transaction / kết nối DB.
 */
@Service
public class AttachmentService {

    private static final Pattern CONTENT_TYPE = Pattern.compile("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*");
    private static final String FALLBACK_TYPE = "application/octet-stream";
    private static final int MAX_FILE_NAME = 255;

    private final AttachmentRepository attachmentRepository;
    private final CommentService commentService;
    private final FileStorage fileStorage;
    private final UsageLimits usageLimits;
    private final AttachmentLinkSigner signer;
    private final StorageProperties storageProperties;
    private final Clock clock;

    public AttachmentService(AttachmentRepository attachmentRepository, CommentService commentService,
                             FileStorage fileStorage, UsageLimits usageLimits, AttachmentLinkSigner signer,
                             StorageProperties storageProperties, Clock clock) {
        this.attachmentRepository = attachmentRepository;
        this.commentService = commentService;
        this.fileStorage = fileStorage;
        this.usageLimits = usageLimits;
        this.signer = signer;
        this.storageProperties = storageProperties;
        this.clock = clock;
    }

    /** Một file đang mở để gửi cho client (controller đóng luồng). */
    public record Download(AttachmentRow attachment, InputStream content) {
    }

    public AttachmentResponse upload(UUID userId, MultipartFile file) {
        if (file == null) {
            throw ApiException.badRequest(ErrorMessage.FILE_REQUIRED);
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest(ErrorMessage.ATTACHMENT_EMPTY);
        }
        usageLimits.checkCommentFileSize(file.getSize()); // nhanh: multipart đã biết cỡ
        FileStorage.Stored stored;
        try (InputStream in = file.getInputStream()) {
            stored = fileStorage.store(in, usageLimits.maxCommentFileBytes());
        } catch (FileStorage.TooLargeException e) {
            usageLimits.checkCommentFileSize(e.actualBytes());
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        AttachmentRow row = new AttachmentRow(UUID.randomUUID(), userId, null, fileName(file.getOriginalFilename()),
                contentType(file.getContentType()), stored.sizeBytes(), stored.storageKey(), clock.instant());
        try {
            attachmentRepository.insert(row);
        } catch (RuntimeException e) {
            deleteQuietly(stored.storageKey());
            throw e;
        }
        return CommentMapper.attachment(row);
    }

    /**
     * Link tải: file đã gắn → cần đọc được comment (404 comment-not-found nếu không); chưa gắn → chỉ người tải lên.
     */
    public AttachmentLinkResponse link(UUID userId, UUID attachmentId) {
        AttachmentRow row = attachmentRepository.find(attachmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.ATTACHMENT_NOT_FOUND));
        if (row.commentId() == null) {
            if (!row.uploaderId().equals(userId)) {
                throw ApiException.notFound(ErrorMessage.ATTACHMENT_NOT_FOUND);
            }
        } else {
            commentService.requireReadable(userId, row.commentId());
        }
        Instant expiresAt = clock.instant().plus(storageProperties.downloadLinkTtl());
        long expires = expiresAt.getEpochSecond();
        String url = "/api/v1/attachments/" + attachmentId + "/download?expires=" + expires + "&sig="
                + signer.sign(attachmentId, expires);
        return new AttachmentLinkResponse(url, Instant.ofEpochSecond(expires));
    }

    /** Chữ ký sai / hết hạn → 403 download-link-invalid; file đã bị xoá → 404. */
    public Download open(UUID attachmentId, long expires, String signature) {
        if (!signer.verify(attachmentId, expires, signature) || clock.instant().getEpochSecond() > expires) {
            throw ApiException.forbidden(ErrorMessage.DOWNLOAD_LINK_INVALID);
        }
        AttachmentRow row = attachmentRepository.find(attachmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.ATTACHMENT_NOT_FOUND));
        try {
            InputStream content = fileStorage.open(row.storageKey())
                    .orElseThrow(() -> ApiException.notFound(ErrorMessage.ATTACHMENT_NOT_FOUND));
            return new Download(row, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bỏ đường dẫn (Windows / Unix), ký tự điều khiển; rỗng → "file"; ≤ 255 ký tự (giữ đuôi). */
    static String fileName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "file";
        }
        if (name.length() > MAX_FILE_NAME) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            name = name.substring(0, MAX_FILE_NAME - ext.length()) + ext;
        }
        return name;
    }

    static String contentType(String declared) {
        if (declared == null) {
            return FALLBACK_TYPE;
        }
        String type = declared.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return type.length() <= 127 && CONTENT_TYPE.matcher(type).matches() ? type : FALLBACK_TYPE;
    }

    private void deleteQuietly(String storageKey) {
        try {
            fileStorage.delete(storageKey);
        } catch (IOException | RuntimeException ignored) {
            // file mồ côi hiếm gặp — ghi nhận ở plan (rủi ro)
        }
    }
}
