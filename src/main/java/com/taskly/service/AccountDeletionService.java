package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.user.DeleteAccountRequest;
import com.taskly.entity.User;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xoá tài khoản vĩnh viễn (spec 5.9, 10.2, 12.1) trong MỘT transaction:
 * (1) chưa có mật khẩu → 409 password-required; (2) sai / thiếu mật khẩu → 400; (3) 2FA bật mà thiếu / sai mã → 400;
 * (4) xoá workspace của user (owner_id không cascade) rồi xoá user (DB cascade phần còn lại).
 */
@Service
@Transactional
public class AccountDeletionService {

    private final UserService userService;
    private final TwoFactorService twoFactorService;
    private final WorkspaceService workspaceService;
    private final PasswordEncoder passwordEncoder;

    public AccountDeletionService(UserService userService, TwoFactorService twoFactorService,
                                  WorkspaceService workspaceService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.twoFactorService = twoFactorService;
        this.workspaceService = workspaceService;
        this.passwordEncoder = passwordEncoder;
    }

    public void deleteAccount(UUID userId, DeleteAccountRequest request) {
        User user = userService.requireUser(userId);
        // M3: password_hash luôn có. M5 (null được) có thể đổi sang User.hasPassword().
        if (user.getPasswordHash() == null) {
            throw ApiException.conflict(ErrorMessage.PASSWORD_REQUIRED).withCode(ProblemCodes.PASSWORD_REQUIRED);
        }
        String password = request.password();
        if (password == null
                || password.getBytes(StandardCharsets.UTF_8).length > AuthService.BCRYPT_MAX_BYTES
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest(ErrorMessage.CURRENT_PASSWORD_INCORRECT);
        }
        if (twoFactorService.isEnabled(userId)
                && (request.code() == null || !twoFactorService.verifyCode(userId, request.code()))) {
            throw ApiException.badRequest(ErrorMessage.TWO_FACTOR_CODE_INVALID);
        }
        twoFactorService.deleteAllFor(userId); // mã khôi phục vừa dùng đang "dirty" — xoá entity trước khi xoá user
        workspaceService.deleteOwnedWorkspaces(userId);
        userService.deleteUser(user);
    }
}
