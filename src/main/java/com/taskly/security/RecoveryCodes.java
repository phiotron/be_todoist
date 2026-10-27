package com.taskly.security;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mã khôi phục 2FA (spec mục 5.9): 10 mã dùng một lần, dạng "xxxx-xxxx" (8 ký tự a–z, 2–7 ≈ 40 bit). Người dùng gõ lại
 * có thể viết hoa / thiếu "-" / thừa khoảng trắng → normalize. DB chỉ lưu HMAC-SHA256 (pepper phía server + userId) của
 * dạng chuẩn hoá — xem RecoveryCodeHasher; verify bị giới hạn 5 lần / 5 phút ở Task 13a.
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    static final int LENGTH = 8;

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final Pattern NORMALIZED = Pattern.compile("[a-z2-7]{8}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    /** 10 mã khác nhau, dạng "xxxx-xxxx" — chỉ trả cho người dùng một lần. */
    public static List<String> generate() {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < COUNT) {
            StringBuilder raw = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                raw.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(raw.substring(0, LENGTH / 2) + "-" + raw.substring(LENGTH / 2));
        }
        return List.copyOf(codes);
    }

    /** Bỏ khoảng trắng hai đầu, "-" và dấu cách, đổi chữ thường; không đúng 8 ký tự a–z / 2–7 → rỗng. */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String compact = input.strip().replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
        return NORMALIZED.matcher(compact).matches() ? Optional.of(compact) : Optional.empty();
    }
}
