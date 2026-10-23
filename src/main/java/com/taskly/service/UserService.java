package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.dto.user.UserResponse;
import com.taskly.mapper.UserMapper;
import com.taskly.repository.UserRepository;
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
        return userRepository.findById(userId)
                .map(UserMapper::toResponse)
                .orElseThrow(() -> ApiException.unauthorized("Phiên đăng nhập không hợp lệ hoặc đã hết hạn"));
    }
}
