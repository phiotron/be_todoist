package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;

/**
 * Lỗi nghiệp vụ của đăng nhập / liên kết Apple, Facebook (M13b) — cùng bảng mã HTTP với GoogleAuthErrors (M5, spec 12.1,
 * 12.10): endpoint công khai auth/* trả 401 khi token sai; endpoint có Bearer me/identities/* trả 400 (không 401).
 */
final class IdentityErrors {

    private IdentityErrors() {
    }

    static ApiException invalidToken() {
        return ApiException.unauthorized(ErrorMessage.IDENTITY_INVALID_TOKEN);
    }

    static ApiException invalidTokenForLink() {
        return ApiException.badRequest(ErrorMessage.IDENTITY_INVALID_TOKEN_FOR_LINK);
    }

    static ApiException exactlyOneToken() {
        return ApiException.badRequest(ErrorMessage.IDENTITY_EXACTLY_ONE_TOKEN);
    }

    static ApiException emailNotVerified() {
        return ApiException.forbidden(ErrorMessage.IDENTITY_EMAIL_NOT_VERIFIED);
    }

    /** Facebook không cấp email và tài khoản chưa liên kết → không tạo / ghép tài khoản được (403). */
    static ApiException emailMissing() {
        return ApiException.forbidden(ErrorMessage.IDENTITY_EMAIL_MISSING);
    }

    static ApiException linkTokenForAnotherAccount() {
        return ApiException.badRequest(ErrorMessage.IDENTITY_LINK_TOKEN_OTHER_ACCOUNT);
    }

    static ApiException notLinked() {
        return ApiException.notFound(ErrorMessage.IDENTITY_NOT_LINKED);
    }

    static ApiException linkRequired(String linkToken) {
        return ApiException.conflict(ErrorMessage.IDENTITY_LINK_REQUIRED)
                .withCode(ProblemCodes.LINK_REQUIRED)
                .withProperty("linkToken", linkToken);
    }

    static ApiException linkedElsewhere() {
        return ApiException.conflict(ErrorMessage.IDENTITY_LINKED_ELSEWHERE);
    }

    static ApiException accountAlreadyLinked() {
        return ApiException.conflict(ErrorMessage.IDENTITY_ACCOUNT_ALREADY_LINKED);
    }

    static ApiException passwordRequiredBeforeUnlink() {
        return ApiException.conflict(ErrorMessage.IDENTITY_PASSWORD_REQUIRED_BEFORE_UNLINK)
                .withCode(ProblemCodes.PASSWORD_REQUIRED);
    }

    static ApiException unavailable() {
        return ApiException.serviceUnavailable(ErrorMessage.IDENTITY_UNAVAILABLE);
    }
}
