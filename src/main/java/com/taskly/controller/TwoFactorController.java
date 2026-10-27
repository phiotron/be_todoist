package com.taskly.controller;

import com.taskly.config.OpenApiConfig;
import com.taskly.dto.twofactor.DisableTwoFactorRequest;
import com.taskly.dto.twofactor.RecoveryCodesResponse;
import com.taskly.dto.twofactor.TwoFactorCodeRequest;
import com.taskly.dto.twofactor.TwoFactorSetupResponse;
import com.taskly.dto.twofactor.TwoFactorStatusResponse;
import com.taskly.security.CurrentUser;
import com.taskly.service.TwoFactorService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Xác minh hai bước của tài khoản đang đăng nhập: /api/v1/me/2fa (spec 12.1). */
@RestController
@RequestMapping("/api/v1/me/2fa")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TwoFactorController {

    private final TwoFactorService twoFactorService;

    public TwoFactorController(TwoFactorService twoFactorService) {
        this.twoFactorService = twoFactorService;
    }

    @GetMapping
    public TwoFactorStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return twoFactorService.status(CurrentUser.id(jwt));
    }

    /** Tạo / thay secret chờ bật; đang bật → 409 two-factor-already-enabled. */
    @PostMapping("/setup")
    public TwoFactorSetupResponse setup(@AuthenticationPrincipal Jwt jwt) {
        return twoFactorService.setup(CurrentUser.id(jwt));
    }

    /** Bật bằng mã TOTP → 10 mã khôi phục (chỉ trả một lần). */
    @PostMapping("/enable")
    public RecoveryCodesResponse enable(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody TwoFactorCodeRequest request) {
        return twoFactorService.enable(CurrentUser.id(jwt), request.code());
    }

    /** Tắt: mật khẩu (nếu có) + mã TOTP / mã khôi phục → 204. App hỏi xác nhận trước (BR-11). */
    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DisableTwoFactorRequest request) {
        twoFactorService.disable(CurrentUser.id(jwt), request);
    }

    /** Tạo lại 10 mã khôi phục; mã cũ hết hiệu lực. */
    @PostMapping("/recovery-codes")
    public RecoveryCodesResponse regenerateRecoveryCodes(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody TwoFactorCodeRequest request) {
        return twoFactorService.regenerateRecoveryCodes(CurrentUser.id(jwt), request.code());
    }
}
