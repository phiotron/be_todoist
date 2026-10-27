package com.taskly.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã hoá secret TOTP trước khi lưu DB (spec mục 5.9, 8.2 quy tắc 7): AES-256-GCM, khoá = base64 của đúng 32 byte
 * (taskly.totp.encryption-key ← biến TOTP_ENCRYPTION_KEY trong .env do người dùng tự đặt).
 * Bản mã = base64(IV 12 byte ‖ ciphertext + tag 16 byte). Không bao giờ đưa khoá / secret vào log hay thông báo lỗi.
 */
public final class SecretCipher {

    static final int KEY_BYTES = 32;
    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    static final String KEY_ERROR = "TOTP_ENCRYPTION_KEY chưa được cấu hình hoặc không phải base64 của đúng "
            + KEY_BYTES + " byte (xem .env.example)";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public SecretCipher(String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank() || base64Key.startsWith("${")) {
            throw new IllegalStateException(KEY_ERROR);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(KEY_ERROR);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(KEY_ERROR);
        }
        return bytes;
    }

    public String encrypt(byte[] plaintext) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không mã hoá được secret 2FA");
        }
    }

    /** Sai khoá, bản mã bị sửa hoặc không phải base64 → IllegalStateException (không kèm dữ liệu). */
    public byte[] decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("Không giải mã được secret 2FA");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Không giải mã được secret 2FA");
        }
    }

    /**
     * Khoá con 32 byte theo nhãn: HMAC-SHA256(khoá, nhãn) — để các mục đích khác (vd băm mã khôi phục) dùng chung
     * TOTP_ENCRYPTION_KEY mà không lộ / dùng lại chính khoá AES. Không bao giờ log kết quả.
     */
    public byte[] deriveKey(String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không dẫn xuất được khoá con");
        }
    }

    @Override
    public String toString() {
        return "SecretCipher[key=***]";
    }
}
