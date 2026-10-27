package com.taskly.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Băm mã khôi phục 2FA để lưu DB: HMAC-SHA256(pepper, userId ‖ ":" ‖ mã đã chuẩn hoá) dạng hex 64. Mã chỉ ≈ 40 bit nên
 * SHA-256 trần có thể bị dò cả bảng offline; HMAC với pepper phía server (dẫn xuất từ TOTP_ENCRYPTION_KEY, nhãn
 * "recovery-code" — không thêm biến .env) và userId vào đầu vào làm bảng bị lộ riêng DB không dò được và cùng một mã ở hai
 * user ra hai hash khác nhau. Đổi / mất TOTP_ENCRYPTION_KEY → mã khôi phục cũ vô hiệu (cùng hậu quả với secret TOTP, spec 17).
 */
@Component
public class RecoveryCodeHasher {

    static final String PEPPER_LABEL = "recovery-code";

    private final byte[] pepper;

    public RecoveryCodeHasher(SecretCipher secretCipher) {
        this.pepper = secretCipher.deriveKey(PEPPER_LABEL);
    }

    /** normalizedCode lấy từ RecoveryCodes.normalize. UUID có độ dài cố định nên "userId:code" không nhập nhằng. */
    public String hash(UUID userId, String normalizedCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((userId + ":" + normalizedCode).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM không hỗ trợ HmacSHA256", e);
        }
    }

    @Override
    public String toString() {
        return "RecoveryCodeHasher[pepper=***]";
    }
}
