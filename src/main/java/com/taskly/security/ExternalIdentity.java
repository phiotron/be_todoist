package com.taskly.security;

/**
 * Danh tính đã xác minh từ Apple / Facebook (M13b). subject = id ổn định của nhà cung cấp ("sub" của Apple, id người dùng
 * Facebook). email đã chuẩn hoá, có thể null (Facebook không cấp email). name có thể null.
 */
public record ExternalIdentity(String subject, String email, boolean emailVerified, String name) {

    @Override
    public String toString() {
        return "ExternalIdentity[subject=***, email=***]";
    }
}
