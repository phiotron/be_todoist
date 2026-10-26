package com.taskly.service;

import com.taskly.common.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Mọi lỗi nghiệp vụ của đăng nhập / liên kết Google (M5) gom một chỗ: app mobile hiển thị nguyên văn detail,
 * và Task 16 (thông báo vi/en của M3) chỉ phải sửa file này.
 */
final class GoogleAuthErrors {

    /** Giá trị property "code" của 409 khi email Google trùng tài khoản mật khẩu. */
    static final String LINK_REQUIRED_CODE = "link-required";

    /** Giá trị property "code" của 409 khi tài khoản chưa có mật khẩu (spec 12.10, chung với DELETE me của M3). */
    static final String PASSWORD_REQUIRED_CODE = "password-required";

    private GoogleAuthErrors() {
    }

    /** auth/google: ID token sai chữ ký / hết hạn / sai aud / sai iss / thiếu email → 401 (spec 12.8). */
    static ApiException invalidIdToken() {
        return ApiException.unauthorized("Đăng nhập Google không thành công, vui lòng thử lại");
    }

    /** me/identities/google: idToken / linkToken không dùng được → 400, KHÔNG 401 (app sẽ tưởng phiên hết hạn). */
    static ApiException invalidTokenForLink() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Mã xác thực Google không hợp lệ hoặc đã hết hạn");
    }

    static ApiException exactlyOneToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Cần gửi đúng một trong hai: idToken hoặc linkToken");
    }

    static ApiException emailNotVerified() {
        return new ApiException(HttpStatus.FORBIDDEN, "Email Google chưa được xác minh");
    }

    /** Email trong linkToken khác email tài khoản → 400 (hợp đồng đã chốt với mobile: không 401/403 trên endpoint có Bearer). */
    static ApiException linkTokenForAnotherAccount() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Mã liên kết này dành cho tài khoản khác");
    }

    /** DELETE me/identities/google khi tài khoản chưa liên kết Google → 404. */
    static ApiException googleNotLinked() {
        return new ApiException(HttpStatus.NOT_FOUND, "Tài khoản chưa liên kết Google");
    }

    /** 409 kèm property code = "link-required" và linkToken (hạn 10 phút). */
    static ApiException linkRequired(String linkToken) {
        ApiException ex = ApiException.conflict(
                "Email này đã có tài khoản Taskly. Hãy đăng nhập bằng mật khẩu để liên kết Google");
        ex.getBody().setProperty("code", LINK_REQUIRED_CODE);
        ex.getBody().setProperty("linkToken", linkToken);
        return ex;
    }

    static ApiException googleAccountLinkedElsewhere() {
        return ApiException.conflict("Tài khoản Google này đã liên kết với một tài khoản Taskly khác");
    }

    static ApiException accountAlreadyLinked() {
        return ApiException.conflict(
                "Tài khoản của bạn đã liên kết với một tài khoản Google khác. Hãy bỏ liên kết trước");
    }

    /** DELETE me/identities/google khi tài khoản chưa có mật khẩu → 409 code "password-required" (spec 12.1, 12.10). */
    static ApiException passwordRequiredBeforeUnlink() {
        ApiException ex = ApiException.conflict("Hãy đặt mật khẩu trước khi bỏ liên kết Google");
        ex.getBody().setProperty("code", PASSWORD_REQUIRED_CODE);
        return ex;
    }

    static ApiException googleUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Không kết nối được tới Google, vui lòng thử lại sau");
    }
}
