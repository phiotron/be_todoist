package com.taskly.common.error;

/**
 * Giá trị thuộc tính "code" của ProblemDetail (spec mục 12.10) — app phân biệt tình huống bằng (status, code), không
 * bằng câu chữ (câu chữ đổi theo ngôn ngữ). M3 dùng 4 mã dưới; M1 / M2 có mã riêng của mình.
 */
public final class ProblemCodes {

    /** Tên thuộc tính trong ProblemDetail. */
    public static final String PROPERTY = "code";

    /** 401 ở auth/login (và auth/google từ M5): tài khoản bật 2FA, kèm "twoFactorToken". */
    public static final String TWO_FACTOR_REQUIRED = "two-factor-required";

    /** 409 ở me/2fa/setup | enable khi 2FA đang bật. */
    public static final String TWO_FACTOR_ALREADY_ENABLED = "two-factor-already-enabled";

    /** 409 ở me/2fa/enable khi chưa setup; ở me/2fa/disable | recovery-codes khi 2FA chưa bật. */
    public static final String TWO_FACTOR_NOT_SETUP = "two-factor-not-setup";

    /**
     * 401 ở auth/2fa/verify: mã 6 số / mã khôi phục sai hoặc đã dùng, twoFactorToken CÒN SỐNG (không bị đốt) → app cho
     * nhập lại mã. Người dùng chọn phương án A (2026-10-27).
     */
    public static final String TWO_FACTOR_CODE_INVALID = "two-factor-code-invalid";

    /**
     * 401 ở auth/2fa/verify: twoFactorToken hết hạn / không hợp lệ / đã dùng / sai typ-aud → app phải quay về Đăng nhập.
     */
    public static final String TWO_FACTOR_TOKEN_INVALID = "two-factor-token-invalid";

    /** 409 ở DELETE me (và bỏ liên kết Google từ M5) khi tài khoản chưa có mật khẩu. */
    public static final String PASSWORD_REQUIRED = "password-required";

    private ProblemCodes() {
    }
}
