package com.taskly.security;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Xác thực webhook email inbound (M13a) — trung lập nhà cung cấp: adapter của dịch vụ nhận thư ký mỗi request bằng
 * bí mật dùng chung (EMAIL_INBOUND_WEBHOOK_SECRET):
 * <pre>
 *   X-Taskly-Timestamp: &lt;epoch giây&gt;
 *   X-Taskly-Signature: v1=&lt;hex HMAC-SHA256(secret, timestamp + "." + body thô)&gt;
 * </pre>
 * Lệch giờ quá {@code tolerance} → từ chối (chặn phát lại request cũ). Bí mật trống = chưa cấu hình → từ chối mọi request.
 * Mọi lỗi → 401 cùng một câu (không nói sai ở đâu). So sánh hằng thời gian. Không bao giờ log bí mật / chữ ký.
 */
public class InboundSignatureVerifier {

    public static final String TIMESTAMP_HEADER = "X-Taskly-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Taskly-Signature";
    static final String VERSION_PREFIX = "v1=";

    private final String secret;
    private final Duration tolerance;
    private final Clock clock;

    public InboundSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        this.secret = secret == null ? "" : secret;
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public boolean configured() {
        return !secret.isBlank();
    }

    /** Đúng → trả về bình thường; sai / thiếu / quá hạn / chưa cấu hình → 401 EMAIL_SIGNATURE_INVALID. */
    public void verify(String timestampHeader, String signatureHeader, byte[] body) {
        if (!configured() || timestampHeader == null || signatureHeader == null
                || !signatureHeader.startsWith(VERSION_PREFIX)) {
            throw invalid();
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.strip());
        } catch (NumberFormatException e) {
            throw invalid();
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > tolerance.toSeconds()) {
            throw invalid();
        }
        byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signatureHeader.strip().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw invalid();
        }
    }

    /** Chữ ký của một request — adapter (và test) dùng cùng công thức. */
    public static String sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return VERSION_PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // IllegalArgumentException: khoá rỗng — coi như không ký được
            return VERSION_PREFIX;
        }
    }

    private static ApiException invalid() {
        return ApiException.unauthorized(ErrorMessage.EMAIL_SIGNATURE_INVALID);
    }

    @Override
    public String toString() {
        return "InboundSignatureVerifier[secret=***, tolerance=" + tolerance + "]";
    }
}
