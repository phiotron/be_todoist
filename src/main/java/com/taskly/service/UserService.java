package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.user.UpdateProfileRequest;
import com.taskly.dto.user.UserResponse;
import com.taskly.entity.User;
import com.taskly.mapper.UserMapper;
import com.taskly.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sở hữu bảng users. Service khác cần user thì gọi qua đây, không gọi UserRepository trực tiếp. */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserResponse getProfile(UUID userId) {
        return UserMapper.toResponse(requireUser(userId));
    }

    /**
     * User đang đăng nhập (id lấy từ JWT). JWT hợp lệ nhưng user không còn → coi như phiên hết hiệu lực (401).
     * Service khác gọi trong transaction ghi của mình để nhận entity được quản lý và sửa trực tiếp.
     */
    public User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized(ErrorMessage.SESSION_INVALID));
    }

    /** Không ném lỗi nếu user không còn (vd auth/2fa/verify: user xoá giữa hai bước → token coi như vô hiệu). */
    public Optional<User> findById(UUID userId) {
        return userRepository.findById(userId);
    }

    /** Đổi tên hiển thị. Tên giống hệt hiện tại → không UPDATE, updated_at giữ nguyên (BR-10). */
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = requireUser(userId);
        user.changeDisplayName(request.displayName());
        return UserMapper.toResponse(user);
    }

    /** Tạo user; email phải đã chuẩn hoá (EmailNormalizer). Email đã có → 409. */
    @Transactional
    public User createUser(String normalizedEmail, String passwordHash, String displayName) {
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw ApiException.conflict(ErrorMessage.EMAIL_TAKEN);
        }
        return userRepository.save(new User(normalizedEmail, passwordHash, displayName));
    }

    /**
     * Xoá user (DELETE me) — workspace của user phải đã xoá trước (AccountDeletionService). DB cascade: refresh_tokens,
     * password_reset_tokens, user_totp, user_recovery_codes, workspace_members (và bảng theo user của M1 / M2).
     */
    @Transactional
    public void deleteUser(User user) {
        userRepository.delete(user);
    }

    /** email phải đã chuẩn hoá (EmailNormalizer). */
    public Optional<User> findByEmail(String normalizedEmail) {
        return userRepository.findByEmail(normalizedEmail);
    }
}
