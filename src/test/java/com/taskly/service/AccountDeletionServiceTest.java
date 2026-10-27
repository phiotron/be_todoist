package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.user.DeleteAccountRequest;
import com.taskly.entity.User;
import com.taskly.security.TwoFactorRateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Unit (Mockito): tài khoản chưa có mật khẩu → 409 password-required, không xoá gì. Ở M3 cột password_hash còn NOT NULL
 * nên không dựng được ca này qua API — từ M5 (V6 cho phép null) thêm test API tương ứng.
 */
class AccountDeletionServiceTest {

    @Test
    void accountWithoutPassword_returns409PasswordRequired_andDeletesNothing() {
        UUID userId = UUID.randomUUID();
        UserService userService = mock(UserService.class);
        TwoFactorService twoFactorService = mock(TwoFactorService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(userService.requireUserForUpdate(userId)).thenReturn(new User("google-only@example.com", null, "Chỉ Google"));
        AccountDeletionService service = new AccountDeletionService(
                userService, twoFactorService, workspaceService, new BCryptPasswordEncoder(),
                mock(TwoFactorRateLimiter.class));

        assertThatThrownBy(() -> service.deleteAccount(userId, new DeleteAccountRequest("bat-ky", null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getErrorMessage()).isEqualTo(ErrorMessage.PASSWORD_REQUIRED);
                    assertThat(ex.getBody().getProperties()).containsEntry("code", "password-required");
                });
        verifyNoInteractions(twoFactorService, workspaceService);
    }
}
