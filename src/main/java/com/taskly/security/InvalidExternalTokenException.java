package com.taskly.security;

/** Token Apple / Facebook / linkToken không dùng được (sai chữ ký, hết hạn, sai aud / app). Không mang dữ liệu token. */
public class InvalidExternalTokenException extends RuntimeException {

    public InvalidExternalTokenException() {
        super("invalid external token", null, false, false);
    }
}
