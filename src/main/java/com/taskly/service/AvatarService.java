package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.user.UserResponse;
import com.taskly.entity.User;
import com.taskly.integration.FileStorage;
import com.taskly.mapper.UserMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ảnh đại diện (M8b, plan F6): JPEG / PNG ≤ 4 MB, nhận diện bằng byte đầu (không tin Content-Type). File ghi vào
 * FileStorage trước, cột users.avatar_* đổi sau trong transaction ngắn; transaction lỗi → xoá file mới. Ảnh cũ do
 * trigger V12 xếp vào hàng đợi dọn.
 */
@Service
public class AvatarService {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";

    private final UserService userService;
    private final FileStorage fileStorage;
    private final UsageLimits usageLimits;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AvatarService(UserService userService, FileStorage fileStorage, UsageLimits usageLimits,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.userService = userService;
        this.fileStorage = fileStorage;
        this.usageLimits = usageLimits;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Ảnh đang mở để gửi cho client (controller đóng luồng). */
    public record Avatar(String contentType, InputStream content) {
    }

    public UserResponse upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest(ErrorMessage.FILE_REQUIRED);
        }
        usageLimits.checkAvatarSize(file.getSize());
        String contentType;
        try (InputStream head = file.getInputStream()) {
            contentType = sniff(head.readNBytes(8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (contentType == null) {
            throw ApiException.badRequest(ErrorMessage.AVATAR_TYPE);
        }
        FileStorage.Stored stored;
        try (InputStream in = file.getInputStream()) {
            stored = fileStorage.store(in, usageLimits.maxAvatarBytes());
        } catch (FileStorage.TooLargeException e) {
            usageLimits.checkAvatarSize(e.actualBytes());
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return transaction.execute(status -> {
                User user = userService.requireUserForUpdate(userId);
                user.setAvatar(stored.storageKey(), contentType, clock.instant());
                return UserMapper.toResponse(user);
            });
        } catch (RuntimeException e) {
            try {
                fileStorage.delete(stored.storageKey());
            } catch (IOException | RuntimeException ignored) {
                // file mồ côi hiếm gặp
            }
            throw e;
        }
    }

    public UserResponse remove(UUID userId) {
        return transaction.execute(status -> {
            User user = userService.requireUserForUpdate(userId);
            user.clearAvatar();
            return UserMapper.toResponse(user);
        });
    }

    /** Ảnh của một người dùng bất kỳ (người đã đăng nhập mới gọi được). Không có ảnh → 404. */
    public Avatar open(UUID userId) {
        User user = transaction.execute(status -> userService.findById(userId).orElse(null));
        if (user == null || user.getAvatarKey() == null) {
            throw ApiException.notFound(ErrorMessage.AVATAR_NOT_FOUND);
        }
        try {
            InputStream content = fileStorage.open(user.getAvatarKey())
                    .orElseThrow(() -> ApiException.notFound(ErrorMessage.AVATAR_NOT_FOUND));
            return new Avatar(user.getAvatarContentType(), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** JPEG: FF D8 FF; PNG: 89 50 4E 47 0D 0A 1A 0A. Khác → null. */
    static String sniff(byte[] head) {
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (head.length >= png.length) {
            for (int i = 0; i < png.length; i++) {
                if (head[i] != png[i]) {
                    return null;
                }
            }
            return PNG;
        }
        return null;
    }
}
