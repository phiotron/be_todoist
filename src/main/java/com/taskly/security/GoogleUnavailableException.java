package com.taskly.security;

/** Không lấy được khoá công khai (JWKS) của Google — lỗi mạng / Google lỗi, KHÔNG phải token sai. */
public class GoogleUnavailableException extends RuntimeException {

    public GoogleUnavailableException(Throwable cause) {
        super("Không lấy được khoá công khai của Google", cause);
    }
}
