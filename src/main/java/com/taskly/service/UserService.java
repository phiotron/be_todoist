package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.dto.user.UserResponse;
import com.taskly.entity.IdentityProvider;
import com.taskly.entity.User;
import com.taskly.mapper.UserMapper;
import com.taskly.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sở hữu bảng users (và user_identities — thuộc aggregate User). Service khác cần user thì gọi qua đây. */
@Service
@Transactional(readOnly = true)
public class UserService {

    static final String SESSION_INVALID = "Phiên đăng nhập không hợp lệ hoặc đã hết hạn";

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** JWT hợp lệ nhưng user không còn → coi như phiên hết hiệu lực (401). */
    public UserResponse getProfile(UUID userId) {
        return UserMapper.toResponse(requireUser(userId));
    }

    /** User của access JWT hiện tại; không còn → 401 (giống getProfile). */
    public User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized(SESSION_INVALID));
    }

    /** Tạo user; email phải đã chuẩn hoá (EmailNormalizer). passwordHash null = chỉ có Google. Email đã có → 409. */
    @Transactional
    public User createUser(String normalizedEmail, String passwordHash, String displayName) {
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw ApiException.conflict("Email đã được dùng");
        }
        return userRepository.save(new User(normalizedEmail, passwordHash, displayName));
    }

    /** email phải đã chuẩn hoá (EmailNormalizer). */
    public Optional<User> findByEmail(String normalizedEmail) {
        return userRepository.findByEmail(normalizedEmail);
    }

    /** User đã liên kết tài khoản (provider, subject). */
    public Optional<User> findByIdentity(IdentityProvider provider, String subject) {
        return userRepository.findByIdentity(provider, subject);
    }
}
