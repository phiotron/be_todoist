package com.taskly.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Ký link tải file đính kèm (M8b): sig = HMAC-SHA256(khoá con, "<attachmentId>.<expiresEpochSecond>"), base64url. Khoá con
 * = HMAC(khoá JWT, nhãn riêng) — tách miền với chữ ký JWT nên chữ ký link không bao giờ dùng được làm JWT và ngược lại.
 * Link không cần Bearer (trình duyệt / Image mở được) nên sống ngắn (taskly.storage.download-link-ttl).
 */
@Component
public class AttachmentLinkSigner {

    private static final String LABEL = "taskly-attachment-download-v1";
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public AttachmentLinkSigner(SecretKey jwtSecretKey) {
        this.key = new SecretKeySpec(hmac(jwtSecretKey, LABEL), ALGORITHM);
    }

    public String sign(UUID attachmentId, long expiresEpochSecond) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(key, attachmentId + "." + expiresEpochSecond));
    }

    /** So sánh hằng thời gian; chữ ký sai định dạng → false. */
    public boolean verify(UUID attachmentId, long expiresEpochSecond, String signature) {
        if (signature == null) {
            return false;
        }
        byte[] expected = sign(attachmentId, expiresEpochSecond).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(java.security.Key key, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
