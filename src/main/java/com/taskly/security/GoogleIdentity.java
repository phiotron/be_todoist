package com.taskly.security;

/**
 * Kết quả xác minh một ID token Google (hoặc linkToken): subject = "sub" (định danh ổn định), email đã chuẩn hoá,
 * emailVerified theo claim email_verified, name có thể null.
 */
public record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
}
