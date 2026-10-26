package com.taskly.security;

/** ID token Google / linkToken không dùng được. Message cố định — KHÔNG bao giờ chứa token. */
public class InvalidGoogleTokenException extends RuntimeException {

    public InvalidGoogleTokenException() {
        super("Token Google không hợp lệ", null, false, false);
    }
}
