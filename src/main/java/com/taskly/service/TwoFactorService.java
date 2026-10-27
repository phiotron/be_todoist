package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.config.TotpProperties;
import com.taskly.dto.twofactor.DisableTwoFactorRequest;
import com.taskly.dto.twofactor.RecoveryCodesResponse;
import com.taskly.dto.twofactor.TwoFactorSetupResponse;
import com.taskly.dto.twofactor.TwoFactorStatusResponse;
import com.taskly.entity.User;
import com.taskly.entity.UserRecoveryCode;
import com.taskly.entity.UserTotp;
import com.taskly.repository.UserRecoveryCodeRepository;
import com.taskly.repository.UserTotpRepository;
import com.taskly.security.RecoveryCodeHasher;
import com.taskly.security.RecoveryCodes;
import com.taskly.security.SecretCipher;
import com.taskly.security.TotpCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xác minh hai bước TOTP (spec mục 5.9, 12.1). Sở hữu bảng user_totp và user_recovery_codes.
 * - Secret 20 byte, lưu bản mã AES-GCM (SecretCipher), trả base32 cho người dùng đúng một lần lúc setup.
 * - Mã TOTP dùng một lần (last_used_step); mã khôi phục dùng một lần (used_at). Mọi lỗi → ApiException → rollback.
 * - KHÔNG log secret, mã TOTP, mã khôi phục.
 */
@Service
@Transactional
public class TwoFactorService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);

    private final UserService userService;
    private final UserTotpRepository totpRepository;
    private final UserRecoveryCodeRepository recoveryCodeRepository;
    private final SecretCipher secretCipher;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final TotpProperties totpProperties;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public TwoFactorService(UserService userService, UserTotpRepository totpRepository,
                            UserRecoveryCodeRepository recoveryCodeRepository, SecretCipher secretCipher,
                            RecoveryCodeHasher recoveryCodeHasher, TotpProperties totpProperties, PasswordEncoder passwordEncoder, Clock clock) {
        this.userService = userService;
        this.totpRepository = totpRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.secretCipher = secretCipher;
        this.recoveryCodeHasher = recoveryCodeHasher;
        this.totpProperties = totpProperties;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TwoFactorStatusResponse status(UUID userId) {
        userService.requireUser(userId);
        boolean enabled = isEnabled(userId);
        int left = enabled ? (int) recoveryCodeRepository.countByUserIdAndUsedAtIsNull(userId) : 0;
        return new TwoFactorStatusResponse(enabled, left);
    }

    /** Đã bật 2FA chưa (đăng nhập — Task 12j; xoá tài khoản — Task 12k). */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return totpRepository.findById(userId).map(UserTotp::isEnabled).orElse(false);
    }

    /** Tạo (hoặc thay) secret chờ bật. Đang bật → 409 two-factor-already-enabled. */
    public TwoFactorSetupResponse setup(UUID userId) {
        User user = userService.requireUser(userId);
        Optional<UserTotp> existing = totpRepository.findForUpdate(userId);
        if (existing.map(UserTotp::isEnabled).orElse(false)) {
            throw alreadyEnabled();
        }
        byte[] secret = TotpCodec.newSecret();
        String encrypted = secretCipher.encrypt(userId, secret);
        if (existing.isPresent()) {
            existing.get().replaceSecret(encrypted);
        } else {
            totpRepository.save(new UserTotp(userId, encrypted));
        }
        String base32 = TotpCodec.base32(secret);
        return new TwoFactorSetupResponse(base32, TotpCodec.otpauthUri(totpProperties.issuer(), user.getEmail(), base32));
    }

    /** Bật bằng mã TOTP của secret đang chờ → 10 mã khôi phục (trả một lần). */
    public RecoveryCodesResponse enable(UUID userId, String code) {
        User user = userService.requireUser(userId);
        UserTotp totp = totpRepository.findForUpdate(userId)
                .orElseThrow(() -> ApiException.conflict(ErrorMessage.TWO_FACTOR_NOT_SETUP)
                        .withCode(ProblemCodes.TWO_FACTOR_NOT_SETUP));
        if (totp.isEnabled()) {
            throw alreadyEnabled();
        }
        if (!acceptTotp(totp, code)) {
            throw ApiException.badRequest(ErrorMessage.TWO_FACTOR_CODE_INVALID);
        }
        totp.enable(Instant.now(clock));
        return new RecoveryCodesResponse(replaceRecoveryCodes(user));
    }

    /** Tắt: mật khẩu hiện tại (nếu tài khoản có) + mã TOTP / mã khôi phục → xoá secret và mọi mã khôi phục. */
    public void disable(UUID userId, DisableTwoFactorRequest request) {
        User user = userService.requireUser(userId);
        UserTotp totp = requireEnabled(userId);
        if (hasPassword(user) && !passwordMatches(user, request.password())) {
            throw ApiException.badRequest(ErrorMessage.CURRENT_PASSWORD_INCORRECT);
        }
        if (!acceptCode(user, totp, request.code())) {
            throw ApiException.badRequest(ErrorMessage.TWO_FACTOR_CODE_INVALID);
        }
        recoveryCodeRepository.deleteAll(recoveryCodeRepository.findAllByUserId(userId));
        totpRepository.delete(totp);
    }

    /** Tạo lại 10 mã khôi phục (mã cũ hết hiệu lực) sau khi xác minh bằng TOTP / mã khôi phục. */
    public RecoveryCodesResponse regenerateRecoveryCodes(UUID userId, String code) {
        User user = userService.requireUser(userId);
        UserTotp totp = requireEnabled(userId);
        if (!acceptCode(user, totp, code)) {
            throw ApiException.badRequest(ErrorMessage.TWO_FACTOR_CODE_INVALID);
        }
        return new RecoveryCodesResponse(replaceRecoveryCodes(user));
    }

    /** Kết quả checkCode. */
    public enum CodeCheck {
        /** Mã đúng và đã được TIÊU THỤ (TOTP ghi last_used_step / mã khôi phục ghi used_at). */
        ACCEPTED,
        /** 2FA đang bật nhưng mã thiếu / sai / đã dùng. */
        REJECTED,
        /** User chưa bật 2FA (hoặc đã tắt). */
        NOT_ENABLED
    }

    /**
     * Kiểm và TIÊU THỤ một mã (TOTP hoặc mã khôi phục) — dùng ở auth/2fa/verify và DELETE me. Khoá dòng user_totp ngay từ
     * lần đọc đầu tiên: KHÔNG gọi isEnabled / findById trước đó trong cùng transaction, vì entity đã nạp vào persistence context
     * (trạng thái cũ) sẽ được findForUpdate trả lại nguyên và hai request song song cùng dùng được một mã TOTP.
     */
    public CodeCheck checkCode(UUID userId, String code) {
        Optional<UserTotp> totp = totpRepository.findForUpdate(userId).filter(UserTotp::isEnabled);
        if (totp.isEmpty()) {
            return CodeCheck.NOT_ENABLED;
        }
        return acceptCode(userService.requireUser(userId), totp.get(), code) ? CodeCheck.ACCEPTED : CodeCheck.REJECTED;
    }

    /**
     * Xoá toàn bộ dữ liệu 2FA của user (secret + mã khôi phục) từng entity — gọi TRƯỚC khi xoá user (DELETE me): nếu một
     * mã khôi phục vừa được đánh dấu đã dùng trong cùng transaction (entity "dirty") thì để nguyên sẽ làm Hibernate flush
     * UPDATE tham chiếu tới user đã bị xoá (TransientPropertyValueException → 500). Xem ghi chú Task 12g.
     */
    public void deleteAllFor(UUID userId) {
        recoveryCodeRepository.deleteAll(recoveryCodeRepository.findAllByUserId(userId));
        totpRepository.findById(userId).ifPresent(totpRepository::delete);
    }

    private UserTotp requireEnabled(UUID userId) {
        return totpRepository.findForUpdate(userId)
                .filter(UserTotp::isEnabled)
                .orElseThrow(() -> ApiException.conflict(ErrorMessage.TWO_FACTOR_NOT_ENABLED)
                        .withCode(ProblemCodes.TWO_FACTOR_NOT_SETUP));
    }

    private static ApiException alreadyEnabled() {
        return ApiException.conflict(ErrorMessage.TWO_FACTOR_ALREADY_ENABLED)
                .withCode(ProblemCodes.TWO_FACTOR_ALREADY_ENABLED);
    }

    /** 6 chữ số (cho phép khoảng trắng giữa) → TOTP; còn lại → mã khôi phục. */
    private boolean acceptCode(User user, UserTotp totp, String code) {
        if (code == null) {
            return false;
        }
        String compact = code.strip().replace(" ", "");
        if (TotpCodec.isWellFormed(compact)) {
            return acceptTotp(totp, compact);
        }
        return RecoveryCodes.normalize(code)
                .flatMap(normalized -> recoveryCodeRepository.findByUserIdAndCodeHashAndUsedAtIsNull(
                        user.getId(), recoveryCodeHasher.hash(user.getId(), normalized)))
                .map(recoveryCode -> {
                    recoveryCode.markUsed(Instant.now(clock));
                    return true;
                })
                .orElse(false);
    }

    private boolean acceptTotp(UserTotp totp, String code) {
        String compact = code == null ? null : code.strip().replace(" ", "");
        byte[] secret;
        try {
            secret = secretCipher.decrypt(totp.getUserId(), totp.getSecretEncrypted());
        } catch (IllegalStateException e) {
            // Mất / đổi TOTP_ENCRYPTION_KEY (spec 17): mã TOTP không kiểm được, mã khôi phục vẫn dùng được. Không log secret.
            log.error("Không giải mã được secret 2FA — kiểm tra TOTP_ENCRYPTION_KEY");
            return false;
        }
        OptionalLong step = TotpCodec.matchingStep(secret, compact, Instant.now(clock), totp.getLastUsedStep());
        step.ifPresent(totp::markStepUsed);
        return step.isPresent();
    }

    /** Xoá mã cũ (từng entity — xem ghi chú Task 12g) và lưu hash của 10 mã mới; trả mã thô đúng một lần. */
    private List<String> replaceRecoveryCodes(User user) {
        recoveryCodeRepository.deleteAll(recoveryCodeRepository.findAllByUserId(user.getId()));
        List<String> codes = RecoveryCodes.generate();
        for (String code : codes) {
            String normalized = RecoveryCodes.normalize(code).orElseThrow();
            recoveryCodeRepository.save(new UserRecoveryCode(user, recoveryCodeHasher.hash(user.getId(), normalized)));
        }
        return codes;
    }

    /** M3: password_hash luôn có. M5 (password_hash null được) đổi sang User.hasPassword(). */
    private static boolean hasPassword(User user) {
        return user.getPasswordHash() != null;
    }

    private boolean passwordMatches(User user, String password) {
        return password != null
                && password.getBytes(StandardCharsets.UTF_8).length <= AuthService.BCRYPT_MAX_BYTES
                && passwordEncoder.matches(password, user.getPasswordHash());
    }
}
