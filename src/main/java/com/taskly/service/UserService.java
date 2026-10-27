package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ProblemCodes;
import com.taskly.common.error.ProblemTexts;
import com.taskly.dto.user.UserResponse;
import com.taskly.entity.User;
import com.taskly.mapper.UserMapper;
import com.taskly.repository.UserRepository;
import java.time.ZoneId;
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

    /** JWT hợp lệ nhưng user không còn → coi như phiên hết hiệu lực (401). */
    public UserResponse getProfile(UUID userId) {
        return UserMapper.toResponse(requireUser(userId));
    }

    /** Múi giờ IANA của tài khoản — dùng để tính "hôm nay" / "bây giờ" cho giờ floating (spec 5.2). */
    public ZoneId timeZoneOf(UUID userId) {
        return ZoneId.of(requireUser(userId).getTimeZone());
    }

    /** Tạo user; email phải đã chuẩn hoá (EmailNormalizer). Email đã có → 409 code email-taken (spec 12.10). */
    @Transactional
    public User createUser(String normalizedEmail, String passwordHash, String displayName) {
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw ApiException.conflict(ProblemTexts.EMAIL_TAKEN).withCode(ProblemCodes.EMAIL_TAKEN);
        }
        return userRepository.save(new User(normalizedEmail, passwordHash, displayName));
    }

    /** email phải đã chuẩn hoá (EmailNormalizer). */
    public Optional<User> findByEmail(String normalizedEmail) {
        return userRepository.findByEmail(normalizedEmail);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized(ProblemTexts.SESSION_INVALID));
    }
}
