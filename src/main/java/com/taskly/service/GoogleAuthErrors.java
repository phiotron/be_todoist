package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;

/**
 * Mọi lỗi nghiệp vụ của đăng nhập / liên kết Google (M5). detail là KHOÁ ErrorMessage; ProblemDetailLocalizer (M3)
 * dịch sang vi / en theo Accept-Language và để nguyên property "code", "linkToken".
 */
final class GoogleAuthErrors {

    private GoogleAuthErrors() {
    }

    /** auth/google: ID token sai chữ ký / hết hạn / sai aud / sai iss / thiếu email → 401 (spec 12.8). */
    static ApiException invalidIdToken() {
        return ApiException.unauthorized(ErrorMessage.GOOGLE_INVALID_ID_TOKEN);
    }

    /** me/identities/google: idToken / linkToken không dùng được → 400, KHÔNG 401 (app sẽ tưởng phiên hết hạn). */
    static ApiException invalidTokenForLink() {
        return ApiException.badRequest(ErrorMessage.GOOGLE_INVALID_TOKEN_FOR_LINK);
    }

    static ApiException exactlyOneToken() {
        return ApiException.badRequest(ErrorMessage.GOOGLE_EXACTLY_ONE_TOKEN);
    }

    static ApiException emailNotVerified() {
        return ApiException.forbidden(ErrorMessage.GOOGLE_EMAIL_NOT_VERIFIED);
    }

    /** Email trong linkToken khác email tài khoản → 400 (hợp đồng đã chốt với mobile: không 401/403 trên endpoint có Bearer). */
    static ApiException linkTokenForAnotherAccount() {
        return ApiException.badRequest(ErrorMessage.GOOGLE_LINK_TOKEN_OTHER_ACCOUNT);
    }

    /** DELETE me/identities/google khi tài khoản chưa liên kết Google → 404. */
    static ApiException googleNotLinked() {
        return ApiException.notFound(ErrorMessage.GOOGLE_NOT_LINKED);
    }

    /** 409 kèm property code = "link-required" và linkToken (hạn 10 phút). */
    static ApiException linkRequired(String linkToken) {
        return ApiException.conflict(ErrorMessage.GOOGLE_LINK_REQUIRED)
                .withCode(ProblemCodes.LINK_REQUIRED)
                .withProperty("linkToken", linkToken);
    }

    static ApiException googleAccountLinkedElsewhere() {
        return ApiException.conflict(ErrorMessage.GOOGLE_LINKED_ELSEWHERE);
    }

    static ApiException accountAlreadyLinked() {
        return ApiException.conflict(ErrorMessage.GOOGLE_ACCOUNT_ALREADY_LINKED);
    }

    /** DELETE me/identities/google khi tài khoản chưa có mật khẩu → 409 code "password-required" (spec 12.1, 12.10). */
    static ApiException passwordRequiredBeforeUnlink() {
        return ApiException.conflict(ErrorMessage.GOOGLE_PASSWORD_REQUIRED_BEFORE_UNLINK)
                .withCode(ProblemCodes.PASSWORD_REQUIRED);
    }

    static ApiException googleUnavailable() {
        return ApiException.serviceUnavailable(ErrorMessage.GOOGLE_UNAVAILABLE);
    }
}
